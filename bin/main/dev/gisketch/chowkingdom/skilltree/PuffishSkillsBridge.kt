package dev.gisketch.chowkingdom.skilltree

import dev.gisketch.chowkingdom.ChowKingdomMod
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import java.util.Optional
import java.util.UUID
import java.util.stream.Stream

object PuffishSkillsBridge {
    private val categoryId: ResourceLocation = ResourceLocation.fromNamespaceAndPath("skill_tree_rpgs", "skill_tree_rpgs")
    private val pointSourceId: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "battlepass")
    private val mirroredStates: MutableMap<UUID, MirrorState> = linkedMapOf()

    fun mirror(player: ServerPlayer, rootSkillId: String?, paidSkillIds: Set<String>, pointsTotal: Int) {
        val state = MirrorState(
            rootSkillId = rootSkillId.orEmpty(),
            paidSkillIds = paidSkillIds.map(String::trim).filter(String::isNotBlank).sorted(),
            pointsTotal = pointsTotal.coerceAtLeast(0),
        )
        synchronized(mirroredStates) {
            if (mirroredStates[player.uuid] == state) return
        }
        val category = category() ?: return
        runCatching {
            category.javaClass.getMethod("unlock", ServerPlayer::class.java).invoke(category, player)
            zeroExperience(category, player)
            zeroForeignPointSources(category, player)
            category.javaClass.getMethod("setExtraPoints", ServerPlayer::class.java, Int::class.javaPrimitiveType).invoke(category, player, 0)
            category.javaClass.getMethod("resetSkills", ServerPlayer::class.java).invoke(category, player)
            category.javaClass.getMethod("setPointsSilently", ServerPlayer::class.java, ResourceLocation::class.java, Int::class.javaPrimitiveType)
                .invoke(category, player, pointSourceId, state.pointsTotal)
            val ids = buildList {
                if (state.rootSkillId.isNotBlank()) add(state.rootSkillId)
                addAll(state.paidSkillIds)
            }
            ids.forEach { id -> unlock(category, player, id) }
            synchronized(mirroredStates) {
                mirroredStates[player.uuid] = state
            }
        }.onFailure { exception ->
            ChowKingdomMod.LOGGER.debug("Failed to mirror CKDM class skill state to Puffish Skills", exception)
        }
    }

    fun clear(player: ServerPlayer) {
        synchronized(mirroredStates) {
            mirroredStates.remove(player.uuid)
        }
    }

    fun clearAll() {
        synchronized(mirroredStates) {
            mirroredStates.clear()
        }
    }

    private fun category(): Any? = runCatching {
        val api = Class.forName("net.puffish.skillsmod.api.SkillsAPI")
        val optional = api.getMethod("getCategory", ResourceLocation::class.java).invoke(null, categoryId) as Optional<*>
        optional.orElse(null)
    }.getOrNull()

    private fun unlock(category: Any, player: ServerPlayer, skillId: String) {
        val optional = category.javaClass.getMethod("getSkill", String::class.java).invoke(category, skillId) as Optional<*>
        val skill = optional.orElse(null) ?: return
        skill.javaClass.getMethod("unlock", ServerPlayer::class.java).invoke(skill, player)
    }

    private fun zeroExperience(category: Any, player: ServerPlayer) {
        val optional = category.javaClass.getMethod("getExperience").invoke(category) as Optional<*>
        val experience = optional.orElse(null) ?: return
        experience.javaClass.getMethod("setTotal", ServerPlayer::class.java, Int::class.javaPrimitiveType).invoke(experience, player, 0)
    }

    private fun zeroForeignPointSources(category: Any, player: ServerPlayer) {
        val stream = category.javaClass.getMethod("streamPointsSources", ServerPlayer::class.java).invoke(category, player) as Stream<*>
        val sources = mutableListOf<Any?>()
        stream.use { values -> values.forEach { source -> sources.add(source) } }
        sources.filterIsInstance<ResourceLocation>().filter { source -> source != pointSourceId }.forEach { source ->
            category.javaClass.getMethod("setPointsSilently", ServerPlayer::class.java, ResourceLocation::class.java, Int::class.javaPrimitiveType)
                .invoke(category, player, source, 0)
        }
    }
}

private data class MirrorState(
    val rootSkillId: String,
    val paidSkillIds: List<String>,
    val pointsTotal: Int,
)
