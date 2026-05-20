package dev.gisketch.chowkingdom.randomtrainers

import com.gitlab.srcmc.rctapi.api.RCTApi
import com.gitlab.srcmc.rctapi.api.battle.BattleFormat
import com.gitlab.srcmc.rctapi.api.battle.BattleRules
import com.gitlab.srcmc.rctapi.api.battle.BattleState
import com.gitlab.srcmc.rctapi.api.events.Event
import com.gitlab.srcmc.rctapi.api.events.EventListener
import com.gitlab.srcmc.rctapi.api.events.Events
import com.gitlab.srcmc.rctapi.api.models.TrainerModel
import com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC
import com.gitlab.srcmc.rctapi.api.trainer.TrainerPlayer
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import java.util.UUID

object RandomTrainerBattleService {
    private const val API_ID = ChowKingdomMod.MOD_ID
    private var api: RCTApi? = null
    private var gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private var registered = false
    private var listenerRegistered = false
    private val activeStates: MutableMap<UUID, BattleState> = linkedMapOf()
    private val battleEndedListener = EventListener<BattleState> { event: Event<BattleState> -> handleBattleEnded(event.value) }

    fun register() {
        if (registered) return
        registered = true
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedOut)
    }

    fun init(server: MinecraftServer) {
        runCatching {
            val instance = RCTApi.getInstance(API_ID) ?: RCTApi.initInstance(API_ID)
            api = instance
            gson = instance.configureGsonBuilder(GsonBuilder().setPrettyPrinting()).create()
            instance.trainerRegistry.init(server)
            if (!listenerRegistered) {
                instance.eventContext.register(Events.BATTLE_ENDED, battleEndedListener)
                listenerRegistered = true
            }
            server.playerList.players.forEach(::registerPlayer)
        }.onFailure { exception ->
            ChowKingdomMod.LOGGER.warn("Failed to initialize random trainer RCT API", exception)
        }
    }

    fun start(player: ServerPlayer, entity: RandomTrainerEntity): RandomTrainerStartResult {
        val instance = api ?: return RandomTrainerStartResult(false, "The trainer battle API is not ready yet.")
        if (entity.inTrainerBattle) return RandomTrainerStartResult(false, "${entity.trainerName} is already battling.")
        if (RandomTrainerStore.hasDefeated(player, entity.rosterId)) return RandomTrainerStartResult(false, "You already defeated ${entity.trainerName}.")
        if (!partyBattleReady(player)) return RandomTrainerStartResult(false, "Your party needs at least one Pokemon that can battle.")
        val definition = RandomTrainerCatalog.byId(entity.rosterId) ?: return RandomTrainerStartResult(false, "Trainer roster is missing: ${entity.rosterId}.")
        val topLevel = playerTopLevel(player).coerceAtLeast(1)
        return runCatching {
            registerPlayer(player)
            entity.navigation.stop()
            entity.lookControl.setLookAt(player, 30.0f, 30.0f)
            entity.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition())
            player.lookAt(EntityAnchorArgument.Anchor.EYES, entity.getEyePosition())
            val playerTrainer = instance.trainerRegistry.getById(playerTrainerId(player), TrainerPlayer::class.java)
                ?: TrainerPlayer(player).also { instance.trainerRegistry.registerPlayer(playerTrainerId(player), it) }
            val npcTrainer = registerNpcTrainer(instance, entity, definition, topLevel)
                ?: return RandomTrainerStartResult(false, "Could not prepare ${entity.trainerName}'s battle team.")
            val settings = RandomTrainerCatalog.settings()
            val rules = BattleRules.Builder()
                .withMaxItemUses(settings.maxItemUses.coerceAtLeast(0))
                .withHealPlayers(settings.healPlayers)
                .withAdjustPlayerLevels(false)
                .withAdjustNPCLevels(false)
                .build()
            val format = runCatching { BattleFormat.valueOf(settings.battleFormat) }.getOrDefault(BattleFormat.GEN_9_SINGLES)
            val battleUuid = instance.battleManager.startBattle(listOf(playerTrainer), listOf(npcTrainer), format, rules)
                ?: return RandomTrainerStartResult(false, "RCT refused to start the battle.")
            instance.battleManager.getState(battleUuid)?.let { activeStates[battleUuid] = it }
            entity.inTrainerBattle = true
            RandomTrainerStore.putBattle(
                battleUuid,
                RandomTrainerBattleContextState(
                    playerUuid = player.stringUUID,
                    playerName = player.gameProfile.name,
                    entityUuid = entity.uuid.toString(),
                    instanceId = entity.instanceId,
                    rosterId = definition.id,
                    trainerName = entity.trainerName,
                ),
            )
            RandomTrainerStartResult(true, "Battle started.", battleUuid)
        }.getOrElse { exception ->
            entity.inTrainerBattle = false
            ChowKingdomMod.LOGGER.warn("Failed to start random trainer battle", exception)
            RandomTrainerStartResult(false, "The battle failed to start. Check the random trainer roster and RCT logs.")
        }
    }

    fun playerTopLevel(player: ServerPlayer): Int = runCatching {
        TrainerPlayer(player).team.mapNotNull(::pokemonLevel).maxOrNull() ?: 0
    }.getOrElse { exception ->
        ChowKingdomMod.LOGGER.debug("Could not inspect Cobblemon party level for {}", player.gameProfile.name, exception)
        0
    }

    private fun partyBattleReady(player: ServerPlayer): Boolean = runCatching {
        TrainerPlayer(player).team.any(::pokemonCanBattle)
    }.getOrElse { exception ->
        ChowKingdomMod.LOGGER.debug("Could not inspect Cobblemon party health for {}", player.gameProfile.name, exception)
        true
    }

    private fun registerNpcTrainer(instance: RCTApi, entity: RandomTrainerEntity, definition: RandomTrainerDefinition, topLevel: Int): TrainerNPC? {
        val id = "random_trainer_${cleanRandomTrainerId(definition.id)}_${entity.instanceId}"
        val model = gson.fromJson(RandomTrainerCatalog.scaledTrainerJson(definition, topLevel), TrainerModel::class.java) ?: return null
        instance.trainerRegistry.unregisterById(id)
        val registered = instance.trainerRegistry.registerNPC(id, model)
        registered.setEntity(entity)
        return registered
    }

    private fun onServerStarted(event: ServerStartedEvent) = init(event.server)

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        registerPlayer(player)
    }

    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        RandomTrainerStore.clearPlayerActiveBattles(player)
        unregisterPlayer(player)
    }

    private fun registerPlayer(player: ServerPlayer) {
        val instance = api ?: return
        runCatching {
            val id = playerTrainerId(player)
            instance.trainerRegistry.unregisterById(id)
            instance.trainerRegistry.registerPlayer(id, player)
        }.onFailure { exception ->
            ChowKingdomMod.LOGGER.debug("Failed to register random trainer player {}", player.gameProfile.name, exception)
        }
    }

    private fun unregisterPlayer(player: ServerPlayer) {
        val instance = api ?: return
        runCatching { instance.trainerRegistry.unregisterById(playerTrainerId(player)) }
    }

    private fun handleBattleEnded(state: BattleState) {
        val uuid = activeStates.entries.firstOrNull { (_, active) -> active === state || active.battle == state.battle }?.key ?: return
        activeStates.remove(uuid)
        val context = RandomTrainerStore.takeBattle(uuid) ?: return
        val server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer()
        val player = server?.playerList?.getPlayer(UUID.fromString(context.playerUuid))
        val entity = server?.allLevels?.asSequence()
            ?.mapNotNull { level -> level.getEntity(UUID.fromString(context.entityUuid)) as? RandomTrainerEntity }
            ?.firstOrNull()
        entity?.inTrainerBattle = false
        if (state.isEndForced || player == null) {
            return
        }
        val winner = state.winners.asSequence()
            .filterIsInstance<TrainerPlayer>()
            .map { it.player }
            .firstOrNull { it.uuid == player.uuid }
        val loser = state.losers.asSequence()
            .filterIsInstance<TrainerPlayer>()
            .map { it.player }
            .firstOrNull { it.uuid == player.uuid }
        val resultPlayer = winner ?: loser ?: player
        val won = winner != null
        RandomTrainerStore.recordResult(resultPlayer, context.rosterId, won)
        SnackbarNetwork.send(
            resultPlayer,
            SnackbarNotification.item(
                "cobblemon:poke_ball",
                if (won) "TRAINER DEFEATED" else "TRAINER BATTLE LOST",
                context.trainerName,
                if (won) SnackbarType.SUCCESS else SnackbarType.GENERIC,
                if (won) SnackbarSounds.REWARD else SnackbarSounds.GENERIC,
            ),
        )
    }

    private fun pokemonLevel(pokemon: Any): Int? {
        pokemon.javaClass.methods.firstOrNull { it.name == "getLevel" && it.parameterCount == 0 }?.let { method ->
            return (method.invoke(pokemon) as? Number)?.toInt()
        }
        return runCatching { pokemon.javaClass.getField("level").get(pokemon) as? Number }.getOrNull()?.toInt()
    }

    private fun pokemonCanBattle(pokemon: Any): Boolean {
        val fainted = listOf("isFainted", "getFainted").firstNotNullOfOrNull { name -> booleanMethod(pokemon, name) }
        if (fainted == true) return false
        val currentHealth = numberMethod(pokemon, "getCurrentHealth")
            ?: numberMethod(pokemon, "getHealth")
            ?: numberField(pokemon, "currentHealth")
            ?: numberField(pokemon, "health")
        return currentHealth == null || currentHealth > 0.0
    }

    private fun booleanMethod(target: Any, name: String): Boolean? = runCatching {
        target.javaClass.methods.firstOrNull { method -> method.name == name && method.parameterCount == 0 }?.invoke(target) as? Boolean
    }.getOrNull()

    private fun numberMethod(target: Any, name: String): Double? = runCatching {
        (target.javaClass.methods.firstOrNull { method -> method.name == name && method.parameterCount == 0 }?.invoke(target) as? Number)?.toDouble()
    }.getOrNull()

    private fun numberField(target: Any, name: String): Double? = runCatching {
        (target.javaClass.getField(name).get(target) as? Number)?.toDouble()
    }.getOrNull()

    private fun playerTrainerId(player: ServerPlayer): String = "player_${player.stringUUID}"
}

data class RandomTrainerStartResult(val started: Boolean, val message: String, val battleUuid: UUID? = null)
