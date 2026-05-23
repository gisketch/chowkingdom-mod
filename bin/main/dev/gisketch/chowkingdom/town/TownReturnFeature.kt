package dev.gisketch.chowkingdom.town

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.roles.RoleStore
import dev.gisketch.chowkingdom.snackbar.SnackbarIconKind
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarProgress
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.item.ItemTossEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import org.joml.Vector3f
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin

@Suppress("DEPRECATION")
object TownReturnFeature {
    private val ITEMS: DeferredRegister<Item> = DeferredRegister.create(Registries.ITEM, ChowKingdomMod.MOD_ID)
    private val channels: MutableMap<UUID, ActiveTownCharmChannel> = linkedMapOf()

    val TOWN_CHARM: DeferredHolder<Item, TownReturnItem> = ITEMS.register("town_charm", Supplier { TownReturnItem(Item.Properties().stacksTo(1)) })

    fun register(modBus: IEventBus) {
        ITEMS.register(modBus)
        TownReturnStore.load()
        TownReturnConfig.load()
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onEntityInteractSpecific)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onEntityInteract)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onRightClickBlock)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onRightClickItem)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onLeftClickBlock)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onAttackEntity)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onBlockBreak)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onBlockPlace)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onItemToss)
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
        NeoForge.EVENT_BUS.addListener(::onLivingDamagePre)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedOut)
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
    }

    fun beginReturn(player: ServerPlayer, stack: ItemStack) {
        TownReturnStore.load()
        if (channels.containsKey(player.uuid)) {
            notify(player, "RETURN ALREADY CHANNELING", "Hold still.", SnackbarType.ERROR)
            return
        }
        val portal = TownReturnStore.portal()
        if (portal == null) {
            notify(player, "NO TOWN PORTAL", "Ask an admin to set one.", SnackbarType.ERROR)
            return
        }
        if (TownReturnStore.portalLevel(player.server) == null) {
            notify(player, "TOWN PORTAL UNAVAILABLE", portal.dimension, SnackbarType.ERROR)
            return
        }
        val remainingMs = TownReturnStore.cooldownUntil(player) - System.currentTimeMillis()
        if (remainingMs > 0L) {
            notify(player, "RETURN ON COOLDOWN", formatCooldown(remainingMs), SnackbarType.ERROR)
            return
        }
        if (nearbyMonster(player)) {
            notify(player, "RETURN BLOCKED", "Monsters are too close.", SnackbarType.ERROR)
            return
        }
        if (stack.isEmpty) return

        val abilities = player.abilities
        val channel = ActiveTownCharmChannel(player.uuid, player.level().dimension(), player.x, player.y, player.z, player.isNoGravity, abilities.mayfly, abilities.flying, player.server.tickCount, player.server.tickCount + CHANNEL_TICKS, TownReturnConfig.colorFor(activeJobId(player)).sanitized())
        channels[player.uuid] = channel
        player.closeContainer()
        player.stopUsingItem()
        enableChannelFlight(player)
        lockChannelPlayer(player, channel, player.server.tickCount)
        player.playNotifySound(SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.45f, 1.25f)
        notifyChanneling(player)
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        TownReturnStore.load()
        TownReturnConfig.load()
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        val tick = event.server.tickCount
        channels.values.toList().forEach { channel ->
            val player = event.server.playerList.getPlayer(channel.playerId) ?: return@forEach cancel(channel, null)
            when {
                player.level().dimension() != channel.dimension -> cancel(channel, "RETURN CANCELED", "Dimension changed.")
                else -> {
                    lockChannelPlayer(player, channel, tick)
                    when {
                        nearbyMonster(player) -> cancel(channel, "RETURN CANCELED", "Monsters are too close.")
                        tick >= channel.completeAtTick -> complete(player, channel)
                        tick % PARTICLE_INTERVAL_TICKS == 0 -> animate(player, channel, tick)
                    }
                }
            }
        }
    }

    private fun onEntityInteractSpecific(event: PlayerInteractEvent.EntityInteractSpecific) {
        val player = event.entity as? ServerPlayer ?: return
        if (!isChanneling(player)) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
    }

    private fun onEntityInteract(event: PlayerInteractEvent.EntityInteract) {
        val player = event.entity as? ServerPlayer ?: return
        if (!isChanneling(player)) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
    }

    private fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        val player = event.entity as? ServerPlayer ?: return
        if (!isChanneling(player)) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
    }

    private fun onRightClickItem(event: PlayerInteractEvent.RightClickItem) {
        val player = event.entity as? ServerPlayer ?: return
        if (!isChanneling(player)) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
    }

    private fun onLeftClickBlock(event: PlayerInteractEvent.LeftClickBlock) {
        val player = event.entity as? ServerPlayer ?: return
        if (isChanneling(player)) event.isCanceled = true
    }

    private fun onAttackEntity(event: AttackEntityEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (isChanneling(player)) event.isCanceled = true
    }

    private fun onBlockBreak(event: BlockEvent.BreakEvent) {
        val player = event.player as? ServerPlayer ?: return
        if (isChanneling(player)) event.isCanceled = true
    }

    private fun onBlockPlace(event: BlockEvent.EntityPlaceEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (isChanneling(player)) event.isCanceled = true
    }

    private fun onItemToss(event: ItemTossEvent) {
        val player = event.player as? ServerPlayer ?: return
        if (!isChanneling(player)) return
        player.inventory.placeItemBackInInventory(event.entity.item.copy())
        event.entity.item.count = 0
        event.isCanceled = true
    }

    private fun onLivingDamagePre(event: LivingDamageEvent.Pre) {
        val player = event.entity as? ServerPlayer ?: return
        channels[player.uuid]?.let { channel -> cancel(channel, "RETURN CANCELED", "Damage interrupted you.") }
    }

    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        channels.remove(player.uuid)?.let { channel -> restoreChannelPlayer(player, channel) }
    }

    private fun complete(player: ServerPlayer, channel: ActiveTownCharmChannel) {
        val portal = TownReturnStore.portal()
        val level = TownReturnStore.portalLevel(player.server)
        if (portal == null || level == null) {
            cancel(channel, "RETURN CANCELED", "Town portal is unavailable.")
            return
        }
        channels.remove(player.uuid)
        restoreChannelPlayer(player, channel)
        TownReturnStore.markCooldown(player, System.currentTimeMillis() + CHARM_COOLDOWN_MS)
        val pos = portal.blockPos()
        (player.level() as? ServerLevel)?.sendParticles(ParticleTypes.POOF, player.x, player.y + 0.6, player.z, 24, 0.45, 0.65, 0.45, 0.02)
        player.teleportTo(level, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, player.yRot, player.xRot)
        player.fallDistance = 0.0f
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.x + 0.5, pos.y + 0.8, pos.z + 0.5, 16, 0.4, 0.4, 0.4, 0.0)
        player.playNotifySound(SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.65f, 1.2f)
        notify(player, "RETURNED TO TOWN", "Town Charm", SnackbarType.SUCCESS, SnackbarSounds.REWARD)
    }

    private fun cancel(channel: ActiveTownCharmChannel, title: String?, content: String? = null) {
        channels.remove(channel.playerId)
        val player = currentPlayer(channel.playerId)
        if (player == null) return
        restoreChannelPlayer(player, channel)
        if (title != null) notify(player, title, content.orEmpty(), SnackbarType.ERROR)
        player.playNotifySound(SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.35f, 0.8f)
    }

    private fun lockChannelPlayer(player: ServerPlayer, channel: ActiveTownCharmChannel, tick: Int) {
        player.closeContainer()
        player.stopUsingItem()
        player.isSprinting = false
        player.setShiftKeyDown(false)
        enableChannelFlight(player)
        player.setNoGravity(true)
        player.fallDistance = 0.0f
        player.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, LOCK_EFFECT_TICKS, 10, false, false, false))
        player.addEffect(MobEffectInstance(MobEffects.DIG_SLOWDOWN, LOCK_EFFECT_TICKS, 10, false, false, false))
        val targetY = channelTargetY(channel, tick)
        val dx = channel.x - player.x
        val dy = targetY - player.y
        val dz = channel.z - player.z
        if (dx * dx + dz * dz > EMERGENCY_LOCK_DISTANCE_SQR || abs(dy) > EMERGENCY_LOCK_Y_DISTANCE) {
            player.teleportTo(channel.x, targetY, channel.z)
            player.setDeltaMovement(Vec3.ZERO)
            return
        }
        player.setDeltaMovement(Vec3((dx * LOCK_CHASE_XZ).coerceIn(-LOCK_VELOCITY_XZ, LOCK_VELOCITY_XZ), (dy * LOCK_CHASE_Y).coerceIn(-LOCK_VELOCITY_Y, LOCK_VELOCITY_Y), (dz * LOCK_CHASE_XZ).coerceIn(-LOCK_VELOCITY_XZ, LOCK_VELOCITY_XZ)))
        player.hasImpulse = true
        player.hurtMarked = true
    }

    private fun restoreChannelPlayer(player: ServerPlayer, channel: ActiveTownCharmChannel) {
        val abilities = player.abilities
        abilities.mayfly = channel.wasMayfly
        abilities.flying = channel.wasFlying
        player.onUpdateAbilities()
        player.setNoGravity(channel.wasNoGravity)
        player.setDeltaMovement(Vec3.ZERO)
        player.fallDistance = 0.0f
        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN)
        player.removeEffect(MobEffects.DIG_SLOWDOWN)
    }

    private fun enableChannelFlight(player: ServerPlayer) {
        val abilities = player.abilities
        if (abilities.mayfly && abilities.flying) return
        abilities.mayfly = true
        abilities.flying = true
        player.onUpdateAbilities()
    }

    private fun channelTargetY(channel: ActiveTownCharmChannel, tick: Int): Double {
        val elapsed = (tick - channel.startedAtTick).coerceAtLeast(0)
        if (elapsed <= RISE_TICKS) {
            val progress = (elapsed.toDouble() / RISE_TICKS.toDouble()).coerceIn(0.0, 1.0)
            return channel.y + easeInOut(progress) * RISE_HEIGHT
        }
        val driftTicks = (CHANNEL_TICKS - RISE_TICKS).coerceAtLeast(1)
        val progress = ((elapsed - RISE_TICKS).toDouble() / driftTicks.toDouble()).coerceIn(0.0, 1.0)
        return channel.y + RISE_HEIGHT + easeInOut(progress) * FINAL_FLOAT_EXTRA_HEIGHT
    }

    private fun easeInOut(progress: Double): Double = progress * progress * (3.0 - 2.0 * progress)

    private fun animate(player: ServerPlayer, channel: ActiveTownCharmChannel, tick: Int) {
        val progress = channel.progress(tick)
        renderChannelRing(player, channel, tick)
        renderDestinationRing(player, channel, tick)
        if (tick % SOUND_INTERVAL_TICKS == 0) player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.25f, 0.9f + progress.toFloat() * 0.4f)
    }

    private fun renderChannelRing(player: ServerPlayer, channel: ActiveTownCharmChannel, tick: Int) {
        val level = player.level() as? ServerLevel ?: return
        val loop = ringLoop(tick, channel)
        val radius = RING_RADIUS * easeInOut(loop.expandProgress)
        val ringY = channelTargetY(channel, tick) + RING_BASE_Y_OFFSET + loop.liftProgress * RING_LIFT_HEIGHT
        renderParticleRing(level, channel.x, ringY, channel.z, radius, loop.visiblePoints, channel.color, loop.spin)
    }

    private fun renderDestinationRing(player: ServerPlayer, channel: ActiveTownCharmChannel, tick: Int) {
        val portal = TownReturnStore.portal() ?: return
        val level = TownReturnStore.portalLevel(player.server) ?: return
        val pos = portal.blockPos()
        val loop = ringLoop(tick, channel)
        val incomingProgress = easeInOut((Math.floorMod(tick - channel.startedAtTick, INCOMING_RING_LOOP_TICKS).toDouble() / INCOMING_RING_LOOP_TICKS.toDouble()).coerceIn(0.0, 1.0))
        val radius = RING_RADIUS * (0.75 + incomingProgress * 0.25)
        val ringY = pos.y + INCOMING_RING_START_Y - incomingProgress * INCOMING_RING_DROP
        val visiblePoints = (RING_POINTS * (1.0 - incomingProgress * RING_FADE_RATIO)).toInt().coerceAtLeast(RING_MIN_POINTS)
        renderParticleRing(level, pos.x + 0.5, ringY, pos.z + 0.5, radius, visiblePoints, channel.color, loop.spin)
    }

    private fun ringLoop(tick: Int, channel: ActiveTownCharmChannel): RingLoop {
        val loopTick = Math.floorMod(tick - channel.startedAtTick, RING_LOOP_TICKS)
        val expanding = loopTick <= RING_EXPAND_TICKS
        val expandProgress = (loopTick.toDouble() / RING_EXPAND_TICKS.toDouble()).coerceIn(0.0, 1.0)
        val liftProgress = if (expanding) 0.0 else ((loopTick - RING_EXPAND_TICKS).toDouble() / (RING_LOOP_TICKS - RING_EXPAND_TICKS).toDouble()).coerceIn(0.0, 1.0)
        val visiblePoints = (RING_POINTS * (1.0 - liftProgress * RING_FADE_RATIO)).toInt().coerceAtLeast(RING_MIN_POINTS)
        val spin = (tick - channel.startedAtTick).toDouble() * RING_SPIN_PER_TICK
        return RingLoop(expandProgress, liftProgress, visiblePoints, spin)
    }

    private fun renderParticleRing(level: ServerLevel, centerX: Double, centerY: Double, centerZ: Double, radius: Double, visiblePoints: Int, color: TownReturnParticleColor, spin: Double) {
        val dust = dustParticle(color)
        repeat(visiblePoints) { index ->
            val angle = spin + index.toDouble() / visiblePoints.toDouble() * PI * 2.0
            val x = centerX + cos(angle) * radius
            val z = centerZ + sin(angle) * radius
            level.sendParticles(dust, x, centerY, z, 1, 0.0, 0.0, 0.0, 0.0)
            if (index % SPARKLE_INTERVAL == 0) level.sendParticles(ParticleTypes.END_ROD, x, centerY + SPARKLE_Y_OFFSET, z, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private fun dustParticle(color: TownReturnParticleColor): DustParticleOptions {
        val sanitized = color.sanitized()
        return DustParticleOptions(Vector3f(sanitized.red / 255.0f, sanitized.green / 255.0f, sanitized.blue / 255.0f), sanitized.scale)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        event.dispatcher.register(Commands.literal("ck").then(townRoot()))
        event.dispatcher.register(Commands.literal("chowkingdom").then(townRoot()))
    }

    private fun townRoot(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("town")
        .requires { source -> source.hasPermission(2) }
        .then(portalRoot("portal"))
        .then(tpRoot())
        .then(cooldownRoot("cooldown"))
        .then(Commands.literal("give")
            .then(giveRoot("charm")))

    private fun portalRoot(name: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .then(Commands.literal("set").executes(::setPortal))
        .then(Commands.literal("status").executes(::statusPortal))
        .then(Commands.literal("clear").executes(::clearPortal))

    private fun tpRoot(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("tp")
        .then(cooldownClearRoot("clear"))
        .then(cooldownRoot("cooldown"))

    private fun cooldownRoot(name: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .then(cooldownClearRoot("clear"))

    private fun cooldownClearRoot(name: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .executes { context -> clearCooldown(context, context.source.playerOrException) }
        .then(Commands.argument("player", EntityArgument.player())
            .executes { context -> clearCooldown(context, EntityArgument.getPlayer(context, "player")) })

    private fun giveRoot(name: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .executes { context -> giveItem(context, context.source.playerOrException, 1) }
        .then(Commands.argument("player", EntityArgument.player())
            .executes { context -> giveItem(context, EntityArgument.getPlayer(context, "player"), 1) }
            .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                .executes { context -> giveItem(context, EntityArgument.getPlayer(context, "player"), IntegerArgumentType.getInteger(context, "count")) }))

    private fun setPortal(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        TownReturnStore.setPortal(player)
        context.source.sendSuccess({ Component.literal("Set town portal to ${player.level().dimension().location()} ${player.blockPosition()}.").withStyle(ChatFormatting.GREEN) }, true)
        notify(player, "TOWN PORTAL SET", "Shared return point updated.", SnackbarType.SUCCESS)
        return 1
    }

    private fun statusPortal(context: CommandContext<CommandSourceStack>): Int {
        val portal = TownReturnStore.portal()
        if (portal == null) {
            context.source.sendFailure(Component.literal("Town portal is not set."))
            return 0
        }
        context.source.sendSuccess({ Component.literal("Town portal: ${portal.dimension} ${portal.x} ${portal.y} ${portal.z} yaw=${portal.yaw} pitch=${portal.pitch}").withStyle(ChatFormatting.GREEN) }, false)
        return 1
    }

    private fun clearPortal(context: CommandContext<CommandSourceStack>): Int {
        TownReturnStore.clearPortal()
        context.source.sendSuccess({ Component.literal("Cleared town portal.").withStyle(ChatFormatting.YELLOW) }, true)
        return 1
    }

    private fun clearCooldown(context: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        val cleared = TownReturnStore.clearCooldown(player)
        val message = if (cleared) "Cleared Town Charm cooldown for ${player.gameProfile.name}." else "${player.gameProfile.name} had no Town Charm cooldown."
        context.source.sendSuccess({ Component.literal(message).withStyle(ChatFormatting.GREEN) }, true)
        notify(player, "TOWN CHARM READY", "Cooldown cleared.", SnackbarType.SUCCESS)
        return 1
    }

    private fun giveItem(context: CommandContext<CommandSourceStack>, player: ServerPlayer, count: Int): Int {
        val remaining = ItemStack(TOWN_CHARM.get(), count)
        player.inventory.add(remaining)
        if (!remaining.isEmpty) player.drop(remaining, false)
        context.source.sendSuccess({ Component.literal("Gave ${player.gameProfile.name} $count Town Charm.").withStyle(ChatFormatting.GREEN) }, true)
        notify(player, "TOWN CHARM RECEIVED", "Right-click to return to town.", SnackbarType.SUCCESS)
        return count
    }

    private fun nearbyMonster(player: ServerPlayer): Boolean = player.level().getEntitiesOfClass(Monster::class.java, player.boundingBox.inflate(MONSTER_BLOCK_RADIUS)) { monster ->
        monster.isAlive && !monster.isRemoved && !monster.isSpectator
    }.isNotEmpty()

    private fun isChanneling(player: ServerPlayer): Boolean = channels.containsKey(player.uuid)

    private fun activeJobId(player: ServerPlayer): String = RoleStore.activeJobIds(player).firstOrNull()
        ?: RoleStore.jobId(player).ifBlank { "default" }

    private fun notify(player: ServerPlayer, title: String, content: String = "", type: SnackbarType = SnackbarType.GENERIC, sound: String = SnackbarSounds.forType(type)) {
        SnackbarNetwork.send(player, SnackbarNotification.item(itemIcon(), title, content, type, sound))
    }

    private fun notifyChanneling(player: ServerPlayer) {
        SnackbarNetwork.clear(player)
        SnackbarNetwork.send(
            player,
            SnackbarNotification(
                SnackbarIconKind.ITEM,
                itemIcon(),
                "RETURN CHANNELING",
                "Town Charm",
                SnackbarType.GENERIC,
                SnackbarSounds.TRADE,
                SnackbarProgress(0, CHANNEL_PROGRESS_TO, CHANNEL_PROGRESS_TIER, CHANNEL_DURATION_MS),
                CHANNEL_DURATION_MS,
            ),
        )
    }

    private fun currentPlayer(playerId: UUID): ServerPlayer? = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer()?.playerList?.getPlayer(playerId)

    private fun formatCooldown(ms: Long): String {
        val seconds = ((ms + 999L) / 1000L).coerceAtLeast(1L)
        val minutes = seconds / 60L
        val remainder = seconds % 60L
        return if (minutes > 0L) "${minutes}m ${remainder}s remaining." else "${remainder}s remaining."
    }

    private fun itemIcon(): String = "${ChowKingdomMod.MOD_ID}:town_charm"

    private const val TICKS_PER_SECOND = 20
    private const val CHANNEL_TICKS = 5 * TICKS_PER_SECOND
    private const val CHANNEL_DURATION_MS = CHANNEL_TICKS * 50L
    private const val CHANNEL_PROGRESS_TO = 999
    private const val CHANNEL_PROGRESS_TIER = 1000
    private const val PARTICLE_INTERVAL_TICKS = 4
    private const val SOUND_INTERVAL_TICKS = 20
    private const val MONSTER_BLOCK_RADIUS = 16.0
    private const val RISE_TICKS = TICKS_PER_SECOND
    private const val RISE_HEIGHT = 1.0
    private const val FINAL_FLOAT_EXTRA_HEIGHT = 0.5
    private const val RING_LOOP_TICKS = 30
    private const val RING_EXPAND_TICKS = 10
    private const val RING_POINTS = 16
    private const val RING_MIN_POINTS = 5
    private const val RING_RADIUS = 1.15
    private const val RING_BASE_Y_OFFSET = 0.15
    private const val RING_LIFT_HEIGHT = 1.25
    private const val RING_FADE_RATIO = 0.7
    private const val RING_SPIN_PER_TICK = 0.08
    private const val INCOMING_RING_LOOP_TICKS = 24
    private const val INCOMING_RING_START_Y = 3.0
    private const val INCOMING_RING_DROP = 2.7
    private const val SPARKLE_INTERVAL = 4
    private const val SPARKLE_Y_OFFSET = 0.02
    private const val LOCK_CHASE_XZ = 0.35
    private const val LOCK_CHASE_Y = 0.35
    private const val LOCK_VELOCITY_XZ = 0.08
    private const val LOCK_VELOCITY_Y = 0.07
    private const val EMERGENCY_LOCK_DISTANCE_SQR = 0.75 * 0.75
    private const val EMERGENCY_LOCK_Y_DISTANCE = 4.0
    private const val LOCK_EFFECT_TICKS = 40
    private const val CHARM_COOLDOWN_MS = 10L * 60L * 1000L
}

private data class ActiveTownCharmChannel(
    val playerId: UUID,
    val dimension: net.minecraft.resources.ResourceKey<Level>,
    val x: Double,
    val y: Double,
    val z: Double,
    val wasNoGravity: Boolean,
    val wasMayfly: Boolean,
    val wasFlying: Boolean,
    val startedAtTick: Int,
    val completeAtTick: Int,
    val color: TownReturnParticleColor,
) {
    constructor(playerId: UUID, dimension: net.minecraft.resources.ResourceKey<Level>, x: Double, y: Double, z: Double, wasNoGravity: Boolean, startedAtTick: Int, completeAtTick: Int) : this(
        playerId,
        dimension,
        x,
        y,
        z,
        wasNoGravity,
        false,
        false,
        startedAtTick,
        completeAtTick,
        TownReturnParticleColor(),
    )

    fun progress(tick: Int): Double = ((tick - startedAtTick).toDouble() / (completeAtTick - startedAtTick).toDouble()).coerceIn(0.0, 1.0)
}

    private data class RingLoop(val expandProgress: Double, val liftProgress: Double, val visiblePoints: Int, val spin: Double)
