package dev.gisketch.chowkingdom.worldborder

import com.google.gson.annotations.SerializedName
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.config.TomlConfigIO
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path

object WorldBorderConfig {
    private var config = defaultConfig()

    private val file: Path
        get() = FMLPaths.CONFIGDIR.get().resolve(ChowKingdomMod.MOD_ID).resolve("world_border").resolve("settings.toml")

    fun load() {
        config = TomlConfigIO.read(file, WorldBorderSettings::class.java, ::defaultConfig, comments = comments()).sanitized()
        save()
    }

    fun save() {
        TomlConfigIO.write(file, config, comments = comments())
    }

    fun enabled(): Boolean = config.enabled
    fun centerX(): Double = config.centerX
    fun centerZ(): Double = config.centerZ
    fun warningDistanceBlocks(): Int = config.warningDistanceBlocks
    fun warningCooldownTicks(): Long = (config.warningCooldownSeconds.coerceAtLeast(1) * 20L)
    fun checkIntervalTicks(): Int = config.checkIntervalTicks
    fun applyIntervalTicks(): Int = config.applyIntervalTicks
    fun tiers(): List<WorldBorderTier> = config.tiers

    fun tierFor(totalChowcoinsSold: Long): WorldBorderTier = tiers()
        .filter { tier -> totalChowcoinsSold >= tier.thresholdChowcoins }
        .maxByOrNull(WorldBorderTier::thresholdChowcoins)
        ?: DEFAULT_TIER

    fun tierAtOrBelow(thresholdChowcoins: Long): WorldBorderTier = tiers()
        .filter { tier -> thresholdChowcoins >= tier.thresholdChowcoins }
        .maxByOrNull(WorldBorderTier::thresholdChowcoins)
        ?: DEFAULT_TIER

    fun defaultRadius(): Int = DEFAULT_TIER.radiusBlocks

    private fun defaultConfig(): WorldBorderSettings = WorldBorderSettings(
        tiers = mutableListOf(
            WorldBorderTier(0L, 1_500, "KINGDOM BORDER", "Default border radius is 1,500 blocks."),
            WorldBorderTier(100_000L, 3_000, "WORLD BORDER EXPANDED", "Shipping reached 100,000 chowcoins. Border radius is now 3,000."),
            WorldBorderTier(250_000L, 5_000, "WORLD BORDER EXPANDED", "Shipping reached 250,000 chowcoins. Border radius is now 5,000."),
            WorldBorderTier(500_000L, 7_500, "WORLD BORDER EXPANDED", "Shipping reached 500,000 chowcoins. Border radius is now 7,500."),
            WorldBorderTier(1_000_000L, 10_000, "WORLD BORDER EXPANDED", "Shipping reached 1,000,000 chowcoins. Border radius is now 10,000."),
        ),
    )

    private fun comments(): Map<String, String> = mapOf(
        "enabled" to "Enable CKDM world border control.",
        "center_x" to "World border center X for every dimension.",
        "center_z" to "World border center Z for every dimension.",
        "warning_distance_blocks" to "Snackbar warning distance from the border edge.",
        "warning_cooldown_seconds" to "Per-player cooldown between border warning snackbars.",
        "check_interval_ticks" to "Player border warning check interval in ticks.",
        "apply_interval_ticks" to "Periodic border reapply interval in ticks.",
        "tiers" to "Shipping-bin total chowcoins sold thresholds mapped to border radius.",
    )

    private val DEFAULT_TIER = WorldBorderTier(0L, 1_500, "KINGDOM BORDER", "Default border radius is 1,500 blocks.")
}

class WorldBorderSettings(
    var enabled: Boolean = true,
    @SerializedName("center_x") var centerX: Double = 0.0,
    @SerializedName("center_z") var centerZ: Double = 0.0,
    @SerializedName("warning_distance_blocks") var warningDistanceBlocks: Int = 100,
    @SerializedName("warning_cooldown_seconds") var warningCooldownSeconds: Int = 10,
    @SerializedName("check_interval_ticks") var checkIntervalTicks: Int = 20,
    @SerializedName("apply_interval_ticks") var applyIntervalTicks: Int = 200,
    var tiers: MutableList<WorldBorderTier> = mutableListOf(),
) {
    fun sanitized(): WorldBorderSettings {
        enabled = enabled
        warningDistanceBlocks = warningDistanceBlocks.coerceAtLeast(1)
        warningCooldownSeconds = warningCooldownSeconds.coerceAtLeast(1)
        checkIntervalTicks = checkIntervalTicks.coerceAtLeast(1)
        applyIntervalTicks = applyIntervalTicks.coerceAtLeast(20)
        tiers = tiers
            .map { it.sanitized() }
            .filter { it.radiusBlocks > 0 }
            .distinctBy { it.thresholdChowcoins }
            .sortedBy { it.thresholdChowcoins }
            .toMutableList()
        if (tiers.none { it.thresholdChowcoins == 0L }) {
            tiers.add(0, WorldBorderTier(0L, 1_500, "KINGDOM BORDER", "Default border radius is 1,500 blocks."))
        }
        return this
    }
}

class WorldBorderTier(
    @SerializedName("threshold_chowcoins") var thresholdChowcoins: Long = 0L,
    @SerializedName("radius_blocks") var radiusBlocks: Int = 1_500,
    var title: String = "WORLD BORDER EXPANDED",
    var message: String = "The kingdom border expanded.",
) {
    fun sanitized(): WorldBorderTier {
        thresholdChowcoins = thresholdChowcoins.coerceAtLeast(0L)
        radiusBlocks = radiusBlocks.coerceAtLeast(1)
        title = title.ifBlank { "WORLD BORDER EXPANDED" }
        message = message.ifBlank { "The kingdom border expanded." }
        return this
    }
}
