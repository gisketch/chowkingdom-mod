package dev.gisketch.chowkingdom.roles

import dev.gisketch.chowkingdom.battlepass.BattlepassNetwork
import dev.gisketch.chowkingdom.battlepass.BattlepassXpStore
import dev.gisketch.chowkingdom.npc.ChowNpcEntity
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import java.util.UUID
import kotlin.math.ceil

object PerformerPerks {
    private val happyBoostScanCooldownUntilTicks: MutableMap<UUID, Long> = linkedMapOf()

    fun friendshipDelta(player: ServerPlayer, delta: Int): Int {
        if (delta <= 0) return delta
        val bonus = RolePerks.configuredJobMaxBonusPercent(player, "charisma_lite").coerceAtLeast(0.0)
        if (bonus <= 0.0) return delta
        return ceil(delta * (1.0 + bonus)).toInt().coerceAtLeast(delta)
    }

    fun giftFriendshipDelta(player: ServerPlayer, mood: String, delta: Int): Int {
        if (RolePerks.jobPerks(player, "charming_gift").isEmpty()) return delta
        if (mood != "loved" && mood != "liked") return delta
        return delta + CHARMING_GIFT_BONUS
    }

    fun onNpcQuestComplete(player: ServerPlayer, passId: String) {
        if (RolePerks.jobPerks(player, "encore").isEmpty()) return
        if (player.random.nextDouble() >= ENCORE_CHANCE) return
        val granted = PerformerProgressStore.addEncoreXp(player, ENCORE_XP, ENCORE_DAILY_CAP)
        if (granted <= 0) return
        val previousXp = BattlepassXpStore.getXp(player, passId)
        BattlepassXpStore.addXp(player, passId, granted)
        BattlepassNetwork.syncAllPlayers()
        SnackbarNetwork.send(player, SnackbarNotification.battlepassXp("ENCORE +$granted XP", previousXp, previousXp + granted, 100))
    }

    fun onPlayerTick(player: ServerPlayer) {
        if (RolePerks.jobPerks(player, "happy_boost_lite").isEmpty()) return
        val level = player.level() as? ServerLevel ?: return
        val now = level.gameTime
        if (now < (happyBoostScanCooldownUntilTicks[player.uuid] ?: 0L)) return
        happyBoostScanCooldownUntilTicks[player.uuid] = now + HAPPY_BOOST_SCAN_INTERVAL_TICKS
        val hasNpcNearby = level.getEntitiesOfClass(ChowNpcEntity::class.java, player.boundingBox.inflate(HAPPY_BOOST_RADIUS)).isNotEmpty()
        if (!hasNpcNearby) return
        val amplifier = happyBoostAmplifier(JobLevels.jobLevel(player))
        player.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SPEED, HAPPY_BOOST_DURATION_TICKS, amplifier, false, false, true))
    }

    private fun happyBoostAmplifier(rank: Int): Int = when {
        rank >= 5 -> 2
        rank >= 3 -> 1
        else -> 0
    }

    private const val CHARMING_GIFT_BONUS = 10
    private const val ENCORE_CHANCE = 0.10
    private const val ENCORE_XP = 10
    private const val ENCORE_DAILY_CAP = 50
    private const val HAPPY_BOOST_RADIUS = 24.0
    private const val HAPPY_BOOST_DURATION_TICKS = 60
    private const val HAPPY_BOOST_SCAN_INTERVAL_TICKS = 20L
}
