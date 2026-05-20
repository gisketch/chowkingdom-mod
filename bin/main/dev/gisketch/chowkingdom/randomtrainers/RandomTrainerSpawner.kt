package dev.gisketch.chowkingdom.randomtrainers

import dev.gisketch.chowkingdom.ChowKingdomMod
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
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

    private fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        val settings = RandomTrainerCatalog.settings()
        if (!settings.enabled) return
        cleanup(server, settings)
        if (!settings.naturalSpawning) return
        val now = server.overworld().gameTime
        server.playerList.players.forEach { player ->
            if (player.isSpectator || player.isCreative) return@forEach
            if (!canNaturalSpawnIn(player, settings)) return@forEach
            val due = nextAttemptTick[player.uuid] ?: 0L
            if (now < due) return@forEach
            nextAttemptTick[player.uuid] = now + nextInterval(player, settings)
            if (player.random.nextDouble() > settings.globalSpawnChance.coerceIn(0.0, 1.0)) return@forEach
            attemptSpawn(player, settings)
        }
    }

    private fun attemptSpawn(player: ServerPlayer, settings: RandomTrainerSettings) {
        if (active.size >= settings.maxTrainersTotal.coerceAtLeast(1)) return
        if (active.values.count { it.originPlayerUuid == player.uuid } >= settings.maxTrainersPerPlayer.coerceAtLeast(1)) return
        val topLevel = RandomTrainerBattleService.playerTopLevel(player)
        if (topLevel <= 0) return
        val defeated = RandomTrainerStore.defeated(player)
        val definition = RandomTrainerCatalog.pickFor(player, topLevel, defeated) ?: return
        repeat(SPAWN_RETRIES) {
            val pos = spawnPos(player) ?: return@repeat
            if (isUniqueNearby(player.server, definition.id, player.level() as ServerLevel, pos, settings.uniqueTrainerRadius)) {
                if (spawn(player, definition, pos)) return
            }
        }
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

    private fun spawnPos(player: ServerPlayer, nearPlayer: Boolean = false): BlockPos? {
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
            val pos = scanSpawnColumn(level, x, startY, z, maxY)
            if (pos != null) return pos
        }
        return null
    }

    private fun scanSpawnColumn(level: ServerLevel, x: Int, startY: Int, z: Int, maxVertical: Int): BlockPos? {
        for (offset in 0..maxVertical) {
            listOf(startY - offset, startY + offset).forEach { y ->
                if (y <= level.minBuildHeight + 1 || y >= level.maxBuildHeight - 2) return@forEach
                val pos = BlockPos(x, y, z)
                if (canSpawnAt(level, pos)) return pos
            }
        }
        return null
    }

    private fun canSpawnAt(level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.hasChunkAt(pos)) return false
        val below = pos.below()
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false
        if (!level.getBlockState(pos).isAir || !level.getBlockState(pos.above()).isAir) return false
        return level.noCollision(AABB.ofSize(pos.center, 0.6, 1.8, 0.6))
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

    private fun entityByUuid(server: MinecraftServer, uuid: UUID): RandomTrainerEntity? =
        server.allLevels.asSequence().mapNotNull { level -> level.getEntity(uuid) as? RandomTrainerEntity }.firstOrNull()
}

private data class ActiveRandomTrainerSpawn(
    val entityUuid: UUID,
    val originPlayerUuid: UUID?,
    val rosterId: String,
    val lastSeenTick: Long,
)
