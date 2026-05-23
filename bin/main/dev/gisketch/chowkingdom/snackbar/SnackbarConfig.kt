package dev.gisketch.chowkingdom.snackbar

import com.google.gson.annotations.SerializedName
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.config.TomlConfigIO
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object SnackbarConfig {
    private var config = SnackbarConfigData()

    private val file: Path
        get() = FMLPaths.CONFIGDIR.get().resolve(ChowKingdomMod.MOD_ID).resolve("snackbar").resolve("config.toml")

    fun load() {
        file.parent.createDirectories()
        if (!file.exists()) writeDefault()
        config = try {
            TomlConfigIO.read(file, SnackbarConfigData::class.java, ::SnackbarConfigData)
        } catch (exception: Exception) {
            ChowKingdomMod.LOGGER.warn("Failed to load snackbar config {}", file, exception)
            SnackbarConfigData()
        }
    }

    fun durationMs(): Long = config.sanitized().durationSeconds * 1_000L

    private fun writeDefault() {
        TomlConfigIO.write(file, SnackbarConfigData())
    }
}

class SnackbarConfigData(
    @SerializedName("duration_seconds") var durationSeconds: Int = 5,
) {
    fun sanitized(): SnackbarConfigData = SnackbarConfigData(durationSeconds.coerceIn(1, 60))
}