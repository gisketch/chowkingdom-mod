package dev.gisketch.chowkingdom.battlepass

import com.google.gson.GsonBuilder
import dev.gisketch.chowkingdom.ChatGlyphs
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.config.TomlConfigIO
import dev.gisketch.chowkingdom.discord.DiscordRelay
import dev.gisketch.chowkingdom.npc.NpcQuestService
import dev.gisketch.chowkingdom.npc.NpcStore
import dev.gisketch.chowkingdom.relicroulette.RelicRouletteFeature
import dev.gisketch.chowkingdom.roles.ClassMentorQuestService
import dev.gisketch.chowkingdom.snackbar.SnackbarIcons
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import dev.gisketch.chowkingdom.tech.TechLicenseQuestService
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object BattlepassMissionProgressStore {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val progress: MutableMap<String, MutableMap<String, MutableMap<String, Int>>> = linkedMapOf()
    private val completed: MutableMap<String, MutableMap<String, MutableMap<String, MutableSet<String>>>> = linkedMapOf()
    private val rotations: MutableMap<String, MutableMap<String, StoredRotation>> = linkedMapOf()
    private var loaded = false
    private var rotationDirty = false

    private val file: Path
        get() = BattlepassWorldData.battlepassFile("mission_progress")

    fun load() {
        file.parent.createDirectories()
        progress.clear()
        completed.clear()
        rotations.clear()

        if (file.exists()) {
            try {
                val data = TomlConfigIO.read(file, StoredMissionProgress::class.java, ::StoredMissionProgress)
                data.players.forEach { (playerId, passes) ->
                    progress[playerId] = passes.mapValues { (_, events) -> events.toMutableMap() }.toMutableMap()
                }
                data.completed.forEach { (playerId, passes) ->
                    completed[playerId] = passes.mapValues { (_, periods) -> periods.mapValues { (_, keys) -> keys.toMutableSet() }.toMutableMap() }.toMutableMap()
                }
                data.rotations.forEach { (passId, scopes) ->
                    rotations[passId] = scopes.mapValues { (_, rotation) -> rotation.copy(activeKeys = rotation.activeKeys.toMutableList(), usageCounts = rotation.usageCounts.toMutableMap()) }.toMutableMap()
                }
            } catch (exception: Exception) {
                ChowKingdomMod.LOGGER.warn("Failed to load battlepass mission progress {}", file, exception)
            }
        }

        loaded = true
    }

    fun getProgress(playerId: UUID, passId: String, eventId: String): Int {
        if (!loaded) load()
        return progress[playerId.toString()]?.get(passId)?.get(eventId) ?: 0
    }

    fun progressForPass(playerId: UUID, passId: String): Map<String, Int> {
        if (!loaded) load()
        return progress[playerId.toString()]?.get(passId)?.toMap().orEmpty()
    }

    fun progressForPass(playerId: UUID, pass: BattlepassPassDefinition): Map<String, Int> {
        if (!loaded) load()
        val passProgress = progress[playerId.toString()]?.get(pass.id).orEmpty()
        return activeEntries(pass)
            .associate { entry -> entry.key to (passProgress[progressKey(pass, entry)] ?: 0) }
            .filterValues { value -> value > 0 }
    }

    fun activeMissionKeys(pass: BattlepassPassDefinition): List<String> {
        if (!loaded) load()
        val keys = activeEntries(pass).map { entry -> entry.key }
        if (rotationDirty) save()
        return keys
    }

    fun activeMissionKeysByPass(passes: List<BattlepassPassDefinition>): Map<String, List<String>> {
        if (!loaded) load()
        val keysByPass = passes.associate { pass -> pass.id to activeEntries(pass).map { entry -> entry.key } }
        if (rotationDirty) save()
        return keysByPass
    }

    fun completedKeysForPass(playerId: UUID, pass: BattlepassPassDefinition): List<String> {
        if (!loaded) load()
        return activeEntries(pass)
            .filter { entry -> isCompleted(playerId, pass, entry) }
            .map { entry -> entry.key }
    }

    fun activeMissionKeys(): List<String> {
        if (!loaded) load()
        return BattlepassPassRegistry.all().flatMap(::activeMissionKeys).distinct()
    }

    fun activeIncompleteMilestoneKeys(playerId: UUID): List<String> {
        if (!loaded) load()
        return BattlepassPassRegistry.all()
            .flatMap { pass ->
                activeEntries(pass)
                    .filter { entry -> BattlepassMissionService.isProgressive(entry.event) && !isCompleted(playerId, pass, entry) }
                    .map { entry -> entry.key }
            }
            .distinct()
    }

    fun reset(scope: BattlepassMissionScope): Int {
        if (!loaded) load()
        if (scope == BattlepassMissionScope.PERMANENT) return 0

        var resetCount = 0
        BattlepassPassRegistry.all().forEach { pass ->
            val definition = if (scope == BattlepassMissionScope.DAILY) pass.dailyEvents else pass.weeklyEvents
            if (definition.events.isEmpty()) return@forEach
            val periodKey = BattlepassMissionService.periodKey(scope, definition)
            rotations[pass.id]?.get(scope.id)?.periodKey = ""
            completed.values.forEach { passes -> passes[pass.id]?.remove(periodKey) }
            progress.values.forEach { passes ->
                passes[pass.id]?.keys?.removeIf { key -> key.startsWith("${scope.id}:") && key.endsWith("@$periodKey") }
            }
            resetCount++
        }

        if (resetCount > 0) save()
        return resetCount
    }

    fun completeMission(player: ServerPlayer, missionKey: String): Boolean {
        if (!loaded) load()
        var changed = false
        BattlepassPassRegistry.all().forEach { pass ->
            activeEntries(pass).filter { entry -> entry.key == missionKey }.forEach { entry ->
                if (BattlepassMissionService.isProgressive(entry.event)) {
                    val current = progress[player.stringUUID]?.get(pass.id)?.get(progressKey(pass, entry)) ?: 0
                    val goal = BattlepassMissionService.progressiveGoal(entry.event)
                    if (current < goal) {
                        recordProgressive(player, pass, entry, goal - current)
                        changed = true
                    }
                } else if (BattlepassMissionService.isCappedRepeating(entry.event)) {
                    val current = progress[player.stringUUID]?.get(pass.id)?.get(progressKey(pass, entry)) ?: 0
                    if (current < entry.event.xpCap) {
                        val repeatsNeeded = ((entry.event.xpCap - current) + entry.event.xp - 1) / entry.event.xp.coerceAtLeast(1)
                        recordCappedRepeating(player, pass, entry, repeatsNeeded)
                        changed = true
                    }
                } else if (entry.event.xp > 0) {
                    addXpAndNotifyRewardUnlock(player, pass, entry.event.xp)
                    changed = true
                }
                markCompleted(player.uuid, pass, entry)
                changed = true
            }
        }

        if (changed) save()
        return changed
    }

    fun setEventProgress(player: ServerPlayer, eventId: String, value: Int): Boolean {
        if (!loaded) load()
        var changed = false

        BattlepassPassRegistry.all().forEach { pass ->
            activeEntries(pass).filter { entry -> entry.event.event == eventId && BattlepassMissionService.isProgressive(entry.event) }.forEach { entry ->
                if (isCompleted(player.uuid, pass, entry)) return@forEach
                val previous = progress[player.stringUUID]?.get(pass.id)?.get(progressKey(pass, entry)) ?: 0
                val wasCompleted = isCompleted(player.uuid, pass, entry)
                val complete = setProgress(player, pass, entry, value)
                if (complete) markCompleted(player.uuid, pass, entry)
                val next = progress[player.stringUUID]?.get(pass.id)?.get(progressKey(pass, entry)) ?: 0
                if (next != previous || (complete && !wasCompleted)) changed = true
            }
        }

        if (changed) save()
        return changed
    }

    fun setSignalCounts(player: ServerPlayer, signals: List<BattlepassMissionSignal>): Boolean {
        if (!loaded) load()
        var changed = false

        BattlepassPassRegistry.all().forEach { pass ->
            activeEntries(pass).filter { entry -> BattlepassMissionService.isProgressive(entry.event) }.forEach { entry ->
                if (isCompleted(player.uuid, pass, entry)) return@forEach
                val value = signals.sumOf { signal -> if (BattlepassMissionEventBank.matches(signal, entry.event)) signal.amount else 0 }
                if (value <= 0) return@forEach
                val previous = progress[player.stringUUID]?.get(pass.id)?.get(progressKey(pass, entry)) ?: 0
                val complete = setProgress(player, pass, entry, value)
                if (complete) markCompleted(player.uuid, pass, entry)
                val next = progress[player.stringUUID]?.get(pass.id)?.get(progressKey(pass, entry)) ?: 0
                if (next != previous) changed = true
            }
        }

        if (changed) save()
        return changed
    }

    fun recordEvent(player: ServerPlayer, eventId: String, amount: Int = 1): Boolean {
        return recordSignal(player, BattlepassMissionSignal(setOf(eventId), amount))
    }

    fun recordSignal(player: ServerPlayer, signal: BattlepassMissionSignal): Boolean {
        if (!loaded) load()
        var changed = false

        BattlepassPassRegistry.all().forEach { pass ->
            activeEntries(pass).filter { entry -> BattlepassMissionEventBank.matches(signal, entry.event) }.forEach { entry ->
                if (isCompleted(player.uuid, pass, entry)) return@forEach
                changed = true
                if (BattlepassMissionService.isProgressive(entry.event)) {
                    val complete = recordProgressive(player, pass, entry, signal.amount)
                    if (complete) markCompleted(player.uuid, pass, entry)
                } else if (BattlepassMissionService.isCappedRepeating(entry.event)) {
                    val complete = recordCappedRepeating(player, pass, entry, signal.amount)
                    if (complete) markCompleted(player.uuid, pass, entry)
                } else {
                    if (entry.event.xp > 0) addXpAndNotifyRewardUnlock(player, pass, entry.event.xp)
                }
            }
        }

        if (NpcQuestService.recordSignal(player, signal)) changed = true
        if (ClassMentorQuestService.recordSignal(player, signal)) changed = true
        if (TechLicenseQuestService.recordSignal(player, signal)) changed = true
        if (changed) save()
        return changed
    }

    private fun recordCappedRepeating(player: ServerPlayer, pass: BattlepassPassDefinition, entry: BattlepassMissionEntry, amount: Int): Boolean {
        val event = entry.event
        val playerProgress = progress.getOrPut(player.stringUUID) { linkedMapOf() }
        val passProgress = playerProgress.getOrPut(pass.id) { linkedMapOf() }
        val storageKey = progressKey(pass, entry)
        val previous = passProgress.getOrDefault(storageKey, 0)
        if (previous >= event.xpCap) return true

        val possibleXp = event.xp * amount.coerceAtLeast(1)
        val awardedXp = possibleXp.coerceAtMost(event.xpCap - previous).coerceAtLeast(0)
        if (awardedXp > 0) addXpAndNotifyRewardUnlock(player, pass, awardedXp)
        val next = (previous + awardedXp).coerceAtMost(event.xpCap)
        passProgress[storageKey] = next
        val completed = next >= event.xpCap
        if (completed && previous < event.xpCap) {
            broadcastMissionCompletion(player, pass, entry, BattlepassMissionService.missionDescription(event, next))
        }
        return completed
    }

    private fun recordProgressive(player: ServerPlayer, pass: BattlepassPassDefinition, entry: BattlepassMissionEntry, amount: Int): Boolean {
        val previous = progress[player.stringUUID]?.get(pass.id)?.get(progressKey(pass, entry)) ?: 0
        return setProgress(player, pass, entry, previous + amount)
    }

    private fun setProgress(player: ServerPlayer, pass: BattlepassPassDefinition, entry: BattlepassMissionEntry, value: Int): Boolean {
        val playerProgress = progress.getOrPut(player.stringUUID) { linkedMapOf() }
        val passProgress = playerProgress.getOrPut(pass.id) { linkedMapOf() }
        val event = entry.event
        val storageKey = progressKey(pass, entry)
        val previous = passProgress.getOrDefault(storageKey, 0)
        val next = value.coerceAtLeast(previous)
        passProgress[storageKey] = next

        event.progressGoals.forEachIndexed { index, goal ->
            if (previous < goal && next >= goal) {
                val xp = event.progressXp.getOrNull(index) ?: event.xp
                if (xp > 0) {
                    addXpAndNotifyRewardUnlock(player, pass, xp)
                }
                if (entry.scope == BattlepassMissionScope.PERMANENT && BattlepassMissionService.isProgressive(event)) {
                    val finalGoal = BattlepassMissionService.progressiveGoal(event)
                    val title = "${event.eventDesc.ifBlank { event.event }.replace("{goal}", goal.toString()).replace("{progress}", goal.toString())} $goal/$finalGoal"
                    broadcastMissionCompletion(player, pass, entry, title)
                    BattlepassNetwork.notifyMissionCompletion(player, pass.id, entry.key, title, entry.scope, "goal:$goal")
                } else {
                    broadcastMissionCompletion(player, pass, entry, event.eventDesc.ifBlank { event.event }.replace("{goal}", goal.toString()).replace("{progress}", goal.toString()))
                }
            }
        }

        return next >= BattlepassMissionService.progressiveGoal(event)
    }

    private fun activeEntries(pass: BattlepassPassDefinition): List<BattlepassMissionEntry> =
        BattlepassMissionService.permanentEntries(pass) + activeRotatingEntries(pass, BattlepassMissionScope.WEEKLY, pass.weeklyEvents)

    private fun addXpAndNotifyRewardUnlock(player: ServerPlayer, pass: BattlepassPassDefinition, xp: Int) {
        if (xp <= 0) return
        val previousXp = BattlepassXpStore.getXp(player, pass.id)
        BattlepassXpStore.addXp(player, pass.id, xp)
        val currentXp = BattlepassXpStore.getXp(player, pass.id)
        val newlyUnlocked = pass.progression.filter { tier -> previousXp < tier.xp && currentXp >= tier.xp && !BattlepassXpStore.isClaimed(player, pass.id, tier.xp) }
        recordTierMilestones(player, pass, newlyUnlocked)
        if (newlyUnlocked.isEmpty()) return
        val unclaimedCount = pass.progression.count { tier -> currentXp >= tier.xp && !BattlepassXpStore.isClaimed(player, pass.id, tier.xp) }
        val passName = pass.displayName.ifBlank { pass.id }
        val rewardWord = if (unclaimedCount == 1) "reward" else "rewards"
        val latestReward = newlyUnlocked.maxByOrNull { tier -> tier.xp }?.rewards?.firstOrNull()
        SnackbarNetwork.send(
            player,
            SnackbarNotification.item(rewardIcon(latestReward), "NEW BATTLEPASS REWARD", "$unclaimedCount unclaimed $rewardWord in \"$passName\" pass", SnackbarType.SUCCESS, SnackbarSounds.REWARD),
        )
    }

    private fun broadcastMissionCompletion(player: ServerPlayer, pass: BattlepassPassDefinition, entry: BattlepassMissionEntry, title: String) {
        SnackbarNetwork.send(
            player,
            SnackbarNotification.item(BattlepassMissionIcons.iconId(entry), missionSnackbarTitle(entry.scope), title, SnackbarType.SUCCESS, SnackbarSounds.REWARD),
        )
        val message = ChatGlyphs.chowKingdomPrefix()
            .append(Component.literal(player.gameProfile.name).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" completed ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(missionTypeLabel(entry.scope)).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(title).withStyle(ChatFormatting.WHITE))
        player.server.playerList.broadcastSystemMessage(message, false)
        DiscordRelay.battlepassMissionCompleted(player, pass.displayName.ifBlank { pass.id }, missionTypeLabel(entry.scope), title, BattlepassXpStore.getXp(player, pass.id))
        val memory = "${player.gameProfile.name} completed ${missionTypeLabel(entry.scope)} in ${pass.displayName.ifBlank { pass.id }}: $title"
        NpcStore.recordGlobalEvent("mission_completed", memory)
        NpcStore.recordGlobalMemory("mission_completed", memory)
        NpcStore.recordPlayerMemory(player, "mission_completed", memory)
    }

    private fun recordTierMilestones(player: ServerPlayer, pass: BattlepassPassDefinition, newlyUnlocked: List<BattlepassProgressionDefinition>) {
        if (newlyUnlocked.isEmpty()) return
        val sortedTiers = pass.progression.sortedBy { tier -> tier.xp }
        val passName = pass.displayName.ifBlank { pass.id }
        newlyUnlocked.forEach { tier ->
            val tierNumber = sortedTiers.indexOfFirst { candidate -> candidate.xp == tier.xp } + 1
            if (tierNumber <= 0 || tierNumber % 10 != 0) return@forEach
            val memory = "${player.gameProfile.name} reached $passName level $tierNumber (${tier.xp} XP)"
            NpcStore.recordGlobalMemory("battlepass_level", memory)
            NpcStore.recordPlayerMemory(player, "battlepass_level", memory)
        }
    }

    private fun missionTypeLabel(scope: BattlepassMissionScope): String = when (scope) {
        BattlepassMissionScope.DAILY -> "NPC Mission"
        BattlepassMissionScope.WEEKLY -> "Weekly Mission"
        BattlepassMissionScope.PERMANENT -> "Chowkingdom Mission"
    }

    private fun missionSnackbarTitle(scope: BattlepassMissionScope): String = when (scope) {
        BattlepassMissionScope.DAILY -> "NPC MISSION COMPLETE"
        BattlepassMissionScope.WEEKLY -> "WEEKLY MISSION COMPLETE"
        BattlepassMissionScope.PERMANENT -> "CHOWKINGDOM MISSION COMPLETE"
    }

    private fun rewardIcon(reward: BattlepassRewardDefinition?): String {
        if (reward == null) return SnackbarIcons.BATTLEPASS
        if (isChowcoinReward(reward)) return "minecraft:gold_ingot"
        if (RelicRouletteFeature.isRelicTokenReward(reward.type)) return RelicRouletteFeature.tokenItemIdForReward(reward.item, reward.data["pool"]).takeIf(String::isNotBlank) ?: SnackbarIcons.BATTLEPASS
        return reward.item.takeIf(String::isNotBlank) ?: SnackbarIcons.BATTLEPASS
    }

    private fun isChowcoinReward(reward: BattlepassRewardDefinition): Boolean {
        if (reward.type.equals("chowcoin", ignoreCase = true) || reward.type.equals("chowcoins", ignoreCase = true)) return true
        return reward.type.equals("currency", ignoreCase = true) && reward.data["currency"]?.equals("chowcoin", ignoreCase = true) == true
    }

    private fun activeRotatingEntries(pass: BattlepassPassDefinition, scope: BattlepassMissionScope, definition: BattlepassRotatingMissionDefinition): List<BattlepassMissionEntry> {
        val entries = BattlepassMissionService.rotatingEntries(scope, definition.events)
        if (definition.count <= 0 || entries.isEmpty()) return emptyList()

        val periodKey = BattlepassMissionService.periodKey(scope, definition)
        val passRotations = rotations.getOrPut(pass.id) { linkedMapOf() }
        val current = passRotations[scope.id]
        val entriesByKey = entries.associateBy { entry -> entry.key }
        val validKeys = entriesByKey.keys
        val count = definition.count.coerceIn(1, entries.size)

        if (current == null || current.periodKey != periodKey || current.activeKeys.any { key -> key !in validKeys } || current.activeKeys.size != count || hasDuplicateRotationFamilies(current.activeKeys, entriesByKey)) {
            val usageCounts = current?.usageCounts?.filterKeys { key -> key in validKeys || key.startsWith("group:") }?.toMutableMap() ?: linkedMapOf()
            val activeKeys = selectRotatingKeys(entries, count, periodKey, usageCounts)
            activeKeys.forEach { key ->
                usageCounts[key] = (usageCounts[key] ?: 0) + 1
                entriesByKey[key]?.let { entry ->
                    val familyKey = usageKey(rotationFamily(entry))
                    usageCounts[familyKey] = (usageCounts[familyKey] ?: 0) + 1
                }
            }
            passRotations[scope.id] = StoredRotation(periodKey, activeKeys, usageCounts)
            rotationDirty = true
        }

        val activeKeys = passRotations[scope.id]?.activeKeys?.toSet().orEmpty()
        return entries.filter { entry -> entry.key in activeKeys }
    }

    private fun selectRotatingKeys(entries: List<BattlepassMissionEntry>, count: Int, periodKey: String, usageCounts: MutableMap<String, Int>): MutableList<String> {
        val families = entries.groupBy(::rotationFamily).toList()
        val selected = families
            .sortedWith(compareBy<Pair<String, List<BattlepassMissionEntry>>> { (family, _) -> usageCounts[usageKey(family)] ?: 0 }.thenBy { (family, _) -> "$periodKey:$family".hashCode() })
            .take(count)
            .map { (_, familyEntries) -> chooseFamilyEntry(familyEntries, periodKey, usageCounts) }
            .toMutableList()

        if (selected.size < count) {
            val selectedKeys = selected.map { entry -> entry.key }.toSet()
            entries
                .filter { entry -> entry.key !in selectedKeys }
                .sortedWith(compareBy<BattlepassMissionEntry> { entry -> usageCounts[entry.key] ?: 0 }.thenBy { entry -> "$periodKey:${entry.key}".hashCode() })
                .take(count - selected.size)
                .forEach(selected::add)
        }

        return selected.map { entry -> entry.key }.toMutableList()
    }

    private fun chooseFamilyEntry(entries: List<BattlepassMissionEntry>, periodKey: String, usageCounts: Map<String, Int>): BattlepassMissionEntry =
        entries.sortedWith(compareBy<BattlepassMissionEntry> { entry -> usageCounts[entry.key] ?: 0 }.thenBy { entry -> "$periodKey:${entry.key}".hashCode() }).first()

    private fun hasDuplicateRotationFamilies(activeKeys: List<String>, entriesByKey: Map<String, BattlepassMissionEntry>): Boolean {
        val families = activeKeys.mapNotNull { key -> entriesByKey[key]?.let(::rotationFamily) }
        return families.size != families.toSet().size
    }

    private fun rotationFamily(entry: BattlepassMissionEntry): String {
        val configured = entry.event.rotationGroup.trim().lowercase(Locale.ROOT)
        if (configured.isNotBlank()) return configured

        val eventId = entry.event.event.trim().lowercase(Locale.ROOT)
        val namespace = eventId.substringBefore(':', "")
        val path = eventId.substringAfter(':', eventId)
        return when {
            namespace == "cobblemon" && path.startsWith("catch_") -> "cobblemon:catch_pokemon"
            namespace == "cobblemon" && (path.startsWith("max_friendship_") || path.contains("friendship") || path.contains("befriend")) -> "cobblemon:max_friendship_pokemon"
            namespace == "cobblemon" && path.startsWith("send_out_") -> "cobblemon:send_out_pokemon"
            namespace == "quality_food" && path.endsWith("quality_crop_harvested") -> "quality_food:quality_crop_harvested"
            path.endsWith("quality_food_cooked") || path.endsWith("quality_food_smelted") -> "quality_food:quality_food_cooked"
            namespace == "quality_food" && path.endsWith("quality_food_eaten") -> "quality_food:quality_food_eaten"
            namespace == "gisketchs_chowkingdom_mod" && path.startsWith("shipping_bin_") && path.endsWith("value_sold") -> "gisketchs_chowkingdom_mod:shipping_bin_value_sold"
            namespace == "gisketchs_chowkingdom_mod" && path.startsWith("shipping_bin_") && path.contains("quality_food") && path.endsWith("sold") -> "gisketchs_chowkingdom_mod:shipping_bin_quality_food_sold"
            namespace == "farmersdelight" && path.contains("cutting_board") -> "farmersdelight:cutting_board"
            else -> eventId.ifBlank { entry.key }
        }
    }

    private fun usageKey(family: String): String = "group:$family"

    private fun isCompleted(playerId: UUID, pass: BattlepassPassDefinition, entry: BattlepassMissionEntry): Boolean {
        if (entry.scope == BattlepassMissionScope.PERMANENT) return false
        return completed[playerId.toString()]?.get(pass.id)?.get(periodKey(pass, entry))?.contains(entry.key) == true
    }

    private fun markCompleted(playerId: UUID, pass: BattlepassPassDefinition, entry: BattlepassMissionEntry) {
        if (entry.scope == BattlepassMissionScope.PERMANENT) return
        completed
            .getOrPut(playerId.toString()) { linkedMapOf() }
            .getOrPut(pass.id) { linkedMapOf() }
            .getOrPut(periodKey(pass, entry)) { linkedSetOf() }
            .add(entry.key)
    }

    private fun periodKey(pass: BattlepassPassDefinition, entry: BattlepassMissionEntry): String = when (entry.scope) {
        BattlepassMissionScope.PERMANENT -> BattlepassMissionScope.PERMANENT.id
        BattlepassMissionScope.DAILY -> BattlepassMissionService.periodKey(entry.scope, pass.dailyEvents)
        BattlepassMissionScope.WEEKLY -> BattlepassMissionService.periodKey(entry.scope, pass.weeklyEvents)
    }

    private fun progressKey(pass: BattlepassPassDefinition, entry: BattlepassMissionEntry): String = when (entry.scope) {
        BattlepassMissionScope.PERMANENT -> entry.key
        BattlepassMissionScope.DAILY,
        BattlepassMissionScope.WEEKLY -> "${entry.key}@${periodKey(pass, entry)}"
    }

    private fun save() {
        TomlConfigIO.write(file, StoredMissionProgress(progress, completed, rotations))
        rotationDirty = false
    }

    private data class StoredRotation(
        var periodKey: String = "",
        var activeKeys: MutableList<String> = mutableListOf(),
        var usageCounts: MutableMap<String, Int> = linkedMapOf(),
    )

    private class StoredMissionProgress(
        var players: MutableMap<String, MutableMap<String, MutableMap<String, Int>>> = linkedMapOf(),
        var completed: MutableMap<String, MutableMap<String, MutableMap<String, MutableSet<String>>>> = linkedMapOf(),
        var rotations: MutableMap<String, MutableMap<String, StoredRotation>> = linkedMapOf(),
    )
}
