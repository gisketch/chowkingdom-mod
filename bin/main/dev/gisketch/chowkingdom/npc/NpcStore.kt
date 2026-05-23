package dev.gisketch.chowkingdom.npc

import com.google.gson.GsonBuilder
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.config.TomlConfigIO
import dev.gisketch.chowkingdom.roles.PerformerPerks
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object NpcStore {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var data = NpcWorldData()
    private var loaded = false

    private val file: Path
        get() {
            val server = ServerLifecycleHooks.getCurrentServer()
            val root = if (server != null) server.getWorldPath(LevelResource.ROOT).resolve("data") else FMLPaths.CONFIGDIR.get()
            val extension = if (server != null) "json" else "toml"
            return root.resolve(ChowKingdomMod.MOD_ID).resolve("npcs").resolve("state.$extension")
        }

    fun load() {
        file.parent.createDirectories()
        data = if (file.exists()) {
            try {
                TomlConfigIO.read(file, NpcWorldData::class.java, ::NpcWorldData)
            } catch (exception: Exception) {
                ChowKingdomMod.LOGGER.warn("Failed to load NPC state {}", file, exception)
                NpcWorldData()
            }
        } else NpcWorldData()
        loaded = true
    }

    fun state(npcId: String): NpcResidentState {
        if (!loaded) load()
        return data.npcs.getOrPut(npcId) { NpcResidentState() }
    }

    fun setEntity(npcId: String, entityId: UUID, campPos: BlockPos) {
        val state = state(npcId)
        val camp = NpcBlockPosData.from(campPos)
        if (state.entityUuid == entityId.toString() && state.camp?.let { it.x == camp.x && it.y == camp.y && it.z == camp.z } == true) return
        state.entityUuid = entityId.toString()
        state.camp = camp
        save()
    }

    fun setCampBlock(pos: BlockPos) {
        if (!loaded) load()
        data.campBlock = NpcBlockPosData.from(pos)
        save()
    }

    fun questState(player: ServerPlayer, period: Long, onExpired: ((List<NpcAcceptedQuestState>) -> Unit)? = null): NpcPlayerQuestState {
        return questState(player, period, Long.MAX_VALUE, onExpired)
    }

    fun questState(player: ServerPlayer, period: Long, currentTick: Long, onExpired: ((List<NpcAcceptedQuestState>) -> Unit)? = null): NpcPlayerQuestState {
        if (!loaded) load()
        val state = data.playerQuests.getOrPut(player.stringUUID) { NpcPlayerQuestState() }
        if (state.period != period) {
            val active = state.active.values.onEach { quest ->
                if (quest.expiresAtTick <= 0L) quest.expiresAtTick = questExpiryTick(quest)
            }
            val expired = active.filter { quest -> quest.expiresAtTick <= currentTick }
            val kept = active.filterNot { quest -> quest.expiresAtTick <= currentTick }
            state.period = period
            state.active = kept.associateByTo(linkedMapOf()) { quest -> quest.npcId }
            state.completedNpcIds.clear()
            state.completedNoCapNpcIds.clear()
            state.declinedUntilTick.clear()
            if (expired.isNotEmpty()) onExpired?.invoke(expired)
            save()
        }
        return state
    }

    private fun questExpiryTick(quest: NpcAcceptedQuestState): Long = when {
        quest.expiresAtTick > 0L -> quest.expiresAtTick
        quest.acceptedAtTick > 0L -> NpcTime.nextDayAtHour(quest.acceptedAtTick, NpcFeature.plazaMeetupStartHour())
        else -> 0L
    }

    fun saveQuestState() {
        if (!loaded) load()
        save()
    }

    fun campBlockPos(): BlockPos? {
        if (!loaded) load()
        return data.campBlock?.toBlockPos()
    }

    fun setTownCenter(pos: BlockPos, radius: Int = townCenterRadius()) {
        if (!loaded) load()
        data.townCenter = NpcBlockPosData.from(pos)
        data.townCenterRadius = radius.coerceIn(4, 64)
        save()
    }

    fun setTownCenterRadius(radius: Int) {
        if (!loaded) load()
        data.townCenterRadius = radius.coerceIn(4, 64)
        save()
    }

    fun clearTownCenter() {
        if (!loaded) load()
        data.townCenter = null
        save()
    }

    fun townCenterPos(): BlockPos? {
        if (!loaded) load()
        return data.townCenter?.toBlockPos()
    }

    fun townCenterRadius(): Int {
        if (!loaded) load()
        return data.townCenterRadius.coerceIn(4, 64)
    }

    fun activeCamperId(): String {
        if (!loaded) load()
        return data.activeCamperId
    }

    fun setActiveCamper(npcId: String, campPos: BlockPos) {
        if (!loaded) load()
        data.activeCamperId = npcId
        data.campBlock = NpcBlockPosData.from(campPos)
        data.camperCooldownUntilTick = -1L
        state(npcId).camperReturnReason = ""
        save()
    }

    fun clearActiveCamper(npcId: String) {
        if (!loaded) load()
        if (data.activeCamperId != npcId) return
        data.activeCamperId = ""
        save()
    }

    fun camperCooldownUntilTick(): Long {
        if (!loaded) load()
        return data.camperCooldownUntilTick
    }

    fun setCamperCooldown(untilTick: Long) {
        if (!loaded) load()
        data.camperCooldownUntilTick = untilTick
        save()
    }

    fun markCamperReturnReason(npcId: String, reason: String) {
        val state = state(npcId)
        state.camperReturnReason = reason
        save()
    }

    fun camperReturnReason(npcId: String): String = state(npcId).camperReturnReason

    fun setHome(npcId: String, homePos: BlockPos) {
        val state = state(npcId)
        state.home = NpcBlockPosData.from(homePos)
        save()
    }

    fun clearHome(npcId: String) {
        val state = state(npcId)
        if (state.home == null) return
        state.home = null
        save()
    }

    fun setWorkplace(npcId: String, workplacePos: BlockPos) {
        val state = state(npcId)
        state.workplace = NpcBlockPosData.from(workplacePos)
        state.workFired = false
        save()
    }

    fun clearWorkplace(npcId: String, fired: Boolean) {
        val state = state(npcId)
        state.workplace = null
        state.workFired = fired
        save()
    }

    fun markContractGiven(npcId: String) {
        val state = state(npcId)
        if (state.contractGiven) return
        state.contractGiven = true
        save()
    }

    fun recordHurt(npcId: String, player: ServerPlayer, timestamp: Long): Int {
        val state = state(npcId)
        if (state.lastHurtPlayerUuid == player.stringUUID) {
            state.hurtStreak += 1
        } else {
            state.lastHurtPlayerUuid = player.stringUUID
            state.lastHurtPlayerName = player.gameProfile.name
            state.hurtStreak = 1
        }
        state.lastHurtAt = timestamp
        if (state.hurtStreak % HURT_HISTORY_RECORD_INTERVAL == 0) {
            state.hurtHistory += NpcHurtRecord(timestamp, player.stringUUID, player.gameProfile.name)
            if (state.hurtHistory.size > MAX_HURT_HISTORY) state.hurtHistory = state.hurtHistory.takeLast(MAX_HURT_HISTORY).toMutableList()
        }
        save()
        return state.hurtStreak
    }

    fun recordConversation(npcId: String, player: ServerPlayer, speaker: String, text: String, type: String) {
        val state = state(npcId)
        val key = player.stringUUID
        val history = state.conversations.getOrPut(key) { mutableListOf() }
        history += NpcConversationRecord(System.currentTimeMillis(), type, speaker, text, player.stringUUID, player.gameProfile.name)
        state.conversations[key] = history.takeLast(MAX_CONVERSATION_HISTORY).toMutableList()
        save()
    }

    fun recordGiftIfAllowed(npcId: String, player: ServerPlayer, period: Long, limit: Int): Boolean {
        if (limit <= 0) return false
        val state = state(npcId)
        val gift = state.giftLimits.getOrPut(player.stringUUID) { NpcGiftLimitState() }
        if (gift.period != period) {
            gift.period = period
            gift.count = 0
        }
        if (gift.count >= limit) return false
        gift.count += 1
        save()
        return true
    }

    fun giftCount(npcId: String, player: ServerPlayer, period: Long): Int {
        val gift = state(npcId).giftLimits[player.stringUUID] ?: return 0
        return if (gift.period == period) gift.count else 0
    }

    fun outgoingGiftReady(npcId: String, player: ServerPlayer, day: Long, hour: Int, scheduledHour: Int): Boolean {
        val gift = state(npcId).outgoingGifts.getOrPut(player.stringUUID) { NpcOutgoingGiftState() }
        if (gift.pendingItem.isNotBlank()) return false
        if (gift.lastOfferDay >= day) return false
        if (gift.scheduledDay != day) {
            gift.scheduledDay = day
            gift.scheduledHour = scheduledHour.coerceIn(0, 23)
            save()
        }
        return hour >= gift.scheduledHour
    }

    fun pendingOutgoingGift(npcId: String, player: ServerPlayer): NpcPendingOutgoingGift? {
        val gift = state(npcId).outgoingGifts[player.stringUUID] ?: return null
        return gift.pendingItem.takeIf(String::isNotBlank)?.let { item -> NpcPendingOutgoingGift(item, gift.pendingQty.coerceAtLeast(1)) }
    }

    fun pendingOutgoingGiftReady(npcId: String, player: ServerPlayer, day: Long): NpcPendingOutgoingGift? {
        val gift = state(npcId).outgoingGifts[player.stringUUID] ?: return null
        if (gift.pendingItem.isBlank() || gift.lastOfferDay >= day) return null
        return NpcPendingOutgoingGift(gift.pendingItem, gift.pendingQty.coerceAtLeast(1))
    }

    fun markPendingOutgoingGiftReminder(npcId: String, player: ServerPlayer, day: Long) {
        val gift = state(npcId).outgoingGifts[player.stringUUID] ?: return
        if (gift.pendingItem.isBlank()) return
        gift.lastOfferDay = day
        save()
    }

    fun setPendingOutgoingGift(npcId: String, player: ServerPlayer, day: Long, item: String, qty: Int) {
        val gift = state(npcId).outgoingGifts.getOrPut(player.stringUUID) { NpcOutgoingGiftState() }
        gift.lastOfferDay = day
        gift.scheduledDay = day + 1
        gift.scheduledHour = -1
        gift.pendingItem = item
        gift.pendingQty = qty.coerceAtLeast(1)
        save()
    }

    fun clearPendingOutgoingGift(npcId: String, player: ServerPlayer, day: Long) {
        val gift = state(npcId).outgoingGifts.getOrPut(player.stringUUID) { NpcOutgoingGiftState() }
        gift.pendingItem = ""
        gift.pendingQty = 0
        gift.lastOfferDay = maxOf(gift.lastOfferDay, day)
        gift.scheduledDay = day + 1
        gift.scheduledHour = -1
        save()
    }

    fun canShowGreeting(npcId: String, player: ServerPlayer, day: Long, nowMs: Long): Boolean {
        val greeting = state(npcId).greetings.getOrPut(player.stringUUID) { NpcGreetingState() }
        return greeting.firstChatDay != day && nowMs >= greeting.cooldownUntilMs
    }

    fun markGreetingShown(npcId: String, player: ServerPlayer, day: Long, cooldownUntilMs: Long) {
        val greeting = state(npcId).greetings.getOrPut(player.stringUUID) { NpcGreetingState() }
        greeting.lastGreetDay = day
        greeting.cooldownUntilMs = cooldownUntilMs
        save()
    }

    fun clearGreetingCooldown(npcId: String, playerId: String) {
        val greeting = state(npcId).greetings[playerId] ?: return
        if (greeting.cooldownUntilMs == 0L) return
        greeting.cooldownUntilMs = 0L
        save()
    }

    fun markFirstChatIfNeeded(npcId: String, player: ServerPlayer, day: Long): Boolean {
        val greeting = state(npcId).greetings.getOrPut(player.stringUUID) { NpcGreetingState() }
        if (greeting.firstChatDay == day) return false
        greeting.firstChatDay = day
        greeting.cooldownUntilMs = 0L
        save()
        return true
    }

    fun recentInteractionTopics(npcId: String, player: ServerPlayer, limit: Int): List<NpcInteractionTopicRecord> {
        if (limit <= 0) return emptyList()
        return state(npcId).interactionTopics[player.stringUUID].orEmpty().takeLast(limit)
    }

    fun recordInteractionTopic(npcId: String, player: ServerPlayer, topicId: String) {
        val clean = topicId.trim().lowercase()
        if (clean.isBlank()) return
        val state = state(npcId)
        val history = state.interactionTopics.getOrPut(player.stringUUID) { mutableListOf() }
        history += NpcInteractionTopicRecord(System.currentTimeMillis(), clean)
        state.interactionTopics[player.stringUUID] = history.takeLast(MAX_INTERACTION_TOPIC_HISTORY).toMutableList()
        save()
    }

    fun friendshipSnapshot(npcId: String, player: ServerPlayer): NpcFriendshipSnapshot {
        return friendshipSnapshot(npcId, player.stringUUID)
    }

    fun friendshipSnapshot(npcId: String, playerId: String): NpcFriendshipSnapshot {
        val friendship = state(npcId).friendships.getOrPut(playerId) { NpcFriendshipState() }
        return NpcFriendshipSnapshot.from(friendship.points)
    }

    fun friendshipSnapshotsFor(player: ServerPlayer): Map<String, NpcFriendshipSnapshot> {
        if (!loaded) load()
        return data.npcs.mapNotNull { (npcId, state) ->
            val friendship = state.friendships[player.stringUUID] ?: return@mapNotNull null
            npcId to NpcFriendshipSnapshot.from(friendship.points)
        }.toMap()
    }

    fun adjustFriendship(npcId: String, player: ServerPlayer, delta: Int, reason: String): NpcFriendshipSnapshot {
        val state = state(npcId)
        val friendship = state.friendships.getOrPut(player.stringUUID) { NpcFriendshipState() }
        friendship.points = (friendship.points + effectiveFriendshipDelta(player, delta)).coerceIn(NpcFriendshipLevels.MIN_POINTS, NpcFriendshipLevels.MAX_POINTS)
        friendship.lastChangedAt = System.currentTimeMillis()
        friendship.lastReason = reason
        save()
        val snapshot = NpcFriendshipSnapshot.from(friendship.points)
        NpcMissionHooks.refreshFriendshipProgress(player)
        return snapshot
    }

    fun effectiveFriendshipDelta(player: ServerPlayer, delta: Int): Int {
        val perkAdjusted = PerformerPerks.friendshipDelta(player, delta)
        if (perkAdjusted <= 0) return perkAdjusted
        return (perkAdjusted / FRIENDSHIP_GAIN_DIVISOR).coerceAtLeast(1)
    }

    fun setFriendship(npcId: String, player: ServerPlayer, points: Int, reason: String): NpcFriendshipSnapshot {
        val state = state(npcId)
        val friendship = state.friendships.getOrPut(player.stringUUID) { NpcFriendshipState() }
        friendship.points = points.coerceIn(NpcFriendshipLevels.MIN_POINTS, NpcFriendshipLevels.MAX_POINTS)
        friendship.lastChangedAt = System.currentTimeMillis()
        friendship.lastReason = reason
        save()
        val snapshot = NpcFriendshipSnapshot.from(friendship.points)
        NpcMissionHooks.refreshFriendshipProgress(player)
        return snapshot
    }

    fun recordGlobalEvent(type: String, text: String) {
        if (!loaded) load()
        data.globalEvents += NpcGlobalEvent(System.currentTimeMillis(), type, text)
        if (data.globalEvents.size > MAX_GLOBAL_EVENTS) data.globalEvents = data.globalEvents.takeLast(MAX_GLOBAL_EVENTS).toMutableList()
        save()
    }

    fun recordGlobalMemory(type: String, text: String) {
        if (!loaded) load()
        val cleanText = text.trim().take(MAX_MEMORY_TEXT_LENGTH)
        if (cleanText.isBlank()) return
        data.globalMemories += NpcMemoryRecord(System.currentTimeMillis(), type, cleanText)
        if (data.globalMemories.size > MAX_GLOBAL_MEMORIES) data.globalMemories = data.globalMemories.takeLast(MAX_GLOBAL_MEMORIES).toMutableList()
        save()
    }

    fun recordPlayerMemory(player: ServerPlayer, type: String, text: String) {
        if (!loaded) load()
        val cleanText = text.trim().take(MAX_MEMORY_TEXT_LENGTH)
        if (cleanText.isBlank()) return
        val memories = data.playerMemories.getOrPut(player.stringUUID) { mutableListOf() }
        memories += NpcMemoryRecord(System.currentTimeMillis(), type, cleanText, player.stringUUID, player.gameProfile.name)
        data.playerMemories[player.stringUUID] = memories.takeLast(MAX_PLAYER_MEMORIES).toMutableList()
        save()
    }

    fun llmContext(npcId: String, player: ServerPlayer): NpcLlmContext {
        return llmContext(npcId, player, -1)
    }

    fun recentGlobalEvents(): List<NpcGlobalEvent> {
        if (!loaded) load()
        return data.globalEvents.takeLast(MAX_GLOBAL_EVENTS)
    }

    fun recentGlobalMemories(): List<NpcMemoryRecord> {
        if (!loaded) load()
        return data.globalMemories.takeLast(MAX_GLOBAL_MEMORIES)
    }

    fun llmContext(npcId: String, player: ServerPlayer, currentHour: Int): NpcLlmContext {
        val state = state(npcId)
        return NpcLlmContext(
            currentHour = currentHour,
            friendship = friendshipSnapshot(npcId, player),
            globalEvents = data.globalEvents.takeLast(MAX_GLOBAL_EVENTS),
            globalMemories = data.globalMemories.takeLast(MAX_GLOBAL_MEMORIES),
            playerMemories = data.playerMemories[player.stringUUID].orEmpty().takeLast(MAX_PLAYER_MEMORIES),
            conversation = state.conversations[player.stringUUID].orEmpty().takeLast(MAX_CONVERSATION_HISTORY),
            lastHurtAt = state.lastHurtAt,
            lastHurtPlayerName = state.lastHurtPlayerName,
            hurtStreak = state.hurtStreak,
            hurtHistory = state.hurtHistory.takeLast(MAX_HURT_HISTORY),
        )
    }

    fun markDead(npcId: String, respawnDay: Long) {
        val state = state(npcId)
        state.dead = true
        state.respawnDay = respawnDay
        save()
    }

    fun clearDead(npcId: String) {
        val state = state(npcId)
        if (!state.dead && state.respawnDay == -1L) return
        state.dead = false
        state.respawnDay = -1L
        save()
    }

    fun deadNpcIds(): List<String> {
        if (!loaded) load()
        return data.npcs.entries.filter { (_, state) -> state.dead }.map { (npcId, _) -> npcId }
    }

    fun isDead(npcId: String): Boolean = state(npcId).dead

    fun respawnDay(npcId: String): Long = state(npcId).respawnDay

    fun campPos(npcId: String): BlockPos? = state(npcId).camp?.toBlockPos()

    fun homePos(npcId: String): BlockPos? = state(npcId).home?.toBlockPos()

    fun workplacePos(npcId: String): BlockPos? = state(npcId).workplace?.toBlockPos()

    fun workFired(npcId: String): Boolean = state(npcId).workFired

    fun homeOwnerAt(pos: BlockPos): String? {
        if (!loaded) load()
        return data.npcs.entries.firstOrNull { (_, state) -> state.home?.toBlockPos() == pos }?.key
    }

    fun entityUuid(npcId: String): UUID? = runCatching { UUID.fromString(state(npcId).entityUuid) }.getOrNull()

    fun contractGiven(npcId: String): Boolean = state(npcId).contractGiven

    fun recognized(npcId: String, player: ServerPlayer): Boolean = state(npcId).recognized[player.stringUUID] == true

    fun markRecognized(npcId: String, player: ServerPlayer) {
        val state = state(npcId)
        if (state.recognized[player.stringUUID] == true) return
        state.recognized[player.stringUUID] = true
        save()
    }

    fun backupAndClearAll(): Path {
        if (!loaded) load()
        val backup = backupStateFile("all")
        data = NpcWorldData()
        save()
        return backup
    }

    fun backupAndClearNpc(npcId: String): Path {
        if (!loaded) load()
        val backup = backupStateFile(npcId)
        data.npcs.remove(npcId)
        if (data.activeCamperId == npcId) data.activeCamperId = ""
        save()
        return backup
    }

    private fun backupStateFile(label: String): Path {
        file.parent.createDirectories()
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val backup = file.parent.resolve("state.backup-$label-$timestamp.json")
        if (file.exists()) {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING)
        } else {
            backup.bufferedWriter().use { writer -> gson.toJson(data, writer) }
        }
        return backup
    }

    private fun save() {
        TomlConfigIO.write(file, data)
    }
}

class NpcWorldData(
    var npcs: MutableMap<String, NpcResidentState> = linkedMapOf(),
    var globalEvents: MutableList<NpcGlobalEvent> = mutableListOf(),
    var globalMemories: MutableList<NpcMemoryRecord> = mutableListOf(),
    var playerQuests: MutableMap<String, NpcPlayerQuestState> = linkedMapOf(),
    var playerMemories: MutableMap<String, MutableList<NpcMemoryRecord>> = linkedMapOf(),
    var campBlock: NpcBlockPosData? = null,
    var townCenter: NpcBlockPosData? = null,
    var townCenterRadius: Int = 12,
    var activeCamperId: String = "",
    var camperCooldownUntilTick: Long = -1L,
)

class NpcResidentState(
    var entityUuid: String = "",
    var camp: NpcBlockPosData? = null,
    var home: NpcBlockPosData? = null,
    var workplace: NpcBlockPosData? = null,
    var workFired: Boolean = false,
    var contractGiven: Boolean = false,
    var lastHurtAt: Long = 0L,
    var lastHurtPlayerUuid: String = "",
    var lastHurtPlayerName: String = "",
    var hurtStreak: Int = 0,
    var hurtHistory: MutableList<NpcHurtRecord> = mutableListOf(),
    var conversations: MutableMap<String, MutableList<NpcConversationRecord>> = linkedMapOf(),
    var recognized: MutableMap<String, Boolean> = linkedMapOf(),
    var giftLimits: MutableMap<String, NpcGiftLimitState> = linkedMapOf(),
    var outgoingGifts: MutableMap<String, NpcOutgoingGiftState> = linkedMapOf(),
    var greetings: MutableMap<String, NpcGreetingState> = linkedMapOf(),
    var interactionTopics: MutableMap<String, MutableList<NpcInteractionTopicRecord>> = linkedMapOf(),
    var friendships: MutableMap<String, NpcFriendshipState> = linkedMapOf(),
    var dead: Boolean = false,
    var respawnDay: Long = -1L,
    var camperReturnReason: String = "",
)

class NpcPlayerQuestState(
    var period: Long = Long.MIN_VALUE,
    var active: MutableMap<String, NpcAcceptedQuestState> = linkedMapOf(),
    var completedNpcIds: MutableSet<String> = linkedSetOf(),
    var completedNoCapNpcIds: MutableSet<String> = linkedSetOf(),
    var declinedUntilTick: MutableMap<String, Long> = linkedMapOf(),
)

class NpcAcceptedQuestState(
    var npcId: String = "",
    var npcName: String = "",
    var questId: String = "",
    var category: String = "task",
    var event: String = "",
    var description: String = "",
    var passId: String = "cozy",
    var xp: Int = 0,
    var chowcoins: Long = 0L,
    var goal: Int = 1,
    var progress: Int = 0,
    var timeWindowSeconds: Int = 0,
    var timedEventTicks: MutableList<Long> = mutableListOf(),
    var fetchItem: String = "",
    var fetchCount: Int = 0,
    var filters: MutableMap<String, String> = mutableMapOf(),
    var quizMessage: String = "",
    var quizChoices: MutableList<String> = mutableListOf(),
    var quizAnswerIndex: Int = -1,
    var ignoreDailyCap: Boolean = false,
    var dailyCapGroup: String = "normal",
    var acceptedAtTick: Long = 0L,
    var expiresAtTick: Long = 0L,
)

class NpcFriendshipState(
    var points: Int = NpcFriendshipLevels.DEFAULT_POINTS,
    var lastChangedAt: Long = 0L,
    var lastReason: String = "",
)

class NpcFriendshipSnapshot(
    val points: Int,
    val level: Int,
    val category: NpcFriendshipCategory,
) {
    companion object {
        fun from(points: Int): NpcFriendshipSnapshot {
            val clamped = points.coerceIn(NpcFriendshipLevels.MIN_POINTS, NpcFriendshipLevels.MAX_POINTS)
            val level = NpcFriendshipLevels.level(clamped)
            return NpcFriendshipSnapshot(clamped, level, NpcFriendshipLevels.category(level))
        }
    }
}

class NpcGiftLimitState(
    var period: Long = Long.MIN_VALUE,
    var count: Int = 0,
)

class NpcOutgoingGiftState(
    var scheduledDay: Long = Long.MIN_VALUE,
    var scheduledHour: Int = -1,
    var lastOfferDay: Long = Long.MIN_VALUE,
    var pendingItem: String = "",
    var pendingQty: Int = 0,
)

class NpcPendingOutgoingGift(
    val item: String,
    val qty: Int,
)

class NpcGreetingState(
    var lastGreetDay: Long = Long.MIN_VALUE,
    var cooldownUntilMs: Long = 0L,
    var firstChatDay: Long = Long.MIN_VALUE,
)

class NpcInteractionTopicRecord(
    var timestamp: Long = 0L,
    var topicId: String = "",
)

class NpcHurtRecord(
    var timestamp: Long = 0L,
    var playerUuid: String = "",
    var playerName: String = "",
)

class NpcConversationRecord(
    var timestamp: Long = 0L,
    var type: String = "",
    var speaker: String = "",
    var text: String = "",
    var playerUuid: String = "",
    var playerName: String = "",
)

class NpcGlobalEvent(
    var timestamp: Long = 0L,
    var type: String = "",
    var text: String = "",
)

class NpcMemoryRecord(
    var timestamp: Long = 0L,
    var type: String = "",
    var text: String = "",
    var playerUuid: String = "",
    var playerName: String = "",
)

class NpcLlmContext(
    val currentHour: Int,
    val friendship: NpcFriendshipSnapshot,
    val globalEvents: List<NpcGlobalEvent>,
    val globalMemories: List<NpcMemoryRecord>,
    val playerMemories: List<NpcMemoryRecord>,
    val conversation: List<NpcConversationRecord>,
    val lastHurtAt: Long,
    val lastHurtPlayerName: String,
    val hurtStreak: Int,
    val hurtHistory: List<NpcHurtRecord>,
)

class NpcBlockPosData(
    var x: Int = 0,
    var y: Int = 0,
    var z: Int = 0,
) {
    fun toBlockPos(): BlockPos = BlockPos(x, y, z)

    companion object {
        fun from(pos: BlockPos): NpcBlockPosData = NpcBlockPosData(pos.x, pos.y, pos.z)
    }
}

private const val MAX_HURT_HISTORY = 10
private const val HURT_HISTORY_RECORD_INTERVAL = 3
private const val MAX_CONVERSATION_HISTORY = 30
private const val MAX_GLOBAL_EVENTS = 30
private const val MAX_GLOBAL_MEMORIES = 60
private const val MAX_PLAYER_MEMORIES = 30
private const val MAX_INTERACTION_TOPIC_HISTORY = 20
private const val MAX_MEMORY_TEXT_LENGTH = 180
private const val FRIENDSHIP_GAIN_DIVISOR = 2
