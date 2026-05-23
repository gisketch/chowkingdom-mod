package dev.gisketch.chowkingdom.wallets

import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStartedEvent

object WalletsFeature {
    fun register(modBus: IEventBus) {
        ChowcoinStore.load()
        ChowcoinNetwork.register(modBus)
        ChowcoinCommands.register()
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        ChowcoinStore.load()
        ChowcoinNetwork.syncAllPlayers()
    }
}