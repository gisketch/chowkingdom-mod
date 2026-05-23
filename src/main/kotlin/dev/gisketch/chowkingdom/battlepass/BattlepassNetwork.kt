package dev.gisketch.chowkingdom.battlepass

import com.google.gson.GsonBuilder
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.npc.NpcQuestService
import dev.gisketch.chowkingdom.revive.ReviveStore
import dev.gisketch.chowkingdom.shipping.ShippingBinStore
import dev.gisketch.chowkingdom.skilltree.ClassSkillTrees
import dev.gisketch.chowkingdom.wallets.ChowcoinStore
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

object BattlepassNetwork {
    private val lastKnownPlayerProgress: MutableMap<java.util.UUID, BattlepassPlayerProgressPayload> = linkedMapOf()

    private val gson = GsonBuilder().create()

    fun register(modBus: IEventBus) {
        modBus.addListener(::registerPayloads)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedOut)
    }

    fun requestSync() {
        runCatching { PacketDistributor.sendToServer(BattlepassSyncRequestPayload) }
    }

    fun claim(passId: String, tierXp: Int) {
        runCatching { PacketDistributor.sendToServer(BattlepassClaimRequestPayload(passId, tierXp)) }
    }

    fun claimAll(passId: String) {
        runCatching { PacketDistributor.sendToServer(BattlepassClaimAllRequestPayload(passId)) }
    }

    fun syncAllPlayers() {
        val server = ServerLifecycleHooks.getCurrentServer() ?: return
        val players = server.playerList.players
        val snapshot = createSyncSnapshot(players)
        players.forEach { player ->
            PacketDistributor.sendToPlayer(player, snapshot.payloadFor(player.uuid))
            NpcQuestService.syncTo(player)
        }
    }

    fun notifyMissionCompletion(player: ServerPlayer, passId: String, missionKey: String, title: String, scope: BattlepassMissionScope, kind: String) {
        PacketDistributor.sendToPlayer(player, BattlepassMissionCompletionNotificationPayload(passId, missionKey, title, scope.id, kind))
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("2")
        registrar.playToServer(BattlepassSyncRequestPayload.TYPE, BattlepassSyncRequestPayload.STREAM_CODEC, ::handleSyncRequest)
        registrar.playToServer(BattlepassClaimRequestPayload.TYPE, BattlepassClaimRequestPayload.STREAM_CODEC, ::handleClaimRequest)
        registrar.playToServer(BattlepassClaimAllRequestPayload.TYPE, BattlepassClaimAllRequestPayload.STREAM_CODEC, ::handleClaimAllRequest)
        registrar.playToClient(BattlepassSyncPayload.TYPE, BattlepassSyncPayload.STREAM_CODEC, ::handleSync)
        registrar.playToClient(BattlepassMissionCompletionNotificationPayload.TYPE, BattlepassMissionCompletionNotificationPayload.STREAM_CODEC, ::handleMissionCompletionNotification)
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        if (event.entity is ServerPlayer) syncAllPlayers()
    }

    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        if (event.entity is ServerPlayer) syncAllPlayers()
    }

    private fun handleSyncRequest(payload: BattlepassSyncRequestPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        syncTo(player)
    }

    private fun handleClaimRequest(payload: BattlepassClaimRequestPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        BattlepassClaimService.claim(player, payload.passId, payload.tierXp)
        syncAllPlayers()
    }

    private fun handleClaimAllRequest(payload: BattlepassClaimAllRequestPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        BattlepassClaimService.claimAll(player, payload.passId)
        syncAllPlayers()
    }

    private fun handleSync(payload: BattlepassSyncPayload, context: IPayloadContext) {
        BattlepassClientState.apply(payload)
    }

    private fun handleMissionCompletionNotification(payload: BattlepassMissionCompletionNotificationPayload, context: IPayloadContext) {
        val scope = BattlepassMissionScope.entries.firstOrNull { scope -> scope.id == payload.scopeId } ?: BattlepassMissionScope.PERMANENT
        BattlepassClientState.enqueueMissionCompletionNotification(payload.passId, payload.missionKey, payload.title, scope, payload.kind)
    }

    fun syncTo(player: ServerPlayer) {
        PacketDistributor.sendToPlayer(player, createSyncPayload(player))
        NpcQuestService.syncTo(player)
    }

    private fun createSyncPayload(receiver: ServerPlayer): BattlepassSyncPayload {
        return createSyncSnapshot(receiver.server.playerList.players).payloadFor(receiver.uuid)
    }

    private fun createSyncSnapshot(online: List<ServerPlayer>): BattlepassSyncSnapshot {
        val passes = BattlepassPassRegistry.all().toList()
        val passIds = passes.map { pass -> pass.id }
        online.forEach(CobblemonBattlepassIntegration::refreshCobblemonProgress)
        val activeMissionKeysByPass = BattlepassMissionProgressStore.activeMissionKeysByPass(passes)
        val onlinePlayers = online.map { player ->
            val missionProgressByPass = passes.associate { pass -> pass.id to BattlepassMissionProgressStore.progressForPass(player.uuid, pass) }
            BattlepassPlayerProgressPayload(
                player.uuid,
                player.gameProfile.name,
                passIds.associateWith { passId -> BattlepassXpStore.getXp(player.uuid, passId) },
                passIds.associateWith { passId -> BattlepassXpStore.claimedTiers(player.uuid, passId).sorted() },
                missionProgressByPass,
                passes.associate { pass -> pass.id to BattlepassMissionProgressStore.completedKeysForPass(player.uuid, pass) },
                CobblemonBattlepassIntegration.uniqueCaughtSpecies(player),
                eventProgress(passes, missionProgressByPass, "minecraft:monster_killed"),
                ReviveStore.incapacitatedCount(player.uuid),
                player.stats.getValue(Stats.CUSTOM.get(Stats.DEATHS)),
                ReviveStore.revivedCount(player.uuid),
                ReviveStore.revivedOthersCount(player.uuid),
                ChowcoinStore.get(player.uuid),
                ClassSkillTrees.pointSummary(player).available,
                player.stats.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)).toLong(),
            )
        }
        onlinePlayers.forEach { player -> lastKnownPlayerProgress[player.uuid] = player }
        val players = (lastKnownPlayerProgress.values + onlinePlayers).associateBy { player -> player.uuid }.values.toList()
        return BattlepassSyncSnapshot(passes.map { pass -> gson.toJson(pass) }, players, activeMissionKeysByPass, ShippingBinStore.totalChowcoinsSold())
    }

    private fun eventProgress(passes: List<BattlepassPassDefinition>, missionProgressByPass: Map<String, Map<String, Int>>, eventId: String): Int =
        passes.maxOfOrNull { pass ->
            val progress = missionProgressByPass[pass.id].orEmpty()
            BattlepassMissionService.allEntries(pass)
                .filter { entry -> entry.event.event == eventId }
                .maxOfOrNull { entry -> progress[entry.key] ?: 0 } ?: 0
        } ?: 0

    private data class BattlepassSyncSnapshot(
        val passesJson: List<String>,
        val players: List<BattlepassPlayerProgressPayload>,
        val activeMissionKeysByPass: Map<String, List<String>>,
        val totalShippedChowcoins: Long,
    ) {
        fun payloadFor(receiverId: UUID): BattlepassSyncPayload =
            BattlepassSyncPayload(passesJson, players, activeMissionKeysByPass, receiverId, totalShippedChowcoins)
    }
}

object BattlepassSyncRequestPayload : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BattlepassSyncRequestPayload> = TYPE

    val TYPE: CustomPacketPayload.Type<BattlepassSyncRequestPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "battlepass/sync_request"))
    val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BattlepassSyncRequestPayload> = object : StreamCodec<RegistryFriendlyByteBuf, BattlepassSyncRequestPayload> {
        override fun decode(buffer: RegistryFriendlyByteBuf): BattlepassSyncRequestPayload = BattlepassSyncRequestPayload

        override fun encode(buffer: RegistryFriendlyByteBuf, value: BattlepassSyncRequestPayload) = Unit
    }
}

data class BattlepassClaimRequestPayload(val passId: String, val tierXp: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BattlepassClaimRequestPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<BattlepassClaimRequestPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "battlepass/claim_request"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BattlepassClaimRequestPayload> = object : StreamCodec<RegistryFriendlyByteBuf, BattlepassClaimRequestPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): BattlepassClaimRequestPayload = BattlepassClaimRequestPayload(buffer.readUtf(MAX_STRING_LENGTH), buffer.readVarInt())

            override fun encode(buffer: RegistryFriendlyByteBuf, value: BattlepassClaimRequestPayload) {
                buffer.writeUtf(value.passId, MAX_STRING_LENGTH)
                buffer.writeVarInt(value.tierXp)
            }
        }
    }
}

data class BattlepassClaimAllRequestPayload(val passId: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BattlepassClaimAllRequestPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<BattlepassClaimAllRequestPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "battlepass/claim_all_request"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BattlepassClaimAllRequestPayload> = object : StreamCodec<RegistryFriendlyByteBuf, BattlepassClaimAllRequestPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): BattlepassClaimAllRequestPayload = BattlepassClaimAllRequestPayload(buffer.readUtf(MAX_STRING_LENGTH))

            override fun encode(buffer: RegistryFriendlyByteBuf, value: BattlepassClaimAllRequestPayload) {
                buffer.writeUtf(value.passId, MAX_STRING_LENGTH)
            }
        }
    }
}

data class BattlepassSyncPayload(
    val passesJson: List<String>,
    val players: List<BattlepassPlayerProgressPayload>,
    val activeMissionKeysByPass: Map<String, List<String>>,
    val selfId: UUID,
    val totalShippedChowcoins: Long,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BattlepassSyncPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<BattlepassSyncPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "battlepass/sync"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BattlepassSyncPayload> = object : StreamCodec<RegistryFriendlyByteBuf, BattlepassSyncPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): BattlepassSyncPayload {
                val passes = List(buffer.readVarInt()) { buffer.readUtf(MAX_PASS_JSON_LENGTH) }
                val players = List(buffer.readVarInt()) { BattlepassPlayerProgressPayload.decode(buffer) }
                val activeMissionKeysByPass = linkedMapOf<String, List<String>>()
                repeat(buffer.readVarInt()) {
                    val passId = buffer.readUtf(MAX_STRING_LENGTH)
                    activeMissionKeysByPass[passId] = List(buffer.readVarInt()) { buffer.readUtf(MAX_STRING_LENGTH) }
                }
                return BattlepassSyncPayload(passes, players, activeMissionKeysByPass, buffer.readUUID(), buffer.readVarLong())
            }

            override fun encode(buffer: RegistryFriendlyByteBuf, value: BattlepassSyncPayload) {
                buffer.writeVarInt(value.passesJson.size)
                value.passesJson.forEach { passJson -> buffer.writeUtf(passJson, MAX_PASS_JSON_LENGTH) }
                buffer.writeVarInt(value.players.size)
                value.players.forEach { player -> player.encode(buffer) }
                buffer.writeVarInt(value.activeMissionKeysByPass.size)
                value.activeMissionKeysByPass.forEach { (passId, keys) ->
                    buffer.writeUtf(passId, MAX_STRING_LENGTH)
                    buffer.writeVarInt(keys.size)
                    keys.forEach { key -> buffer.writeUtf(key, MAX_STRING_LENGTH) }
                }
                buffer.writeUUID(value.selfId)
                buffer.writeVarLong(value.totalShippedChowcoins)
            }
        }
    }
}

data class BattlepassMissionCompletionNotificationPayload(
    val passId: String,
    val missionKey: String,
    val title: String,
    val scopeId: String,
    val kind: String,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<BattlepassMissionCompletionNotificationPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<BattlepassMissionCompletionNotificationPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "battlepass/mission_completion_notification"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BattlepassMissionCompletionNotificationPayload> = object : StreamCodec<RegistryFriendlyByteBuf, BattlepassMissionCompletionNotificationPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): BattlepassMissionCompletionNotificationPayload = BattlepassMissionCompletionNotificationPayload(
                buffer.readUtf(MAX_STRING_LENGTH),
                buffer.readUtf(MAX_STRING_LENGTH),
                buffer.readUtf(MAX_STRING_LENGTH),
                buffer.readUtf(MAX_STRING_LENGTH),
                buffer.readUtf(MAX_STRING_LENGTH),
            )

            override fun encode(buffer: RegistryFriendlyByteBuf, value: BattlepassMissionCompletionNotificationPayload) {
                buffer.writeUtf(value.passId, MAX_STRING_LENGTH)
                buffer.writeUtf(value.missionKey, MAX_STRING_LENGTH)
                buffer.writeUtf(value.title, MAX_STRING_LENGTH)
                buffer.writeUtf(value.scopeId, MAX_STRING_LENGTH)
                buffer.writeUtf(value.kind, MAX_STRING_LENGTH)
            }
        }
    }
}

data class BattlepassPlayerProgressPayload(
    val uuid: UUID,
    val name: String,
    val xpByPass: Map<String, Int>,
    val claimedByPass: Map<String, List<Int>>,
    val missionProgressByPass: Map<String, Map<String, Int>>,
    val completedMissionKeysByPass: Map<String, List<String>>,
    val uniquePokemonCaught: Int,
    val hostileMonstersKilled: Int,
    val koCount: Int,
    val deaths: Int,
    val revivedCount: Int,
    val revivedOthersCount: Int,
    val chowcoins: Long,
    val skillPointsAvailable: Int,
    val playtimeTicks: Long,
) {
    fun encode(buffer: RegistryFriendlyByteBuf) {
        buffer.writeUUID(uuid)
        buffer.writeUtf(name, MAX_STRING_LENGTH)
        buffer.writeVarInt(xpByPass.size)
        xpByPass.forEach { (passId, xp) ->
            buffer.writeUtf(passId, MAX_STRING_LENGTH)
            buffer.writeVarInt(xp)
        }
        buffer.writeVarInt(claimedByPass.size)
        claimedByPass.forEach { (passId, tiers) ->
            buffer.writeUtf(passId, MAX_STRING_LENGTH)
            buffer.writeVarInt(tiers.size)
            tiers.forEach(buffer::writeVarInt)
        }
        buffer.writeVarInt(missionProgressByPass.size)
        missionProgressByPass.forEach { (passId, events) ->
            buffer.writeUtf(passId, MAX_STRING_LENGTH)
            buffer.writeVarInt(events.size)
            events.forEach { (eventId, progress) ->
                buffer.writeUtf(eventId, MAX_STRING_LENGTH)
                buffer.writeVarInt(progress)
            }
        }
        buffer.writeVarInt(completedMissionKeysByPass.size)
        completedMissionKeysByPass.forEach { (passId, keys) ->
            buffer.writeUtf(passId, MAX_STRING_LENGTH)
            buffer.writeVarInt(keys.size)
            keys.forEach { key -> buffer.writeUtf(key, MAX_STRING_LENGTH) }
        }
        buffer.writeVarInt(uniquePokemonCaught)
        buffer.writeVarInt(hostileMonstersKilled)
        buffer.writeVarInt(koCount)
        buffer.writeVarInt(deaths)
        buffer.writeVarInt(revivedCount)
        buffer.writeVarInt(revivedOthersCount)
        buffer.writeVarLong(chowcoins)
        buffer.writeVarInt(skillPointsAvailable)
        buffer.writeVarLong(playtimeTicks)
    }

    companion object {
        fun decode(buffer: RegistryFriendlyByteBuf): BattlepassPlayerProgressPayload {
            val uuid = buffer.readUUID()
            val name = buffer.readUtf(MAX_STRING_LENGTH)
            val xpByPass = linkedMapOf<String, Int>()
            repeat(buffer.readVarInt()) {
                xpByPass[buffer.readUtf(MAX_STRING_LENGTH)] = buffer.readVarInt()
            }
            val claimedByPass = linkedMapOf<String, List<Int>>()
            repeat(buffer.readVarInt()) {
                val passId = buffer.readUtf(MAX_STRING_LENGTH)
                claimedByPass[passId] = List(buffer.readVarInt()) { buffer.readVarInt() }
            }
            val missionProgressByPass = linkedMapOf<String, Map<String, Int>>()
            repeat(buffer.readVarInt()) {
                val passId = buffer.readUtf(MAX_STRING_LENGTH)
                val eventProgress = linkedMapOf<String, Int>()
                repeat(buffer.readVarInt()) {
                    eventProgress[buffer.readUtf(MAX_STRING_LENGTH)] = buffer.readVarInt()
                }
                missionProgressByPass[passId] = eventProgress
            }
            val completedMissionKeysByPass = linkedMapOf<String, List<String>>()
            repeat(buffer.readVarInt()) {
                val passId = buffer.readUtf(MAX_STRING_LENGTH)
                completedMissionKeysByPass[passId] = List(buffer.readVarInt()) { buffer.readUtf(MAX_STRING_LENGTH) }
            }
            val uniquePokemonCaught = buffer.readVarInt()
            val hostileMonstersKilled = buffer.readVarInt()
            val koCount = buffer.readVarInt()
            val deaths = buffer.readVarInt()
            val revivedCount = buffer.readVarInt()
            val revivedOthersCount = buffer.readVarInt()
            val chowcoins = buffer.readVarLong()
            val skillPointsAvailable = buffer.readVarInt()
            val playtimeTicks = buffer.readVarLong()
            return BattlepassPlayerProgressPayload(uuid, name, xpByPass, claimedByPass, missionProgressByPass, completedMissionKeysByPass, uniquePokemonCaught, hostileMonstersKilled, koCount, deaths, revivedCount, revivedOthersCount, chowcoins, skillPointsAvailable, playtimeTicks)
        }
    }
}

private const val MAX_STRING_LENGTH = 256
private const val MAX_PASS_JSON_LENGTH = 262_144
