package dev.gisketch.chowkingdom.skilltree

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.battlepass.BattlepassNetwork
import dev.gisketch.chowkingdom.battlepass.BattlepassXpStore
import dev.gisketch.chowkingdom.roles.RoleStore
import dev.gisketch.chowkingdom.roles.RolesConfig
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.max

object ClassSkillTrees {
    const val MAX_POINTS = 10
    const val POINT_EVERY_OVERALL_LEVELS = 10
    private const val CATEGORY_PATH = "resourcepacks/ckdm_skill_tree_changes/data/skill_tree_rpgs/puffish_skills/categories/skill_tree_rpgs"

    private val data: SkillTreeData by lazy(::loadData)

    fun register() = Unit

    fun budget(player: ServerPlayer): Int =
        (BattlepassXpStore.overallLevel(player) / POINT_EVERY_OVERALL_LEVELS).coerceIn(0, MAX_POINTS)

    fun pointSummary(player: ServerPlayer): ClassSkillPointSummary {
        val allowed = ownedRootIds(player)
        val budget = budget(player)
        val spent = totalSpent(player, allowed)
        return ClassSkillPointSummary(budget, spent, (budget - spent).coerceAtLeast(0))
    }

    fun syncPayload(player: ServerPlayer, openScreen: Boolean): ClassSkillTreeSyncPayload {
        val ownedClasses = RoleStore.activeClassIds(player).sorted()
        val ownedRoots = ownedClasses.mapNotNull { classId -> rootForClass(classId)?.let { root -> classId to root } }
        val allowedRoots = ownedRoots.map { (_, root) -> root.id }.toSet()
        RoleStore.pruneClassSkillRoots(player, allowedRoots)

        val selectedRoot = selectedRoot(player, allowedRoots)
        val budget = budget(player)
        val spent = totalSpent(player, allowedRoots)
        val pointsLeft = (budget - spent).coerceAtLeast(0)
        if (spent > budget) {
            RoleStore.resetClassSkills(player)
            PuffishSkillsBridge.mirror(player, null, emptySet(), 0)
            return syncPayload(player, openScreen)
        }

        reconcile(player)
        return ClassSkillTreeSyncPayload(
            openScreen = openScreen,
            overallLevel = BattlepassXpStore.overallLevel(player),
            budget = budget,
            spent = spent,
            pointsLeft = pointsLeft,
            selectedRootSkillId = selectedRoot,
            classes = ownedRoots.map { (classId, root) -> classPayload(classId, root, selectedRoot) },
            roots = allowedRoots.mapNotNull { rootId -> rootPayload(player, rootId, selectedRoot, pointsLeft) },
        )
    }

    fun selectRoot(player: ServerPlayer, rootSkillId: String) {
        val allowed = ownedRootIds(player)
        val root = rootSkillId.trim()
        if (root !in allowed) return
        RoleStore.setSelectedClassSkillRoot(player, root)
        reconcile(player)
        ClassSkillTreeNetwork.syncTo(player, openScreen = true)
        BattlepassNetwork.syncTo(player)
    }

    fun unlock(player: ServerPlayer, rootSkillId: String, skillId: String): ClassSkillTreeUnlockResult {
        val root = rootSkillId.trim()
        val skill = skillId.trim()
        val allowed = ownedRootIds(player)
        if (root !in allowed) return ClassSkillTreeUnlockResult(false, "Skill path is not owned")
        if (skill.isBlank()) return ClassSkillTreeUnlockResult(false, "Unknown skill")
        val budget = budget(player)
        val spent = totalSpent(player, allowed)
        val state = unlockState(root, skill, RoleStore.classSkillIds(player, root), budget - spent)
        if (!state.available) return ClassSkillTreeUnlockResult(false, state.reason.ifBlank { "Skill is locked" })
        RoleStore.unlockClassSkill(player, root, skill)
        RoleStore.setSelectedClassSkillRoot(player, root)
        reconcile(player)
        ClassSkillTreeNetwork.syncTo(player, openScreen = true)
        BattlepassNetwork.syncTo(player)
        return ClassSkillTreeUnlockResult(true, "")
    }

    fun reset(player: ServerPlayer, rootSkillId: String? = null) {
        RoleStore.resetClassSkills(player, rootSkillId)
        reconcile(player)
        ClassSkillTreeNetwork.syncTo(player, openScreen = true)
        BattlepassNetwork.syncTo(player)
    }

    fun reconcile(player: ServerPlayer) {
        val allowed = ownedRootIds(player)
        RoleStore.pruneClassSkillRoots(player, allowed)
        val selected = selectedRoot(player, allowed).takeIf(String::isNotBlank)
        if (selected == null) {
            PuffishSkillsBridge.mirror(player, null, emptySet(), 0)
            return
        }
        val budget = budget(player)
        val totalSpent = totalSpent(player, allowed)
        if (totalSpent > budget) {
            RoleStore.resetClassSkills(player)
            PuffishSkillsBridge.mirror(player, null, emptySet(), 0)
            return
        }
        val selectedPaid = RoleStore.classSkillIds(player, selected).filter { it in (data.rootTrees[selected]?.skillIds.orEmpty()) }.toSet()
        val selectedSpent = selectedPaid.sumOf { data.nodes[it]?.cost ?: 1 }
        val remaining = (budget - totalSpent).coerceAtLeast(0)
        PuffishSkillsBridge.mirror(player, selected, selectedPaid, selectedSpent + remaining)
    }

    fun rootForClass(classId: String): SkillNode? {
        val rootDefinition = rootDefinitionForClass(classId.trim().lowercase(Locale.ROOT))
        return data.rootByDefinition[rootDefinition]
    }

    internal fun rootSkillIdForDefinition(definitionId: String): String? =
        data.rootByDefinition[definitionId]?.id

    internal fun skillIdsForDefinition(definitionId: String): List<String> =
        data.nodes.values.filter { node -> node.definitionId == definitionId }.map(SkillNode::id)

    internal fun definitionIdForSkill(skillId: String): String? =
        data.nodes[skillId]?.definitionId

    internal fun availableSkillIds(rootSkillId: String, unlockedPaidSkillIds: Set<String>, pointsLeft: Int): Set<String> =
        data.rootTrees[rootSkillId]?.skillIds.orEmpty()
            .filter { skillId -> unlockState(rootSkillId, skillId, unlockedPaidSkillIds, pointsLeft).available }
            .toSet()

    internal fun unlockStateFor(rootSkillId: String, skillId: String, unlockedPaidSkillIds: Set<String>, pointsLeft: Int): ClassSkillTreeUnlockState =
        unlockState(rootSkillId, skillId, unlockedPaidSkillIds, pointsLeft)

    private fun rootDefinitionForClass(classId: String): String = when (classId) {
        "wizard", "arcane_wizard", "fire_wizard", "frost_wizard", "water_wizard", "earth_wizard", "wind_wizard" -> "wizard_root"
        "bounty_hunter" -> "deadeye_root"
        "tundra_archer" -> "tundra_hunter_root"
        else -> "${classId}_root"
    }

    private fun classPayload(classId: String, root: SkillNode, selectedRoot: String): ClassSkillTreeClassPayload {
        val role = RolesConfig.roleClass(classId)
        return ClassSkillTreeClassPayload(
            classId = classId,
            displayName = role?.displayName?.ifBlank { classId } ?: titleCase(classId),
            icon = role?.icon.orEmpty(),
            rootSkillId = root.id,
            selected = root.id == selectedRoot,
        )
    }

    private fun rootPayload(player: ServerPlayer, rootId: String, selectedRoot: String, pointsLeft: Int): ClassSkillTreeRootPayload? {
        val tree = data.rootTrees[rootId] ?: return null
        val paidUnlocked = RoleStore.classSkillIds(player, rootId).filter { it in tree.skillIds }.toSet()
        val unlocked = paidUnlocked + rootId
        return ClassSkillTreeRootPayload(
            rootSkillId = rootId,
            selected = rootId == selectedRoot,
            nodes = tree.skillIds.mapNotNull { id ->
                val node = data.nodes[id] ?: return@mapNotNull null
                val state = unlockState(rootId, id, paidUnlocked, pointsLeft)
                ClassSkillTreeNodePayload(
                    skillId = node.id,
                    definitionId = node.definitionId,
                    titleKey = node.titleKey,
                    descriptionKey = node.descriptionKey,
                    icon = node.icon,
                    x = node.x,
                    y = node.y,
                    root = node.root,
                    cost = node.cost,
                    unlocked = node.id in unlocked,
                    available = state.available,
                    blocked = state.blocked,
                    blockedReason = state.reason,
                )
            },
            connections = tree.connections.map { (a, b) -> ClassSkillTreeConnectionPayload(a, b) },
            exclusiveConnections = tree.exclusiveConnections.map { (a, b) -> ClassSkillTreeConnectionPayload(a, b) },
        )
    }

    private fun selectedRoot(player: ServerPlayer, allowedRoots: Set<String>): String {
        val selected = RoleStore.selectedClassSkillRoot(player)
        if (selected in allowedRoots) return selected
        val next = allowedRoots.firstOrNull().orEmpty()
        if (next.isNotBlank()) RoleStore.setSelectedClassSkillRoot(player, next)
        return next
    }

    private fun ownedRootIds(player: ServerPlayer): Set<String> =
        RoleStore.activeClassIds(player).mapNotNull { classId -> rootForClass(classId)?.id }.toSet()

    private fun totalSpent(player: ServerPlayer, roots: Set<String>): Int =
        roots.sumOf { root -> RoleStore.classSkillIds(player, root).sumOf { id -> data.nodes[id]?.cost ?: 0 } }

    private fun unlockState(rootSkillId: String, skillId: String, unlockedPaidSkillIds: Set<String>, pointsLeft: Int): ClassSkillTreeUnlockState {
        val tree = data.rootTrees[rootSkillId] ?: return ClassSkillTreeUnlockState(false, false, "Unknown skill path")
        val node = data.nodes[skillId] ?: return ClassSkillTreeUnlockState(false, false, "Unknown skill")
        val unlocked = unlockedPaidSkillIds + rootSkillId
        if (skillId !in tree.skillIds) return ClassSkillTreeUnlockState(false, false, "Skill is outside this path")
        if (node.root) return ClassSkillTreeUnlockState(false, false, "Root is already active")
        if (skillId in unlocked) return ClassSkillTreeUnlockState(false, false, "Already unlocked")
        if (pointsLeft < node.cost) return ClassSkillTreeUnlockState(false, true, "Need ${node.cost} skill point${if (node.cost == 1) "" else "s"}")
        if (!tree.neighbors(skillId).any { neighbor -> neighbor in unlocked }) {
            return ClassSkillTreeUnlockState(false, true, "Requires a connected unlocked skill")
        }
        if (data.exclusiveNeighbors(skillId).any { other -> other in unlocked }) {
            return ClassSkillTreeUnlockState(false, true, "Blocked by another branch")
        }
        return ClassSkillTreeUnlockState(true, false, "")
    }

    private fun loadData(): SkillTreeData {
        val skills = readJson("$CATEGORY_PATH/skills.json").asJsonObject
        val definitions = readJson("$CATEGORY_PATH/definitions.json").asJsonObject
        val connections = readJson("$CATEGORY_PATH/connections.json").asJsonObject

        val nodes = linkedMapOf<String, SkillNode>()
        skills.entrySet().forEach { (id, value) ->
            val skill = value.asJsonObject
            val definitionId = skill.get("definition")?.asString.orEmpty()
            val definition = definitions.getAsJsonObject(definitionId)
            val titleKey = translateKey(definition?.get("title"), definitionId, "title")
            nodes[id] = SkillNode(
                id = id,
                definitionId = definitionId,
                titleKey = titleKey,
                descriptionKey = descriptionKey(definition?.get("description"), definitionId),
                icon = icon(definition),
                x = skill.get("x")?.asInt ?: 0,
                y = skill.get("y")?.asInt ?: 0,
                root = skill.get("root")?.asBoolean == true,
                cost = if (skill.get("root")?.asBoolean == true) 0 else 1,
            )
        }

        val normal = connectionPairs(connections.getAsJsonObject("normal"))
        val exclusive = connectionPairs(connections.getAsJsonObject("exclusive"))
        val rootTrees = nodes.values.filter(SkillNode::root).associate { root ->
            val ids = connectedComponent(root.id, normal).filter { it in nodes }.toSet()
            root.id to SkillRootTree(
                root.id,
                ids,
                normal.filter { (a, b) -> a in ids && b in ids },
                exclusive.filter { (a, b) -> a in ids && b in ids },
            )
        }
        return SkillTreeData(
            nodes = nodes,
            rootByDefinition = nodes.values.filter(SkillNode::root).associateBy { it.definitionId },
            rootTrees = rootTrees,
            normalConnections = normal,
            exclusiveConnections = exclusive,
        )
    }

    private fun readJson(path: String): JsonElement {
        val stream = ChowKingdomMod::class.java.classLoader.getResourceAsStream(path)
            ?: error("Missing CKDM skill tree data resource: $path")
        return InputStreamReader(stream).use(JsonParser::parseReader)
    }

    private fun connectionPairs(group: JsonObject?): List<Pair<String, String>> =
        group?.getAsJsonArray("bidirectional")?.mapNotNull { entry ->
            val pair = entry.asJsonArray
            val first = pair.getOrNull(0)?.asString ?: return@mapNotNull null
            val second = pair.getOrNull(1)?.asString ?: return@mapNotNull null
            first to second
        }.orEmpty()

    private fun connectedComponent(root: String, connections: List<Pair<String, String>>): List<String> {
        val neighbors = linkedMapOf<String, MutableSet<String>>()
        connections.forEach { (a, b) ->
            neighbors.getOrPut(a) { linkedSetOf() }.add(b)
            neighbors.getOrPut(b) { linkedSetOf() }.add(a)
        }
        val seen = linkedSetOf(root)
        val queue = ArrayDeque<String>()
        queue += root
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            neighbors[current].orEmpty().forEach { next ->
                if (seen.add(next)) queue += next
            }
        }
        return seen.toList()
    }

    private fun JsonArrayGetOrNull(array: com.google.gson.JsonArray, index: Int): JsonElement? =
        if (index in 0 until array.size()) array[index] else null

    private fun com.google.gson.JsonArray.getOrNull(index: Int): JsonElement? = JsonArrayGetOrNull(this, index)

    private fun translateKey(element: JsonElement?, definitionId: String, suffix: String): String {
        val obj = element as? JsonObject
        val translate = obj?.get("translate")?.asString
        if (!translate.isNullOrBlank()) return translate
        return "skill.${ChowKingdomMod.MOD_ID}.${definitionId}.$suffix"
    }

    private fun descriptionKey(element: JsonElement?, definitionId: String): String {
        val obj = element as? JsonObject
        val translate = obj?.get("translate")?.asString
        if (!translate.isNullOrBlank() && translate.startsWith("skill.${ChowKingdomMod.MOD_ID}.")) return translate
        val ckdmKey = "skill.${ChowKingdomMod.MOD_ID}.${definitionId}.description"
        return ckdmKey
    }

    private fun icon(definition: JsonObject?): String {
        val icon = definition?.getAsJsonObject("icon") ?: return ""
        val data = icon.getAsJsonObject("data")
        return data?.get("texture")?.asString ?: data?.get("item")?.asString ?: data?.get("id")?.asString ?: ""
    }

    private fun SkillRootTree.neighbors(skillId: String): Set<String> =
        connections.asSequence().flatMap { (a, b) ->
            when (skillId) {
                a -> sequenceOf(b)
                b -> sequenceOf(a)
                else -> emptySequence()
            }
        }.toSet()

    private fun SkillTreeData.exclusiveNeighbors(skillId: String): Set<String> =
        exclusiveConnections.asSequence().flatMap { (a, b) ->
            when (skillId) {
                a -> sequenceOf(b)
                b -> sequenceOf(a)
                else -> emptySequence()
            }
        }.toSet()

    private fun titleCase(id: String): String =
        id.replace('_', ' ').split(' ').joinToString(" ") { part -> part.replaceFirstChar { char -> char.titlecase(Locale.ROOT) } }
}

data class SkillNode(
    val id: String,
    val definitionId: String,
    val titleKey: String,
    val descriptionKey: String,
    val icon: String,
    val x: Int,
    val y: Int,
    val root: Boolean,
    val cost: Int,
)

data class ClassSkillTreeUnlockResult(
    val unlocked: Boolean,
    val reason: String,
)

data class ClassSkillTreeUnlockState(
    val available: Boolean,
    val blocked: Boolean,
    val reason: String,
)

data class ClassSkillPointSummary(
    val budget: Int,
    val spent: Int,
    val available: Int,
)

private data class SkillTreeData(
    val nodes: Map<String, SkillNode>,
    val rootByDefinition: Map<String, SkillNode>,
    val rootTrees: Map<String, SkillRootTree>,
    val normalConnections: List<Pair<String, String>>,
    val exclusiveConnections: List<Pair<String, String>>,
)

private data class SkillRootTree(
    val rootSkillId: String,
    val skillIds: Set<String>,
    val connections: List<Pair<String, String>>,
    val exclusiveConnections: List<Pair<String, String>>,
)
