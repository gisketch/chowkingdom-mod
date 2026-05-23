package dev.gisketch.chowkingdom.randomtrainers

import dev.gisketch.chowkingdom.ChowKingdomMod
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.SpawnUtil
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

object RandomTrainerSpawner {
    private const val SPAWN_RETRIES = 8
    private val active: MutableMap<UUID, ActiveRandomTrainerSpawn> = linkedMapOf()
    private val nextAttemptTick: MutableMap<UUID, Long> = linkedMapOf()
    private val lastNaturalStatus: MutableMap<UUID, NaturalSpawnDebugState> = linkedMapOf()
    private val nextDebugStatusTick: MutableMap<UUID, Long> = linkedMapOf()
    private val liveDebugPlayers: MutableSet<UUID> = linkedSetOf()
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        NeoForge.EVENT_BUS.addListener(::onServerTick)
    }

    fun track(entity: RandomTrainerEntity) {
        val now = (entity.level() as? ServerLevel)?.server?.overworld()?.gameTime ?: entity.tickCount.toLong()
        active[entity.uuid] = active[entity.uuid] ?: ActiveRandomTrainerSpawn(
            entityUuid = entity.uuid,
            originPlayerUuid = entity.originPlayerUuid,
            rosterId = entity.rosterId,
            lastSeenTick = now,
        )
    }

    fun spawnedCount(): Int = active.size

    fun despawnAll(server: MinecraftServer): Int {
        val entities = active.keys.mapNotNull { uuid -> entityByUuid(server, uuid) }
        entities.forEach { it.discard() }
        val count = entities.size
        active.clear()
        return count
    }

    fun debugSpawn(player: ServerPlayer, rosterId: String = ""): Boolean {
        val definition = rosterId.takeIf { it.isNotBlank() }?.let(RandomTrainerCatalog::byId)
            ?: RandomTrainerCatalog.pickFor(player, RandomTrainerBattleService.playerTopLevel(player), emptySet())
            ?: return false
        val pos = spawnPos(player, nearPlayer = true) ?: player.blockPosition()
        return spawn(player, definition, pos)
    }

    fun debugStatus(player: ServerPlayer): List<String> {
        val settings = RandomTrainerCatalog.settings()
        val stats = RandomTrainerCatalog.stats()
        val level = player.level() as? ServerLevel
        val now = level?.server?.overworld()?.gameTime ?: 0L
        val dimensionId = level?.dimension()?.location()?.toString().orEmpty()
        val due = nextAttemptTick[player.uuid] ?: 0L
        val status = lastNaturalStatus[player.uuid]
        val allowed = level != null && canNaturalSpawnIn(player, settings)
        val activeForPlayer = active.values.count { it.originPlayerUuid == player.uuid }
        return listOf(
            "Random trainer natural spawn debug:",
            "enabled=${settings.enabled}, natural=${settings.naturalSpawning}, debugNatural=${settings.debugNaturalSpawning}",
            "player=${player.name.string}, mode=${if (player.isCreative) "creative" else if (player.isSpectator) "spectator" else "survival"}, dimension=$dimensionId, dimensionAllowed=$allowed",
            "catalog=${stats.trainerCount} loaded, spawnable=${stats.spawnableCount}, imported=${stats.importedCount}, generated=${stats.generatedCount}, invalid=${stats.invalidCount}",
            "active=${active.size}/${settings.maxTrainersTotal}, activeForPlayer=$activeForPlayer/${settings.maxTrainersPerPlayer}, defeated=${RandomTrainerStore.defeated(player).size}",
            "chance=${settings.globalSpawnChance}, interval=${settings.spawnIntervalTicks}-${settings.spawnIntervalTicksMaximum} ticks, nextAttemptIn=${(due - now).coerceAtLeast(0)} ticks",
            "spawnRange=${settings.minHorizontalDistanceToPlayers}-${settings.maxHorizontalDistanceToPlayers} horizontal, vertical=${settings.maxVerticalDistanceToPlayers}, retries=$SPAWN_RETRIES",
            "last=${status?.summary(now) ?: "none yet"}",
        )
    }

    fun setLiveDebug(player: ServerPlayer, enabled: Boolean): Boolean {
        if (enabled) {
            liveDebugPlayers += player.uuid
        } else {
            liveDebugPlayers -= player.uuid
        }
        return player.uuid in liveDebugPlayers
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        val settings = RandomTrainerCatalog.settings()
        if (!settings.enabled) return
        cleanup(server, settings)
        if (!settings.naturalSpawning) return
        val now = server.overworld().gameTime
        server.playerList.players.forEach { player ->
            if (player.isSpectator || player.isCreative) {
                recordNaturalStatus(player, settings, now, "blocked_player_mode", if (player.isCreative) "creative" else "spectator")
                return@forEach
            }
            if (!canNaturalSpawnIn(player, settings)) {
                val dimensionId = (player.level() as? ServerLevel)?.dimension()?.location()?.toString().orEmpty()
                recordNaturalStatus(player, settings, now, "blocked_dimension", dimensionId)
                return@forEach
            }
            val due = nextAttemptTick[player.uuid] ?: 0L
            if (now < due) return@forEach
            nextAttemptTick[player.uuid] = now + nextInterval(player, settings)
            val roll = player.random.nextDouble()
            val chance = settings.globalSpawnChance.coerceIn(0.0, 1.0)
            if (roll > chance) {
                recordNaturalStatus(player, settings, now, "chance_failed", "roll=${"%.3f".format(roll)} chance=$chance")
                return@forEach
            }
            val result = attemptSpawn(player, settings)
            recordNaturalStatus(player, settings, now, result.reason, result.detail, result.pos)
        }
    }

    private fun attemptSpawn(player: ServerPlayer, settings: RandomTrainerSettings): NaturalSpawnAttemptResult {
        if (active.size >= settings.maxTrainersTotal.coerceAtLeast(1)) {
            return NaturalSpawnAttemptResult("blocked_total_cap", "active=${active.size}")
        }
        val activeForPlayer = active.values.count { it.originPlayerUuid == player.uuid }
        if (activeForPlayer >= settings.maxTrainersPerPlayer.coerceAtLeast(1)) {
            return NaturalSpawnAttemptResult("blocked_player_cap", "activeForPlayer=$activeForPlayer")
        }
        val topLevel = RandomTrainerBattleService.playerTopLevel(player).coerceAtLeast(1)
        val defeated = RandomTrainerStore.defeated(player)
        val definition = RandomTrainerCatalog.pickFor(player, topLevel, defeated)
            ?: return NaturalSpawnAttemptResult("no_roster_candidate", "topLevel=$topLevel defeated=${defeated.size}")
        spawnWithVanillaPlacement(player, definition, settings)?.let { return it }
        var noPosition = 0
        var duplicateNearby = 0
        var addFailed = 0
        val positionDiagnostics = SpawnPositionDiagnostics()
        repeat(SPAWN_RETRIES) {
            val pos = spawnPos(player, diagnostics = positionDiagnostics)
            if (pos == null) {
                noPosition += 1
                return@repeat
            }
            if (isUniqueNearby(player.server, definition.id, player.level() as ServerLevel, pos, settings.uniqueTrainerRadius)) {
                if (spawn(player, definition, pos)) {
                    return NaturalSpawnAttemptResult("spawned", definition.id, pos)
                }
                addFailed += 1
            } else {
                duplicateNearby += 1
            }
        }
        return NaturalSpawnAttemptResult("spawn_failed", "noPosition=$noPosition ${positionDiagnostics.summary()} duplicateNearby=$duplicateNearby addFailed=$addFailed roster=${definition.id}")
    }

    private fun spawnWithVanillaPlacement(player: ServerPlayer, definition: RandomTrainerDefinition, settings: RandomTrainerSettings): NaturalSpawnAttemptResult? {
        val level = player.level() as? ServerLevel ?: return NaturalSpawnAttemptResult("spawn_failed", "player level is not ServerLevel")
        val horizontalRange = settings.maxHorizontalDistanceToPlayers.coerceAtLeast(settings.minHorizontalDistanceToPlayers).coerceAtLeast(8)
        val verticalRange = settings.maxVerticalDistanceToPlayers.coerceAtLeast(8)
        val attempts = (SPAWN_RETRIES * 4).coerceAtLeast(16)
        val spawned = SpawnUtil.trySpawnMob(
            RandomTrainerFeature.RANDOM_TRAINER_ENTITY.get(),
            MobSpawnType.NATURAL,
            level,
            player.blockPosition(),
            attempts,
            horizontalRange,
            verticalRange,
            SpawnUtil.Strategy.ON_TOP_OF_COLLIDER,
        ).orElse(null) ?: return null
        val pos = spawned.blockPosition()
        if (!isUniqueNearby(player.server, definition.id, level, pos, settings.uniqueTrainerRadius)) {
            spawned.discard()
            return NaturalSpawnAttemptResult("spawn_failed", "vanillaPlacement=duplicateNearby roster=${definition.id}")
        }
        spawned.configure(definition, player, level.server.overworld().gameTime)
        track(spawned)
        return NaturalSpawnAttemptResult("spawned", definition.id, pos)
    }

    private fun spawn(player: ServerPlayer, definition: RandomTrainerDefinition, pos: BlockPos): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        val entity = RandomTrainerFeature.RANDOM_TRAINER_ENTITY.get().create(level) ?: return false
        entity.configure(definition, player, level.server.overworld().gameTime)
        entity.moveTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, level.random.nextFloat() * 360.0f, 0.0f)
        val added = level.addFreshEntity(entity)
        if (added) track(entity)
        return added
    }

    private fun spawnPos(player: ServerPlayer, nearPlayer: Boolean = false, diagnostics: SpawnPositionDiagnostics? = null): BlockPos? {
        val settings = RandomTrainerCatalog.settings()
        val level = player.level() as? ServerLevel ?: return null
        val minDistance = if (nearPlayer) 4 else settings.minHorizontalDistanceToPlayers.coerceAtLeast(1)
        val maxDistance = if (nearPlayer) 8 else settings.maxHorizontalDistanceToPlayers.coerceAtLeast(minDistance)
        val maxY = settings.maxVerticalDistanceToPlayers.coerceAtLeast(4)
        repeat(SPAWN_RETRIES) {
            val angle = level.random.nextDouble() * Math.PI * 2.0
            val distance = minDistance + level.random.nextInt((maxDistance - minDistance + 1).coerceAtLeast(1))
            val x = player.blockX + (cos(angle) * distance).toInt()
            val z = player.blockZ + (sin(angle) * distance).toInt()
            val startY = (player.blockY + level.random.nextInt(maxY * 2 + 1) - maxY).coerceIn(level.minBuildHeight + 2, level.maxBuildHeight - 2)
            val surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
            if (surfaceY in (level.minBuildHeight + 2)..(level.maxBuildHeight - 2)) {
                val surfacePos = BlockPos(x, surfaceY, z)
                if (canSpawnAt(level, surfacePos, diagnostics)) return surfacePos
                val surfaceScan = scanSpawnColumn(level, x, surfaceY, z, maxY.coerceAtLeast(32), diagnostics)
                if (surfaceScan != null) return surfaceScan
            }
            val pos = scanSpawnColumn(level, x, startY, z, maxY, diagnostics)
            if (pos != null) return pos
        }
        return null
    }

    private fun scanSpawnColumn(level: ServerLevel, x: Int, startY: Int, z: Int, maxVertical: Int, diagnostics: SpawnPositionDiagnostics?): BlockPos? {
        for (offset in 0..maxVertical) {
            listOf(startY - offset, startY + offset).forEach { y ->
                if (y <= level.minBuildHeight + 1 || y >= level.maxBuildHeight - 2) return@forEach
                val pos = BlockPos(x, y, z)
                if (canSpawnAt(level, pos, diagnostics)) return pos
            }
        }
        return null
    }

    private fun canSpawnAt(level: ServerLevel, pos: BlockPos, diagnostics: SpawnPositionDiagnostics? = null): Boolean {
        if (!level.hasChunkAt(pos)) {
            diagnostics?.let { it.noChunk += 1 }
            return false
        }
        val below = pos.below()
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            diagnostics?.let { it.noGround += 1 }
            return false
        }
        if (!hasOpenMobSpace(level, pos) || !hasOpenMobSpace(level, pos.above())) {
            diagnostics?.let { it.notEmpty += 1 }
            return false
        }
        if (!level.noCollision(AABB.ofSize(pos.center, 0.6, 1.8, 0.6))) {
            diagnostics?.let { it.collision += 1 }
            return false
        }
        return true
    }

    private fun hasOpenMobSpace(level: ServerLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (!state.fluidState.isEmpty) return false
        return state.getCollisionShape(level as BlockGetter, pos).isEmpty
    }

    private fun isUniqueNearby(server: MinecraftServer, rosterId: String, level: ServerLevel, pos: BlockPos, radius: Int): Boolean {
        val radiusSqr = radius.toDouble() * radius.toDouble()
        return active.values.none { spawn ->
            if (spawn.rosterId != rosterId) return@none false
            val entity = entityByUuid(server, spawn.entityUuid) ?: return@none false
            entity.level() == level && entity.distanceToSqr(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5) <= radiusSqr
        }
    }

    private fun cleanup(server: MinecraftServer, settings: RandomTrainerSettings) {
        val now = server.overworld().gameTime
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val (uuid, spawn) = iterator.next()
            val entity = entityByUuid(server, uuid)
            if (entity == null || !entity.isAlive) {
                iterator.remove()
                continue
            }
            if (entity.inTrainerBattle) continue
            val nearest = entity.level().getNearestPlayer(entity, settings.maxHorizontalDistanceToPlayers * 3.0)
            if (nearest != null) {
                active[uuid] = spawn.copy(lastSeenTick = now)
            } else if (now - spawn.lastSeenTick > settings.despawnTicksIfUnseen.coerceAtLeast(200)) {
                entity.discard()
                iterator.remove()
            }
        }
    }

    private fun nextInterval(player: ServerPlayer, settings: RandomTrainerSettings): Long {
        val count = active.values.count { it.originPlayerUuid == player.uuid }
        val base = settings.spawnIntervalTicks.coerceAtLeast(20)
        val max = settings.spawnIntervalTicksMaximum.coerceAtLeast(base)
        return (base + count * base / 2).coerceAtMost(max).toLong()
    }

    private fun canNaturalSpawnIn(player: ServerPlayer, settings: RandomTrainerSettings): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        val dimensionId = cleanDimensionId(level.dimension().location().toString())
        val allowed = settings.allowedDimensions.map(::cleanDimensionId).filter(String::isNotBlank)
        val blocked = settings.blockedDimensions.map(::cleanDimensionId).filter(String::isNotBlank).toSet()
        if (dimensionId in blocked) return false
        if (allowed.isNotEmpty() && dimensionId !in allowed) return false
        return true
    }

    private fun recordNaturalStatus(player: ServerPlayer, settings: RandomTrainerSettings, now: Long, reason: String, detail: String, pos: BlockPos? = null) {
        val state = NaturalSpawnDebugState(
            tick = now,
            reason = reason,
            detail = detail,
            dimension = (player.level() as? ServerLevel)?.dimension()?.location()?.toString().orEmpty(),
            pos = pos,
        )
        lastNaturalStatus[player.uuid] = state
        if (!settings.debugNaturalSpawning && player.uuid !in liveDebugPlayers) return
        if (reason == "waiting") return
        val next = nextDebugStatusTick[player.uuid] ?: 0L
        if (reason in setOf("blocked_player_mode", "blocked_dimension")) {
            if (now < next) return
            nextDebugStatusTick[player.uuid] = now + settings.debugStatusIntervalTicks.coerceAtLeast(20)
        }
        player.sendSystemMessage(liveDebugComponent(state))
    }

    private fun entityByUuid(server: MinecraftServer, uuid: UUID): RandomTrainerEntity? =
        server.allLevels.asSequence().mapNotNull { level -> level.getEntity(uuid) as? RandomTrainerEntity }.firstOrNull()
}

private data class NaturalSpawnAttemptResult(
    val reason: String,
    val detail: String,
    val pos: BlockPos? = null,
)

private data class SpawnPositionDiagnostics(
    var noChunk: Int = 0,
    var noGround: Int = 0,
    var notEmpty: Int = 0,
    var collision: Int = 0,
) {
    fun summary(): String = "posChecks(noChunk=$noChunk,noGround=$noGround,notEmpty=$notEmpty,collision=$collision)"
}

private data class NaturalSpawnDebugState(
    val tick: Long,
    val reason: String,
    val detail: String,
    val dimension: String,
    val pos: BlockPos? = null,
) {
    fun summary(now: Long): String {
        val suffix = detail.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "$reason$suffix in $dimension ${now - tick} ticks ago"
    }
}

private fun liveDebugComponent(state: NaturalSpawnDebugState): Component {
    val pos = state.pos
    if (state.reason == "spawned" && pos != null) {
        val command = "/execute in ${state.dimension} run tp @s ${pos.x} ${pos.y} ${pos.z}"
        return Component.literal("SUCCESS SPAWN: ${state.detail} in [${pos.x}, ${pos.y}, ${pos.z}] ")
            .withStyle(ChatFormatting.GREEN)
            .append(
                Component.literal("[TP]")
                    .withStyle { style ->
                        style.withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                    },
            )
    }
    val detail = state.detail.ifBlank { "no detail" }
    return Component.literal("FAILED TO SPAWN: ${state.reason} because $detail").withStyle(ChatFormatting.RED)
}

private data class ActiveRandomTrainerSpawn(
    val entityUuid: UUID,
    val originPlayerUuid: UUID?,
    val rosterId: String,
    val lastSeenTick: Long,
)
