package dev.gisketch.chowkingdom.wallets

import dev.gisketch.chowkingdom.ChowKingdomMod
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.server.ServerLifecycleHooks

object ChowcoinNetwork {
    fun register(modBus: IEventBus) {
        modBus.addListener(::registerPayloads)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
    }

    fun syncTo(player: ServerPlayer) {
        PacketDistributor.sendToPlayer(player, ChowcoinSyncPayload(ChowcoinStore.get(player)))
    }

    fun syncAllPlayers() {
        val server = ServerLifecycleHooks.getCurrentServer() ?: return
        server.playerList.players.forEach(::syncTo)
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToClient(ChowcoinSyncPayload.TYPE, ChowcoinSyncPayload.STREAM_CODEC, ::handleSync)
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        syncTo(player)
    }

    private fun handleSync(payload: ChowcoinSyncPayload, context: IPayloadContext) {
        ChowcoinClientState.apply(payload)
    }
}

data class ChowcoinSyncPayload(val balance: Long) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ChowcoinSyncPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ChowcoinSyncPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "wallets/chowcoin_sync"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ChowcoinSyncPayload> = object : StreamCodec<RegistryFriendlyByteBuf, ChowcoinSyncPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): ChowcoinSyncPayload = ChowcoinSyncPayload(buffer.readVarLong())

            override fun encode(buffer: RegistryFriendlyByteBuf, value: ChowcoinSyncPayload) {
                buffer.writeVarLong(value.balance)
            }
        }
    }
}