package dev.gisketch.chowkingdom.roles

import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.snackbar.SnackbarIcons
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import dev.gisketch.chowkingdom.wallets.ChowcoinNetwork
import dev.gisketch.chowkingdom.wallets.ChowcoinStore
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.EntityTypeTags
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import java.util.UUID

internal object SpiritMediumPerks {
    private val SOUL_SPEED_MODIFIER = ResourceLocation.parse("${ChowKingdomMod.MOD_ID}:spirit_medium_soul_speed")
    private val etherealStepCooldownUntilTicks: MutableMap<UUID, Long> = linkedMapOf()
    private val spiritSightCooldownUntilTicks: MutableMap<UUID, Long> = linkedMapOf()
    private val spiritSightScanCooldownUntilTicks: MutableMap<UUID, Long> = linkedMapOf()

    fun onLivingDamage(event: LivingDamageEvent.Pre) {
        val player = event.entity as? ServerPlayer ?: return
        if (RolePerks.jobPerks(player, "ethereal_step_lite").isEmpty()) return
        val maxHealth = player.maxHealth
        val threshold = maxHealth * ETHEREAL_STEP_HEALTH_THRESHOLD
        if (player.health <= threshold || player.health - event.newDamage > threshold) return
        val now = player.level().gameTime
        if (now < (etherealStepCooldownUntilTicks[player.uuid] ?: 0L)) return
        etherealStepCooldownUntilTicks[player.uuid] = now + ETHEREAL_STEP_COOLDOWN_TICKS
        val rank = JobLevels.jobLevel(player)
        val duration = etherealStepDurationTicks(rank)
        val amplifier = if (rank >= 5) 1 else 0
        player.addEffect(MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, duration, amplifier, false, false, true))
    }

    fun onLivingDeath(event: LivingDeathEvent) {
        val player = event.source.entity as? ServerPlayer ?: return
        val target = event.entity
        if (!isUndead(target)) return
        applyGraveWhisper(player)
    }

    fun onPlayerTick(player: ServerPlayer) {
        applySoulSpeed(player)
        applySpiritSight(player)
    }

    private fun applySoulSpeed(player: ServerPlayer) {
        val attribute = player.getAttribute(Attributes.MOVEMENT_SPEED) ?: return
        val bonus = RolePerks.configuredJobMaxBonusPercent(player, "soul_speed_lite").coerceAtLeast(0.0)
        if (bonus <= 0.0 || !isSoulSpeedBlock(player)) {
            attribute.removeModifier(SOUL_SPEED_MODIFIER)
            return
        }
        attribute.addOrUpdateTransientModifier(
            AttributeModifier(
                SOUL_SPEED_MODIFIER,
                bonus,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
            ),
        )
    }

    private fun applySpiritSight(player: ServerPlayer) {
        if (!player.isCrouching || RolePerks.jobPerks(player, "spirit_sight").isEmpty()) return
        val now = player.level().gameTime
        if (now < (spiritSightCooldownUntilTicks[player.uuid] ?: 0L)) return
        if (now < (spiritSightScanCooldownUntilTicks[player.uuid] ?: 0L)) return
        spiritSightScanCooldownUntilTicks[player.uuid] = now + SPIRIT_SIGHT_SCAN_INTERVAL_TICKS
        val level = player.level() as? ServerLevel ?: return
        val undead = level.getEntitiesOfClass(LivingEntity::class.java, player.boundingBox.inflate(SPIRIT_SIGHT_RADIUS)) { entity -> entity !== player && isUndead(entity) }
        if (undead.isEmpty()) return
        undead.forEach { entity -> entity.addEffect(MobEffectInstance(MobEffects.GLOWING, SPIRIT_SIGHT_DURATION_TICKS, 0, false, false, true)) }
        spiritSightCooldownUntilTicks[player.uuid] = now + SPIRIT_SIGHT_COOLDOWN_TICKS
    }

    private fun applyGraveWhisper(player: ServerPlayer) {
        if (RolePerks.jobPerks(player, "grave_whisper").isEmpty()) return
        if (player.random.nextDouble() >= GRAVE_WHISPER_CHANCE) return
        val requested = player.random.nextInt(GRAVE_WHISPER_MAX - GRAVE_WHISPER_MIN + 1) + GRAVE_WHISPER_MIN
        val granted = SpiritMediumProgressStore.addGraveWhisperChowcoins(player, requested, GRAVE_WHISPER_WEEKLY_CAP)
        if (granted <= 0) return
        ChowcoinStore.add(player, granted.toLong())
        ChowcoinNetwork.syncTo(player)
        SnackbarNetwork.send(player, SnackbarNotification.texture(SnackbarIcons.CHOWCOIN_TEXTURE, "GRAVE WHISPER", "+$granted Chowcoins", SnackbarType.SUCCESS, SnackbarSounds.REWARD))
    }

    private fun isSoulSpeedBlock(player: ServerPlayer): Boolean {
        val state = player.level().getBlockState(player.blockPosition().below())
        if (state.`is`(Blocks.SOUL_SAND) || state.`is`(Blocks.SOUL_SOIL)) return true
        val id = BuiltInRegistries.BLOCK.getKey(state.block).path
        return id.contains("soul") || id.contains("skull") || id.contains("candle") || id.contains("sculk") || id.contains("web")
    }

    private fun isUndead(entity: LivingEntity): Boolean = entity.type.`is`(EntityTypeTags.UNDEAD)

    private fun etherealStepDurationTicks(rank: Int): Int = when {
        rank >= 5 -> 100
        rank == 4 -> 100
        rank == 3 -> 80
        rank == 2 -> 60
        else -> 40
    }

    private const val ETHEREAL_STEP_HEALTH_THRESHOLD = 0.25f
    private const val ETHEREAL_STEP_COOLDOWN_TICKS = 2400L
    private const val SPIRIT_SIGHT_RADIUS = 16.0
    private const val SPIRIT_SIGHT_DURATION_TICKS = 100
    private const val SPIRIT_SIGHT_COOLDOWN_TICKS = 600L
    private const val SPIRIT_SIGHT_SCAN_INTERVAL_TICKS = 10L
    private const val GRAVE_WHISPER_CHANCE = 0.05
    private const val GRAVE_WHISPER_MIN = 10
    private const val GRAVE_WHISPER_MAX = 50
    private const val GRAVE_WHISPER_WEEKLY_CAP = 500
}
