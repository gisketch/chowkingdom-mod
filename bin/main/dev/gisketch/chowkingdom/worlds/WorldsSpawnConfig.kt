package dev.gisketch.chowkingdom.worlds

import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.config.TomlConfigIO
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object WorldsSpawnConfig {
    private var config = WorldsSpawnConfigData()

    private val file: Path
        get() = FMLPaths.CONFIGDIR.get().resolve(ChowKingdomMod.MOD_ID).resolve("worlds").resolve("spawn.toml")

    fun load() {
        file.parent.createDirectories()
        if (!file.exists()) TomlConfigIO.write(file, WorldsSpawnConfigData())
        config = try {
            TomlConfigIO.read(file, WorldsSpawnConfigData::class.java, ::WorldsSpawnConfigData).sanitized()
        } catch (exception: Exception) {
            ChowKingdomMod.LOGGER.warn("Failed to load worlds spawn config {}", file, exception)
            WorldsSpawnConfigData()
        }
    }

    fun worldSpawnDimension(): ResourceKey<Level> = dimensionKey(config.worldSpawnDimension)

    fun setWorldSpawnDimension(dimension: ResourceKey<Level>) {
        config.worldSpawnDimension = dimension.location().toString()
        TomlConfigIO.write(file, config.sanitized())
    }

    private fun dimensionKey(raw: String): ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(raw))
}

data class WorldsSpawnConfigData(
    var worldSpawnDimension: String = "minecraft:overworld",
) {
    fun sanitized(): WorldsSpawnConfigData = WorldsSpawnConfigData(
        worldSpawnDimension = worldSpawnDimension.takeIf { it.isNotBlank() } ?: "minecraft:overworld",
    )
}
