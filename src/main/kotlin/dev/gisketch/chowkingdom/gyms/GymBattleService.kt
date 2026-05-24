package dev.gisketch.chowkingdom.gyms

import com.gitlab.srcmc.rctapi.api.RCTApi
import com.gitlab.srcmc.rctapi.api.battle.BattleFormat
import com.gitlab.srcmc.rctapi.api.battle.BattleRules
import com.gitlab.srcmc.rctapi.api.battle.BattleState
import com.gitlab.srcmc.rctapi.api.events.Event
import com.gitlab.srcmc.rctapi.api.events.EventListener
import com.gitlab.srcmc.rctapi.api.events.Events
import com.gitlab.srcmc.rctapi.api.models.TrainerModel
import com.gitlab.srcmc.rctapi.api.trainer.Trainer
import com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC
import com.gitlab.srcmc.rctapi.api.trainer.TrainerPlayer
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.npc.ChowNpcEntity
import dev.gisketch.chowkingdom.npc.NpcConfig
import dev.gisketch.chowkingdom.npc.NpcDialogTokens
import dev.gisketch.chowkingdom.npc.NpcFeature
import dev.gisketch.chowkingdom.npc.NpcLlmService
import dev.gisketch.chowkingdom.npc.NpcNetwork
import dev.gisketch.chowkingdom.npc.NpcPokemonCompanions
import dev.gisketch.chowkingdom.npc.NpcStore
import dev.gisketch.chowkingdom.snackbar.SnackbarIcons
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.RelativeMovement
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.util.EnumSet
import java.util.UUID
import kotlin.math.atan2
import kotlin.io.path.bufferedReader
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent

object GymBattleService {
    private const val API_ID = ChowKingdomMod.MOD_ID
    private var api: RCTApi? = null
    private var gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val activeStates: MutableMap<UUID, BattleState> = linkedMapOf()
    private val pendingStarts: MutableList<PendingGymBattleStart> = mutableListOf()
    private val battleLocksByNpcId: MutableMap<String, ActiveGymBattleLock> = linkedMapOf()
    private val battleLocksByNpcUuid: MutableMap<UUID, ActiveGymBattleLock> = linkedMapOf()
    private val pendingFacing: MutableList<PendingFacingSync> = mutableListOf()
    private var listenerRegistered = false
    private var tickRegistered = false
    private val battleEndedListener = EventListener<BattleState> { event: Event<BattleState> -> handleBattleEnded(event.value) }

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
            if (!tickRegistered) {
                NeoForge.EVENT_BUS.addListener(::onServerTick)
                tickRegistered = true
            }
            StadiumBattlePokemonPlacement.register()
            server.playerList.players.forEach(::registerPlayer)
        }.onFailure { exception ->
            ChowKingdomMod.LOGGER.warn("Failed to initialize RCT gym API", exception)
        }
    }

    fun registerPlayer(player: ServerPlayer) {
        val instance = api ?: return
        runCatching {
            val id = playerTrainerId(player)
            instance.trainerRegistry.unregisterById(id)
            instance.trainerRegistry.registerPlayer(id, player)
        }.onFailure { exception ->
            ChowKingdomMod.LOGGER.debug("Failed to register gym trainer player {}", player.gameProfile.name, exception)
        }
    }

    fun unregisterPlayer(player: ServerPlayer) {
        val instance = api ?: return
        runCatching { instance.trainerRegistry.unregisterById(playerTrainerId(player)) }
    }

    fun startBattle(player: ServerPlayer, npc: ChowNpcEntity, league: GymLeagueDefinition, encounter: GymEncounterDefinition, trainer: GymTrainerDefinition, official: Boolean = true): GymStartBattleResult {
        api ?: return GymStartBattleResult(false, "The trainer battle API is not ready yet.")
        if (GymLeagueStore.area(league.stadiumArea) == null) return GymStartBattleResult(false, "Gym stadium is not set. Use /ck gyms area set <radius> first.")
        GymTransitionNetwork.playBattleFade(player)
        pendingStarts.removeIf { pending -> pending.playerUuid == player.uuid && pending.trainerId == trainer.id }
        pendingStarts += PendingGymBattleStart(
            executeAtTick = player.server.overworld().gameTime + BATTLE_FADE_TELEPORT_DELAY_TICKS,
            playerUuid = player.uuid,
            leagueId = league.id,
            encounterId = encounter.id,
            trainerId = trainer.id,
            npcId = trainer.npcId,
            official = official,
        )
        return GymStartBattleResult(true, "Battle transition started.")
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        val now = server.overworld().gameTime
        tickPendingFacing(server, now)
        val ready = pendingStarts.filter { pending -> now >= pending.executeAtTick }
        if (ready.isEmpty()) return
        pendingStarts.removeAll(ready.toSet())
        ready.forEach { pending -> executePendingStart(server, pending) }
    }

    private fun executePendingStart(server: MinecraftServer, pending: PendingGymBattleStart) {
        val instance = api ?: return
        val player = server.playerList.getPlayer(pending.playerUuid) ?: return
        val league = GymLeagueConfig.league(pending.leagueId) ?: return failPending(player, "League config missing.")
        val encounter = league.encounter(pending.encounterId) ?: return failPending(player, "Encounter config missing.")
        val trainer = league.trainer(pending.trainerId) ?: return failPending(player, "Trainer config missing.")
        val npc = NpcFeature.existingNpc(server, pending.npcId) ?: return failPending(player, "${trainer.name} is not loaded.")
        runCatching {
            prepareBattlePositions(player, npc, league)?.let { message -> return failPending(player, message) }
            lockNpcForBattle(npc, player, pending.official)
            NpcPokemonCompanions.suspendForBattle(trainer.npcId, server)
            registerPlayer(player)
            val playerTrainer = instance.trainerRegistry.getById(playerTrainerId(player), TrainerPlayer::class.java)
                ?: TrainerPlayer(player).also { instance.trainerRegistry.registerPlayer(playerTrainerId(player), it) }
            val npcTrainer = registerEncounterTrainer(instance, server, npc, league, encounter)
                ?: run {
                    NpcPokemonCompanions.resumeAfterBattle(trainer.npcId)
                    unlockNpcForBattle(trainer.npcId, npc.uuid)
                    return failPending(player, "Could not prepare ${trainer.name}'s battle team.")
                }
            npcTrainer.setEntity(npc)
            val rules = BattleRules.Builder()
                .withMaxItemUses(league.defaults.maxItemUses)
                .withHealPlayers(league.defaults.healPlayers)
                .withAdjustPlayerLevels(false)
                .withAdjustNPCLevels(false)
                .build()
            val format = runCatching { BattleFormat.valueOf(league.defaults.battleFormat) }.getOrDefault(BattleFormat.GEN_9_SINGLES)
            val uuid = instance.battleManager.startBattle(listOf(playerTrainer), listOf(npcTrainer), format, rules)
                ?: run {
                    NpcPokemonCompanions.resumeAfterBattle(trainer.npcId)
                    unlockNpcForBattle(trainer.npcId, npc.uuid)
                    return failPending(player, "RCT refused to start the battle.")
                }
            val state = instance.battleManager.getState(uuid)
            if (state != null) activeStates[uuid] = state
            GymLeagueStore.putBattle(
                uuid,
                GymBattleContextState(
                    playerUuid = player.stringUUID,
                    playerName = player.gameProfile.name,
                    leagueId = league.id,
                    encounterId = encounter.id,
                    trainerId = trainer.id,
                    npcId = trainer.npcId,
                    official = pending.official,
                ),
            )
        }.getOrElse { exception ->
            NpcPokemonCompanions.resumeAfterBattle(trainer.npcId)
            unlockNpcForBattle(trainer.npcId, npc.uuid)
            ChowKingdomMod.LOGGER.warn("Failed to start gym battle", exception)
            failPending(player, "The battle failed to start. Check the trainer JSON and RCT logs.")
        }
    }

    private fun failPending(player: ServerPlayer, message: String) {
        SnackbarNetwork.send(player, SnackbarNotification.item(SnackbarIcons.ERROR, "BATTLE FAILED", message, SnackbarType.ERROR, SnackbarSounds.ERROR))
    }

    fun partyLevelViolation(player: ServerPlayer, cap: Int): Int? = runCatching {
        val team = TrainerPlayer(player).team
        team.mapNotNull(::pokemonLevel).firstOrNull { it > cap }
    }.getOrElse { exception ->
        ChowKingdomMod.LOGGER.debug("Could not inspect Cobblemon party levels for {}", player.gameProfile.name, exception)
        null
    }

    fun partyBattleReady(player: ServerPlayer): Boolean = runCatching {
        TrainerPlayer(player).team.any(::pokemonCanBattle)
    }.getOrElse { exception ->
        ChowKingdomMod.LOGGER.debug("Could not inspect Cobblemon party health for {}", player.gameProfile.name, exception)
        true
    }

    private fun registerEncounterTrainer(instance: RCTApi, server: MinecraftServer, npc: ChowNpcEntity, league: GymLeagueDefinition, encounter: GymEncounterDefinition): TrainerNPC? {
        val id = encounterTrainerId(league.id, encounter.id)
        val file = GymLeagueConfig.teamFile(encounter.teamRef)
        if (!Files.exists(file)) return null
        val model = file.bufferedReader().use { reader -> gson.fromJson(reader, TrainerModel::class.java) } ?: return null
        instance.trainerRegistry.unregisterById(id)
        val registered = instance.trainerRegistry.registerNPC(id, model)
        registered.setEntity(npc)
        return registered
    }

    private fun prepareBattlePositions(player: ServerPlayer, npc: ChowNpcEntity, league: GymLeagueDefinition): String? {
        val area = GymLeagueStore.area(league.stadiumArea) ?: return "Gym stadium is not set. Use /ck gyms area set <radius> first."
        val dimensionId = area.playerSpot.dimension.ifBlank { area.dimension }
        val dimension = runCatching { ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimensionId)) }.getOrNull()
            ?: return "Gym stadium dimension is invalid: $dimensionId"
        val level = player.server.getLevel(dimension) ?: return "Gym stadium dimension is not loaded: $dimensionId"
        val playerSpot = area.playerSpot.takeIf { it.configured } ?: GymBattleSpotState(dimensionId, area.x - 3, area.y, area.z, 0.0f, 0.0f, true)
        val trainerSpot = area.trainerSpot.takeIf { it.configured } ?: GymBattleSpotState(dimensionId, area.x + 3, area.y, area.z, 180.0f, 0.0f, true)
        val playerPos = Vec3(playerSpot.x + 0.5, playerSpot.y.toDouble(), playerSpot.z + 0.5)
        val trainerPos = Vec3(trainerSpot.x + 0.5, trainerSpot.y.toDouble(), trainerSpot.z + 0.5)
        val playerYaw = yawToward(playerPos, trainerPos)
        val trainerYaw = yawToward(trainerPos, playerPos)
        player.teleportTo(level, playerPos.x, playerPos.y, playerPos.z, playerYaw, 0.0f)
        player.setYRot(playerYaw)
        player.setXRot(0.0f)
        player.yHeadRot = playerYaw
        player.yBodyRot = playerYaw
        npc.navigation.stop()
        if (npc.level() != level) {
            npc.teleportTo(level, trainerPos.x, trainerPos.y, trainerPos.z, EnumSet.noneOf(RelativeMovement::class.java), trainerYaw, 0.0f)
        } else {
            npc.moveTo(trainerPos.x, trainerPos.y, trainerPos.z, trainerYaw, 0.0f)
        }
        forceFaceEachOther(player, npc)
        StadiumBattlePokemonPlacement.queue(player, npc, playerPos, trainerPos)
        pendingFacing += PendingFacingSync(
            untilTick = player.server.overworld().gameTime + BATTLE_FACE_SYNC_TICKS,
            playerUuid = player.uuid,
            npcUuid = npc.uuid,
        )
        return null
    }

    private fun forceFaceEachOther(player: ServerPlayer, npc: ChowNpcEntity) {
        val playerYaw = yawToward(player.position(), npc.position())
        val trainerYaw = yawToward(npc.position(), player.position())
        player.setYRot(playerYaw)
        player.setXRot(0.0f)
        player.yHeadRot = playerYaw
        player.yBodyRot = playerYaw
        npc.setYRot(trainerYaw)
        npc.setXRot(0.0f)
        npc.yHeadRot = trainerYaw
        npc.yBodyRot = trainerYaw
        npc.lookControl.setLookAt(player, 30.0f, 30.0f)
        npc.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition())
        player.lookAt(EntityAnchorArgument.Anchor.EYES, npc.getEyePosition())
    }

    private fun tickPendingFacing(server: MinecraftServer, now: Long) {
        val iterator = pendingFacing.iterator()
        while (iterator.hasNext()) {
            val sync = iterator.next()
            if (now > sync.untilTick) {
                iterator.remove()
                continue
            }
            val player = server.playerList.getPlayer(sync.playerUuid) ?: continue
            val npc = server.allLevels.asSequence().mapNotNull { level -> level.getEntity(sync.npcUuid) as? ChowNpcEntity }.firstOrNull() ?: continue
            if (player.level() == npc.level()) forceFaceEachOther(player, npc)
        }
    }

    private fun yawToward(from: Vec3, to: Vec3): Float {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return (atan2(dz, dx) * 180.0 / Math.PI - 90.0).toFloat()
    }

    private fun handleBattleEnded(state: BattleState) {
        val uuid = activeStates.entries.firstOrNull { (_, active) -> active === state || active.battle == state.battle }?.key ?: return
        activeStates.remove(uuid)
        val context = GymLeagueStore.takeBattle(uuid) ?: return
        NpcPokemonCompanions.resumeAfterBattle(context.npcId)
        handleBattleEndedByContext(state, context)
    }

    private fun handleBattleEndedByContext(state: BattleState, context: GymBattleContextState) {
        if (state.isEndForced) {
            val server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer()
            unlockNpcForBattle(context.npcId, server?.let { NpcFeature.existingNpc(it, context.npcId) }?.uuid)
            return
        }
        val winner = state.winners.asSequence()
            .filterIsInstance<TrainerPlayer>()
            .map { it.player }
            .firstOrNull { it.stringUUID == context.playerUuid }
        val loser = state.losers.asSequence()
            .filterIsInstance<TrainerPlayer>()
            .map { it.player }
            .firstOrNull { it.stringUUID == context.playerUuid }
        val player = winner ?: loser ?: return unlockNpcForBattle(context.npcId, null)
        val npc = NpcFeature.existingNpc(player.server, context.npcId)
        val won = winner != null
        if (won) GymLeagueFeature.completeEncounterWin(player, context) else GymLeagueFeature.completeEncounterLoss(player, context)
        openResultDialogue(player, context, won)
        unlockNpcForBattle(context.npcId, npc?.uuid)
    }

    fun isBattleLocked(npc: ChowNpcEntity): Boolean = battleLocksByNpcUuid.containsKey(npc.uuid) || battleLocksByNpcId.containsKey(npc.npcId)

    fun tickBattleLock(npc: ChowNpcEntity): Boolean {
        val lock = battleLocksByNpcUuid[npc.uuid] ?: battleLocksByNpcId[npc.npcId] ?: return false
        holdNpcAtBattleAnchor(npc, lock)
        npc.debugActivity = "gym_battle"
        npc.debugGoal = if (lock.official) "league_battle" else "friendly_battle"
        val level = npc.level() as? net.minecraft.server.level.ServerLevel
        val player = lock.playerUuid?.let { level?.getPlayerByUUID(it) }
        if (player != null && player.isAlive && player.level() == npc.level()) {
            npc.lookControl.setLookAt(player, 30.0f, 30.0f)
            npc.lookAt(EntityAnchorArgument.Anchor.EYES, player.getEyePosition())
        }
        return true
    }

    private fun lockNpcForBattle(npc: ChowNpcEntity, player: ServerPlayer, official: Boolean) {
        val anchor = npc.position()
        val lock = ActiveGymBattleLock(
            npcId = npc.npcId,
            npcUuid = npc.uuid,
            playerUuid = player.uuid,
            official = official,
            anchorDimension = npc.level().dimension().location().toString(),
            anchorX = anchor.x,
            anchorY = anchor.y,
            anchorZ = anchor.z,
            anchorYaw = npc.yRot,
        )
        battleLocksByNpcId[npc.npcId] = lock
        battleLocksByNpcUuid[npc.uuid] = lock
        npc.navigation.stop()
    }

    private fun holdNpcAtBattleAnchor(npc: ChowNpcEntity, lock: ActiveGymBattleLock) {
        npc.navigation.stop()
        npc.target = null
        npc.deltaMovement = Vec3.ZERO
        if (npc.level().dimension().location().toString() != lock.anchorDimension) return
        val dx = npc.x - lock.anchorX
        val dy = npc.y - lock.anchorY
        val dz = npc.z - lock.anchorZ
        if (dx * dx + dy * dy + dz * dz > BATTLE_NPC_ANCHOR_TOLERANCE_SQR) {
            npc.moveTo(lock.anchorX, lock.anchorY, lock.anchorZ, lock.anchorYaw, 0.0f)
        }
    }

    private fun unlockNpcForBattle(npcId: String, npcUuid: UUID?) {
        battleLocksByNpcId.remove(npcId)
        if (npcUuid != null) battleLocksByNpcUuid.remove(npcUuid)
    }

    private fun openResultDialogue(player: ServerPlayer, context: GymBattleContextState, won: Boolean) {
        val definition = NpcConfig.get(context.npcId) ?: return
        val npc = NpcFeature.existingNpc(player.server, context.npcId) ?: return
        if (npc.level() != player.level()) return
        val league = GymLeagueConfig.league(context.leagueId)
        val encounter = league?.encounter(context.encounterId)
        val trainer = league?.trainer(context.trainerId)
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val fallback = resultFallback(won, context.official, trainer?.name ?: definition.name, encounter?.displayName.orEmpty())
        val prompt = buildString {
            append(GymLlmContext.forEvent(player, definition.id, if (won) "battle win result" else "battle loss result", official = context.official, result = if (won) "player won" else "player lost"))
            append("\n\n")
            append("${player.gameProfile.name} just ")
            append(if (won) "won" else "lost")
            append(" a ")
            append(if (context.official) "league record battle" else "friendly battle")
            append(" against you")
            if (encounter != null) append(" for ${encounter.displayName}")
            append(". Reply as ${definition.name} in 1-2 short in-character sentences. ")
            if (won && context.official) append("Acknowledge the record update without sounding like a UI. ")
            if (!won) append("Do not insult them hard; invite a rematch after they recover. ")
        }
        npc.startTalkingTo(player, 100)
        val token = NpcDialogTokens.next()
        NpcNetwork.openDialog(player, NpcFeature.dialogPayload(definition, npc, if (NpcConfig.settings().llm.enabled) "..." else fallback, false, friendship.level, closeOnly = true, closeLabel = "OKAY", responseToken = if (NpcConfig.settings().llm.enabled) token else 0L, dialogMode = "gym_result"))
        if (NpcConfig.settings().llm.enabled) {
            NpcLlmService.event(
                player,
                npc,
                definition,
                fallback,
                prompt,
                inputLabel = "Gym battle result",
                excludePlayerFromBalloon = true,
                showBalloon = false,
                relayToNearby = false,
                npcRecordType = if (won) "gym_battle_win_dialogue" else "gym_battle_loss_dialogue",
                responseToken = token,
            )
        } else {
            NpcStore.recordConversation(definition.id, player, definition.name, fallback, if (won) "gym_battle_win_dialogue" else "gym_battle_loss_dialogue")
        }
    }

    private fun resultFallback(won: Boolean, official: Boolean, trainerName: String, encounterName: String): String = when {
        won && official -> "Good battle. Your record moves forward${if (encounterName.isNotBlank()) " after $encounterName" else ""}, but do not get comfortable."
        won -> "Not bad. Friendly battle or not, you made me work for that one."
        official -> "You are not through me yet. Fix your team, breathe, and challenge me again when you are ready."
        else -> "Good warm-up. Come back after your team catches its breath."
    }

    private fun pokemonLevel(pokemon: Any): Int? {
        pokemon.javaClass.methods.firstOrNull { it.name == "getLevel" && it.parameterCount == 0 }?.let { method ->
            return (method.invoke(pokemon) as? Number)?.toInt()
        }
        return runCatching { pokemon.javaClass.getField("level").get(pokemon) as? Number }.getOrNull()?.toInt()
    }

    private fun pokemonCanBattle(pokemon: Any): Boolean {
        val fainted = listOf("isFainted", "getFainted")
            .firstNotNullOfOrNull { name -> pokemon.booleanMethod(name) }
        if (fainted == true) return false
        val currentHealth = pokemon.numberMethod("getCurrentHealth")
            ?: pokemon.numberMethod("getHealth")
            ?: pokemon.numberField("currentHealth")
            ?: pokemon.numberField("health")
        return currentHealth == null || currentHealth > 0.0
    }

    private fun Any.booleanMethod(name: String): Boolean? = runCatching {
        javaClass.methods.firstOrNull { method -> method.name == name && method.parameterCount == 0 }?.invoke(this) as? Boolean
    }.getOrNull()

    private fun Any.numberMethod(name: String): Double? = runCatching {
        (javaClass.methods.firstOrNull { method -> method.name == name && method.parameterCount == 0 }?.invoke(this) as? Number)?.toDouble()
    }.getOrNull()

    private fun Any.numberField(name: String): Double? = runCatching {
        (javaClass.getField(name).get(this) as? Number)?.toDouble()
    }.getOrNull()

    private fun playerTrainerId(player: ServerPlayer): String = "player_${player.stringUUID}"

    private fun encounterTrainerId(leagueId: String, encounterId: String): String = "gym_${cleanId(leagueId)}_${cleanId(encounterId)}"
}

data class GymStartBattleResult(val started: Boolean, val message: String, val battleUuid: UUID? = null)

private data class PendingGymBattleStart(
    val executeAtTick: Long,
    val playerUuid: UUID,
    val leagueId: String,
    val encounterId: String,
    val trainerId: String,
    val npcId: String,
    val official: Boolean,
)

private data class ActiveGymBattleLock(
    val npcId: String,
    val npcUuid: UUID,
    val playerUuid: UUID?,
    val official: Boolean,
    val anchorDimension: String,
    val anchorX: Double,
    val anchorY: Double,
    val anchorZ: Double,
    val anchorYaw: Float,
)

private data class PendingFacingSync(
    val untilTick: Long,
    val playerUuid: UUID,
    val npcUuid: UUID,
)

private const val BATTLE_FADE_TELEPORT_DELAY_TICKS = 8L
private const val BATTLE_FACE_SYNC_TICKS = 40L
private const val BATTLE_NPC_ANCHOR_TOLERANCE_SQR = 0.08 * 0.08
