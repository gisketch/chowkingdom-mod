package dev.gisketch.chowkingdom.worldborder

import kotlin.test.Test
import kotlin.test.assertEquals

class WorldBorderConfigTest {
    @Test
    fun shippingTiersUseReachedMilestone() {
        val settings = WorldBorderSettings(
            tiers = mutableListOf(
                WorldBorderTier(0L, 1_500),
                WorldBorderTier(100_000L, 3_000),
                WorldBorderTier(250_000L, 5_000),
                WorldBorderTier(500_000L, 7_500),
                WorldBorderTier(1_000_000L, 10_000),
            ),
        ).sanitized()

        fun radiusAt(total: Long): Int = settings.tiers
            .filter { total >= it.thresholdChowcoins }
            .maxByOrNull(WorldBorderTier::thresholdChowcoins)!!
            .radiusBlocks

        assertEquals(1_500, radiusAt(99_999L))
        assertEquals(3_000, radiusAt(100_000L))
        assertEquals(5_000, radiusAt(250_000L))
        assertEquals(7_500, radiusAt(500_000L))
        assertEquals(10_000, radiusAt(1_000_000L))
    }

    @Test
    fun sanitizedSettingsKeepDefaultTier() {
        val settings = WorldBorderSettings(tiers = mutableListOf(WorldBorderTier(100_000L, 3_000))).sanitized()

        assertEquals(0L, settings.tiers.first().thresholdChowcoins)
        assertEquals(1_500, settings.tiers.first().radiusBlocks)
    }
}
