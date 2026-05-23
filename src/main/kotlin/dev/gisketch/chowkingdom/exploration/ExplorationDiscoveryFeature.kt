package dev.gisketch.chowkingdom.exploration

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.structure.Structure
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.UUID

object ExplorationDiscoveryFeature {
    private val lastScannedChunk: MutableMap<UUID, String> = linkedMapOf()
    private val structureScanCooldownUntil: MutableMap<UUID, Long> = linkedMapOf()

    fun register() {
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        lastScannedChunk.clear()
        structureScanCooldownUntil.clear()
        ExplorationDiscoveryStore.load(event.server)
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        ExplorationDiscoveryStore.refreshProgress(player)
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        if (event.server.tickCount % DISCOVERY_SCAN_INTERVAL_TICKS != 0) return
        event.server.playerList.players.forEach(::scanPlayer)
    }

    private fun scanPlayer(player: ServerPlayer) {
        if (!player.isAlive || player.isSpectator) return
        val level = player.level() as? ServerLevel ?: return
        val pos = player.blockPosition()
        val chunk = ChunkPos(pos)
        val dimension = level.dimension().location().toString()
        val scanKey = "$dimension|${chunk.x}|${chunk.z}"
        val sameChunk = lastScannedChunk[player.uuid] == scanKey
        val now = level.gameTime
        if (sameChunk && now < (structureScanCooldownUntil[player.uuid] ?: 0L)) return
        val hasStructureReferences = level.structureManager().hasAnyStructureAt(pos)
        structureScanCooldownUntil[player.uuid] = now + STRUCTURE_RESCAN_INTERVAL_TICKS
        if (sameChunk && !hasStructureReferences) return
        if (!sameChunk) lastScannedChunk[player.uuid] = scanKey

        if (!sameChunk) {
            level.getBiome(pos).unwrapKey().ifPresent { key ->
                ExplorationDiscoveryStore.recordBiome(player, dimension, key.location().toString())
            }
        }
        if (hasStructureReferences) {
            currentStructures(level, pos).forEach { structure ->
                ExplorationDiscoveryStore.recordStructure(player, dimension, structure.id, structure.x, structure.z)
            }
        }
    }

    private fun currentStructures(level: ServerLevel, pos: BlockPos): List<DiscoveredStructure> {
        val manager = level.structureManager()
        if (!manager.shouldGenerateStructures()) return emptyList()
        val references = manager.getAllStructuresAt(pos)
        if (references.isEmpty()) return emptyList()
        val registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
        val found = linkedMapOf<String, DiscoveredStructure>()
        references.forEach { (structure: Structure, starts) ->
            manager.fillStartsForStructure(structure, starts) { start ->
                if (!start.isValid || !manager.structureHasPieceAt(pos, start)) return@fillStartsForStructure
                val id = registry.getKey(structure)?.toString() ?: return@fillStartsForStructure
                val center = start.boundingBox.center
                found.putIfAbsent("$id|${center.x}|${center.z}", DiscoveredStructure(id, center.x, center.z))
            }
        }
        return found.values.toList()
    }

    private data class DiscoveredStructure(val id: String, val x: Int, val z: Int)

    private const val DISCOVERY_SCAN_INTERVAL_TICKS = 80
    private const val STRUCTURE_RESCAN_INTERVAL_TICKS = 20L * 20L
}
