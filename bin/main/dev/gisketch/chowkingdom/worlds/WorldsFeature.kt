package dev.gisketch.chowkingdom.worlds

import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.commands.arguments.AngleArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent

object WorldsFeature {
    val COZY_WORLD: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("ckdm", "cozy_world"))
    val SKY_LANDS: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("ckdm", "sky_lands"))

    fun register() {
        WorldsSpawnConfig.load()
        NeoForge.EVENT_BUS.addListener(::onPlayerRespawn)
        NeoForge.EVENT_BUS.addListener(::onPlayerTick)
        NeoForge.EVENT_BUS.addListener(::onSpawnPlacementCheck)
        NeoForge.EVENT_BUS.addListener(::onPositionCheck)
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
    }

    private fun onPlayerRespawn(event: PlayerEvent.PlayerRespawnEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (event.isEndConquered || player.respawnPosition != null) return
        player.server.execute { sendToConfiguredWorldSpawn(player) }
    }

    private fun onPlayerTick(event: PlayerTickEvent.Post) {
        val player = event.entity as? ServerPlayer ?: return
        val velocity = player.deltaMovement
        when {
            isSkyLands(player.level()) && player.y <= SKY_LANDS_FALLTHROUGH_Y -> {
                val overworld = player.server.overworld()
                teleportToOverworldSkyDrop(player, overworld, player.x, player.z, velocity)
            }
            player.level().dimension() == Level.OVERWORLD && player.y >= OVERWORLD_SKY_RETURN_Y -> {
                val skyLands = player.server.getLevel(SKY_LANDS) ?: return
                teleportToFallbackOrSpawn(player, skyLands, player.x, SKY_LANDS_RETURN_Y, player.z, velocity)
            }
        }
    }

    private fun onSpawnPlacementCheck(event: MobSpawnEvent.SpawnPlacementCheck) {
        if (!isSkyLands(event.level.level) || !blocksNaturalMobSpawn(event.spawnType)) return
        event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL)
    }

    private fun onPositionCheck(event: MobSpawnEvent.PositionCheck) {
        if (!isSkyLands(event.level.level) || !blocksNaturalMobSpawn(event.spawnType)) return
        event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        registerSetWorldSpawn(event)
        event.dispatcher.register(Commands.literal("ck").then(worldsRoot()))
        event.dispatcher.register(Commands.literal("chowkingdom").then(worldsRoot()))
    }

    private fun registerSetWorldSpawn(event: RegisterCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("setworldspawn")
                .requires { source -> source.hasPermission(2) }
                .executes { context -> setWorldSpawn(context, BlockPos.containing(context.source.position), 0.0f) }
                .then(
                    Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes { context -> setWorldSpawn(context, BlockPosArgument.getSpawnablePos(context, "pos"), 0.0f) }
                        .then(
                            Commands.argument("angle", AngleArgument.angle())
                                .executes { context -> setWorldSpawn(context, BlockPosArgument.getSpawnablePos(context, "pos"), AngleArgument.getAngle(context, "angle")) },
                        ),
                ),
        )
    }

    private fun worldsRoot() = Commands.literal("worlds")
        .requires { source -> source.hasPermission(2) }
        .then(Commands.literal("status").executes(::statusSelf).then(Commands.argument("player", EntityArgument.player()).executes(::status)))
        .then(Commands.literal("cozy_world").executes(::cozyWorldSelf).then(Commands.argument("player", EntityArgument.player()).executes(::cozyWorld)))
        .then(Commands.literal("cozy").executes(::cozyWorldSelf).then(Commands.argument("player", EntityArgument.player()).executes(::cozyWorld)))
        .then(Commands.literal("sky_lands").executes(::skyLandsSelf).then(Commands.argument("player", EntityArgument.player()).executes(::skyLands)))
        .then(Commands.literal("hub").executes(::skyLandsSelf).then(Commands.argument("player", EntityArgument.player()).executes(::skyLands)))
        .then(Commands.literal("overworld").executes(::overworldSelf).then(Commands.argument("player", EntityArgument.player()).executes(::overworld)))
        .then(Commands.literal("base").executes(::overworldSelf).then(Commands.argument("player", EntityArgument.player()).executes(::overworld)))

    private fun statusSelf(context: CommandContext<CommandSourceStack>): Int = status(context, context.source.playerOrException)

    private fun status(context: CommandContext<CommandSourceStack>): Int = status(context, EntityArgument.getPlayer(context, "player"))

    private fun status(context: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        val cozyLoaded = context.source.server.getLevel(COZY_WORLD) != null
        val skyLoaded = context.source.server.getLevel(SKY_LANDS) != null
        context.source.sendSuccess(
            {
                val cozySpawn = context.source.server.getLevel(COZY_WORLD)?.sharedSpawnPos
                val skySpawn = context.source.server.getLevel(SKY_LANDS)?.sharedSpawnPos
                Component.literal("Worlds: ${player.gameProfile.name} is in ${player.level().dimension().location()} | cozy_world_loaded=$cozyLoaded | cozy_spawn=$cozySpawn | sky_lands_loaded=$skyLoaded | sky_spawn=$skySpawn")
                    .withStyle(if (cozyLoaded || skyLoaded) ChatFormatting.GREEN else ChatFormatting.RED)
            },
            false,
        )
        return 1
    }

    private fun cozyWorldSelf(context: CommandContext<CommandSourceStack>): Int = cozyWorld(context, context.source.playerOrException)

    private fun cozyWorld(context: CommandContext<CommandSourceStack>): Int = cozyWorld(context, EntityArgument.getPlayer(context, "player"))

    private fun cozyWorld(context: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        return if (sendToCozyWorld(player)) {
            context.source.sendSuccess({ Component.literal("Sent ${player.gameProfile.name} to Cozy World.").withStyle(ChatFormatting.GREEN) }, true)
            1
        } else {
            context.source.sendFailure(Component.literal("Cozy World is not loaded. Check ckdm:cozy_world datapack/dimension setup."))
            0
        }
    }

    private fun skyLandsSelf(context: CommandContext<CommandSourceStack>): Int = skyLands(context, context.source.playerOrException)

    private fun skyLands(context: CommandContext<CommandSourceStack>): Int = skyLands(context, EntityArgument.getPlayer(context, "player"))

    private fun skyLands(context: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        return if (sendToSkyLands(player)) {
            context.source.sendSuccess({ Component.literal("Sent ${player.gameProfile.name} to Sky Lands.").withStyle(ChatFormatting.GREEN) }, true)
            1
        } else {
            context.source.sendFailure(Component.literal("Sky Lands is not loaded. Check Sky Archipelago and ckdm:sky_lands datapack."))
            0
        }
    }

    private fun overworldSelf(context: CommandContext<CommandSourceStack>): Int = overworld(context, context.source.playerOrException)

    private fun overworld(context: CommandContext<CommandSourceStack>): Int = overworld(context, EntityArgument.getPlayer(context, "player"))

    private fun overworld(context: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        val level = context.source.server.overworld()
        val pos = safeSpawnPos(level, level.sharedSpawnPos)
        teleportToFeet(player, level, pos, player.yRot, player.xRot)
        context.source.sendSuccess({ Component.literal("Sent ${player.gameProfile.name} to the normal overworld.").withStyle(ChatFormatting.GREEN) }, true)
        return 1
    }

    private fun sendToSkyLands(player: ServerPlayer): Boolean {
        val level = player.server.getLevel(SKY_LANDS) ?: return false
        return sendToLevelSpawn(player, level)
    }

    private fun sendToCozyWorld(player: ServerPlayer): Boolean {
        val level = player.server.getLevel(COZY_WORLD) ?: return false
        return sendToLevelSpawn(player, level)
    }

    private fun sendToConfiguredWorldSpawn(player: ServerPlayer): Boolean {
        val level = player.server.getLevel(WorldsSpawnConfig.worldSpawnDimension()) ?: player.server.overworld()
        return sendToLevelSpawn(player, level)
    }

    private fun sendToLevelSpawn(player: ServerPlayer, level: ServerLevel): Boolean {
        val pos = safeSpawnPos(level, level.sharedSpawnPos)
        teleportToFeet(player, level, pos, 0.0f, 0.0f)
        return true
    }

    private fun teleportToFallbackOrSpawn(
        player: ServerPlayer,
        level: ServerLevel,
        x: Double,
        y: Double,
        z: Double,
        velocity: net.minecraft.world.phys.Vec3,
    ) {
        val target = BlockPos.containing(x, y, z)
        val sameColumnSurface = heightmapSafePos(level, target)
        if (isSafeStandPosition(level, target) || (sameColumnSurface != null && y > sameColumnSurface.y)) {
            player.teleportTo(level, x, y, z, player.yRot, player.xRot)
            player.deltaMovement = velocity
        } else {
            val spawn = safeSpawnPos(level, level.sharedSpawnPos)
            teleportToFeet(player, level, spawn, player.yRot, player.xRot)
        }
        player.hurtMarked = true
    }

    private fun teleportToOverworldSkyDrop(
        player: ServerPlayer,
        level: ServerLevel,
        x: Double,
        z: Double,
        velocity: net.minecraft.world.phys.Vec3,
    ) {
        val column = BlockPos.containing(x, level.minBuildHeight.toDouble(), z)
        val surface = heightmapSafePos(level, column)
        val targetX = x
        val targetZ = z
        val targetSurfaceY = surface?.y ?: level.sharedSpawnPos.y
        val ceilingY = minOf(level.maxBuildHeight - OVERWORLD_SKY_DROP_TOP_MARGIN, OVERWORLD_SKY_RETURN_Y.toInt() - OVERWORLD_SKY_DROP_RETURN_GAP)
        val desiredY = ceilingY
        val minDropY = targetSurfaceY + OVERWORLD_SKY_DROP_MIN_ABOVE_SURFACE
        val targetY = if (minDropY <= ceilingY) desiredY.coerceIn(minDropY, ceilingY) else ceilingY
        player.teleportTo(level, targetX, targetY.toDouble(), targetZ, player.yRot, player.xRot)
        player.deltaMovement = if (velocity.y < 0.0) velocity else velocity.add(0.0, -0.08, 0.0)
        player.hurtMarked = true
    }

    private fun teleportToFeet(player: ServerPlayer, level: ServerLevel, pos: BlockPos, yaw: Float, pitch: Float) {
        player.teleportTo(level, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, yaw, pitch)
        player.deltaMovement = player.deltaMovement.scale(0.0)
        player.hurtMarked = true
    }

    private fun safeSpawnPos(level: ServerLevel, base: BlockPos): BlockPos {
        listOf(base, base.above(), base.below()).firstOrNull { pos -> isSafeStandPosition(level, pos) }?.let { return it }
        heightmapSafePos(level, base)?.let { return it }
        for (radius in 1..SAFE_SPAWN_SEARCH_RADIUS) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    if (kotlin.math.abs(dx) != radius && kotlin.math.abs(dz) != radius) continue
                    val candidate = BlockPos(base.x + dx, base.y, base.z + dz)
                    heightmapSafePos(level, candidate)?.let { return it }
                }
            }
        }
        return BlockPos(base.x, (base.y + 1).coerceIn(level.minBuildHeight + 2, level.maxBuildHeight - 2), base.z)
    }

    private fun heightmapSafePos(level: ServerLevel, base: BlockPos): BlockPos? {
        val pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base)
        return listOf(pos, pos.above(), pos.below()).firstOrNull { candidate -> isSafeStandPosition(level, candidate) }
    }

    private fun isSafeStandPosition(level: ServerLevel, pos: BlockPos): Boolean {
        val below = pos.below()
        if (below.y < level.minBuildHeight || pos.y >= level.maxBuildHeight - 1) return false
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) &&
            level.getBlockState(pos).getCollisionShape(level, pos).isEmpty &&
            level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty
    }

    private fun setWorldSpawn(context: CommandContext<CommandSourceStack>, pos: BlockPos, angle: Float): Int {
        val level = context.source.level
        if (level.dimension() !in SET_WORLD_SPAWN_DIMENSIONS) {
            context.source.sendFailure(Component.literal("World spawn can only be set in minecraft:overworld, ckdm:cozy_world, or ckdm:sky_lands."))
            return 0
        }
        level.setDefaultSpawnPos(pos, angle)
        WorldsSpawnConfig.setWorldSpawnDimension(level.dimension())
        context.source.sendSuccess({ Component.translatable("commands.setworldspawn.success", pos.x, pos.y, pos.z, angle) }, true)
        return 1
    }

    private fun blocksNaturalMobSpawn(spawnType: MobSpawnType): Boolean = spawnType in BLOCKED_SPAWN_TYPES

    private fun isSkyLands(level: Level): Boolean = level.dimension() == SKY_LANDS

    private val BLOCKED_SPAWN_TYPES = setOf(
        MobSpawnType.NATURAL,
        MobSpawnType.CHUNK_GENERATION,
        MobSpawnType.STRUCTURE,
        MobSpawnType.SPAWNER,
        MobSpawnType.PATROL,
        MobSpawnType.REINFORCEMENT,
        MobSpawnType.TRIAL_SPAWNER,
    )
    private val SET_WORLD_SPAWN_DIMENSIONS = setOf(Level.OVERWORLD, COZY_WORLD, SKY_LANDS)
    private const val SKY_LANDS_FALLTHROUGH_Y = 48.0
    private const val SKY_LANDS_RETURN_Y = 64.0
    private const val OVERWORLD_SKY_RETURN_Y = 600.0
    private const val OVERWORLD_SKY_DROP_MIN_ABOVE_SURFACE = 48
    private const val OVERWORLD_SKY_DROP_TOP_MARGIN = 16
    private const val OVERWORLD_SKY_DROP_RETURN_GAP = 32
    private const val SAFE_SPAWN_SEARCH_RADIUS = 32
}
