package dev.gisketch.chowkingdom.town

import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.config.TomlConfigIO
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object TownReturnStore {
    private var data = TownReturnData()
    private var loaded = false

    private val file: Path
        get() {
            val server = ServerLifecycleHooks.getCurrentServer()
            val root = if (server != null) server.getWorldPath(LevelResource.ROOT).resolve("data") else FMLPaths.CONFIGDIR.get()
            val extension = if (server != null) "json" else "toml"
            return root.resolve(ChowKingdomMod.MOD_ID).resolve("town_return").resolve("state.$extension")
        }

    fun load() {
        file.parent.createDirectories()
        data = if (file.exists()) {
            try {
                TomlConfigIO.read(file, TownReturnData::class.java, ::TownReturnData)
            } catch (exception: Exception) {
                ChowKingdomMod.LOGGER.warn("Failed to load town return state {}", file, exception)
                TownReturnData()
            }
        } else TownReturnData()
        loaded = true
    }

    fun setPortal(player: ServerPlayer) {
        if (!loaded) load()
        data.portal = TownPortalData.from(player)
        save()
    }

    fun clearPortal() {
        if (!loaded) load()
        data.portal = null
        save()
    }

    fun portal(): TownPortalData? {
        if (!loaded) load()
        return data.portal
    }

    fun portalLevel(server: MinecraftServer): ServerLevel? {
        val portal = portal() ?: return null
        val id = runCatching { ResourceLocation.parse(portal.dimension) }.getOrNull() ?: return null
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id))
    }

    fun cooldownUntil(player: ServerPlayer): Long {
        if (!loaded) load()
        return data.cooldownUntilMs[player.stringUUID] ?: data.cooldownUntilMs["${player.stringUUID}:town_charm"] ?: 0L
    }

    fun markCooldown(player: ServerPlayer, untilMs: Long) {
        if (!loaded) load()
        data.cooldownUntilMs[player.stringUUID] = untilMs
        save()
    }

    fun clearCooldown(player: ServerPlayer): Boolean {
        if (!loaded) load()
        val removedCurrent = data.cooldownUntilMs.remove(player.stringUUID) != null
        val removedLegacy = data.cooldownUntilMs.remove("${player.stringUUID}:town_charm") != null
        if (removedCurrent || removedLegacy) save()
        return removedCurrent || removedLegacy
    }

    private fun save() {
        file.parent.createDirectories()
        TomlConfigIO.write(file, data)
    }
}

data class TownReturnData(
    var portal: TownPortalData? = null,
    var cooldownUntilMs: MutableMap<String, Long> = linkedMapOf(),
)

data class TownPortalData(
    var dimension: String = Level.OVERWORLD.location().toString(),
    var x: Int = 0,
    var y: Int = 64,
    var z: Int = 0,
    var yaw: Float = 0.0f,
    var pitch: Float = 0.0f,
) {
    fun blockPos(): BlockPos = BlockPos(x, y, z)

    companion object {
        fun from(player: ServerPlayer): TownPortalData {
            val pos = player.blockPosition()
            return TownPortalData(player.level().dimension().location().toString(), pos.x, pos.y, pos.z, player.yRot, player.xRot)
        }
    }
}