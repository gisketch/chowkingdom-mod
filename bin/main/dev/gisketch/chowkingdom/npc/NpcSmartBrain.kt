package dev.gisketch.chowkingdom.npc

import com.mojang.datafixers.util.Pair
import dev.gisketch.chowkingdom.gyms.GymBattleService
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraft.world.entity.projectile.Arrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.tslat.smartbrainlib.api.core.BrainActivityGroup
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour
import net.tslat.smartbrainlib.api.core.behaviour.custom.look.LookAtTarget
import net.tslat.smartbrainlib.api.core.behaviour.custom.move.FloatToSurfaceOfFluid
import net.tslat.smartbrainlib.api.core.behaviour.custom.move.MoveToWalkTarget
import net.tslat.smartbrainlib.api.core.sensor.ExtendedSensor
import java.util.UUID

object NpcSmartBrain {
    fun sensors(): List<ExtendedSensor<out ChowNpcEntity>> = emptyList()

    fun coreTasks(): BrainActivityGroup<ChowNpcEntity> = BrainActivityGroup.coreTasks(
        FloatToSurfaceOfFluid<ChowNpcEntity>(),
        LookAtTarget<ChowNpcEntity>(),
        MoveToWalkTarget<ChowNpcEntity>(),
    )

    fun idleTasks(): BrainActivityGroup<ChowNpcEntity> = BrainActivityGroup.idleTasks(
        NpcTownBrainBehaviour(),
    )

}

private class NpcTownBrainBehaviour : ExtendedBehaviour<ChowNpcEntity>() {
    private val tasks: List<NpcSmartBrainTask> = listOf(
        NpcGymBattleLockTask,
        NpcCriticalOverrideTask,
        NpcFeatureTask("quest_claim", NpcFeature::tickSmartBrainQuestClaim),
        NpcFeatureTask("rent_contract_follow", NpcFeature::tickSmartBrainRentContractFollow),
        NpcFeatureTask("job_application_follow", NpcFeature::tickSmartBrainJobApplicationFollow),
        NpcFeatureTask("npc_micro_interaction", NpcFeature::tickSmartBrainNpcMicroInteraction),
        NpcFeatureTask("outgoing_gift", NpcFeature::tickSmartBrainOutgoingGift),
        NpcFeatureTask("quest_offer", NpcFeature::tickSmartBrainQuestOffer),
        NpcFeatureTask("talking_pause", NpcFeature::tickSmartBrainTalkingPause),
        NpcFeatureTask("ambient_life", NpcFeature::tickSmartBrainAmbientLife),
        NpcRoutineTask,
    )

    init {
        noTimeout()
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    override fun checkExtraStartConditions(level: ServerLevel, entity: ChowNpcEntity): Boolean = true

    override fun tick(entity: ChowNpcEntity) {
        tasks.firstOrNull { task -> task.run(entity) }
    }

    override fun shouldKeepRunning(entity: ChowNpcEntity): Boolean = true
}

private interface NpcSmartBrainTask {
    val id: String
    fun run(entity: ChowNpcEntity): Boolean
}

private class NpcFeatureTask(
    override val id: String,
    private val action: (ChowNpcEntity) -> Boolean,
) : NpcSmartBrainTask {
    override fun run(entity: ChowNpcEntity): Boolean = action(entity)
}

private object NpcRoutineTask : NpcSmartBrainTask {
    override val id: String = "routine"
    private val nextScanTick: MutableMap<UUID, Long> = linkedMapOf()
    private val lastActivity: MutableMap<UUID, String> = linkedMapOf()

    override fun run(entity: ChowNpcEntity): Boolean {
        val definition = NpcFeature.smartBrainDefinition(entity) ?: return false
        val activity = NpcFeature.activityFor(entity, definition)
        val now = entity.level().gameTime
        val previousActivity = lastActivity.put(entity.uuid, activity)
        val activityChanged = previousActivity != null && previousActivity != activity
        entity.debugActivity = activity
        if (activity != "sleep" && entity.isSleeping) entity.stopSleeping()
        if (activityChanged) {
            entity.navigation.stop()
            nextScanTick[entity.uuid] = now + initialDelay(entity, definition.jobDefinition.scanIntervalTicks)
            return true
        }
        if (!entity.navigation.isDone) return true
        val next = nextScanTick[entity.uuid]
        if (next == null) {
            nextScanTick[entity.uuid] = now + initialDelay(entity, definition.jobDefinition.scanIntervalTicks)
            return true
        }
        if (now < next) return true
        NpcFeature.moveToActivityTarget(entity, definition, activity)
        nextScanTick[entity.uuid] = now + routineDelay(entity, definition.jobDefinition.scanIntervalTicks)
        return true
    }

    private fun initialDelay(entity: ChowNpcEntity, scanIntervalTicks: Int): Long {
        val interval = scanIntervalTicks.coerceAtLeast(10)
        return entity.random.nextInt(interval + 1).toLong()
    }

    private fun routineDelay(entity: ChowNpcEntity, scanIntervalTicks: Int): Long {
        val interval = scanIntervalTicks.coerceAtLeast(10)
        val minimum = (interval / 2).coerceAtLeast(10)
        return minimum + entity.random.nextInt(interval + 1).toLong()
    }
}

private object NpcCriticalOverrideTask : NpcSmartBrainTask {
    override val id: String = "critical_override"

    override fun run(entity: ChowNpcEntity): Boolean = NpcBossFights.tick(entity) || NpcSmartBrainOverrides.tick(entity)
}

private object NpcGymBattleLockTask : NpcSmartBrainTask {
    override val id: String = "gym_battle_lock"

    override fun run(entity: ChowNpcEntity): Boolean = GymBattleService.tickBattleLock(entity) || NpcPokemonBattleService.tickBattleLock(entity)
}

object NpcSmartBrainOverrides {
    private val active: MutableMap<UUID, ActiveOverride> = linkedMapOf()

    fun tick(entity: ChowNpcEntity): Boolean {
        active[entity.uuid]?.let { override ->
            if (tickActive(entity, override)) return true
            finish(entity)
        }
        val hazardPos = hazardPos(entity) ?: return false
        startRunAway(entity, hazardPos.center, "avoid_fire")
        return active[entity.uuid]?.let { override -> tickActive(entity, override) } ?: false
    }

    fun startHurtResponse(entity: ChowNpcEntity, player: ServerPlayer) {
        startAttackBack(entity, player)
    }

    fun clear(entity: ChowNpcEntity) {
        finish(entity)
    }

    private fun startAttackBack(entity: ChowNpcEntity, player: ServerPlayer) {
        if (entity.isSleeping) entity.stopSleeping()
        entity.navigation.stop()
        val definition = NpcFeature.smartBrainDefinition(entity)
        val profile = retaliationProfile(definition)
        val snapshot = NpcCustomAnimationController.snapshot(entity)
        val override = ActiveOverride(
            type = NpcSmartBrainOverrideType.ATTACK_BACK,
            untilTick = entity.tickCount + ATTACK_BACK_MAX_TICKS,
            targetId = player.uuid,
            attacksRemaining = 2,
            nextActionTick = entity.tickCount,
            animationSnapshot = snapshot,
            profile = profile,
        )
        active[entity.uuid] = override
        playTemplate(entity, override, profile.chaseTemplate)
    }

    private fun startRunAway(entity: ChowNpcEntity, threatPos: Vec3, goal: String) {
        if (entity.isSleeping) entity.stopSleeping()
        active[entity.uuid] = ActiveOverride(NpcSmartBrainOverrideType.RUN_AWAY, entity.tickCount + RUN_AWAY_TICKS, threatPos = threatPos, goal = goal)
        moveAway(entity, threatPos)
    }

    private fun tickActive(entity: ChowNpcEntity, override: ActiveOverride): Boolean {
        if (entity.tickCount > override.untilTick || !entity.isAlive) return false
        return when (override.type) {
            NpcSmartBrainOverrideType.ATTACK_BACK -> tickAttackBack(entity, override)
            NpcSmartBrainOverrideType.RUN_AWAY -> tickRunAway(entity, override)
        }
    }

    private fun tickAttackBack(entity: ChowNpcEntity, override: ActiveOverride): Boolean {
        val level = entity.level() as? ServerLevel ?: return false
        val player = override.targetId?.let(level::getPlayerByUUID) as? ServerPlayer ?: return false
        if (!player.isAlive) return false
        if (override.phase == NpcSmartBrainOverridePhase.ATTACKING) return tickAttackAnimation(entity, player, override)
        if (override.attacksRemaining <= 0) return entity.tickCount < override.nextActionTick
        entity.debugActivity = "override"
        entity.debugGoal = "attack_back"
        entity.debugTargetPos = player.blockPosition().immutable()
        entity.lookControl.setLookAt(player, 30.0f, 30.0f)
        entity.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition())
        val profile = override.profile ?: UNARMED_RETALIATION
        val move = override.activeMove ?: profile.randomMove(entity).also { override.activeMove = it }
        if (entity.distanceToSqr(player) > move.startDistanceSqr) {
            playTemplate(entity, override, profile.chaseTemplate)
            moveTowardTarget(entity, player, profile.chaseSpeed)
            return true
        }
        entity.navigation.stop()
        if (entity.tickCount >= override.nextActionTick) {
            startAttackAnimation(entity, override)
        }
        return true
    }

    private fun startAttackAnimation(entity: ChowNpcEntity, override: ActiveOverride) {
        val profile = override.profile ?: UNARMED_RETALIATION
        val move = override.activeMove ?: profile.randomMove(entity)
        override.activeMove = move
        playTemplate(entity, override, move.castTemplate, forceRestart = true)
        override.phase = NpcSmartBrainOverridePhase.ATTACKING
        override.phaseStartedTick = entity.tickCount
        override.firedEvents.clear()
    }

    private fun tickAttackAnimation(entity: ChowNpcEntity, player: ServerPlayer, override: ActiveOverride): Boolean {
        entity.debugActivity = "override"
        val move = override.activeMove ?: (override.profile ?: UNARMED_RETALIATION).moves.first()
        entity.debugGoal = "attack_${move.id}"
        entity.debugTargetPos = player.blockPosition().immutable()
        entity.lookControl.setLookAt(player, 30.0f, 30.0f)
        entity.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition())
        val elapsed = entity.tickCount - override.phaseStartedTick
        if (move.commitDuringWindup && elapsed < (move.hitTicks.minOrNull() ?: move.durationTicks)) {
            moveTowardTarget(entity, player, (override.profile ?: UNARMED_RETALIATION).chaseSpeed * 0.7)
        } else {
            entity.navigation.stop()
        }
        move.hitTicks.forEach { hitTick ->
            if (elapsed >= hitTick && override.firedEvents.add(NpcAnimationEvent(hitTick, NpcAnimationEventType.ATTACK_HIT))) {
                attackHit(entity, player, move)
            }
        }
        if (elapsed < move.durationTicks) return true
        override.attacksRemaining -= 1
        override.phase = NpcSmartBrainOverridePhase.CHASING
        override.nextActionTick = entity.tickCount + if (override.attacksRemaining <= 0) ATTACK_FINISH_HOLD_TICKS else ATTACK_COOLDOWN_TICKS
        override.activeMove = null
        if (override.attacksRemaining > 0) playTemplate(entity, override, (override.profile ?: UNARMED_RETALIATION).chaseTemplate)
        return true
    }

    private fun attackHit(entity: ChowNpcEntity, player: ServerPlayer, move: RetaliationMove) {
        if (move.releaseTemplate != null) playTemplate(entity, active[entity.uuid] ?: return, move.releaseTemplate, forceRestart = true)
        if (move.kind == NpcBossMoveKinds.PROJECTILE && move.projectileType == "arrow") {
            shootRetaliationArrow(entity, player, move)
            return
        }
        val distanceSqr = entity.distanceToSqr(player)
        if (distanceSqr > move.hitDistanceSqr) {
            entity.debugGoal = "attack_miss_range_${"%.2f".format(kotlin.math.sqrt(distanceSqr))}"
            return
        }
        val level = entity.level() as? ServerLevel ?: return
        entity.swing(InteractionHand.MAIN_HAND, true)
        level.broadcastEntityEvent(entity, ATTACK_ANIMATION_EVENT)
        level.playSound(null, entity.x, entity.y, entity.z, move.sound, SoundSource.HOSTILE, 1.0f, 0.9f + entity.random.nextFloat() * 0.2f)
        applyAttackDamage(entity, player, move.damage)
        player.knockback(move.knockback, entity.x - player.x, entity.z - player.z)
        player.hurtMarked = true
    }

    private fun shootRetaliationArrow(entity: ChowNpcEntity, player: ServerPlayer, move: RetaliationMove) {
        val level = entity.level() as? ServerLevel ?: return
        val eye = entity.getEyePosition()
        val aim = player.getEyePosition().subtract(eye).takeIf { vector -> vector.lengthSqr() > 0.0001 }?.normalize() ?: entity.lookAngle.normalize()
        val start = eye.add(aim.scale(0.45)).add(0.0, -0.15, 0.0)
        val arrow = Arrow(level, entity, ItemStack(Items.ARROW), move.mainHand)
        arrow.setPos(start.x, start.y, start.z)
        arrow.setBaseDamage(move.damage.toDouble())
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED
        arrow.shoot(aim.x, aim.y, aim.z, move.projectileSpeed.toFloat(), move.projectileInaccuracy.toFloat())
        level.addFreshEntity(arrow)
        level.playSound(null, entity.x, entity.eyeY, entity.z, SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 1.0f, 0.9f + entity.random.nextFloat() * 0.2f)
    }

    private fun applyAttackDamage(entity: ChowNpcEntity, player: ServerPlayer, damage: Float) {
        if (player.abilities.invulnerable) return
        player.invulnerableTime = 0
        val before = player.health
        val source = entity.damageSources().mobAttack(entity)
        val damaged = player.hurt(source, damage) || player.hurt(player.damageSources().generic(), damage)
        if (!damaged && player.health >= before) forceHealthDamage(player, source, damage)
    }

    private fun forceHealthDamage(player: ServerPlayer, source: DamageSource, damage: Float) {
        val newHealth = (player.health - damage).coerceAtLeast(0.0f)
        player.setHealth(newHealth)
        player.hurtDuration = 10
        player.hurtTime = player.hurtDuration
        if (newHealth <= 0.0f) player.die(source)
    }

    private fun playTemplate(entity: ChowNpcEntity, override: ActiveOverride, template: NpcAnimationTemplate, forceRestart: Boolean = false) {
        if (!forceRestart && override.activeTemplateId == template.id) return
        NpcCustomAnimationController.play(entity, template)
        override.activeTemplateId = template.id
    }

    private fun moveTowardTarget(entity: ChowNpcEntity, player: ServerPlayer, speed: Double) {
        entity.navigation.moveTo(player.x, player.y, player.z, speed)
        if (entity.navigation.isDone) {
            entity.moveControl.setWantedPosition(player.x, player.y, player.z, speed)
        }
    }

    private fun tickRunAway(entity: ChowNpcEntity, override: ActiveOverride): Boolean {
        val threatPos = override.threatPos ?: return false
        entity.debugActivity = "override"
        entity.debugGoal = override.goal
        if (entity.tickCount >= override.nextActionTick || entity.navigation.isDone) {
            moveAway(entity, threatPos)
            override.nextActionTick = entity.tickCount + RUN_REPATH_TICKS
        }
        return true
    }

    private fun moveAway(entity: ChowNpcEntity, threatPos: Vec3) {
        val away = entity.position().subtract(threatPos).let { vector ->
            if (vector.horizontalDistanceSqr() < 0.01) Vec3(entity.random.nextDouble() - 0.5, 0.0, entity.random.nextDouble() - 0.5) else vector
        }.normalize()
        val target = entity.position().add(away.x * RUN_AWAY_DISTANCE, 0.0, away.z * RUN_AWAY_DISTANCE)
        val targetPos = BlockPos.containing(target.x, entity.y, target.z)
        entity.debugTargetPos = targetPos.immutable()
        entity.navigation.moveTo(target.x, entity.y, target.z, RUN_AWAY_SPEED)
    }

    private fun retaliationProfile(definition: NpcDefinition?): RetaliationProfile {
        if (definition == null || !hasRetaliationCombatIdentity(definition)) return UNARMED_RETALIATION
        val moveset = NpcBossMovesets.forDefinition(definition).normalized()
        val mainHand = retaliationMainHand(definition, moveset)
        val offHand = retaliationOffHand(definition, moveset)
        val moves = moveset.moves
            .filter { move -> move.kind in RETALIATION_ATTACK_KINDS && move.maxPhaseIndex >= 0 }
            .ifEmpty { UNARMED_RETALIATION.moves.map { move -> move.sourceMove } }
            .map { move -> retaliationMove(move, moveset, mainHand, offHand) }
        return RetaliationProfile(
            id = moveset.id,
            attackStartDistanceSqr = moveset.attackStartDistance * moveset.attackStartDistance,
            chaseSpeed = when (moveset.movementStyle) {
                NpcBossMovementStyles.RANGED, NpcBossMovementStyles.CASTER -> RANGED_RETALIATION_CHASE_SPEED
                else -> ATTACK_CHASE_SPEED
            },
            chaseTemplate = NpcAnimationTemplate(
                id = "retaliation_${moveset.id}_chase",
                animationId = moveset.approachAnimationId.ifBlank { NpcBossMovesetDefinition.DEFAULT_READY_ANIMATION },
                animationSource = moveset.approachAnimationSource,
                loop = true,
                durationTicks = 16,
                mainHand = mainHand,
                offHand = offHand,
            ),
            moves = moves,
        )
    }

    private fun hasRetaliationCombatIdentity(definition: NpcDefinition): Boolean {
        val template = NpcBossMovesets.normalizeId(definition.boss.template)
        return definition.classId.isNotBlank() || (template.isNotBlank() && template != NpcBossDefinition.DEFAULT_BOSS_TEMPLATE)
    }

    private fun retaliationMove(move: NpcBossMoveDefinition, moveset: NpcBossMovesetDefinition, mainHand: ItemStack, offHand: ItemStack): RetaliationMove {
        val range = when (move.kind) {
            NpcBossMoveKinds.PROJECTILE, NpcBossMoveKinds.BEAM -> moveset.attackStartDistance.coerceAtLeast(move.maxDistance)
            NpcBossMoveKinds.AREA -> move.areaRadius.coerceAtLeast(move.range)
            else -> move.range
        }.coerceAtLeast(1.5)
        val hitTicks = move.hitTicks.ifEmpty { mutableListOf((move.durationTicks / 2).coerceAtLeast(1)) }
        return RetaliationMove(
            id = move.id,
            kind = move.kind,
            projectileType = move.projectileType,
            durationTicks = move.durationTicks.coerceIn(6, 80),
            hitTicks = hitTicks.map { tick -> tick.coerceIn(1, move.durationTicks.coerceAtLeast(1)) },
            startDistanceSqr = retaliationStartDistance(move.kind, range),
            hitDistanceSqr = retaliationHitDistance(move.kind, range),
            commitDuringWindup = move.kind == NpcBossMoveKinds.MELEE,
            damage = (move.damage.takeIf { value -> value > 0.0 } ?: moveset.damage).toFloat().coerceIn(1.0f, RETALIATION_MAX_DAMAGE),
            knockback = move.knockback.coerceIn(0.0, 2.0),
            projectileSpeed = move.projectileSpeed.coerceIn(0.5, 6.0),
            projectileInaccuracy = move.projectileInaccuracy.coerceIn(0.0, 8.0),
            mainHand = mainHand,
            castTemplate = NpcAnimationTemplate(
                id = "retaliation_${moveset.id}_${move.id}",
                animationId = move.animationId,
                animationSource = NpcBossAnimationSources.PLAYERLIKE,
                loop = false,
                durationTicks = move.durationTicks.coerceIn(6, 80),
                mainHand = mainHand,
                offHand = offHand,
            ),
            releaseTemplate = move.releaseAnimationId.takeIf { id -> id.isNotBlank() && id != move.animationId }?.let { releaseId ->
                NpcAnimationTemplate(
                    id = "retaliation_${moveset.id}_${move.id}_release",
                    animationId = releaseId,
                    animationSource = NpcBossAnimationSources.PLAYERLIKE,
                    loop = false,
                    durationTicks = 8,
                    mainHand = mainHand,
                    offHand = offHand,
                )
            },
            sound = if (move.kind == NpcBossMoveKinds.PROJECTILE || move.kind == NpcBossMoveKinds.BEAM) SoundEvents.EVOKER_CAST_SPELL else SoundEvents.PLAYER_ATTACK_STRONG,
            sourceMove = move,
        )
    }

    private fun retaliationMainHand(definition: NpcDefinition, moveset: NpcBossMovesetDefinition): ItemStack {
        if (definition.boss.mainHand.isNotBlank()) return itemStack(definition.boss.mainHand, fallbackMainHand(moveset.id))
        return fallbackMainHand(moveset.id)
    }

    private fun retaliationStartDistance(kind: String, range: Double): Double {
        val distance = when (kind) {
            NpcBossMoveKinds.MELEE -> (range - 0.35).coerceIn(1.35, 2.55)
            NpcBossMoveKinds.AREA -> (range - 0.25).coerceIn(1.5, 3.0)
            else -> range
        }
        return distance * distance
    }

    private fun retaliationHitDistance(kind: String, range: Double): Double {
        val distance = when (kind) {
            NpcBossMoveKinds.MELEE -> (range + 0.35).coerceIn(2.0, 3.35)
            NpcBossMoveKinds.AREA -> (range + 0.25).coerceIn(2.0, 5.0)
            else -> range
        }
        return distance * distance
    }

    private fun retaliationOffHand(definition: NpcDefinition, moveset: NpcBossMovesetDefinition): ItemStack {
        if (definition.boss.offHand.isNotBlank()) return itemStack(definition.boss.offHand, fallbackOffHand(moveset.id))
        return fallbackOffHand(moveset.id)
    }

    private fun fallbackMainHand(movesetId: String): ItemStack = when (movesetId) {
        "archer", "bounty_hunter", "tundra_archer", "war_archer" -> itemStack("archers:aether_longbow", ItemStack(Items.BOW))
        "bard" -> itemStack("bards_rpg:aether_harp_crossbow", ItemStack(Items.CROSSBOW))
        "water_wizard", "arcane_wizard", "fire_wizard", "frost_wizard", "wind_wizard", "earth_wizard" -> ItemStack.EMPTY
        "forcemaster" -> itemStack("forcemaster_rpg:unique_knuckle_1", itemStack("forcemaster_rpg:wooden_knuckle", ItemStack.EMPTY))
        "paladin" -> ItemStack(Items.MACE)
        "priest" -> itemStack("paladins:holy_staff", ItemStack(Items.BLAZE_ROD))
        "wizard" -> itemStack("wizards:staff_wizard", ItemStack(Items.BLAZE_ROD))
        "berserker" -> itemStack("simplyswords:ribboncleaver", ItemStack(Items.NETHERITE_SWORD))
        "witcher" -> itemStack("witcher_rpg:steel_witcher_sword", ItemStack(Items.IRON_SWORD))
        "rogue" -> itemStack("simplyswords:iron_rapier", ItemStack(Items.IRON_SWORD))
        else -> ItemStack(Items.IRON_SWORD)
    }

    private fun fallbackOffHand(movesetId: String): ItemStack = when (movesetId) {
        "forcemaster" -> itemStack("forcemaster_rpg:unique_knuckle_0", itemStack("forcemaster_rpg:wooden_knuckle", ItemStack.EMPTY))
        "paladin" -> itemStack("paladins:netherite_kite_shield", ItemStack(Items.SHIELD))
        "rogue" -> itemStack("simplyswords:iron_rapier", ItemStack.EMPTY)
        else -> ItemStack.EMPTY
    }

    private fun itemStack(itemId: String, fallback: ItemStack): ItemStack {
        val raw = itemId.trim()
        if (raw.isBlank()) return fallback.copy()
        if (raw.equals("none", ignoreCase = true) || raw.equals("empty", ignoreCase = true) || raw.equals("air", ignoreCase = true) || raw.equals("minecraft:air", ignoreCase = true)) return ItemStack.EMPTY
        val normalized = if (':' in raw) raw else "minecraft:$raw"
        val id = runCatching { ResourceLocation.parse(normalized) }.getOrNull() ?: return fallback.copy()
        val item = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR)
        return if (item == Items.AIR) fallback.copy() else ItemStack(item)
    }

    private fun finish(entity: ChowNpcEntity) {
        val override = active.remove(entity.uuid)
        entity.navigation.stop()
        if (override?.type == NpcSmartBrainOverrideType.ATTACK_BACK) {
            override.animationSnapshot?.let { snapshot -> NpcCustomAnimationController.restore(entity, snapshot) }
        }
    }

    private fun hazardPos(entity: ChowNpcEntity): BlockPos? {
        val level = entity.level()
        val pos = entity.blockPosition()
        return listOf(pos, pos.below()).firstOrNull { candidate ->
            val block = level.getBlockState(candidate).block
            block == Blocks.FIRE || block == Blocks.SOUL_FIRE || block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE
        }
    }

    private class ActiveOverride(
        val type: NpcSmartBrainOverrideType,
        val untilTick: Int,
        val targetId: UUID? = null,
        val threatPos: Vec3? = null,
        val goal: String = type.id,
        var attacksRemaining: Int = 0,
        var nextActionTick: Int = 0,
        val animationSnapshot: NpcAnimationSnapshot? = null,
        val profile: RetaliationProfile? = null,
        var phase: NpcSmartBrainOverridePhase = NpcSmartBrainOverridePhase.CHASING,
        var phaseStartedTick: Int = 0,
        var activeTemplateId: String = "",
        var activeMove: RetaliationMove? = null,
        val firedEvents: MutableSet<NpcAnimationEvent> = mutableSetOf(),
    )

    private data class RetaliationProfile(
        val id: String,
        val attackStartDistanceSqr: Double,
        val chaseSpeed: Double,
        val chaseTemplate: NpcAnimationTemplate,
        val moves: List<RetaliationMove>,
    ) {
        fun randomMove(entity: ChowNpcEntity): RetaliationMove = moves[entity.random.nextInt(moves.size)]
    }

    private data class RetaliationMove(
        val id: String,
        val kind: String,
        val projectileType: String,
        val durationTicks: Int,
        val hitTicks: List<Int>,
        val startDistanceSqr: Double,
        val hitDistanceSqr: Double,
        val commitDuringWindup: Boolean,
        val damage: Float,
        val knockback: Double,
        val projectileSpeed: Double,
        val projectileInaccuracy: Double,
        val mainHand: ItemStack,
        val castTemplate: NpcAnimationTemplate,
        val releaseTemplate: NpcAnimationTemplate?,
        val sound: net.minecraft.sounds.SoundEvent,
        val sourceMove: NpcBossMoveDefinition,
    )

    private enum class NpcSmartBrainOverridePhase {
        CHASING,
        ATTACKING,
    }

    private enum class NpcSmartBrainOverrideType(val id: String) {
        ATTACK_BACK("attack_back"),
        RUN_AWAY("run_away"),
    }

    private const val RUN_AWAY_TICKS = 60
    private const val RUN_REPATH_TICKS = 10
    private const val RUN_AWAY_DISTANCE = 7.0
    private const val RUN_AWAY_SPEED = 1.25
    private const val ATTACK_BACK_MAX_TICKS = 160
    private const val ATTACK_START_DISTANCE_SQR = 4.0
    private const val ATTACK_CHASE_SPEED = 1.75
    private const val RANGED_RETALIATION_CHASE_SPEED = 1.25
    private const val ATTACK_DAMAGE = 4.0f
    private const val RETALIATION_MAX_DAMAGE = 6.0f
    private const val ATTACK_KNOCKBACK = 0.35
    private const val ATTACK_COOLDOWN_TICKS = 13
    private const val ATTACK_FINISH_HOLD_TICKS = 12
    private const val ATTACK_ANIMATION_EVENT: Byte = 4
    private val RETALIATION_ATTACK_KINDS = setOf(NpcBossMoveKinds.MELEE, NpcBossMoveKinds.AREA, NpcBossMoveKinds.PROJECTILE, NpcBossMoveKinds.BEAM)
    private val UNARMED_RETALIATION_MOVE_SOURCE = NpcBossMoveDefinition(
        id = "empty_hand_hit",
        kind = NpcBossMoveKinds.MELEE,
        animationId = "bettercombat:one_handed_slash_horizontal_right",
        durationTicks = 16,
        hitTicks = mutableListOf(6),
        damage = ATTACK_DAMAGE.toDouble(),
        range = 2.25,
        knockback = ATTACK_KNOCKBACK,
    ).normalized(ATTACK_DAMAGE.toDouble())
    private val UNARMED_RETALIATION = RetaliationProfile(
        id = "unarmed",
        attackStartDistanceSqr = ATTACK_START_DISTANCE_SQR,
        chaseSpeed = ATTACK_CHASE_SPEED,
        chaseTemplate = NpcAnimationTemplate(
            id = "retaliation_unarmed_chase",
            animationId = NpcBossMovesetDefinition.DEFAULT_READY_ANIMATION,
            animationSource = NpcBossAnimationSources.NATURAL,
            loop = true,
            durationTicks = 16,
            mainHand = ItemStack.EMPTY,
            offHand = ItemStack.EMPTY,
        ),
        moves = listOf(
            RetaliationMove(
                id = "empty_hand_hit",
                kind = NpcBossMoveKinds.MELEE,
                projectileType = "arrow",
                durationTicks = 16,
                hitTicks = listOf(6),
                startDistanceSqr = ATTACK_START_DISTANCE_SQR,
                hitDistanceSqr = ATTACK_START_DISTANCE_SQR,
                commitDuringWindup = true,
                damage = ATTACK_DAMAGE,
                knockback = ATTACK_KNOCKBACK,
                projectileSpeed = 0.0,
                projectileInaccuracy = 0.0,
                mainHand = ItemStack.EMPTY,
                castTemplate = NpcAnimationTemplate(
                    id = "retaliation_unarmed_empty_hand_hit",
                    animationId = "bettercombat:one_handed_slash_horizontal_right",
                    animationSource = NpcBossAnimationSources.PLAYERLIKE,
                    loop = false,
                    durationTicks = 16,
                    mainHand = ItemStack.EMPTY,
                    offHand = ItemStack.EMPTY,
                ),
                releaseTemplate = null,
                sound = SoundEvents.PLAYER_ATTACK_STRONG,
                sourceMove = UNARMED_RETALIATION_MOVE_SOURCE,
            ),
        ),
    )
}
