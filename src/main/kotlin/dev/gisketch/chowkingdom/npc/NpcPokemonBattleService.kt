package dev.gisketch.chowkingdom.npc

import com.gitlab.srcmc.rctapi.api.RCTApi
import com.gitlab.srcmc.rctapi.api.battle.BattleFormat
import com.gitlab.srcmc.rctapi.api.battle.BattleRules
import com.gitlab.srcmc.rctapi.api.battle.BattleState
import com.gitlab.srcmc.rctapi.api.events.Event
import com.gitlab.srcmc.rctapi.api.events.EventListener
import com.gitlab.srcmc.rctapi.api.events.Events
import com.gitlab.srcmc.rctapi.api.models.TrainerModel
import com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC
import com.gitlab.srcmc.rctapi.api.trainer.TrainerPlayer
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.config.TomlConfigIO
import dev.gisketch.chowkingdom.gyms.GymBattleSpotState
import dev.gisketch.chowkingdom.gyms.GymLeagueStore
import dev.gisketch.chowkingdom.gyms.StadiumBattlePokemonPlacement
import dev.gisketch.chowkingdom.gyms.GymTransitionNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarIcons
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.RelativeMovement
import net.minecraft.world.phys.Vec3
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumSet
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.math.atan2
import kotlin.random.Random

object NpcPokemonBattleService {
    private const val API_ID = ChowKingdomMod.MOD_ID
    private const val STADIUM_ID = "main_stadium"
    private const val BATTLE_FADE_TELEPORT_DELAY_TICKS = 8L
    private const val BATTLE_FACE_SYNC_TICKS = 40L
    private var api: RCTApi? = null
    private var gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private var registered = false
    private var listenerRegistered = false
    private val pendingStarts: MutableList<PendingNpcBattleStart> = mutableListOf()
    private val activeStates: MutableMap<UUID, BattleState> = linkedMapOf()
    private val activeContexts: MutableMap<UUID, NpcPokemonBattleContext> = linkedMapOf()
    private val battleLocksByNpcId: MutableMap<String, ActiveNpcPokemonBattleLock> = linkedMapOf()
    private val battleLocksByNpcUuid: MutableMap<UUID, ActiveNpcPokemonBattleLock> = linkedMapOf()
    private val pendingFacing: MutableList<PendingFacingSync> = mutableListOf()
    private val battleEndedListener = EventListener<BattleState> { event: Event<BattleState> -> handleBattleEnded(event.value) }

    fun register() {
        if (registered) return
        registered = true
        ensureDefaultRosters()
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedOut)
    }

    fun init(server: MinecraftServer) {
        runCatching {
            ensureDefaultRosters()
            val instance = RCTApi.getInstance(API_ID) ?: RCTApi.initInstance(API_ID)
            api = instance
            gson = instance.configureGsonBuilder(GsonBuilder().setPrettyPrinting()).create()
            instance.trainerRegistry.init(server)
            if (!listenerRegistered) {
                instance.eventContext.register(Events.BATTLE_ENDED, battleEndedListener)
                listenerRegistered = true
            }
            StadiumBattlePokemonPlacement.register()
            server.playerList.players.forEach(::registerPlayer)
        }.onFailure { exception ->
            ChowKingdomMod.LOGGER.warn("Failed to initialize resident NPC Pokemon battle API", exception)
        }
    }

    fun hasRoster(npcId: String): Boolean = existingRosterFile(npcId) != null || defaultRosters.containsKey(cleanId(npcId))

    fun friendlyBattleAvailable(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition): Boolean =
        hasRoster(definition.id) && NpcStore.workplacePos(definition.id) != null && !isBattleLocked(npc)

    fun startFriendlyBattle(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition): NpcPokemonBattleStartResult =
        start(player, npc, definition, quest = false)

    fun startQuestBattle(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition): NpcPokemonBattleStartResult =
        start(player, npc, definition, quest = true)

    fun isBattleLocked(npc: ChowNpcEntity): Boolean = battleLocksByNpcUuid.containsKey(npc.uuid) || battleLocksByNpcId.containsKey(npc.npcId)

    fun tickBattleLock(npc: ChowNpcEntity): Boolean {
        val lock = battleLocksByNpcUuid[npc.uuid] ?: battleLocksByNpcId[npc.npcId] ?: return false
        holdNpcAtBattleAnchor(npc, lock)
        npc.debugActivity = "npc_pokemon_battle"
        npc.debugGoal = if (lock.quest) "quest_battle" else "friendly_battle"
        val level = npc.level() as? net.minecraft.server.level.ServerLevel
        val player = lock.playerUuid?.let { level?.getPlayerByUUID(it) }
        if (player != null && player.isAlive && player.level() == npc.level()) {
            npc.lookControl.setLookAt(player, 30.0f, 30.0f)
            npc.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition())
        }
        return true
    }

    private fun start(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, quest: Boolean): NpcPokemonBattleStartResult {
        api ?: return NpcPokemonBattleStartResult(false, "The trainer battle API is not ready yet.")
        if (!hasRoster(definition.id)) return NpcPokemonBattleStartResult(false, "${definition.displayName()} has no Pokemon roster.")
        if (GymLeagueStore.area(STADIUM_ID) == null) return NpcPokemonBattleStartResult(false, "Main stadium is not set. Use /ck gyms area set main_stadium <radius> first.")
        if (!partyBattleReady(player)) return NpcPokemonBattleStartResult(false, "Your party needs at least one Pokemon that can battle.")
        if (battleLocksByNpcId.containsKey(definition.id) || battleLocksByNpcUuid.containsKey(npc.uuid)) return NpcPokemonBattleStartResult(false, "${definition.displayName()} is already in a Pokemon battle.")
        if (activeContexts.values.any { context -> context.playerUuid == player.uuid }) return NpcPokemonBattleStartResult(false, "You are already in an NPC Pokemon battle.")
        GymTransitionNetwork.playBattleFade(player)
        pendingStarts.removeIf { pending -> pending.playerUuid == player.uuid && pending.npcId == definition.id }
        pendingStarts += PendingNpcBattleStart(
            executeAtTick = player.server.overworld().gameTime + BATTLE_FADE_TELEPORT_DELAY_TICKS,
            playerUuid = player.uuid,
            npcId = definition.id,
            quest = quest,
        )
        return NpcPokemonBattleStartResult(true, "Battle transition started.")
    }

    private fun onServerStarted(event: ServerStartedEvent) = init(event.server)

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        registerPlayer(player)
    }

    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        restoreAndCancelPlayer(player)
        unregisterPlayer(player)
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        val now = server.overworld().gameTime
        tickPendingFacing(server, now)
        val ready = pendingStarts.filter { pending -> now >= pending.executeAtTick }
        if (ready.isEmpty()) return
        pendingStarts.removeAll(ready.toSet())
        ready.forEach { pending -> executePendingStart(server, pending) }
    }

    private fun executePendingStart(server: MinecraftServer, pending: PendingNpcBattleStart) {
        val instance = api ?: return
        val player = server.playerList.getPlayer(pending.playerUuid) ?: return
        val definition = NpcConfig.get(pending.npcId) ?: return failPending(player, "NPC config missing.")
        val npc = NpcFeature.existingNpc(server, pending.npcId) ?: return failPending(player, "${definition.displayName()} is not loaded.")
        runCatching {
            prepareBattlePositions(player, npc)?.let { message -> return failPending(player, message) }
            lockNpcForBattle(npc, player, pending.quest)
            NpcPokemonCompanions.suspendForBattle(definition.id, server)
            registerPlayer(player)
            val snapshot = snapshotAndSetPartyLevel(player, 50)
            val playerTrainer = instance.trainerRegistry.getById(playerTrainerId(player), TrainerPlayer::class.java)
                ?: TrainerPlayer(player).also { instance.trainerRegistry.registerPlayer(playerTrainerId(player), it) }
            val npcTrainer = registerResidentTrainer(instance, npc, definition, pending)
                ?: run {
                    restorePartySnapshot(player, snapshot)
                    NpcPokemonCompanions.resumeAfterBattle(definition.id)
                    unlockNpcForBattle(definition.id, npc.uuid)
                    return failPending(player, "Could not prepare ${definition.displayName()}'s battle team.")
                }
            npcTrainer.setEntity(npc)
            val rules = BattleRules.Builder()
                .withMaxItemUses(0)
                .withHealPlayers(true)
                .withAdjustPlayerLevels(false)
                .withAdjustNPCLevels(false)
                .build()
            val uuid = instance.battleManager.startBattle(listOf(playerTrainer), listOf(npcTrainer), BattleFormat.GEN_9_SINGLES, rules)
                ?: run {
                    restorePartySnapshot(player, snapshot)
                    NpcPokemonCompanions.resumeAfterBattle(definition.id)
                    unlockNpcForBattle(definition.id, npc.uuid)
                    return failPending(player, "RCT refused to start the battle.")
                }
            instance.battleManager.getState(uuid)?.let { state -> activeStates[uuid] = state }
            activeContexts[uuid] = NpcPokemonBattleContext(
                playerUuid = player.uuid,
                playerName = player.gameProfile.name,
                npcId = definition.id,
                npcUuid = npc.uuid,
                quest = pending.quest,
                partySnapshot = snapshot,
                sampledSpecies = pending.sampledSpecies,
            )
        }.getOrElse { exception ->
            NpcPokemonCompanions.resumeAfterBattle(definition.id)
            unlockNpcForBattle(definition.id, npc.uuid)
            ChowKingdomMod.LOGGER.warn("Failed to start resident NPC Pokemon battle", exception)
            failPending(player, "The battle failed to start. Check the NPC roster JSON and RCT logs.")
        }
    }

    private fun failPending(player: ServerPlayer, message: String) {
        SnackbarNetwork.send(player, SnackbarNotification.item(SnackbarIcons.ERROR, "BATTLE FAILED", message, SnackbarType.ERROR, SnackbarSounds.ERROR))
    }

    private fun registerPlayer(player: ServerPlayer) {
        val instance = api ?: return
        runCatching {
            val id = playerTrainerId(player)
            instance.trainerRegistry.unregisterById(id)
            instance.trainerRegistry.registerPlayer(id, player)
        }.onFailure { exception ->
            ChowKingdomMod.LOGGER.debug("Failed to register resident NPC battle player {}", player.gameProfile.name, exception)
        }
    }

    private fun unregisterPlayer(player: ServerPlayer) {
        val instance = api ?: return
        runCatching { instance.trainerRegistry.unregisterById(playerTrainerId(player)) }
    }

    private fun registerResidentTrainer(instance: RCTApi, npc: ChowNpcEntity, definition: NpcDefinition, pending: PendingNpcBattleStart): TrainerNPC? {
        val id = "npc_resident_${cleanId(definition.id)}_${pending.playerUuid}"
        val model = sampledTrainerModel(definition, pending) ?: return null
        instance.trainerRegistry.unregisterById(id)
        val registered = instance.trainerRegistry.registerNPC(id, model)
        registered.setEntity(npc)
        return registered
    }

    private fun sampledTrainerModel(definition: NpcDefinition, pending: PendingNpcBattleStart): TrainerModel? {
        if (existingRosterFile(definition.id) == null) ensureDefaultRoster(definition.id, definition.displayName())
        val file = existingRosterFile(definition.id) ?: return null
        val root = TomlConfigIO.readObject(file) ?: return null
        val team = root.getAsJsonArray("team") ?: return null
        if (team.size() < 1) return null
        val seed = "${definition.id}:${pending.playerUuid}:${pending.executeAtTick}:${pending.quest}".hashCode()
        val sample = team.map { it.asJsonObject.deepCopy() }
            .shuffled(Random(seed))
            .take(6)
        pending.sampledSpecies = sample.mapNotNull { pokemon -> pokemon.get("species")?.asString?.substringAfter(':') }
        root.add("team", JsonArray().apply { sample.forEach(::add) })
        root.addProperty("name", definition.displayName())
        return gson.fromJson(root, TrainerModel::class.java)
    }

    private fun prepareBattlePositions(player: ServerPlayer, npc: ChowNpcEntity): String? {
        val area = GymLeagueStore.area(STADIUM_ID) ?: return "Main stadium is not set. Use /ck gyms area set main_stadium <radius> first."
        val dimensionId = area.playerSpot.dimension.ifBlank { area.dimension }
        val dimension = runCatching { ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimensionId)) }.getOrNull()
            ?: return "Main stadium dimension is invalid: $dimensionId"
        val level = player.server.getLevel(dimension) ?: return "Main stadium dimension is not loaded: $dimensionId"
        val playerSpot = area.playerSpot.takeIf { it.configured } ?: GymBattleSpotState(dimensionId, area.x - 3, area.y, area.z, 0.0f, 0.0f, true)
        val trainerSpot = area.trainerSpot.takeIf { it.configured } ?: GymBattleSpotState(dimensionId, area.x + 3, area.y, area.z, 180.0f, 0.0f, true)
        val playerPos = Vec3(playerSpot.x + 0.5, playerSpot.y.toDouble(), playerSpot.z + 0.5)
        val trainerPos = Vec3(trainerSpot.x + 0.5, trainerSpot.y.toDouble(), trainerSpot.z + 0.5)
        val playerYaw = yawToward(playerPos, trainerPos)
        val trainerYaw = yawToward(trainerPos, playerPos)
        player.teleportTo(level, playerPos.x, playerPos.y, playerPos.z, playerYaw, 0.0f)
        player.setYRot(playerYaw)
        player.setXRot(0.0f)
        player.yHeadRot = playerYaw
        player.yBodyRot = playerYaw
        npc.navigation.stop()
        if (npc.level() != level) {
            npc.teleportTo(level, trainerPos.x, trainerPos.y, trainerPos.z, EnumSet.noneOf(RelativeMovement::class.java), trainerYaw, 0.0f)
        } else {
            npc.moveTo(trainerPos.x, trainerPos.y, trainerPos.z, trainerYaw, 0.0f)
        }
        forceFaceEachOther(player, npc)
        StadiumBattlePokemonPlacement.queue(player, npc, playerPos, trainerPos)
        pendingFacing += PendingFacingSync(player.server.overworld().gameTime + BATTLE_FACE_SYNC_TICKS, player.uuid, npc.uuid)
        return null
    }

    private fun forceFaceEachOther(player: ServerPlayer, npc: ChowNpcEntity) {
        val playerYaw = yawToward(player.position(), npc.position())
        val trainerYaw = yawToward(npc.position(), player.position())
        player.setYRot(playerYaw)
        player.setXRot(0.0f)
        player.yHeadRot = playerYaw
        player.yBodyRot = playerYaw
        npc.setYRot(trainerYaw)
        npc.setXRot(0.0f)
        npc.yHeadRot = trainerYaw
        npc.yBodyRot = trainerYaw
        npc.lookControl.setLookAt(player, 30.0f, 30.0f)
        npc.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition())
        player.lookAt(EntityAnchorArgument.Anchor.EYES, npc.getEyePosition())
    }

    private fun tickPendingFacing(server: MinecraftServer, now: Long) {
        val iterator = pendingFacing.iterator()
        while (iterator.hasNext()) {
            val sync = iterator.next()
            if (now > sync.untilTick) {
                iterator.remove()
                continue
            }
            val player = server.playerList.getPlayer(sync.playerUuid) ?: continue
            val npc = server.allLevels.asSequence().mapNotNull { level -> level.getEntity(sync.npcUuid) as? ChowNpcEntity }.firstOrNull() ?: continue
            if (player.level() == npc.level()) forceFaceEachOther(player, npc)
        }
    }

    private fun yawToward(from: Vec3, to: Vec3): Float {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return (atan2(dz, dx) * 180.0 / Math.PI - 90.0).toFloat()
    }

    private fun handleBattleEnded(state: BattleState) {
        val uuid = activeStates.entries.firstOrNull { (_, active) -> active === state || active.battle == state.battle }?.key ?: return
        activeStates.remove(uuid)
        val context = activeContexts.remove(uuid) ?: return
        NpcPokemonCompanions.resumeAfterBattle(context.npcId)
        val player = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer()?.playerList?.getPlayer(context.playerUuid)
        if (player != null) restorePartySnapshot(player, context.partySnapshot)
        val npc = player?.server?.let { NpcFeature.existingNpc(it, context.npcId) }
        unlockNpcForBattle(context.npcId, npc?.uuid ?: context.npcUuid)
        if (state.isEndForced || player == null) return
        val winner = state.winners.asSequence()
            .filterIsInstance<TrainerPlayer>()
            .map { it.player }
            .firstOrNull { it.uuid == context.playerUuid }
        val loser = state.losers.asSequence()
            .filterIsInstance<TrainerPlayer>()
            .map { it.player }
            .firstOrNull { it.uuid == context.playerUuid }
        val resultPlayer = winner ?: loser ?: player
        val won = winner != null
        if (context.quest && won) {
            NpcConfig.get(context.npcId)?.let { definition -> NpcQuestService.completeBattleQuest(resultPlayer, definition, "pokemon_battle", npc) }
        } else {
            val title = when {
                context.quest && won -> "NPC BATTLE WON"
                context.quest -> "NPC BATTLE LOST"
                won -> "FRIENDLY BATTLE WON"
                else -> "FRIENDLY BATTLE LOST"
            }
            SnackbarNetwork.send(
                resultPlayer,
                SnackbarNotification.npc(context.npcId, title, if (context.quest) "Quest battle can be retried before reset." else "Practice battle complete. No reward.", if (won) SnackbarType.SUCCESS else SnackbarType.GENERIC, if (won) SnackbarSounds.REWARD else SnackbarSounds.GENERIC),
            )
        }
        openResultDialogue(resultPlayer, context, won)
    }

    private fun openResultDialogue(player: ServerPlayer, context: NpcPokemonBattleContext, won: Boolean) {
        val definition = NpcConfig.get(context.npcId) ?: return
        val npc = NpcFeature.existingNpc(player.server, context.npcId) ?: return
        if (npc.level() != player.level()) return
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val sampled = context.sampledSpecies.takeIf { it.isNotEmpty() }?.take(2)?.joinToString(" and ").orEmpty()
        val fallback = when {
            context.quest && won -> "Good battle, ${player.gameProfile.name}. My team needed that test."
            context.quest -> "Not this time. My team and I can run it back before the reset."
            won -> "Clean practice battle. ${if (sampled.isNotBlank()) "$sampled gave you a proper test." else "My team needed that."}"
            else -> "Good practice. Heal up and we can battle again later."
        }
        val llmEnabled = NpcConfig.settings().llm.enabled
        val token = if (llmEnabled) NpcDialogTokens.next() else 0L
        npc.startTalkingTo(player, 100)
        NpcNetwork.openDialog(player, NpcFeature.dialogPayload(definition, npc, if (llmEnabled) "..." else fallback, false, friendship.level, closeOnly = true, closeLabel = "OKAY", responseToken = token, dialogMode = "npc_battle_result"))
        if (!llmEnabled) {
            NpcStore.recordConversation(definition.id, player, definition.name, fallback, if (won) "npc_pokemon_battle_win_dialogue" else "npc_pokemon_battle_loss_dialogue")
            return
        }
        NpcLlmService.event(
            player,
            npc,
            definition,
            fallback,
            "${player.gameProfile.name} just ${if (won) "won" else "lost"} a ${if (context.quest) "reward quest" else "friendly practice"} Pokemon battle against your personal team${if (sampled.isNotBlank()) " including $sampled" else ""}. Reply as ${definition.name} in 1-2 short in-character sentences. Mention testing or training your team naturally. Do not mention UI wording or Pokemon XP.",
            inputLabel = "NPC Pokemon battle result",
            excludePlayerFromBalloon = true,
            showBalloon = false,
            relayToNearby = false,
            npcRecordType = if (won) "npc_pokemon_battle_win_dialogue" else "npc_pokemon_battle_loss_dialogue",
            responseToken = token,
        )
    }

    private fun lockNpcForBattle(npc: ChowNpcEntity, player: ServerPlayer, quest: Boolean) {
        val anchor = npc.position()
        val lock = ActiveNpcPokemonBattleLock(
            npcId = npc.npcId,
            npcUuid = npc.uuid,
            playerUuid = player.uuid,
            quest = quest,
            anchorDimension = npc.level().dimension().location().toString(),
            anchorX = anchor.x,
            anchorY = anchor.y,
            anchorZ = anchor.z,
            anchorYaw = npc.yRot,
        )
        battleLocksByNpcId[npc.npcId] = lock
        battleLocksByNpcUuid[npc.uuid] = lock
        npc.navigation.stop()
    }

    private fun holdNpcAtBattleAnchor(npc: ChowNpcEntity, lock: ActiveNpcPokemonBattleLock) {
        npc.navigation.stop()
        npc.target = null
        npc.deltaMovement = Vec3.ZERO
        if (npc.level().dimension().location().toString() != lock.anchorDimension) return
        val dx = npc.x - lock.anchorX
        val dy = npc.y - lock.anchorY
        val dz = npc.z - lock.anchorZ
        if (dx * dx + dy * dy + dz * dz > BATTLE_NPC_ANCHOR_TOLERANCE_SQR) {
            npc.moveTo(lock.anchorX, lock.anchorY, lock.anchorZ, lock.anchorYaw, 0.0f)
        }
    }

    private fun unlockNpcForBattle(npcId: String, npcUuid: UUID?) {
        battleLocksByNpcId.remove(npcId)
        if (npcUuid != null) battleLocksByNpcUuid.remove(npcUuid)
    }

    private fun restoreAndCancelPlayer(player: ServerPlayer) {
        val ids = activeContexts.filterValues { context -> context.playerUuid == player.uuid }.keys.toList()
        ids.forEach { uuid ->
            val context = activeContexts.remove(uuid) ?: return@forEach
            activeStates.remove(uuid)
            restorePartySnapshot(player, context.partySnapshot)
            NpcPokemonCompanions.resumeAfterBattle(context.npcId)
            unlockNpcForBattle(context.npcId, context.npcUuid)
        }
        pendingStarts.removeIf { pending -> pending.playerUuid == player.uuid }
    }

    private fun partyBattleReady(player: ServerPlayer): Boolean = runCatching {
        TrainerPlayer(player).team.any(::pokemonCanBattle)
    }.getOrElse { exception ->
        ChowKingdomMod.LOGGER.debug("Could not inspect Cobblemon party health for {}", player.gameProfile.name, exception)
        true
    }

    private fun snapshotAndSetPartyLevel(player: ServerPlayer, level: Int): NpcPokemonPartySnapshot = runCatching {
        val pokemon = TrainerPlayer(player).team.toList()
        val snapshots = pokemon.mapIndexed { index, entry -> pokemonSnapshot(index, entry) }
        pokemon.forEach { entry -> setNumber(entry, listOf("setLevel"), level.toDouble()) }
        NpcPokemonPartySnapshot(snapshots)
    }.getOrElse { exception ->
        ChowKingdomMod.LOGGER.debug("Could not snapshot NPC battle party for {}", player.gameProfile.name, exception)
        NpcPokemonPartySnapshot(emptyList())
    }

    private fun restorePartySnapshot(player: ServerPlayer, snapshot: NpcPokemonPartySnapshot) {
        if (snapshot.entries.isEmpty()) return
        runCatching {
            val pokemon = TrainerPlayer(player).team.toList()
            snapshot.entries.forEach { entry ->
                val target = pokemon.getOrNull(entry.index) ?: return@forEach
                entry.level?.let { setNumber(target, listOf("setLevel"), it) }
                entry.experience?.let { setNumber(target, listOf("setExperience", "setExperiencePoints"), it) }
                entry.currentHealth?.let { setNumber(target, listOf("setCurrentHealth", "setHealth"), it) }
                entry.status?.let { setObject(target, "setStatus", it) }
            }
        }.onFailure { exception ->
            ChowKingdomMod.LOGGER.debug("Could not restore NPC battle party for {}", player.gameProfile.name, exception)
        }
    }

    private fun pokemonSnapshot(index: Int, pokemon: Any): NpcPokemonSnapshot = NpcPokemonSnapshot(
        index = index,
        level = number(pokemon, listOf("getLevel"), listOf("level")),
        experience = number(pokemon, listOf("getExperience", "getExperiencePoints"), listOf("experience", "experiencePoints")),
        currentHealth = number(pokemon, listOf("getCurrentHealth", "getHealth"), listOf("currentHealth", "health")),
        status = objectValue(pokemon, listOf("getStatus"), listOf("status")),
    )

    private fun pokemonCanBattle(pokemon: Any): Boolean {
        val fainted = listOf("isFainted", "getFainted").firstNotNullOfOrNull { name -> booleanMethod(pokemon, name) }
        if (fainted == true) return false
        val currentHealth = number(pokemon, listOf("getCurrentHealth", "getHealth"), listOf("currentHealth", "health"))
        return currentHealth == null || currentHealth > 0.0
    }

    private fun booleanMethod(target: Any, name: String): Boolean? = runCatching {
        target.javaClass.methods.firstOrNull { method -> method.name == name && method.parameterCount == 0 }?.invoke(target) as? Boolean
    }.getOrNull()

    private fun number(target: Any, methodNames: List<String>, fieldNames: List<String>): Double? {
        methodNames.forEach { name ->
            runCatching {
                val value = target.javaClass.methods.firstOrNull { method -> method.name == name && method.parameterCount == 0 }?.invoke(target)
                if (value is Number) return value.toDouble()
            }
        }
        fieldNames.forEach { name ->
            runCatching {
                val value = target.javaClass.fields.firstOrNull { field -> field.name == name }?.get(target)
                if (value is Number) return value.toDouble()
            }
        }
        return null
    }

    private fun objectValue(target: Any, methodNames: List<String>, fieldNames: List<String>): Any? {
        methodNames.forEach { name ->
            runCatching {
                target.javaClass.methods.firstOrNull { method -> method.name == name && method.parameterCount == 0 }?.invoke(target)?.let { return it }
            }
        }
        fieldNames.forEach { name ->
            runCatching {
                target.javaClass.fields.firstOrNull { field -> field.name == name }?.get(target)?.let { return it }
            }
        }
        return null
    }

    private fun setNumber(target: Any, methodNames: List<String>, value: Double): Boolean {
        methodNames.forEach { name ->
            target.javaClass.methods
                .filter { method -> method.name == name && method.parameterCount == 1 }
                .forEach { method ->
                    val arg = when (method.parameterTypes[0]) {
                        java.lang.Integer.TYPE, java.lang.Integer::class.java -> value.toInt()
                        java.lang.Long.TYPE, java.lang.Long::class.java -> value.toLong()
                        java.lang.Float.TYPE, java.lang.Float::class.java -> value.toFloat()
                        java.lang.Double.TYPE, java.lang.Double::class.java -> value
                        else -> return@forEach
                    }
                    if (runCatching { method.invoke(target, arg) }.isSuccess) return true
                }
        }
        return false
    }

    private fun setObject(target: Any, methodName: String, value: Any): Boolean {
        target.javaClass.methods
            .filter { method -> method.name == methodName && method.parameterCount == 1 }
            .forEach { method ->
                if (method.parameterTypes[0].isAssignableFrom(value.javaClass) && runCatching { method.invoke(target, value) }.isSuccess) return true
            }
        return false
    }

    private fun ensureDefaultRosters() {
        rosterRoot().createDirectories()
        defaultRosters.forEach { (npcId, species) -> ensureDefaultRoster(npcId, npcId.replace('_', ' ').replaceFirstChar { it.titlecase() }, species) }
    }

    private fun ensureDefaultRoster(npcId: String, displayName: String, species: List<String> = defaultRosters[cleanId(npcId)].orEmpty()) {
        if (species.isEmpty()) return
        val file = rosterFile(npcId)
        if (file.exists()) return
        file.writeText(defaultRosterJson(displayName, species))
    }

    private fun defaultRosterJson(displayName: String, species: List<String>): String {
        val team = species.joinToString(",\n") { id -> pokemonJson(id) }
        return """
            {
              "name": "$displayName",
              "ai": { "type": "rct" },
              "battleFormat": "GEN_9_SINGLES",
              "battleRules": {
                "maxItemUses": 0,
                "healPlayers": true,
                "adjustPlayerLevels": false,
                "adjustNPCLevels": false
              },
              "team": [
            $team
              ]
            }
        """.trimIndent()
    }

    private fun pokemonJson(species: String): String {
        val clean = if (species.contains(':')) species else "cobblemon:$species"
        return """
                {
                  "species": "$clean",
                  "level": 50,
                  "ivs": { "hp": 31, "atk": 31, "def": 31, "spa": 31, "spd": 31, "spe": 31 },
                  "evs": { "atk": 252, "spa": 4, "spe": 252 },
                  "moveset": []
                }""".trimEnd()
    }

    private fun rosterFile(npcId: String): Path = rosterRoot().resolve("${cleanId(npcId)}.json")

    private fun rosterTomlFile(npcId: String): Path = rosterRoot().resolve("${cleanId(npcId)}.toml")

    private fun existingRosterFile(npcId: String): Path? {
        val json = rosterFile(npcId)
        if (json.exists()) return json
        val toml = rosterTomlFile(npcId)
        if (toml.exists()) return toml
        return null
    }

    private fun rosterRoot(): Path = FMLPaths.CONFIGDIR.get().resolve(ChowKingdomMod.MOD_ID).resolve("npc_battles").resolve("rosters")

    private fun playerTrainerId(player: ServerPlayer): String = "player_${player.stringUUID}"

    private fun cleanId(value: String): String = value.trim().lowercase().replace(Regex("[^a-z0-9_.:-]+"), "_").trim('_')

    private val defaultRosters: Map<String, List<String>> = linkedMapOf(
        "aang" to listOf("altaria", "pidgeot", "dragonite", "noivern", "talonflame", "swanna", "xatu", "jumpluff", "whimsicott", "drifblim", "braviary", "corviknight", "gliscor", "mantine", "togekiss"),
        "aloy" to listOf("lycanroc", "luxray", "manectric", "ampharos", "kleavor", "scizor", "metagross", "skarmory", "absol", "arcanine", "houndoom", "jolteon", "aggron", "bisharp", "falinks"),
        "princess_bubblegum" to listOf("chansey", "blissey", "clefable", "alcremie", "sylveon", "gardevoir", "reuniclus", "porygon2", "porygon_z", "rotom", "ampharos", "audino", "hatterene", "tinkaton", "musharna"),
        "ciri" to listOf("absol", "gallade", "gardevoir", "ceruledge", "houndoom", "zoroark", "mismagius", "gengar", "weavile", "umbreon", "lucario", "rapidash", "arcanine", "bisharp", "aegislash"),
        "elsa" to listOf("glaceon", "froslass", "abomasnow", "mamoswine", "lapras", "walrein", "weavile", "aurorus", "avalugg", "cryogonal", "vanilluxe", "frosmoth", "eiscue", "dewgong", "glalie"),
        "ezio" to listOf("talonflame", "decidueye", "corviknight", "absol", "umbreon", "zoroark", "weavile", "greninja", "bisharp", "honchkrow", "crobat", "gliscor", "scizor", "gallade", "lucario"),
        "finn" to listOf("growlithe", "arcanine", "lucario", "gallade", "heracross", "machamp", "sirfetchd", "dragonite", "aegislash", "lycanroc", "tauros", "haxorus", "breloom", "falinks", "stoutland"),
        "gandalf" to listOf("alakazam", "delphox", "gardevoir", "gallade", "espeon", "bronzong", "oranguru", "starmie", "xatu", "noivern", "hatterene", "reuniclus", "slowking", "chandelure", "metagross"),
        "geralt" to listOf("absol", "houndoom", "umbreon", "zoroark", "weavile", "lycanroc", "lucario", "corviknight", "aegislash", "toxicroak", "crobat", "arcanine", "bisharp", "ceruledge", "hydreigon"),
        "huntress_wizard" to listOf("decidueye", "leafeon", "trevenant", "shiftry", "breloom", "roserade", "lurantis", "whimsicott", "sawsbuck", "scyther", "heracross", "galvantula", "vespiquen", "flygon", "noivern"),
        "invoker" to listOf("rotom", "alakazam", "metagross", "magnezone", "ampharos", "espeon", "slowking", "reuniclus", "bronzong", "gardevoir", "starmie", "gengar", "dragonite", "hydreigon", "noivern"),
        "katara" to listOf("lapras", "milotic", "vaporeon", "gyarados", "starmie", "slowbro", "slowking", "kingdra", "swampert", "ludicolo", "mantine", "walrein", "floatzel", "primarina", "dewgong"),
        "legolas" to listOf("leafeon", "decidueye", "pidgeot", "talonflame", "corviknight", "braviary", "gliscor", "scyther", "heracross", "gallade", "lucario", "breloom", "shiftry", "flygon", "noivern"),
        "link" to listOf("lucario", "leafeon", "gallade", "aegislash", "arcanine", "rapidash", "decidueye", "corviknight", "snorlax", "lapras", "dragonite", "roserade", "breloom", "sylveon", "haxorus"),
        "marceline" to listOf("noivern", "crobat", "houndoom", "umbreon", "gengar", "mismagius", "zoroark", "toxtricity", "exploud", "chandelure", "absol", "weavile", "honchkrow", "sableye", "drifblim"),
        "pope_leo" to listOf("primarina", "blissey", "audino", "clefable", "togekiss", "gardevoir", "gallade", "espeon", "stoutland", "arcanine", "bronzong", "sylveon", "chansey", "lucario", "altaria"),
        "prof_chowfan" to listOf("eevee", "pikachu", "bulbasaur", "charmander", "squirtle", "chikorita", "cyndaquil", "totodile", "treecko", "torchic", "mudkip", "riolu", "ralts", "porygon", "rotom"),
        "shoumai" to listOf("minccino", "cinccino", "glaceon", "ninetales", "meowstic", "espeon", "sylveon", "absol", "weavile", "froslass", "mawile", "altaria", "milotic", "gardevoir", "zoroark"),
        "tarnished" to listOf("corviknight", "aegislash", "lucario", "gallade", "bisharp", "aggron", "metagross", "skarmory", "houndoom", "arcanine", "dragonite", "tyranitar", "ceruledge", "armarouge", "falinks"),
        "toph" to listOf("onix", "steelix", "golem", "rhyperior", "hippowdon", "excadrill", "krookodile", "flygon", "torterra", "claydol", "gliscor", "mamoswine", "aggron", "tyranitar", "garganacl"),
        "traxex" to listOf("glaceon", "froslass", "weavile", "mamoswine", "walrein", "lapras", "dewgong", "cloyster", "abomasnow", "aurorus", "avalugg", "frosmoth", "eiscue", "delibird", "glalie"),
        "venti" to listOf("altaria", "pidgeot", "noivern", "talonflame", "whimsicott", "jumpluff", "xatu", "togekiss", "swanna", "chatot", "braviary", "drifblim", "tropius", "mantine", "corviknight"),
        "vi" to listOf("lucario", "machamp", "conkeldurr", "pangoro", "primeape", "annihilape", "hitmonchan", "hitmontop", "scrafty", "toxicroak", "heracross", "falinks", "medicham", "gallade", "hawlucha"),
        "zagreus" to listOf("houndoom", "ceruledge", "chandelure", "gengar", "marowak", "arcanine", "infernape", "talonflame", "hydreigon", "absol", "dusknoir", "cofagrigus", "spiritomb", "krookodile", "tyranitar"),
        "zelda" to listOf("dragonair", "altaria", "togekiss", "gardevoir", "sylveon", "espeon", "milotic", "lapras", "ninetales", "rapidash", "lucario", "bronzong", "haxorus", "dragonite", "clefable"),
        "zuko" to listOf("charizard", "arcanine", "houndoom", "ninetales", "talonflame", "infernape", "flareon", "typhlosion", "blaziken", "magmortar", "chandelure", "ceruledge", "armarouge", "salazzle", "torkoal"),
    )
}

data class NpcPokemonBattleStartResult(val started: Boolean, val message: String)

private data class PendingNpcBattleStart(
    val executeAtTick: Long,
    val playerUuid: UUID,
    val npcId: String,
    val quest: Boolean,
    var sampledSpecies: List<String> = emptyList(),
)

private data class NpcPokemonBattleContext(
    val playerUuid: UUID,
    val playerName: String,
    val npcId: String,
    val npcUuid: UUID,
    val quest: Boolean,
    val partySnapshot: NpcPokemonPartySnapshot,
    val sampledSpecies: List<String>,
)

private data class ActiveNpcPokemonBattleLock(
    val npcId: String,
    val npcUuid: UUID,
    val playerUuid: UUID?,
    val quest: Boolean,
    val anchorDimension: String,
    val anchorX: Double,
    val anchorY: Double,
    val anchorZ: Double,
    val anchorYaw: Float,
)

private data class PendingFacingSync(
    val untilTick: Long,
    val playerUuid: UUID,
    val npcUuid: UUID,
)

private data class NpcPokemonPartySnapshot(val entries: List<NpcPokemonSnapshot>)

private data class NpcPokemonSnapshot(
    val index: Int,
    val level: Double?,
    val experience: Double?,
    val currentHealth: Double?,
    val status: Any?,
)

private const val BATTLE_NPC_ANCHOR_TOLERANCE_SQR = 0.08 * 0.08
