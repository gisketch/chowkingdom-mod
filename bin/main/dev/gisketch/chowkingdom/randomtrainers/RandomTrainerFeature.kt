package dev.gisketch.chowkingdom.randomtrainers

import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.npc.NpcConfig
import dev.gisketch.chowkingdom.npc.NpcDialogPayload
import dev.gisketch.chowkingdom.npc.NpcDialogTokens
import dev.gisketch.chowkingdom.npc.NpcLlmService
import dev.gisketch.chowkingdom.npc.NpcNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarIcons
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.ai.attributes.Attributes
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModList
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.UUID
import java.util.function.Supplier

object RandomTrainerFeature {
    private val ENTITIES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, ChowKingdomMod.MOD_ID)

    val RANDOM_TRAINER_ENTITY: DeferredHolder<EntityType<*>, EntityType<RandomTrainerEntity>> = ENTITIES.register(
        "random_trainer",
        Supplier {
            EntityType.Builder.of(::RandomTrainerEntity, MobCategory.CREATURE)
                .sized(0.6f, 1.8f)
                .clientTrackingRange(10)
                .updateInterval(1)
                .build("random_trainer")
        },
    )

    fun register(modBus: IEventBus) {
        ENTITIES.register(modBus)
        RandomTrainerCatalog.load()
        RandomTrainerStore.load()
        RandomTrainerSpawner.register()
        RandomTrainerBattleService.register()
        RandomTrainerCommands.register()
        modBus.addListener(::registerAttributes)
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
    }

    fun openDialog(player: ServerPlayer, entity: RandomTrainerEntity) {
        if (player.distanceToSqr(entity) > 64.0) return
        val definition = RandomTrainerCatalog.byId(entity.rosterId)
        val alreadyDefeated = RandomTrainerStore.hasDefeated(player, entity.rosterId)
        val challengeAvailable = !alreadyDefeated && !entity.inTrainerBattle
        val challengeDisabledReason = when {
            alreadyDefeated -> "Already defeated."
            entity.inTrainerBattle -> "Already battling."
            else -> ""
        }
        val fallback = definition?.dialogue?.randomOrNull()
            ?: "I am ${entity.trainerName}. Challenge me if your team is ready."
        val llmEnabled = RandomTrainerCatalog.settings().llmDialogue && NpcConfig.settings().llm.enabled
        val token = if (llmEnabled) NpcDialogTokens.next() else 0L
        NpcNetwork.openDialog(
            player,
            NpcDialogPayload(
                npcId = dialogId(entity.uuid),
                name = entity.trainerName,
                title = entity.trainerTitle,
                message = if (llmEnabled) "..." else fallback,
                contractGranted = false,
                closeOnly = false,
                closeLabel = "BYE",
                friendshipLevel = 0,
                npcEntityId = entity.id,
                animalesePitch = "med",
                animalesePitchMultiplier = 1.0f,
                animaleseVolume = 0.75f,
                animaleseRadius = 12.0f,
                talkEnabled = false,
                responseToken = token,
                dialogMode = "random_trainer",
                challengeAvailable = challengeAvailable,
                challengeDisabledReason = challengeDisabledReason,
            ),
        )
        if (llmEnabled) {
            NpcLlmService.randomTrainerEvent(
                player = player,
                npcId = dialogId(entity.uuid),
                trainerName = entity.trainerName,
                title = entity.trainerTitle,
                fallbackMessage = fallback,
                input = "A player named ${player.gameProfile.name} right-clicked you in the wild before a Pokemon trainer battle. Reply as ${entity.trainerName}, a ${entity.trainerTitle}, in one short in-character line. Invite a challenge without mentioning UI.",
                responseToken = token,
            )
        }
    }

    fun handleDialogAction(player: ServerPlayer, npcId: String, action: String): Boolean {
        if (!npcId.startsWith(DIALOG_PREFIX)) return false
        val uuid = runCatching { UUID.fromString(npcId.removePrefix(DIALOG_PREFIX)) }.getOrNull()
            ?: return true
        val entity = player.server.allLevels.asSequence()
            .mapNotNull { level -> level.getEntity(uuid) as? RandomTrainerEntity }
            .firstOrNull()
            ?: return true
        if (action != "gym_challenge") return true
        if (RandomTrainerStore.hasDefeated(player, entity.rosterId)) {
            SnackbarNetwork.send(player, SnackbarNotification.item("minecraft:paper", "TRAINER DEFEATED", "You already defeated ${entity.trainerName}.", SnackbarType.GENERIC, SnackbarSounds.GENERIC))
            return true
        }
        val result = RandomTrainerBattleService.start(player, entity)
        if (!result.started) {
            SnackbarNetwork.send(player, SnackbarNotification.item(SnackbarIcons.ERROR, "BATTLE FAILED", result.message, SnackbarType.ERROR, SnackbarSounds.ERROR))
        }
        return true
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        RandomTrainerCatalog.load()
        RandomTrainerStore.load()
        RandomTrainerBattleService.init(event.server)
        if (ModList.get().isLoaded("rctmod")) {
            ChowKingdomMod.LOGGER.warn("RCT Mod is loaded. CKDM random trainers only use RCT API; disable RCT Mod trainer progression/level-cap systems to avoid conflicting progression.")
        }
    }

    private fun registerAttributes(event: EntityAttributeCreationEvent) {
        event.put(
            RANDOM_TRAINER_ENTITY.get(),
            Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.FOLLOW_RANGE, 24.0)
                .build(),
        )
    }

    private fun dialogId(uuid: UUID): String = "$DIALOG_PREFIX$uuid"

    private const val DIALOG_PREFIX = "rt:"
}
