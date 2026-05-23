package dev.gisketch.chowkingdom.snackbar

import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent

object SnackbarFeature {
    fun register(modBus: IEventBus) {
        SnackbarConfig.load()
        SnackbarStore.load()
        SnackbarNetwork.register(modBus)
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        SnackbarCommands.register(event.dispatcher)
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        SnackbarConfig.load()
        SnackbarStore.load()
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? net.minecraft.server.level.ServerPlayer ?: return
        SnackbarStore.remember(player)
        SnackbarStore.drain(player).forEach { notification -> SnackbarNetwork.send(player, notification) }
    }
}