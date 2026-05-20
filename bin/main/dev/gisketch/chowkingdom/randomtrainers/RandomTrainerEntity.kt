package dev.gisketch.chowkingdom.randomtrainers

import dev.gisketch.chowkingdom.compat.PehkuiScaleBridge
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import java.util.UUID

class RandomTrainerEntity(entityType: EntityType<out PathfinderMob>, level: Level) : PathfinderMob(entityType, level) {
    var originPlayerUuid: UUID? = null
    var spawnedAtTick: Long = 0L

    var instanceId: String
        get() = entityData.get(INSTANCE_ID_DATA)
        set(value) = entityData.set(INSTANCE_ID_DATA, value.trim().ifBlank { UUID.randomUUID().toString() })
    var rosterId: String
        get() = entityData.get(ROSTER_ID_DATA)
        set(value) = entityData.set(ROSTER_ID_DATA, cleanRandomTrainerId(value))
    var trainerName: String
        get() = entityData.get(NAME_DATA)
        set(value) = entityData.set(NAME_DATA, value.trim().ifBlank { "Trainer" })
    var trainerTitle: String
        get() = entityData.get(TITLE_DATA)
        set(value) = entityData.set(TITLE_DATA, value.trim().ifBlank { "Trainer" })
    var trainerGender: String
        get() = entityData.get(GENDER_DATA)
        set(value) = entityData.set(GENDER_DATA, value.trim().lowercase().ifBlank { "any" })
    var skinSet: String
        get() = entityData.get(SKIN_SET_DATA)
        set(value) = entityData.set(SKIN_SET_DATA, cleanRandomTrainerId(value))
    var inTrainerBattle: Boolean
        get() = entityData.get(IN_BATTLE_DATA)
        set(value) = entityData.set(IN_BATTLE_DATA, value)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(INSTANCE_ID_DATA, UUID.randomUUID().toString())
        builder.define(ROSTER_ID_DATA, "")
        builder.define(NAME_DATA, "Trainer")
        builder.define(TITLE_DATA, "Trainer")
        builder.define(GENDER_DATA, "any")
        builder.define(SKIN_SET_DATA, "")
        builder.define(IN_BATTLE_DATA, false)
    }

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(5, WaterAvoidingRandomStrollGoal(this, 0.8))
        goalSelector.addGoal(6, LookAtPlayerGoal(this, Player::class.java, 8.0f))
        goalSelector.addGoal(7, RandomLookAroundGoal(this))
    }

    fun configure(definition: RandomTrainerDefinition, originPlayer: ServerPlayer, spawnTick: Long) {
        instanceId = UUID.randomUUID().toString()
        rosterId = definition.id
        trainerName = definition.displayName()
        trainerTitle = definition.title
        trainerGender = definition.gender
        skinSet = definition.skinPath()
        originPlayerUuid = originPlayer.uuid
        spawnedAtTick = spawnTick
        customName = Component.literal(trainerName)
        isCustomNameVisible = true
        PehkuiScaleBridge.apply(this, definition.height, definition.weight)
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS
        if (!level().isClientSide && player is ServerPlayer) RandomTrainerFeature.openDialog(player, this)
        return InteractionResult.sidedSuccess(level().isClientSide)
    }

    override fun aiStep() {
        super.aiStep()
        if (inTrainerBattle) freezeBattleMotion()
        if (!level().isClientSide) RandomTrainerSpawner.track(this)
    }

    override fun customServerAiStep() {
        if (inTrainerBattle) {
            freezeBattleMotion()
            return
        }
        super.customServerAiStep()
    }

    private fun freezeBattleMotion() {
        navigation.stop()
        target = null
        setDeltaMovement(0.0, deltaMovement.y, 0.0)
        xxa = 0.0f
        zza = 0.0f
    }

    override fun shouldBeSaved(): Boolean = false

    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putString(INSTANCE_ID_TAG, instanceId)
        tag.putString(ROSTER_ID_TAG, rosterId)
        tag.putString(NAME_TAG, trainerName)
        tag.putString(TITLE_TAG, trainerTitle)
        tag.putString(GENDER_TAG, trainerGender)
        tag.putString(SKIN_SET_TAG, skinSet)
        tag.putBoolean(IN_BATTLE_TAG, inTrainerBattle)
        tag.putLong(SPAWNED_AT_TAG, spawnedAtTick)
        originPlayerUuid?.let { tag.putUUID(ORIGIN_PLAYER_TAG, it) }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        instanceId = tag.getString(INSTANCE_ID_TAG).ifBlank { UUID.randomUUID().toString() }
        rosterId = tag.getString(ROSTER_ID_TAG)
        trainerName = tag.getString(NAME_TAG).ifBlank { "Trainer" }
        trainerTitle = tag.getString(TITLE_TAG).ifBlank { "Trainer" }
        trainerGender = tag.getString(GENDER_TAG).ifBlank { "any" }
        skinSet = tag.getString(SKIN_SET_TAG)
        inTrainerBattle = tag.getBoolean(IN_BATTLE_TAG)
        spawnedAtTick = tag.getLong(SPAWNED_AT_TAG)
        if (tag.hasUUID(ORIGIN_PLAYER_TAG)) originPlayerUuid = tag.getUUID(ORIGIN_PLAYER_TAG)
        customName = Component.literal(trainerName)
        isCustomNameVisible = true
    }

    companion object {
        private val INSTANCE_ID_DATA: EntityDataAccessor<String> = SynchedEntityData.defineId(RandomTrainerEntity::class.java, EntityDataSerializers.STRING)
        private val ROSTER_ID_DATA: EntityDataAccessor<String> = SynchedEntityData.defineId(RandomTrainerEntity::class.java, EntityDataSerializers.STRING)
        private val NAME_DATA: EntityDataAccessor<String> = SynchedEntityData.defineId(RandomTrainerEntity::class.java, EntityDataSerializers.STRING)
        private val TITLE_DATA: EntityDataAccessor<String> = SynchedEntityData.defineId(RandomTrainerEntity::class.java, EntityDataSerializers.STRING)
        private val GENDER_DATA: EntityDataAccessor<String> = SynchedEntityData.defineId(RandomTrainerEntity::class.java, EntityDataSerializers.STRING)
        private val SKIN_SET_DATA: EntityDataAccessor<String> = SynchedEntityData.defineId(RandomTrainerEntity::class.java, EntityDataSerializers.STRING)
        private val IN_BATTLE_DATA: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(RandomTrainerEntity::class.java, EntityDataSerializers.BOOLEAN)
        private const val INSTANCE_ID_TAG = "InstanceId"
        private const val ROSTER_ID_TAG = "RosterId"
        private const val NAME_TAG = "TrainerName"
        private const val TITLE_TAG = "TrainerTitle"
        private const val GENDER_TAG = "TrainerGender"
        private const val SKIN_SET_TAG = "SkinSet"
        private const val IN_BATTLE_TAG = "InTrainerBattle"
        private const val SPAWNED_AT_TAG = "SpawnedAtTick"
        private const val ORIGIN_PLAYER_TAG = "OriginPlayer"
    }
}
