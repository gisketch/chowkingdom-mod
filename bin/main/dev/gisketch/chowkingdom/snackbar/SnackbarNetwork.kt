package dev.gisketch.chowkingdom.snackbar

import dev.gisketch.chowkingdom.ChowKingdomMod
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext

object SnackbarNetwork {
    fun register(modBus: IEventBus) {
        modBus.addListener(::registerPayloads)
    }

    fun send(player: ServerPlayer, notification: SnackbarNotification) {
        PacketDistributor.sendToPlayer(
            player,
            SnackbarPayload(
                notification.iconKind.id,
                notification.icon.take(MAX_ICON_LENGTH),
                notification.title.take(MAX_TITLE_LENGTH),
                notification.content.take(MAX_CONTENT_LENGTH),
                notification.type.id,
                notification.sound.take(MAX_SOUND_LENGTH),
                notification.durationMs ?: SnackbarConfig.durationMs(),
                notification.progress?.fromXp ?: 0,
                notification.progress?.toXp ?: 0,
                notification.progress?.tierSize ?: 0,
                notification.progress?.animationMs ?: 0L,
            ),
        )
    }

    fun send(player: ServerPlayer, icon: ResourceLocation, title: String, content: String = "", type: SnackbarType = SnackbarType.GENERIC, sound: String = SnackbarSounds.forType(type)) {
        send(player, SnackbarNotification.item(icon.toString(), title, content, type, sound))
    }

    fun sendOrQueue(server: MinecraftServer, playerId: java.util.UUID, notification: SnackbarNotification) {
        val player = server.playerList.getPlayer(playerId)
        if (player != null) send(player, notification) else SnackbarStore.queue(playerId, notification)
    }

    fun sendToAllKnown(server: MinecraftServer, notification: SnackbarNotification) {
        val onlineIds = server.playerList.players.map { it.uuid }.toSet()
        server.playerList.players.forEach { player -> send(player, notification) }
        SnackbarStore.knownPlayerIds().filter { it !in onlineIds }.forEach { playerId -> SnackbarStore.queue(playerId, notification) }
    }

    fun clear(player: ServerPlayer) {
        PacketDistributor.sendToPlayer(player, SnackbarClearPayload)
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToClient(SnackbarPayload.TYPE, SnackbarPayload.STREAM_CODEC, ::handleSnackbar)
        registrar.playToClient(SnackbarClearPayload.TYPE, SnackbarClearPayload.STREAM_CODEC, ::handleClear)
    }

    private fun handleSnackbar(payload: SnackbarPayload, context: IPayloadContext) {
        if (FMLEnvironment.dist != Dist.CLIENT) return
        context.enqueueWork {
            runCatching {
                val client = Class.forName("dev.gisketch.chowkingdom.snackbar.SnackbarClient")
                client.getMethod("show", SnackbarPayload::class.java).invoke(client.getField("INSTANCE").get(null), payload)
            }
        }
    }

    private fun handleClear(payload: SnackbarClearPayload, context: IPayloadContext) {
        if (FMLEnvironment.dist != Dist.CLIENT) return
        context.enqueueWork {
            runCatching {
                val client = Class.forName("dev.gisketch.chowkingdom.snackbar.SnackbarClient")
                client.getMethod("clearActive").invoke(client.getField("INSTANCE").get(null))
            }
        }
    }
}

data class SnackbarPayload(val iconKind: String, val icon: String, val title: String, val content: String, val type: String, val sound: String, val durationMs: Long, val progressFromXp: Int = 0, val progressToXp: Int = 0, val progressTierSize: Int = 0, val progressAnimationMs: Long = 0L) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SnackbarPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SnackbarPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "snackbar/show"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SnackbarPayload> = object : StreamCodec<RegistryFriendlyByteBuf, SnackbarPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): SnackbarPayload =
                SnackbarPayload(buffer.readUtf(MAX_ICON_KIND_LENGTH), buffer.readUtf(MAX_ICON_LENGTH), buffer.readUtf(MAX_TITLE_LENGTH), buffer.readUtf(MAX_CONTENT_LENGTH), buffer.readUtf(MAX_TYPE_LENGTH), buffer.readUtf(MAX_SOUND_LENGTH), buffer.readVarLong(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarLong())

            override fun encode(buffer: RegistryFriendlyByteBuf, value: SnackbarPayload) {
                buffer.writeUtf(value.iconKind.take(MAX_ICON_KIND_LENGTH), MAX_ICON_KIND_LENGTH)
                buffer.writeUtf(value.icon.take(MAX_ICON_LENGTH), MAX_ICON_LENGTH)
                buffer.writeUtf(value.title.take(MAX_TITLE_LENGTH), MAX_TITLE_LENGTH)
                buffer.writeUtf(value.content.take(MAX_CONTENT_LENGTH), MAX_CONTENT_LENGTH)
                buffer.writeUtf(value.type.take(MAX_TYPE_LENGTH), MAX_TYPE_LENGTH)
                buffer.writeUtf(value.sound.take(MAX_SOUND_LENGTH), MAX_SOUND_LENGTH)
                buffer.writeVarLong(value.durationMs.coerceIn(1_000L, 60_000L))
                buffer.writeVarInt(value.progressFromXp.coerceAtLeast(0))
                buffer.writeVarInt(value.progressToXp.coerceAtLeast(0))
                buffer.writeVarInt(value.progressTierSize.coerceAtLeast(0))
                buffer.writeVarLong(value.progressAnimationMs.coerceIn(0L, 60_000L))
            }
        }
    }
}

object SnackbarClearPayload : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SnackbarClearPayload> = TYPE

    val TYPE: CustomPacketPayload.Type<SnackbarClearPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "snackbar/clear"))
    val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, SnackbarClearPayload> = object : StreamCodec<RegistryFriendlyByteBuf, SnackbarClearPayload> {
        override fun decode(buffer: RegistryFriendlyByteBuf): SnackbarClearPayload = SnackbarClearPayload

        override fun encode(buffer: RegistryFriendlyByteBuf, value: SnackbarClearPayload) = Unit
    }
}

private const val MAX_ICON_KIND_LENGTH = 16
private const val MAX_ICON_LENGTH = 128
private const val MAX_TITLE_LENGTH = 96
private const val MAX_CONTENT_LENGTH = 256
private const val MAX_TYPE_LENGTH = 16
private const val MAX_SOUND_LENGTH = 128