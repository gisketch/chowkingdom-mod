package dev.gisketch.chowkingdom.roles

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.item.ItemEntity
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import java.util.UUID

internal object EsperPerks {
    private val focusMindState: MutableMap<UUID, FocusMindState> = linkedMapOf()
    private val focusMindCooldownUntilTicks: MutableMap<UUID, Long> = linkedMapOf()
    private val premonitionCooldownUntilTicks: MutableMap<UUID, Long> = linkedMapOf()
    private val telekinesisScanCooldownUntilTicks: MutableMap<UUID, Long> = linkedMapOf()

    fun onLivingDamage(player: ServerPlayer, event: LivingDamageEvent.Pre) {
        if (!event.source.`is`(DamageTypeTags.IS_PROJECTILE)) return
        val reduction = RolePerks.configuredJobMaxBonusPercent(player, "projectile_damage_reduction").coerceIn(0.0, 0.95)
        if (reduction > 0.0) event.newDamage = (event.newDamage * (1.0 - reduction).toFloat()).coerceAtLeast(0.0f)
        applyPremonition(player)
    }

    fun onPlayerTick(player: ServerPlayer) {
        applyTelekinesis(player)
        applyFocusMind(player)
    }

    private fun applyTelekinesis(player: ServerPlayer) {
        val bonus = RolePerks.configuredJobMaxBonusPercent(player, "telekinesis_lite").coerceAtLeast(0.0)
        if (bonus <= 0.0) return
        val level = player.level() as? ServerLevel ?: return
        val now = level.gameTime
        if (now < (telekinesisScanCooldownUntilTicks[player.uuid] ?: 0L)) return
        telekinesisScanCooldownUntilTicks[player.uuid] = now + TELEKINESIS_SCAN_INTERVAL_TICKS
        level.getEntitiesOfClass(ItemEntity::class.java, player.boundingBox.inflate(BASE_PICKUP_RANGE + bonus)).forEach { itemEntity ->
            if (itemEntity.hasPickUpDelay() || itemEntity.item.isEmpty || itemEntity.isRemoved) return@forEach
            itemEntity.playerTouch(player)
        }
    }

    private fun applyFocusMind(player: ServerPlayer) {
        if (RolePerks.jobPerks(player, "focus_mind").isEmpty()) return
        val now = player.level().gameTime
        val previous = focusMindState[player.uuid]
        val currentPosition = player.position()
        val stillTicks = if (previous != null && previous.position.distanceToSqr(currentPosition) <= STILL_DISTANCE_SQUARED) {
            previous.stillTicks + 1
        } else {
            0
        }
        focusMindState[player.uuid] = FocusMindState(currentPosition, stillTicks)
        if (stillTicks < FOCUS_MIND_CHARGE_TICKS || now < (focusMindCooldownUntilTicks[player.uuid] ?: 0L)) return
        player.addEffect(MobEffectInstance(MobEffects.DIG_SPEED, FOCUS_MIND_DURATION_TICKS, 0, false, false, true))
        focusMindCooldownUntilTicks[player.uuid] = now + FOCUS_MIND_COOLDOWN_TICKS
        focusMindState[player.uuid] = FocusMindState(currentPosition, 0)
    }

    private fun applyPremonition(player: ServerPlayer) {
        if (RolePerks.jobPerks(player, "premonition").isEmpty()) return
        val now = player.level().gameTime
        if (now < (premonitionCooldownUntilTicks[player.uuid] ?: 0L)) return
        premonitionCooldownUntilTicks[player.uuid] = now + PREMONITION_COOLDOWN_TICKS
        player.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SPEED, PREMONITION_DURATION_TICKS, 0, false, false, true))
        player.addEffect(MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, PREMONITION_DURATION_TICKS, 0, false, false, true))
    }

    private data class FocusMindState(
        val position: net.minecraft.world.phys.Vec3,
        val stillTicks: Int,
    )

    private const val BASE_PICKUP_RANGE = 1.0
    private const val STILL_DISTANCE_SQUARED = 0.0004
    private const val FOCUS_MIND_CHARGE_TICKS = 60
    private const val FOCUS_MIND_DURATION_TICKS = 100
    private const val FOCUS_MIND_COOLDOWN_TICKS = 600L
    private const val PREMONITION_DURATION_TICKS = 80
    private const val PREMONITION_COOLDOWN_TICKS = 1200L
    private const val TELEKINESIS_SCAN_INTERVAL_TICKS = 2L
}
