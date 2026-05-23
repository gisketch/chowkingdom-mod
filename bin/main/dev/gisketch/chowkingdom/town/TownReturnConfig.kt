package dev.gisketch.chowkingdom.town

import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.config.TomlConfigIO
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object TownReturnConfig {
    private var config = TownReturnConfigData()

    private val file: Path
        get() = FMLPaths.CONFIGDIR.get().resolve(ChowKingdomMod.MOD_ID).resolve("town_return").resolve("config.toml")

    fun load() {
        file.parent.createDirectories()
        if (!file.exists()) TomlConfigIO.write(file, defaultConfig())
        config = try {
            TomlConfigIO.read(file, TownReturnConfigData::class.java, ::defaultConfig)
        } catch (exception: Exception) {
            ChowKingdomMod.LOGGER.warn("Failed to load town return config {}", file, exception)
            defaultConfig()
        }
    }

    fun colorFor(jobId: String): TownReturnParticleColor = config.jobColors[jobId]
        ?: config.jobColors["default"]
        ?: defaultColor()

    private fun defaultConfig(): TownReturnConfigData = TownReturnConfigData(defaultJobColors().toMutableMap())

    private fun defaultColor(): TownReturnParticleColor = TownReturnParticleColor(255, 221, 126)

    private fun defaultJobColors(): Map<String, TownReturnParticleColor> = linkedMapOf(
        "default" to defaultColor(),
        "botanist" to TownReturnParticleColor(96, 212, 112),
        "diver" to TownReturnParticleColor(70, 190, 255),
        "magma_scout" to TownReturnParticleColor(255, 92, 48),
        "engineer" to TownReturnParticleColor(245, 190, 72),
        "field_researcher" to TownReturnParticleColor(155, 225, 90),
        "bug_scout" to TownReturnParticleColor(140, 232, 88),
        "falconer" to TownReturnParticleColor(132, 206, 255),
        "shade_runner" to TownReturnParticleColor(138, 96, 220),
        "esper" to TownReturnParticleColor(235, 128, 255),
        "martial_artist" to TownReturnParticleColor(255, 118, 96),
        "mountaineer" to TownReturnParticleColor(185, 170, 145),
        "shinobi" to TownReturnParticleColor(92, 108, 132),
        "mason" to TownReturnParticleColor(170, 166, 150),
        "excavator" to TownReturnParticleColor(206, 154, 88),
        "blacksmith" to TownReturnParticleColor(255, 146, 84),
        "spirit_medium" to TownReturnParticleColor(186, 150, 255),
        "drake_tamer" to TownReturnParticleColor(255, 112, 72),
        "performer" to TownReturnParticleColor(255, 176, 218),
    )
}

data class TownReturnConfigData(
    var jobColors: MutableMap<String, TownReturnParticleColor> = linkedMapOf(),
)

data class TownReturnParticleColor(
    var red: Int = 255,
    var green: Int = 221,
    var blue: Int = 126,
    var scale: Float = 1.15f,
) {
    fun sanitized(): TownReturnParticleColor = TownReturnParticleColor(
        red.coerceIn(0, 255),
        green.coerceIn(0, 255),
        blue.coerceIn(0, 255),
        scale.coerceIn(0.2f, 4.0f),
    )
}
