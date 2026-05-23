package dev.gisketch.chowkingdom.roles

import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.relicroulette.RelicRouletteFeature
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import java.util.UUID

internal object EngineerPerks {
    private val TECHNICIAN_REACH_MODIFIER = ResourceLocation.parse("${ChowKingdomMod.MOD_ID}:engineer_technician_reach")
    private val chargedMaintenanceCooldownUntilTicks: MutableMap<UUID, Long> = linkedMapOf()
    private val magnetScanCooldownUntilTicks: MutableMap<UUID, Long> = linkedMapOf()

    fun onBreakSpeed(event: PlayerEvent.BreakSpeed) {
        val player = event.entity as? ServerPlayer ?: return
        val toolSpeedBonus = RolePerks.configuredJobBonusPercent(player, "tool_mining_speed").coerceAtLeast(0.0)
        if (toolSpeedBonus > 0.0 && isEngineerTool(player.mainHandItem)) event.newSpeed = (event.newSpeed * (1.0 + toolSpeedBonus).toFloat()).coerceAtLeast(0.0f)
    }

    fun onBlockBreak(player: ServerPlayer, state: BlockState) {
        val chance = RolePerks.configuredJobChance(player, "charged_maintenance")
        if (chance <= 0.0 || !isChargedMaintenanceBlock(state)) return
        val now = player.level().gameTime
        if (now < (chargedMaintenanceCooldownUntilTicks[player.uuid] ?: 0L)) return
        if (player.random.nextDouble() >= chance) return
        val stack = player.mainHandItem
        if (stack.isEmpty || !stack.isDamageableItem || !stack.isDamaged) return
        stack.damageValue = (stack.damageValue - 1).coerceAtLeast(0)
        chargedMaintenanceCooldownUntilTicks[player.uuid] = now + chargedMaintenanceCooldownTicks(JobLevels.jobLevel(player))
    }

    fun onPlayerTick(player: ServerPlayer) {
        applyMagnet(player)
        applyTechnicianReach(player)
    }

    private fun applyMagnet(player: ServerPlayer) {
        val radius = RolePerks.configuredJobMaxBonusPercent(player, "magnet").coerceAtLeast(0.0)
        if (radius <= 0.0) return
        val level = player.level() as? ServerLevel ?: return
        val now = level.gameTime
        if (now < (magnetScanCooldownUntilTicks[player.uuid] ?: 0L)) return
        magnetScanCooldownUntilTicks[player.uuid] = now + MAGNET_SCAN_INTERVAL_TICKS
        val center = player.position().add(0.0, 0.45, 0.0)
        val speed = (0.025 + 0.008 * JobLevels.jobLevel(player)).coerceAtMost(0.08)
        level.getEntitiesOfClass(ItemEntity::class.java, player.boundingBox.inflate(radius)).forEach { itemEntity ->
            if (!canMagnetPull(itemEntity)) return@forEach
            val delta = center.subtract(itemEntity.position())
            val distance = delta.length()
            if (distance <= 0.25 || distance > radius) return@forEach
            itemEntity.deltaMovement = itemEntity.deltaMovement.add(delta.normalize().scale(speed))
            itemEntity.hasImpulse = true
        }
    }

    private fun canMagnetPull(itemEntity: ItemEntity): Boolean {
        if (itemEntity.hasPickUpDelay()) return false
        val stack = itemEntity.item
        if (stack.isEmpty || RelicRouletteFeature.isTokenItem(stack.item)) return false
        return true
    }

    private fun applyTechnicianReach(player: ServerPlayer) {
        val attribute = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE) ?: return
        val bonus = RolePerks.configuredJobMaxBonusPercent(player, "technician_reach").coerceAtLeast(0.0)
        if (bonus <= 0.0) {
            attribute.removeModifier(TECHNICIAN_REACH_MODIFIER)
            return
        }
        attribute.addOrUpdateTransientModifier(
            AttributeModifier(
                TECHNICIAN_REACH_MODIFIER,
                bonus,
                AttributeModifier.Operation.ADD_VALUE,
            ),
        )
    }

    private fun isChargedMaintenanceBlock(state: BlockState): Boolean {
        val id = BuiltInRegistries.BLOCK.getKey(state.block).path
        return id.contains("redstone_ore") || id.contains("copper_ore") || id.contains("iron_ore")
    }

    private fun chargedMaintenanceCooldownTicks(rank: Int): Long = when {
        rank >= 5 -> 600L
        rank == 4 -> 800L
        rank == 3 -> 900L
        rank == 2 -> 1000L
        else -> 1200L
    }

    private fun isEngineerTool(stack: ItemStack): Boolean = stack.`is`(ItemTags.PICKAXES) || stack.`is`(ItemTags.AXES) || stack.`is`(ItemTags.SHOVELS)

    private const val MAGNET_SCAN_INTERVAL_TICKS = 2L
}
