package dev.gisketch.chowkingdom.trading

import dev.gisketch.chowkingdom.ChowKingdomMod
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

object TradingNetwork {
    fun register(modBus: IEventBus) {
        modBus.addListener(::registerPayloads)
    }

    fun setGlow(player: ServerPlayer, targetId: UUID, enabled: Boolean) {
        PacketDistributor.sendToPlayer(player, TradeGlowPayload(targetId, enabled))
    }

    fun syncTo(player: ServerPlayer, payload: TradeStatePayload) {
        PacketDistributor.sendToPlayer(player, payload)
    }

    fun sendAction(sessionId: UUID, action: TradeAction, amount: Long = 0L) {
        runCatching { PacketDistributor.sendToServer(TradeActionPayload(sessionId, action.id, amount)) }
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToServer(TradeActionPayload.TYPE, TradeActionPayload.STREAM_CODEC, ::handleAction)
        registrar.playToClient(TradeStatePayload.TYPE, TradeStatePayload.STREAM_CODEC, ::handleState)
        registrar.playToClient(TradeGlowPayload.TYPE, TradeGlowPayload.STREAM_CODEC, ::handleGlow)
    }

    private fun handleAction(payload: TradeActionPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        TradingManager.handleAction(player, payload)
    }

    private fun handleState(payload: TradeStatePayload, context: IPayloadContext) {
        TradingClientState.apply(payload)
    }

    private fun handleGlow(payload: TradeGlowPayload, context: IPayloadContext) {
        TradingClient.setGlow(payload.entityId, payload.enabled)
    }
}

data class TradeActionPayload(val sessionId: UUID, val action: Int, val amount: Long) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TradeActionPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TradeActionPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "trading/action"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TradeActionPayload> = object : StreamCodec<RegistryFriendlyByteBuf, TradeActionPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): TradeActionPayload = TradeActionPayload(buffer.readUUID(), buffer.readVarInt(), buffer.readVarLong())

            override fun encode(buffer: RegistryFriendlyByteBuf, value: TradeActionPayload) {
                buffer.writeUUID(value.sessionId)
                buffer.writeVarInt(value.action)
                buffer.writeVarLong(value.amount)
            }
        }
    }
}

data class TradeStatePayload(
    val sessionId: UUID,
    val selfId: UUID,
    val otherId: UUID,
    val selfName: String,
    val otherName: String,
    val selfBalance: Long,
    val otherBalance: Long,
    val selfChowcoins: Long,
    val otherChowcoins: Long,
    val selfReady: Boolean,
    val otherReady: Boolean,
    val selfConfirmed: Boolean,
    val otherConfirmed: Boolean,
    val debug: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TradeStatePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TradeStatePayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "trading/state"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TradeStatePayload> = object : StreamCodec<RegistryFriendlyByteBuf, TradeStatePayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): TradeStatePayload = TradeStatePayload(
                buffer.readUUID(),
                buffer.readUUID(),
                buffer.readUUID(),
                buffer.readUtf(32),
                buffer.readUtf(32),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
            )

            override fun encode(buffer: RegistryFriendlyByteBuf, value: TradeStatePayload) {
                buffer.writeUUID(value.sessionId)
                buffer.writeUUID(value.selfId)
                buffer.writeUUID(value.otherId)
                buffer.writeUtf(value.selfName, 32)
                buffer.writeUtf(value.otherName, 32)
                buffer.writeVarLong(value.selfBalance)
                buffer.writeVarLong(value.otherBalance)
                buffer.writeVarLong(value.selfChowcoins)
                buffer.writeVarLong(value.otherChowcoins)
                buffer.writeBoolean(value.selfReady)
                buffer.writeBoolean(value.otherReady)
                buffer.writeBoolean(value.selfConfirmed)
                buffer.writeBoolean(value.otherConfirmed)
                buffer.writeBoolean(value.debug)
            }
        }
    }
}

data class TradeGlowPayload(val entityId: UUID, val enabled: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TradeGlowPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TradeGlowPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "trading/glow"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TradeGlowPayload> = object : StreamCodec<RegistryFriendlyByteBuf, TradeGlowPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): TradeGlowPayload = TradeGlowPayload(buffer.readUUID(), buffer.readBoolean())

            override fun encode(buffer: RegistryFriendlyByteBuf, value: TradeGlowPayload) {
                buffer.writeUUID(value.entityId)
                buffer.writeBoolean(value.enabled)
            }
        }
    }
}
