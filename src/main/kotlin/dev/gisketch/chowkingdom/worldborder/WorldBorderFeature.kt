package dev.gisketch.chowkingdom.worldborder

import com.mojang.brigadier.context.CommandContext
import dev.gisketch.chowkingdom.shipping.ShippingBinStore
import dev.gisketch.chowkingdom.snackbar.SnackbarIcons
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

object WorldBorderFeature {
    private val warningCooldowns: MutableMap<UUID, Long> = linkedMapOf()
    private var lastAppliedRadius = -1
    private var activeTier = WorldBorderTier(0L, 1_500, "KINGDOM BORDER", "Default border radius is 1,500 blocks.")

    fun register() {
        WorldBorderConfig.load()
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
    }

    fun checkShippingUnlocks(server: MinecraftServer, announce: Boolean = true) {
        if (!WorldBorderConfig.enabled()) return
        WorldBorderStore.ensureLoaded(server)
        val total = ShippingBinStore.totalChowcoinsSold()
        val totalTier = WorldBorderConfig.tierFor(total)
        val persistedTier = WorldBorderConfig.tierAtOrBelow(WorldBorderStore.highestUnlockedThreshold(server))
        val tier = listOf(totalTier, persistedTier).maxBy { it.radiusBlocks }
        val previousRadius = WorldBorderStore.snapshot(server).lastAppliedRadius.takeIf { it > 0 } ?: lastAppliedRadius
        applyToAllDimensions(server, tier)
        activeTier = tier
        lastAppliedRadius = tier.radiusBlocks
        val expanded = previousRadius > 0 && tier.radiusBlocks > previousRadius
        val shouldAnnounce = announce && expanded && tier.thresholdChowcoins > 0L && tier.thresholdChowcoins !in WorldBorderStore.snapshot(server).announcedThresholds
        WorldBorderStore.markApplied(server, tier.thresholdChowcoins, tier.radiusBlocks, shouldAnnounce)
        if (shouldAnnounce) announceExpansion(server, tier)
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        WorldBorderConfig.load()
        WorldBorderStore.load(event.server)
        checkShippingUnlocks(event.server, announce = false)
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        if (!WorldBorderConfig.enabled()) return
        if (event.server.tickCount % WorldBorderConfig.applyIntervalTicks() == 0) checkShippingUnlocks(event.server, announce = true)
        if (event.server.tickCount % WorldBorderConfig.checkIntervalTicks() == 0) warnNearBorder(event.server)
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        checkShippingUnlocks(player.server, announce = false)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        event.dispatcher.register(worldBorderRoot("worldborder"))
        event.dispatcher.register(Commands.literal("chowkingdom").then(worldBorderRoot("worldborder")))
        event.dispatcher.register(Commands.literal("ck").then(worldBorderRoot("worldborder")))
    }

    private fun worldBorderRoot(name: String) = Commands.literal(name)
        .requires { source -> source.hasPermission(2) }
        .then(Commands.literal("status").executes(::status))
        .then(Commands.literal("check").executes(::check))
        .then(Commands.literal("reload").executes(::reload))
        .then(Commands.literal("reset").executes(::reset))

    private fun applyToAllDimensions(server: MinecraftServer, tier: WorldBorderTier) {
        server.allLevels.forEach { level -> applyToDimension(level, tier.radiusBlocks) }
    }

    private fun applyToDimension(level: ServerLevel, radius: Int) {
        val border = level.worldBorder
        border.setCenter(WorldBorderConfig.centerX(), WorldBorderConfig.centerZ())
        border.setSize(radius.toDouble() * 2.0)
        border.setWarningBlocks(WorldBorderConfig.warningDistanceBlocks())
    }

    private fun warnNearBorder(server: MinecraftServer) {
        val tier = activeTier.takeIf { it.radiusBlocks > 0 } ?: WorldBorderConfig.tierAtOrBelow(WorldBorderStore.highestUnlockedThreshold(server))
        val radius = tier.radiusBlocks.toDouble()
        val warningDistance = WorldBorderConfig.warningDistanceBlocks().toDouble()
        val now = server.tickCount.toLong()
        server.playerList.players.forEach { player ->
            val distance = distanceToConfiguredBorder(player, radius)
            if (distance > warningDistance) return@forEach
            val previous = warningCooldowns[player.uuid]
            if (previous != null && now - previous < WorldBorderConfig.warningCooldownTicks()) return@forEach
            warningCooldowns[player.uuid] = now
            SnackbarNetwork.send(
                player,
                SnackbarNotification.item(
                    SnackbarIcons.ERROR,
                    "WORLD BORDER AHEAD",
                    "Ship more goods to expand the kingdom border. Current radius: ${format(tier.radiusBlocks.toLong())}.",
                    SnackbarType.ERROR,
                    SnackbarSounds.ERROR,
                ),
            )
        }
        warningCooldowns.entries.removeIf { (_, tick) -> now - tick > WorldBorderConfig.warningCooldownTicks() * 6L }
    }

    private fun distanceToConfiguredBorder(player: ServerPlayer, radius: Double): Double {
        val dx = abs(player.x - WorldBorderConfig.centerX())
        val dz = abs(player.z - WorldBorderConfig.centerZ())
        return radius - max(dx, dz)
    }

    private fun announceExpansion(server: MinecraftServer, tier: WorldBorderTier) {
        SnackbarNetwork.sendToAllKnown(
            server,
            SnackbarNotification.item(
                "minecraft:filled_map",
                tier.title,
                tier.message,
                SnackbarType.SUCCESS,
                SnackbarSounds.REWARD,
            ),
        )
    }

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val server = context.source.server
        WorldBorderStore.ensureLoaded(server)
        val total = ShippingBinStore.totalChowcoinsSold()
        val state = WorldBorderStore.snapshot(server)
        val tier = WorldBorderConfig.tierAtOrBelow(state.highestUnlockedThreshold)
        context.source.sendSuccess(
            {
                Component.literal(
                    "World border radius ${format(tier.radiusBlocks.toLong())}; shipping ${format(total)}; highest threshold ${format(state.highestUnlockedThreshold)}; dimensions ${server.allLevels.count()}.",
                )
            },
            false,
        )
        return tier.radiusBlocks
    }

    private fun check(context: CommandContext<CommandSourceStack>): Int {
        checkShippingUnlocks(context.source.server, announce = true)
        val radius = WorldBorderStore.snapshot(context.source.server).lastAppliedRadius
        context.source.sendSuccess({ Component.literal("Checked world border. Radius: ${format(radius.toLong())}.") }, true)
        return radius.coerceAtLeast(1)
    }

    private fun reload(context: CommandContext<CommandSourceStack>): Int {
        WorldBorderConfig.load()
        WorldBorderStore.load(context.source.server)
        checkShippingUnlocks(context.source.server, announce = false)
        context.source.sendSuccess({ Component.literal("Reloaded world border config.") }, true)
        return 1
    }

    private fun reset(context: CommandContext<CommandSourceStack>): Int {
        WorldBorderStore.reset(context.source.server)
        checkShippingUnlocks(context.source.server, announce = false)
        context.source.sendSuccess({ Component.literal("Reset world border state and reapplied current shipping tier.") }, true)
        return 1
    }

    private fun format(value: Long): String = String.format(Locale.US, "%,d", value)
}
