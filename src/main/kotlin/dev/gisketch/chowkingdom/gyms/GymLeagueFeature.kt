package dev.gisketch.chowkingdom.gyms

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.battlepass.BattlepassMissionHooks
import dev.gisketch.chowkingdom.battlepass.BattlepassNetwork
import dev.gisketch.chowkingdom.battlepass.BattlepassXpStore
import dev.gisketch.chowkingdom.discord.DiscordRelay
import dev.gisketch.chowkingdom.npc.ChowNpcEntity
import dev.gisketch.chowkingdom.npc.NpcConfig
import dev.gisketch.chowkingdom.npc.NpcDefinition
import dev.gisketch.chowkingdom.npc.NpcDialogTokens
import dev.gisketch.chowkingdom.npc.NpcFeature
import dev.gisketch.chowkingdom.npc.NpcLlmService
import dev.gisketch.chowkingdom.npc.NpcNetwork
import dev.gisketch.chowkingdom.npc.NpcPokemonCompanions
import dev.gisketch.chowkingdom.npc.NpcQuestHudEntryPayload
import dev.gisketch.chowkingdom.npc.NpcQuestService
import dev.gisketch.chowkingdom.npc.NpcStore
import dev.gisketch.chowkingdom.npc.NpcTime
import dev.gisketch.chowkingdom.snackbar.SnackbarIcons
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import dev.gisketch.chowkingdom.wallets.ChowcoinStore
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

object GymLeagueFeature {
    private const val DEFAULT_STADIUM_AREA = "main_stadium"
    private const val TRAINER_RECONCILE_INTERVAL_TICKS = 20L * 30L
    private var nextSpawnTick = 0L
    private var nextAvailabilityTick = 0L
    private var nextTrainerReconcileTick = 0L
    private val pendingTrainerReconcile: MutableSet<String> = linkedSetOf()
    private data class TrainerSpawnTarget(val trainer: GymTrainerDefinition, val area: GymStadiumAreaState, val level: ServerLevel)

    fun register() {
        GymLeagueConfig.load()
        GymLeagueStore.load()
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedOut)
        NeoForge.EVENT_BUS.addListener(::onEntityJoinLevel)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
    }

    fun leagueAvailable(definition: NpcDefinition): Boolean = GymLeagueConfig.isChowfan(definition.id)

    fun isTrainerNpc(npcId: String): Boolean = GymLeagueConfig.isTrainerNpc(npcId)

    fun shouldShowTrainerRoamBalloon(npcId: String, server: net.minecraft.server.MinecraftServer): Boolean =
        cleanId(npcId) in activeTrainerSpawnTargets(server, server.overworld()).keys

    fun handleTrainerDeath(npc: ChowNpcEntity, definition: NpcDefinition): Boolean {
        val (league, trainer) = GymLeagueConfig.trainerNpc(definition.id) ?: return false
        val server = npc.server ?: return true
        val delayMs = league.defaults.trainerRespawnMinutes * 60_000L
        NpcPokemonCompanions.removeForNpc(server, definition.id)
        GymLeagueStore.markTrainerDefeated(definition.id, delayMs)
        removeLoadedTrainer(server, definition.id)
        NpcStore.clearDead(definition.id)
        if (NpcStore.activeCamperId() == definition.id) NpcStore.clearActiveCamper(definition.id)
        NpcStore.recordGlobalEvent("gym_trainer_recovery", "${definition.name} is recovering after a trainer encounter.")
        SnackbarNetwork.sendToAllKnown(
            server,
            SnackbarNotification.npc(definition.id, "${trainer.name.uppercase()} RECOVERING", "${trainer.name} will return in ${league.defaults.trainerRespawnMinutes} minutes.", SnackbarType.GENERIC, SnackbarSounds.GENERIC),
        )
        return true
    }

    fun tryOpenNpcDialog(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition): Boolean {
        val trainerBinding = GymLeagueConfig.trainerNpc(definition.id)
        if (trainerBinding != null) {
            openTrainerDialog(player, npc, definition, trainerBinding.first, trainerBinding.second)
            return true
        }
        return false
    }

    fun handleAction(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, rawAction: String): Boolean {
        val action = rawAction.lowercase()
        if (GymLeagueConfig.isChowfan(definition.id)) {
            when (action) {
                "league_ticket" -> {
                    openChowfanDialog(player, npc, definition)
                    return true
                }
                "league_status" -> {
                    openChowfanDialog(player, npc, definition)
                    return true
                }
                "league_retire" -> {
                    openRetireConfirmDialog(player, npc, definition)
                    return true
                }
                "league_compass" -> {
                    requestCompassFromChowfan(player, npc, definition)
                    return true
                }
                "league_retire_confirm" -> {
                    retireLeagueFromChowfan(player, npc, definition)
                    return true
                }
                "league_retire_cancel" -> {
                    openChowfanDialog(player, npc, definition)
                    return true
                }
                "join_talk" -> {
                    NpcLlmService.joinConversation(player, definition.id)
                    return true
                }
            }
            if (action.startsWith("league_choice:")) {
                NpcLlmService.leaveDialog(player, definition.id)
                val index = action.substringAfter(':').toIntOrNull() ?: return true
                val league = selectableLeagues().firstOrNull { it.first == index }?.second
                if (league != null) startLeagueFromChowfan(player, npc, definition, league.id) else openChowfanDialog(player, npc, definition)
                return true
            }
        }
        val trainerBinding = GymLeagueConfig.trainerNpc(definition.id) ?: return false
        val (league, trainer) = trainerBinding
        when (action) {
            "gym_challenge" -> {
                NpcLlmService.leaveDialog(player, definition.id)
                challengeTrainer(player, npc, definition, league, trainer, forceFriendly = false)
                return true
            }
            "gym_friendly_battle" -> {
                NpcLlmService.leaveDialog(player, definition.id)
                challengeTrainer(player, npc, definition, league, trainer, forceFriendly = true)
                return true
            }
            "gym_badge" -> {
                openBadgeDialog(player, npc, definition, league, trainer)
                return true
            }
            "join_talk" -> {
                NpcLlmService.joinConversation(player, definition.id)
                return true
            }
        }
        return false
    }

    fun completeEncounterWin(player: ServerPlayer, context: GymBattleContextState) {
        val league = GymLeagueConfig.league(context.leagueId) ?: return
        val encounter = league.encounter(context.encounterId) ?: return
        val trainer = league.trainer(context.trainerId) ?: return
        if (!context.official) {
            SnackbarNetwork.send(player, SnackbarNotification.item("cobblemon:poke_ball", "FRIENDLY BATTLE WON", trainer.name, SnackbarType.SUCCESS, SnackbarSounds.REWARD))
            BattlepassNetwork.syncAllPlayers()
            GymLeagueNetwork.syncTo(player)
            NpcQuestService.syncTo(player)
            LeagueCompassFeature.updateFor(player)
            return
        }
        val activeLeague = GymLeagueStore.activeLeague(player)
        val expected = if (activeLeague == league.id) GymLeagueStore.nextPlayerEncounter(player, league) else null
        if (activeLeague != league.id || expected?.id != encounter.id) {
            SnackbarNetwork.send(player, SnackbarNotification.item("cobblemon:poke_ball", "BATTLE RECORDED AS PRACTICE", "Your active league route changed, so the record was not advanced.", SnackbarType.GENERIC, SnackbarSounds.GENERIC))
            BattlepassNetwork.syncAllPlayers()
            GymLeagueNetwork.syncTo(player)
            NpcQuestService.syncTo(player)
            LeagueCompassFeature.updateFor(player)
            spawnUnlockedTrainers(player.server.overworld())
            return
        }
        recordMission(player, "gisketchs_chowkingdom_mod:gym_battle_won", league, encounter, trainer)
        val firstClear = GymLeagueStore.grantClear(player, league, encounter)
        if (!firstClear) {
            GymLeagueNetwork.syncTo(player)
            NpcQuestService.syncTo(player)
            LeagueCompassFeature.updateFor(player)
            return
        }
        if (encounter.rewardXp > 0) BattlepassXpStore.addXp(player, league.defaults.passId, encounter.rewardXp)
        if (encounter.rewardChowcoins > 0) ChowcoinStore.add(player, encounter.rewardChowcoins)
        if (isGymLeaderClear(encounter, trainer)) {
            recordMission(player, "gisketchs_chowkingdom_mod:gym_leader_defeated", league, encounter, trainer)
        }
        if (encounter.badgeId.isNotBlank()) {
            recordMission(player, "gisketchs_chowkingdom_mod:gym_badge_earned", league, encounter, trainer)
            SnackbarNetwork.send(
                player,
                SnackbarNotification.item("cobblemon:poke_ball", "RECORD UPDATED", "${GymLeagueText.encounterLabel(league, encounter)} cleared. ${encounter.badgeId.replace('_', ' ').uppercase()}", SnackbarType.SUCCESS, SnackbarSounds.REWARD),
            )
            DiscordRelay.gymBadgeEarned(player, league.displayName, GymLeagueText.encounterLabel(league, encounter), encounter.badgeId)
        } else {
            SnackbarNetwork.send(player, SnackbarNotification.item("cobblemon:poke_ball", "LEAGUE BATTLE WON", GymLeagueText.encounterLabel(league, encounter), SnackbarType.SUCCESS, SnackbarSounds.REWARD))
        }
        if (GymLeagueStore.nextPlayerEncounter(player, league) == null) recordLeagueCompleted(player, league)
        BattlepassNetwork.syncAllPlayers()
        GymLeagueNetwork.syncTo(player)
        NpcQuestService.syncTo(player)
        LeagueCompassFeature.updateFor(player)
        spawnUnlockedTrainers(player.server.overworld())
    }

    fun completeEncounterLoss(player: ServerPlayer, context: GymBattleContextState) {
        val league = GymLeagueConfig.league(context.leagueId) ?: return
        val trainer = league.trainer(context.trainerId) ?: return
        val title = if (context.official) "LEAGUE BATTLE LOST" else "FRIENDLY BATTLE LOST"
        SnackbarNetwork.send(player, SnackbarNotification.item("cobblemon:poke_ball", title, trainer.name, SnackbarType.GENERIC, SnackbarSounds.GENERIC))
        BattlepassNetwork.syncAllPlayers()
        GymLeagueNetwork.syncTo(player)
        NpcQuestService.syncTo(player)
        LeagueCompassFeature.updateFor(player)
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        GymLeagueConfig.load()
        GymLeagueStore.load()
        GymBattleService.init(event.server)
        reconcileGymTrainers(event.server)
        spawnUnlockedTrainers(event.server.overworld())
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        GymBattleService.registerPlayer(player)
        spawnUnlockedTrainers(player.server.overworld())
        checkChallengeAvailability(player)
        GymLeagueNetwork.syncTo(player)
        NpcQuestService.syncTo(player)
        LeagueCompassFeature.updateFor(player)
    }

    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        GymBattleService.unregisterPlayer(player)
    }

    private fun onEntityJoinLevel(event: EntityJoinLevelEvent) {
        val npc = event.entity as? ChowNpcEntity ?: return
        if (!isTrainerNpc(npc.npcId)) return
        pendingTrainerReconcile += npc.npcId
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        val server = event.server
        val now = server.overworld().gameTime
        if (now >= nextAvailabilityTick) {
            nextAvailabilityTick = now + 20L * 30L
            server.playerList.players.forEach { player -> checkChallengeAvailability(player) }
        }
        if (now >= nextTrainerReconcileTick || pendingTrainerReconcile.isNotEmpty()) {
            nextTrainerReconcileTick = now + TRAINER_RECONCILE_INTERVAL_TICKS
            reconcilePendingGymTrainers(server)
        }
        if (now < nextSpawnTick) return
        nextSpawnTick = now + 20L * 30L
        spawnUnlockedTrainers(server.overworld())
    }

    fun hudEntriesFor(player: ServerPlayer): List<NpcQuestHudEntryPayload> {
        val activeLeague = GymLeagueStore.activeLeague(player)
        if (activeLeague.isBlank()) {
            val chowfanId = GymLeagueConfig.all().firstOrNull()?.chowfanNpcId ?: "prof_chowfan"
            val chowfanName = NpcConfig.get(chowfanId)?.name ?: "Professor Chowfan"
            return listOf(
                NpcQuestHudEntryPayload(
                    npcId = chowfanId,
                    npcName = chowfanName,
                    description = "Talk to Professor Chowfan about the League",
                    passId = "cozy",
                    xp = 0,
                    chowcoins = 0L,
                    progress = 0,
                    goal = 1,
                    acceptedAtTick = 0L,
                ),
            )
        }
        val league = GymLeagueConfig.league(activeLeague) ?: return emptyList()
        val encounter = GymLeagueStore.nextPlayerEncounter(player, league) ?: return emptyList()
        val day = NpcTime.day(player.level())
        if (!GymLeagueStore.isUnlocked(league.id, encounter.id, day)) return emptyList()
        val trainer = league.trainer(encounter.trainer) ?: return emptyList()
        val maxAttempts = league.defaults.dailyAttemptsPerNpc
        if (GymLeagueStore.attempts(player, trainer.id, maxAttempts) >= maxAttempts) return emptyList()
        return listOf(
            NpcQuestHudEntryPayload(
                npcId = trainer.npcId,
                npcName = trainer.name,
                description = "Challenge ${trainer.name} in a Pokemon Battle",
                passId = league.defaults.passId,
                xp = encounter.rewardXp,
                chowcoins = encounter.rewardChowcoins,
                progress = 0,
                goal = 1,
                acceptedAtTick = 0L,
            ),
        )
    }

    private fun spawnUnlockedTrainers(fallbackLevel: ServerLevel) {
        val targets = activeTrainerSpawnTargets(fallbackLevel.server, fallbackLevel)
        pruneInactiveLoadedTrainers(fallbackLevel.server, targets.keys)
        targets.values.forEach { target ->
            val trainer = target.trainer
            if (GymLeagueStore.trainerRespawnRemainingMs(trainer.npcId) > 0L) {
                removeLoadedTrainer(target.level.server, trainer.npcId)
                return@forEach
            }
            if (reconcileTrainer(target.level.server, trainer.npcId) != null) return@forEach
            val definition = NpcConfig.get(trainer.npcId) ?: return@forEach
            if (NpcFeature.spawnConfiguredNpcAt(target.level, definition, BlockPos(target.area.x, target.area.y, target.area.z))) {
                reconcileTrainer(target.level.server, trainer.npcId)
            }
        }
    }

    private fun activeTrainerSpawnTargets(server: net.minecraft.server.MinecraftServer, fallbackLevel: ServerLevel): Map<String, TrainerSpawnTarget> {
        val targets = linkedMapOf<String, TrainerSpawnTarget>()
        server.playerList.players.forEach { player ->
            val activeLeagueId = GymLeagueStore.activeLeague(player)
            if (activeLeagueId.isBlank()) return@forEach
            val league = GymLeagueConfig.league(activeLeagueId) ?: return@forEach
            val encounter = GymLeagueStore.nextPlayerEncounter(player, league) ?: return@forEach
            val day = NpcTime.day(player.level())
            if (!GymLeagueStore.isUnlocked(league.id, encounter.id, day)) return@forEach
            val trainer = league.trainer(encounter.trainer) ?: return@forEach
            val area = GymLeagueStore.area(league.stadiumArea) ?: return@forEach
            val dimension = runCatching { ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(area.dimension)) }.getOrNull() ?: fallbackLevel.dimension()
            val level = server.getLevel(dimension) ?: fallbackLevel
            targets.putIfAbsent(trainer.npcId, TrainerSpawnTarget(trainer, area, level))
        }
        return targets
    }

    private fun pruneInactiveLoadedTrainers(server: net.minecraft.server.MinecraftServer, activeNpcIds: Set<String>) {
        GymLeagueConfig.all().flatMap { league -> league.trainers }.map { trainer -> trainer.npcId }.distinct().forEach { npcId ->
            if (npcId in activeNpcIds) return@forEach
            val live = NpcFeature.existingNpcs(server, npcId)
            if (live.isEmpty()) return@forEach
            val locked = live.any { npc -> GymBattleService.isBattleLocked(npc) }
            live.filterNot { npc -> GymBattleService.isBattleLocked(npc) }.forEach { npc ->
                npc.navigation.stop()
                npc.discard()
            }
            if (!locked) {
                NpcPokemonCompanions.removeForNpc(server, npcId)
                NpcStore.clearDead(npcId)
                if (NpcStore.activeCamperId() == npcId) NpcStore.clearActiveCamper(npcId)
            }
        }
    }

    fun reconcileGymTrainers(server: net.minecraft.server.MinecraftServer) {
        GymLeagueConfig.all().forEach { league ->
            league.trainers.forEach { trainer -> reconcileTrainer(server, trainer.npcId) }
        }
    }

    private fun reconcilePendingGymTrainers(server: net.minecraft.server.MinecraftServer) {
        if (pendingTrainerReconcile.isEmpty()) {
            reconcileGymTrainers(server)
            return
        }
        val ids = pendingTrainerReconcile.toList()
        pendingTrainerReconcile.clear()
        ids.forEach { npcId -> reconcileTrainer(server, npcId) }
    }

    private fun reconcileTrainer(server: net.minecraft.server.MinecraftServer, npcId: String): ChowNpcEntity? {
        val binding = GymLeagueConfig.trainerNpc(npcId)
        val league = binding?.first
        val area = league?.let { GymLeagueStore.area(it.stadiumArea) }
        if (GymLeagueStore.trainerRespawnRemainingMs(npcId) > 0L) {
            removeLoadedTrainer(server, npcId)
            return null
        }
        val live = NpcFeature.existingNpcs(server, npcId)
        if (live.isEmpty()) {
            NpcStore.clearDead(npcId)
            if (NpcStore.activeCamperId() == npcId) NpcStore.clearActiveCamper(npcId)
            return null
        }
        val stored = NpcStore.entityUuid(npcId)
        val kept = live.sortedWith(
            compareBy<ChowNpcEntity> { npc -> if (insideStadium(npc, area)) 0 else 1 }
                .thenBy { npc -> stadiumDistanceSqr(npc, area) }
                .thenBy { npc -> if (npc.uuid == stored) 0 else 1 },
        ).first()
        live.filterNot { npc -> npc.uuid == kept.uuid }.forEach { duplicate ->
            duplicate.navigation.stop()
            duplicate.discard()
        }
        val camp = area?.let { BlockPos(it.x, it.y, it.z) } ?: kept.campPos ?: kept.blockPosition()
        NpcStore.setEntity(npcId, kept.uuid, camp)
        NpcStore.clearDead(npcId)
        if (NpcStore.activeCamperId() == npcId) NpcStore.clearActiveCamper(npcId)
        GymLeagueStore.clearTrainerRespawn(npcId)
        NpcConfig.get(npcId)?.let { definition ->
            NpcPokemonCompanions.ensureFor(kept, definition)
        }
        if (live.size > 1) ChowKingdomMod.LOGGER.info("Reconciled gym trainer {}: kept {}, removed {}", npcId, kept.uuid, live.size - 1)
        return kept
    }

    private fun removeLoadedTrainer(server: net.minecraft.server.MinecraftServer, npcId: String) {
        NpcFeature.existingNpcs(server, npcId).forEach { npc ->
            npc.navigation.stop()
            npc.discard()
        }
        NpcPokemonCompanions.removeForNpc(server, npcId)
    }

    private fun insideStadium(npc: ChowNpcEntity, area: GymStadiumAreaState?): Boolean {
        val stadium = area ?: return false
        if (npc.level().dimension().location().toString() != stadium.dimension) return false
        return npc.blockPosition().distSqr(BlockPos(stadium.x, stadium.y, stadium.z)) <= stadium.radius.toDouble() * stadium.radius.toDouble()
    }

    private fun stadiumDistanceSqr(npc: ChowNpcEntity, area: GymStadiumAreaState?): Double {
        val stadium = area ?: return Double.MAX_VALUE
        if (npc.level().dimension().location().toString() != stadium.dimension) return Double.MAX_VALUE / 2.0
        return npc.blockPosition().distSqr(BlockPos(stadium.x, stadium.y, stadium.z))
    }

    private fun openChowfanDialog(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition) {
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val activeLeague = GymLeagueStore.activeLeague(player)
        if (activeLeague.isBlank()) {
            openGymDialog(
                player,
                npc,
                definition,
                "Your league record is unopened. Choose a regional record and I will stamp your Skylands league file.",
                "Professor Chowfan opened the LEAGUE selector. Invite the player to choose Kanto, Johto, or Hoenn as an imported Skylands-hosted badge record. Do not auto-pick Kanto. Explain that Arceus brought strong trainers to CKDM Skylands and they follow level-matching league rules.",
                "gym_chowfan_league_select",
                "league_select",
                friendship.level,
                leagueAvailable = true,
                leagueCompassAvailable = false,
            )
        } else {
            val league = GymLeagueConfig.league(activeLeague)
            val next = league?.let { GymLeagueStore.nextPlayerEncounter(player, it) }
            val badgeCount = league?.let { GymLeagueStore.badges(player, it.id).size } ?: 0
            val fallback = if (league != null && next != null) {
                "Your ${league.displayName} record is active. Next match: ${GymLeagueText.encounterLabel(league, next)}. Level cap ${next.levelCap}. Badges recorded: $badgeCount."
            } else if (league != null) {
                "Your ${league.displayName} record looks complete from my desk. Badges recorded: $badgeCount."
            } else {
                "Your active league record is missing its paperwork. Ask an admin if the record looks wrong."
            }
            openGymDialog(
                player,
                npc,
                definition,
                fallback,
                "Professor Chowfan opened the active LEAGUE record screen. Explain current progress and mention that RETIRE RECORD is available only if the player wants to stop the active run. Do not sound like a normal greeting.",
                "gym_chowfan_league_menu",
                "league_record",
                friendship.level,
                leagueAvailable = true,
                leagueCompassAvailable = !LeagueCompassFeature.hasInInventory(player),
            )
        }
    }

    private fun startLeagueFromChowfan(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, leagueId: String) {
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val active = GymLeagueStore.activeLeague(player)
        if (active.isNotBlank()) {
            openChowfanDialog(player, npc, definition)
            return
        }
        val league = GymLeagueConfig.league(leagueId) ?: run {
            NpcNetwork.openDialog(player, NpcFeature.dialogPayload(definition, npc, "That league paperwork is missing from the desk.", false, friendship.level, closeOnly = true, closeLabel = "OKAY"))
            return
        }
        GymLeagueStore.startLeague(player, league)
        LeagueCompassFeature.updateFor(player)
        val first = league.firstEncounter()
        val fallback = "Record opened. Your ${league.displayName} badge run starts with ${first?.let { GymLeagueText.encounterLabel(league, it) } ?: "the first posted match"}. Keep your party under level ${first?.levelCap ?: "the posted cap"}."
        SnackbarNetwork.send(player, SnackbarNotification.item("cobblemon:poke_ball", "LEAGUE RECORD OPEN", league.displayName, SnackbarType.SUCCESS, SnackbarSounds.REWARD))
        GymLeagueNetwork.syncTo(player)
        NpcQuestService.syncTo(player)
        LeagueCompassFeature.updateFor(player)
        spawnUnlockedTrainers(player.server.overworld())
        openGymDialog(
            player,
            npc,
            definition,
            fallback,
            "Professor Chowfan just opened the player's ${league.displayName} record. Be excited, professor-like, and explain the first match without pretending Skylands is ${league.region}. Mention Arceus brought strong trainers here and the trainers will select lower-level Pokemon to match the posted cap.",
            "gym_chowfan_league_start",
            "league_record",
            friendship.level,
            leagueAvailable = true,
        )
    }

    private fun openRetireConfirmDialog(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition) {
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val league = GymLeagueConfig.league(GymLeagueStore.activeLeague(player))
        val fallback = "Retiring your active ${league?.displayName ?: "league"} record will remove the active route from your tracker. Earned badges stay in your permanent record."
        openGymDialog(
            player,
            npc,
            definition,
            fallback,
            "Professor Chowfan is warning the player before retiring the active league record. Make it serious but not scary: active route stops, badges stay saved, confirm only if sure.",
            "gym_chowfan_retire_warning",
            "league_retire",
            friendship.level,
            leagueAvailable = true,
        )
    }

    private fun requestCompassFromChowfan(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition) {
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val active = GymLeagueStore.activeLeague(player)
        val fallback = when {
            active.isBlank() -> "Open a league record first, then I can tune a compass to your next trainer."
            LeagueCompassFeature.hasInInventory(player) -> "You are already carrying a League Compass. I will not stuff your pockets with duplicates."
            else -> {
                LeagueCompassFeature.giveTo(player)
                "There. One League Compass, tuned to your active record. If the needle wanders, the next trainer is not posted in the stadium yet."
            }
        }
        openGymDialog(
            player,
            npc,
            definition,
            fallback,
            "Professor Chowfan is handling a League Compass request. Keep it short, professor-like, and explain that the compass points to the next posted trainer only when that trainer exists in the stadium.",
            "gym_chowfan_compass",
            if (active.isBlank()) "league_select" else "league_record",
            friendship.level,
            leagueAvailable = true,
            leagueCompassAvailable = active.isNotBlank() && !LeagueCompassFeature.hasInInventory(player),
        )
    }

    private fun retireLeagueFromChowfan(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition) {
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val retired = GymLeagueStore.retireActiveLeague(player)
        val league = GymLeagueConfig.league(retired)
        val fallback = if (retired.isBlank()) {
            "No active record was open. Your badge archive is unchanged."
        } else {
            "Active ${league?.displayName ?: retired} record retired. Your earned badges stay in the archive."
        }
        SnackbarNetwork.send(player, SnackbarNotification.item("cobblemon:poke_ball", "LEAGUE RECORD RETIRED", league?.displayName ?: "No active record", SnackbarType.GENERIC, SnackbarSounds.GENERIC))
        GymLeagueNetwork.syncTo(player)
        NpcQuestService.syncTo(player)
        LeagueCompassFeature.updateFor(player)
        openGymDialog(
            player,
            npc,
            definition,
            fallback,
            "Professor Chowfan has retired the active league route. Confirm that badges stayed archived and invite the player to choose another record later.",
            "gym_chowfan_retired",
            "league_select",
            friendship.level,
            leagueAvailable = true,
        )
    }

    private fun selectableLeagues(): List<Pair<Int, GymLeagueDefinition>> =
        GymLeagueConfig.all()
            .sortedWith(compareBy<GymLeagueDefinition> { it.generation.takeIf { gen -> gen > 0 } ?: 999 }.thenBy { it.id })
            .mapIndexed { index, league -> index to league }

    private fun openTrainerDialog(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, league: GymLeagueDefinition, trainer: GymTrainerDefinition) {
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val activeLeague = GymLeagueStore.activeLeague(player)
        val current = if (activeLeague == league.id) GymLeagueStore.nextPlayerEncounter(player, league) else null
        val day = NpcTime.day(player.level())
        val officialEncounter = current?.takeIf { it.trainer == trainer.id }
        val officialForTrainer = officialEncounter != null
        val currentUnlocked = current?.let { GymLeagueStore.isUnlocked(league.id, it.id, day) } == true
        val maxAttempts = league.defaults.dailyAttemptsPerNpc
        val attemptsLeft = (maxAttempts - GymLeagueStore.attempts(player, trainer.id, maxAttempts)).coerceAtLeast(0)
        val cooldownMs = GymLeagueStore.attemptCooldownRemainingMs(player, trainer.id, maxAttempts)
        val challengeAvailable = officialForTrainer && currentUnlocked && attemptsLeft > 0
        val challengeDisabledReason = when {
            officialEncounter != null && !currentUnlocked -> availabilityReason(league.id, officialEncounter.id, day)
            officialForTrainer && attemptsLeft <= 0 -> "Cooldown: ${formatCooldown(cooldownMs)}"
            else -> ""
        }
        val friendlyAvailable = attemptsLeft > 0
        val fallback = when {
            activeLeague != league.id && attemptsLeft > 0 -> "FRIENDLY BATTLE is open. It will not touch your badge record. Attempts left before cooldown: $attemptsLeft."
            activeLeague != league.id -> "Friendly battle cooldown is active. Come back in ${formatCooldown(cooldownMs)}."
            officialEncounter != null && !currentUnlocked -> "${GymLeagueText.encounterLabel(league, officialEncounter)} is next, but the stadium posting is not ready yet. Maybe tomorrow, maybe after Finn hears the crowd moving. FRIENDLY BATTLE is open if you want practice."
            officialEncounter != null && attemptsLeft > 0 -> "You're up for ${GymLeagueText.encounterLabel(league, officialEncounter)}. Level cap ${officialEncounter.levelCap}. Attempts left before cooldown: $attemptsLeft."
            officialForTrainer -> "Battle cooldown is active. Come back in ${formatCooldown(cooldownMs)}."
            current != null -> "Your next ${league.displayName} record match is ${GymLeagueText.encounterLabel(league, current)}. I can still do a friendly battle."
            else -> "Your ${league.displayName} record looks complete from here."
        }
        val surface = when {
            activeLeague != league.id -> "friendly battle offer"
            officialEncounter != null && !currentUnlocked -> "official challenge delayed"
            officialEncounter != null && attemptsLeft > 0 -> "official challenge ready"
            officialForTrainer -> "trainer cooldown"
            current != null -> "wrong trainer for current record"
            else -> "league record complete"
        }
        openGymDialog(
            player,
            npc,
            definition,
            fallback,
            "${trainer.name} opened trainer dialogue. Surface: $surface. Reply as ${trainer.name}, with concrete battle flavor and no UI wording. If naming a Pokemon, use only ${trainer.name}'s current battle team context, not another trainer's next-match team.",
            "gym_trainer_open",
            "gym_trainer",
            friendship.level,
            challengeAvailable = challengeAvailable,
            challengeDisabledReason = challengeDisabledReason,
            friendlyBattleAvailable = friendlyAvailable,
        )
    }

    private fun openBadgeDialog(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, league: GymLeagueDefinition, trainer: GymTrainerDefinition) {
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val badges = GymLeagueStore.badges(player, league.id)
        val trainerBadge = trainer.badgeId.takeIf(String::isNotBlank)
        val fallback = when {
            trainerBadge == null -> "${trainer.name} has no badge entry on this record. This is a league battle checkpoint."
            trainerBadge in badges -> "Record confirmed: ${trainerBadge.replace('_', ' ').uppercase()} is yours."
            else -> "Record missing: beat ${trainer.name} in your active ${league.displayName} sequence to earn ${trainerBadge.replace('_', ' ').uppercase()}."
        }
        openGymDialog(
            player,
            npc,
            definition,
            fallback,
            "${trainer.name} is showing the player's league record/badge status. Use RECORD language, not badge-menu language.",
            "gym_record_dialogue",
            "gym_trainer",
            friendship.level,
            friendlyBattleAvailable = true,
        )
    }

    private fun challengeTrainer(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, league: GymLeagueDefinition, trainer: GymTrainerDefinition, forceFriendly: Boolean) {
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val day = NpcTime.day(player.level())
        val active = GymLeagueStore.activeLeague(player)
        val official = !forceFriendly && active == league.id
        val encounter = if (official) GymLeagueStore.nextPlayerEncounter(player, league) else league.sequence.firstOrNull { it.trainer == trainer.id }
        fun fail(message: String, surface: String) = openGymDialog(
            player,
            npc,
            definition,
            message,
            "${trainer.name} is blocking a Pokemon battle request. Reason: $surface. Reply in character, tell the player what changed or what to fix, and do not sound like a system message.",
            "gym_challenge_blocked",
            "gym_trainer",
            friendship.level,
            closeOnly = true,
            closeLabel = "OKAY",
            official = official,
        )
        if (encounter == null) return fail(if (official) "Your ${league.displayName} record is already complete." else "${trainer.name} has no friendly team on file.", "no encounter available")
        if (official && encounter.trainer != trainer.id) return fail("Your next match is ${GymLeagueText.encounterLabel(league, encounter)}, not this one.", "wrong trainer")
        if (official && !GymLeagueStore.isUnlocked(league.id, encounter.id, day)) return fail("${GymLeagueText.encounterLabel(league, encounter)} is not posted at the stadium yet. Wait for the next posting, or use your League Compass once the trainer appears.", "story delay")
        val maxAttempts = league.defaults.dailyAttemptsPerNpc
        val attempts = GymLeagueStore.attempts(player, trainer.id, maxAttempts)
        if (attempts >= maxAttempts) return fail("${trainer.name} is on cooldown. Come back in ${formatCooldown(GymLeagueStore.attemptCooldownRemainingMs(player, trainer.id, maxAttempts))}.", "attempt cooldown")
        if (!GymBattleService.partyBattleReady(player)) return fail("Your party cannot battle right now. Heal at least one Pokemon first.", "party cannot battle")
        if (official) GymBattleService.partyLevelViolation(player, encounter.levelCap)?.let { level ->
            return fail("Level cap is ${encounter.levelCap}. One of your active party Pokemon is level $level.", "level cap violation")
        }
        GymLeagueStore.incrementAttempts(player, trainer.id, maxAttempts, league.defaults.attemptCooldownMinutes * 60_000L)
        if (official) recordMission(player, "gisketchs_chowkingdom_mod:gym_battle_attempted", league, encounter, trainer)
        val started = GymBattleService.startBattle(player, npc, league, encounter, trainer, official = official)
        if (!started.started) return fail(started.message, "battle start failed")
        BattlepassNetwork.syncAllPlayers()
        GymLeagueNetwork.syncTo(player)
        NpcQuestService.syncTo(player)
        val message = if (official) "Record battle started. Show me your badge run is real." else "Friendly battle started. No badge record pressure."
        openGymDialog(
            player,
            npc,
            definition,
            message,
            "${trainer.name} just accepted a ${if (official) "league record" else "friendly practice"} battle. Give a short pre-battle line. If naming a Pokemon, use only ${trainer.name}'s current battle team context.",
            if (official) "gym_challenge_started" else "gym_friendly_started",
            "gym_trainer",
            friendship.level,
            closeOnly = true,
            closeLabel = "OKAY",
            official = official,
        )
    }

    private fun openGymDialog(
        player: ServerPlayer,
        npc: ChowNpcEntity,
        definition: NpcDefinition,
        fallback: String,
        prompt: String,
        recordType: String,
        dialogMode: String,
        friendshipLevel: Int,
        closeOnly: Boolean = false,
        closeLabel: String = "BYE",
        leagueAvailable: Boolean = false,
        challengeAvailable: Boolean = false,
        challengeDisabledReason: String = "",
        friendlyBattleAvailable: Boolean = false,
        leagueCompassAvailable: Boolean = false,
        official: Boolean? = null,
    ) {
        val settings = NpcConfig.settings()
        val llmEnabled = settings.llm.enabled && settings.llmMessageUsage.gymDialogue
        val responseToken = if (llmEnabled) NpcDialogTokens.next() else 0L
        npc.startTalkingTo(player, 100)
        NpcNetwork.openDialog(
            player,
            NpcFeature.dialogPayload(
                definition,
                npc,
                if (llmEnabled) "..." else fallback,
                false,
                friendshipLevel,
                closeOnly = closeOnly,
                closeLabel = closeLabel,
                responseToken = responseToken,
                dialogMode = dialogMode,
                leagueAvailable = leagueAvailable,
                challengeAvailable = challengeAvailable,
                challengeDisabledReason = challengeDisabledReason,
                friendlyBattleAvailable = friendlyBattleAvailable,
                leagueCompassAvailable = leagueCompassAvailable,
            ),
        )
        if (llmEnabled) {
            val context = GymLlmContext.forEvent(player, definition.id, recordType, official)
            NpcLlmService.event(
                player,
                npc,
                definition,
                fallback,
                "$context\n\nCurrent gym dialogue instruction:\n$prompt",
                inputLabel = "Gym dialogue",
                npcRecordType = recordType,
                responseToken = responseToken,
            )
        } else {
            NpcStore.recordConversation(definition.id, player, definition.name, fallback, recordType)
        }
    }

    private fun recordMission(player: ServerPlayer, eventId: String, league: GymLeagueDefinition, encounter: GymEncounterDefinition, trainer: GymTrainerDefinition) {
        val attributes = buildMap {
            put("league", league.id)
            put("encounter", encounter.id)
            put("trainer", trainer.id)
            put("kind", encounter.kind)
            if (encounter.badgeId.isNotBlank()) put("badge", encounter.badgeId)
        }
        BattlepassMissionHooks.record(player, eventId, attributes = attributes)
    }

    private fun recordLeagueCompleted(player: ServerPlayer, league: GymLeagueDefinition) {
        BattlepassMissionHooks.record(
            player,
            "gisketchs_chowkingdom_mod:league_completed",
            attributes = mapOf(
                "league" to league.id,
                "generation" to league.generation.toString(),
                "region" to league.region,
            ),
        )
    }

    private fun isGymLeaderClear(encounter: GymEncounterDefinition, trainer: GymTrainerDefinition): Boolean =
        encounter.kind == "gym" || trainer.role == "gym_leader"

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        event.dispatcher.register(Commands.literal("ck").then(gymsRoot()))
        event.dispatcher.register(Commands.literal("chowkingdom").then(gymsRoot()))
    }

    private fun gymsRoot() = Commands.literal("gyms")
        .then(Commands.literal("reload").requires { it.hasPermission(2) }.executes(::reload))
        .then(
            Commands.literal("area")
                .then(Commands.literal("status").executes(::areaStatus))
                .then(
                    Commands.literal("set").requires { it.hasPermission(2) }
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 256)).executes(::areaSetDefault)),
                )
                .then(
                    Commands.literal("set_named").requires { it.hasPermission(2) }
                        .then(Commands.argument("area_id", StringArgumentType.word())
                            .then(Commands.argument("radius", IntegerArgumentType.integer(4, 256)).executes(::areaSet))),
                )
                .then(Commands.literal("set_player").requires { it.hasPermission(2) }.executes(::areaSetPlayerSpot))
                .then(Commands.literal("set_trainer").requires { it.hasPermission(2) }.executes(::areaSetTrainerSpot))
                .then(
                    Commands.literal("tp")
                        .executes(::areaTeleportSelf)
                        .then(Commands.argument("player", EntityArgument.player()).requires { it.hasPermission(2) }.executes(::areaTeleportPlayer)),
                )
                .then(
                    Commands.literal("teleport")
                        .executes(::areaTeleportSelf)
                        .then(Commands.argument("player", EntityArgument.player()).requires { it.hasPermission(2) }.executes(::areaTeleportPlayer)),
                ),
        )
        .then(Commands.literal("status").executes(::statusSelf).then(Commands.argument("player", EntityArgument.player()).executes(::statusPlayer)))
        .then(Commands.literal("compass").executes(LeagueCompassFeature::giveCommand).then(Commands.argument("player", EntityArgument.player()).requires { it.hasPermission(2) }.executes { context -> LeagueCompassFeature.giveCommand(context, EntityArgument.getPlayer(context, "player")) }))
        .then(
            Commands.literal("league")
                .then(Commands.literal("start").requires { it.hasPermission(2) }
                    .then(Commands.argument("league_id", StringArgumentType.word()).suggests { _, builder -> SharedSuggestionProvider.suggest(GymLeagueConfig.all().map { it.id }, builder) }
                        .executes(::leagueStartSelf)
                        .then(Commands.argument("player", EntityArgument.player()).executes(::leagueStartPlayer))))
                .then(Commands.literal("reset").requires { it.hasPermission(2) }
                    .then(Commands.argument("league_id", StringArgumentType.word()).suggests { _, builder -> SharedSuggestionProvider.suggest(GymLeagueConfig.all().map { it.id }, builder) }
                        .then(Commands.argument("player", EntityArgument.player()).executes(::leagueReset)))),
        )
        .then(Commands.literal("unlock").requires { it.hasPermission(2) }
            .then(Commands.argument("league_id", StringArgumentType.word()).suggests { _, builder -> SharedSuggestionProvider.suggest(GymLeagueConfig.all().map { it.id }, builder) }
                .then(Commands.argument("encounter_id", StringArgumentType.word()).suggests(::suggestEncounters).executes(::unlock))))
        .then(Commands.literal("grant").requires { it.hasPermission(2) }
            .then(Commands.argument("league_id", StringArgumentType.word()).suggests { _, builder -> SharedSuggestionProvider.suggest(GymLeagueConfig.all().map { it.id }, builder) }
                .then(Commands.argument("encounter_id", StringArgumentType.word()).suggests(::suggestEncounters)
                    .then(Commands.argument("player", EntityArgument.player()).executes(::grant)))))
        .then(
            Commands.literal("attempts").requires { it.hasPermission(2) }
                .then(Commands.literal("get").then(Commands.argument("trainer_id", StringArgumentType.word()).then(Commands.argument("player", EntityArgument.player()).executes(::attemptsGet))))
                .then(Commands.literal("reset").then(Commands.argument("trainer_id", StringArgumentType.word()).then(Commands.argument("player", EntityArgument.player()).executes(::attemptsReset)))),
        )

    private fun reload(context: CommandContext<CommandSourceStack>): Int {
        GymLeagueConfig.load()
        GymLeagueStore.load()
        reconcileGymTrainers(context.source.server)
        GymLeagueNetwork.syncAllPlayers()
        context.source.sendSuccess({ Component.literal("Reloaded ${GymLeagueConfig.all().size} gym league(s).") }, true)
        return GymLeagueConfig.all().size
    }

    private fun areaSet(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val id = StringArgumentType.getString(context, "area_id")
        val radius = IntegerArgumentType.getInteger(context, "radius")
        GymLeagueStore.setArea(id, player.level().dimension().location().toString(), player.blockPosition(), radius)
        context.source.sendSuccess({ Component.literal("Set gym area ${cleanId(id)} at ${player.blockPosition().toShortString()} radius $radius.") }, true)
        return 1
    }

    private fun areaSetDefault(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val radius = IntegerArgumentType.getInteger(context, "radius")
        GymLeagueStore.setArea(DEFAULT_STADIUM_AREA, player.level().dimension().location().toString(), player.blockPosition(), radius)
        context.source.sendSuccess({ Component.literal("Set main gym stadium at ${player.blockPosition().toShortString()} radius $radius.") }, true)
        return 1
    }

    private fun areaSetPlayerSpot(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        GymLeagueStore.setBattleSpot(DEFAULT_STADIUM_AREA, "player", player.level().dimension().location().toString(), player.blockPosition(), player.yRot, player.xRot)
        context.source.sendSuccess({ Component.literal("Set gym player battle spot at ${player.blockPosition().toShortString()}.") }, true)
        return 1
    }

    private fun areaSetTrainerSpot(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        GymLeagueStore.setBattleSpot(DEFAULT_STADIUM_AREA, "trainer", player.level().dimension().location().toString(), player.blockPosition(), player.yRot, player.xRot)
        context.source.sendSuccess({ Component.literal("Set gym trainer battle spot at ${player.blockPosition().toShortString()}.") }, true)
        return 1
    }

    private fun areaTeleportSelf(context: CommandContext<CommandSourceStack>): Int = areaTeleport(context, context.source.playerOrException)

    private fun areaTeleportPlayer(context: CommandContext<CommandSourceStack>): Int = areaTeleport(context, EntityArgument.getPlayer(context, "player"))

    private fun areaTeleport(context: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        val area = GymLeagueStore.area(DEFAULT_STADIUM_AREA) ?: return fail(context, "Main gym stadium is not set. Use /ck gyms area set <radius> first.")
        val dimension = runCatching { ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(area.dimension)) }.getOrNull()
            ?: return fail(context, "Gym stadium dimension is invalid: ${area.dimension}")
        val level = context.source.server.getLevel(dimension) ?: return fail(context, "Gym stadium dimension is not loaded: ${area.dimension}")
        player.teleportTo(level, area.x + 0.5, area.y.toDouble(), area.z + 0.5, player.yRot, player.xRot)
        SnackbarNetwork.send(player, SnackbarNotification.texture(SnackbarIcons.CHOWCOIN_TEXTURE, "GYM STADIUM", "Teleported to the league area.", SnackbarType.SUCCESS, SnackbarSounds.SUCCESS))
        context.source.sendSuccess({ Component.literal("Teleported ${player.gameProfile.name} to the main gym stadium.") }, true)
        return 1
    }

    private fun areaStatus(context: CommandContext<CommandSourceStack>): Int {
        val areas = GymLeagueStore.areas()
        if (areas.isEmpty()) {
            context.source.sendSystemMessage(Component.literal("No gym stadium areas set."))
            return 0
        }
        areas.forEach { (id, area) ->
            val playerSpot = if (area.playerSpot.configured) "${area.playerSpot.x},${area.playerSpot.y},${area.playerSpot.z}" else "unset"
            val trainerSpot = if (area.trainerSpot.configured) "${area.trainerSpot.x},${area.trainerSpot.y},${area.trainerSpot.z}" else "unset"
            context.source.sendSystemMessage(Component.literal("$id ${area.dimension} ${area.x},${area.y},${area.z} r=${area.radius} player=$playerSpot trainer=$trainerSpot"))
        }
        return areas.size
    }

    private fun statusSelf(context: CommandContext<CommandSourceStack>): Int = status(context, context.source.playerOrException)

    private fun statusPlayer(context: CommandContext<CommandSourceStack>): Int = status(context, EntityArgument.getPlayer(context, "player"))

    private fun status(context: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        GymLeagueStore.statusLines(player).forEach { line -> context.source.sendSystemMessage(Component.literal(line)) }
        return 1
    }

    private fun leagueStartSelf(context: CommandContext<CommandSourceStack>): Int = leagueStart(context, context.source.playerOrException)

    private fun leagueStartPlayer(context: CommandContext<CommandSourceStack>): Int = leagueStart(context, EntityArgument.getPlayer(context, "player"))

    private fun leagueStart(context: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        val leagueId = StringArgumentType.getString(context, "league_id")
        val league = GymLeagueConfig.league(leagueId) ?: return fail(context, "Unknown league '$leagueId'.")
        if (!GymLeagueStore.startLeague(player, league)) return fail(context, "${player.gameProfile.name} already has active league ${GymLeagueStore.activeLeague(player)}.")
        spawnUnlockedTrainers(player.server.overworld())
        GymLeagueNetwork.syncTo(player)
        NpcQuestService.syncTo(player)
        LeagueCompassFeature.updateFor(player)
        context.source.sendSuccess({ Component.literal("Started ${league.displayName} for ${player.gameProfile.name}.") }, true)
        return 1
    }

    private fun leagueReset(context: CommandContext<CommandSourceStack>): Int {
        val player = EntityArgument.getPlayer(context, "player")
        val leagueId = StringArgumentType.getString(context, "league_id")
        GymLeagueStore.resetLeague(player, leagueId)
        spawnUnlockedTrainers(player.server.overworld())
        GymLeagueNetwork.syncTo(player)
        NpcQuestService.syncTo(player)
        LeagueCompassFeature.updateFor(player)
        context.source.sendSuccess({ Component.literal("Reset ${cleanId(leagueId)} for ${player.gameProfile.name}.") }, true)
        return 1
    }

    private fun unlock(context: CommandContext<CommandSourceStack>): Int {
        val leagueId = StringArgumentType.getString(context, "league_id")
        val encounterId = StringArgumentType.getString(context, "encounter_id")
        if (GymLeagueConfig.league(leagueId)?.encounter(encounterId) == null) return fail(context, "Unknown encounter '$encounterId'.")
        GymLeagueStore.unlock(leagueId, encounterId, NpcTime.day(context.source.server.overworld()))
        spawnUnlockedTrainers(context.source.server.overworld())
        context.source.server.playerList.players.forEach { player -> checkChallengeAvailability(player) }
        GymLeagueNetwork.syncAllPlayers()
        context.source.server.playerList.players.forEach(LeagueCompassFeature::updateFor)
        context.source.sendSuccess({ Component.literal("Unlocked ${cleanId(encounterId)} in ${cleanId(leagueId)}.") }, true)
        return 1
    }

    private fun grant(context: CommandContext<CommandSourceStack>): Int {
        val player = EntityArgument.getPlayer(context, "player")
        val league = GymLeagueConfig.league(StringArgumentType.getString(context, "league_id")) ?: return fail(context, "Unknown league.")
        val encounter = league.encounter(StringArgumentType.getString(context, "encounter_id")) ?: return fail(context, "Unknown encounter.")
        GymLeagueStore.grantClear(player, league, encounter)
        spawnUnlockedTrainers(player.server.overworld())
        GymLeagueNetwork.syncTo(player)
        NpcQuestService.syncTo(player)
        LeagueCompassFeature.updateFor(player)
        context.source.sendSuccess({ Component.literal("Granted ${encounter.id} to ${player.gameProfile.name}.") }, true)
        return 1
    }

    private fun attemptsGet(context: CommandContext<CommandSourceStack>): Int {
        val player = EntityArgument.getPlayer(context, "player")
        val trainerId = StringArgumentType.getString(context, "trainer_id")
        val maxAttempts = GymLeagueConfig.all().firstOrNull()?.defaults?.dailyAttemptsPerNpc ?: 3
        val cooldown = GymLeagueStore.attemptCooldownRemainingMs(player, trainerId, maxAttempts)
        context.source.sendSystemMessage(Component.literal("${player.gameProfile.name} ${cleanId(trainerId)} record: ${GymLeagueStore.attempts(player, trainerId, maxAttempts)}/$maxAttempts, cooldown=${formatCooldown(cooldown)}"))
        return 1
    }

    private fun attemptsReset(context: CommandContext<CommandSourceStack>): Int {
        val player = EntityArgument.getPlayer(context, "player")
        val trainerId = StringArgumentType.getString(context, "trainer_id")
        GymLeagueStore.resetAttempts(player, trainerId)
        spawnUnlockedTrainers(player.server.overworld())
        GymLeagueNetwork.syncTo(player)
        NpcQuestService.syncTo(player)
        LeagueCompassFeature.updateFor(player)
        context.source.sendSuccess({ Component.literal("Reset ${cleanId(trainerId)} attempts for ${player.gameProfile.name}.") }, true)
        return 1
    }

    private fun checkChallengeAvailability(player: ServerPlayer) {
        val activeLeague = GymLeagueStore.activeLeague(player)
        if (activeLeague.isBlank()) return
        val league = GymLeagueConfig.league(activeLeague) ?: return
        if (GymLeagueStore.clearedCount(player, league.id) <= 0) return
        val encounter = GymLeagueStore.nextPlayerEncounter(player, league) ?: return
        val day = NpcTime.day(player.level())
        if (!GymLeagueStore.isUnlocked(league.id, encounter.id, day)) return
        if (GymLeagueStore.hasAnnouncedAvailableEncounter(player, league.id, encounter.id)) return
        val trainer = league.trainer(encounter.trainer) ?: return
        val maxAttempts = league.defaults.dailyAttemptsPerNpc
        if (GymLeagueStore.attempts(player, trainer.id, maxAttempts) >= maxAttempts) return
        GymLeagueStore.markAvailableEncounterAnnounced(player, league.id, encounter.id)
        SnackbarNetwork.send(
            player,
            SnackbarNotification.item("cobblemon:poke_ball", "POKEMON CHALLENGE READY", "${trainer.name} is ready for your ${GymLeagueText.encounterLabel(league, encounter)} match.", SnackbarType.SUCCESS, SnackbarSounds.SUCCESS),
        )
        GymLeagueNetwork.syncTo(player)
        NpcQuestService.syncTo(player)
    }

    private fun availabilityReason(leagueId: String, encounterId: String, day: Long): String {
        val available = GymLeagueStore.availableDay(leagueId, encounterId) ?: return "Not posted yet"
        val days = (available - day).coerceAtLeast(1L)
        return if (days <= 1L) "Likely next Skylands day" else "Not posted yet. Check the compass later."
    }

    private fun suggestEncounters(context: CommandContext<CommandSourceStack>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder): java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
        val leagueId = runCatching { StringArgumentType.getString(context, "league_id") }.getOrDefault("")
        return SharedSuggestionProvider.suggest(GymLeagueConfig.league(leagueId)?.sequence?.map { it.id }.orEmpty(), builder)
    }

    private fun fail(context: CommandContext<CommandSourceStack>, message: String): Int {
        context.source.sendFailure(Component.literal(message).withStyle(ChatFormatting.RED))
        return 0
    }

    private fun formatCooldown(ms: Long): String {
        if (ms <= 0L) return "ready"
        val totalSeconds = ((ms + 999L) / 1000L).coerceAtLeast(1L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) "${minutes}m ${seconds.toString().padStart(2, '0')}s" else "${seconds}s"
    }
}
