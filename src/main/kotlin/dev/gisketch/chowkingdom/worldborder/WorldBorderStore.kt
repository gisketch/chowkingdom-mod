package dev.gisketch.chowkingdom.worldborder

import com.google.gson.annotations.SerializedName
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.config.TomlConfigIO
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object WorldBorderStore {
    private var state = WorldBorderState()
    private var loaded = false

    private fun file(server: MinecraftServer): Path = server
        .getWorldPath(LevelResource.ROOT)
        .resolve("data")
        .resolve(ChowKingdomMod.MOD_ID)
        .resolve("world_border")
        .resolve("state.json")

    fun load(server: MinecraftServer) {
        val file = file(server)
        file.parent.createDirectories()
        state = if (file.exists()) TomlConfigIO.read(file, WorldBorderState::class.java, ::WorldBorderState).sanitized() else WorldBorderState()
        loaded = true
    }

    fun ensureLoaded(server: MinecraftServer) {
        if (!loaded) load(server)
    }

    fun snapshot(server: MinecraftServer): WorldBorderState {
        ensureLoaded(server)
        return state.copy(announcedThresholds = state.announcedThresholds.toMutableSet())
    }

    fun highestUnlockedThreshold(server: MinecraftServer): Long {
        ensureLoaded(server)
        return state.highestUnlockedThreshold
    }

    fun markApplied(server: MinecraftServer, threshold: Long, radius: Int, announced: Boolean) {
        ensureLoaded(server)
        state.highestUnlockedThreshold = maxOf(state.highestUnlockedThreshold, threshold.coerceAtLeast(0L))
        state.lastAppliedRadius = radius.coerceAtLeast(1)
        if (announced) state.announcedThresholds.add(threshold.coerceAtLeast(0L))
        save(server)
    }

    fun markAnnounced(server: MinecraftServer, threshold: Long) {
        ensureLoaded(server)
        state.announcedThresholds.add(threshold.coerceAtLeast(0L))
        save(server)
    }

    fun reset(server: MinecraftServer) {
        state = WorldBorderState()
        loaded = true
        save(server)
    }

    private fun save(server: MinecraftServer) {
        TomlConfigIO.write(file(server), state.sanitized())
    }
}

data class WorldBorderState(
    @SerializedName("highest_unlocked_threshold") var highestUnlockedThreshold: Long = 0L,
    @SerializedName("last_applied_radius") var lastAppliedRadius: Int = 0,
    @SerializedName("announced_thresholds") var announcedThresholds: MutableSet<Long> = mutableSetOf(),
) {
    fun sanitized(): WorldBorderState {
        highestUnlockedThreshold = highestUnlockedThreshold.coerceAtLeast(0L)
        lastAppliedRadius = lastAppliedRadius.coerceAtLeast(0)
        announcedThresholds = announcedThresholds.map { it.coerceAtLeast(0L) }.toMutableSet()
        return this
    }
}
