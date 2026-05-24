package dev.gisketch.chowkingdom.npc

import com.google.gson.annotations.SerializedName

object NpcEmoteSurfaces {
    const val DISABLED = "disabled"
    const val CONVERSATION = "conversation"
    const val WORLD_CHAT = "world_chat"
    const val MICRO = "micro"
    const val AMBIENT = "ambient"
    const val AMBIENT_POSTURE = "ambient_posture"
    const val POKEMON = "pokemon"

    private val valid = setOf(CONVERSATION, WORLD_CHAT, MICRO, AMBIENT, AMBIENT_POSTURE, POKEMON)

    fun normalize(value: String): String = when (cleanNpcEmoteToken(value)) {
        "", "off", "disabled" -> DISABLED
        "talk", "dialog", "dialogue", "interact", "interaction", "gift", "quest", "class_training" -> CONVERSATION
        "world", "worldchat", "remote_chat", "discord" -> WORLD_CHAT
        "microinteraction", "micro_interaction", "npc_interaction" -> MICRO
        "idle", "solo", "solo_moment", "roam", "life" -> AMBIENT
        "posture", "sit", "sitting", "rest", "lay", "lying" -> AMBIENT_POSTURE
        "cobblemon", "pokemon_watch", "trainer" -> POKEMON
        else -> cleanNpcEmoteToken(value)
    }

    fun isValid(value: String): Boolean = normalize(value) in valid
}

private val REMOVED_NPC_EMOTE_IDS = setOf("proud", "lookout")

class NpcEmoteCatalogDefinition(
    var emotes: MutableList<NpcEmoteDefinition> = mutableListOf(),
) {
    fun normalized(defaults: List<NpcEmoteDefinition> = NpcEmoteDefaults.entries()): NpcEmoteCatalogDefinition = apply {
        emotes = emotes.map { emote -> emote.normalized() }
            .filter { emote -> emote.id.isNotBlank() && emote.animationId.isNotBlank() && emote.enabled && emote.id !in REMOVED_NPC_EMOTE_IDS }
            .distinctBy { emote -> emote.id }
            .toMutableList()
        if (emotes.isEmpty()) emotes = defaults.map { emote -> emote.copyDefinition() }.toMutableList()
    }
}

class NpcEmoteDefinition(
    var id: String = "",
    @SerializedName("animation_id") var animationId: String = "",
    var label: String = "",
    var description: String = "",
    var surfaces: MutableList<String> = mutableListOf(),
    var tags: MutableList<String> = mutableListOf(),
    var weight: Double = 1.0,
    @SerializedName("duration_ticks") var durationTicks: Int = 60,
    @SerializedName("cooldown_ticks") var cooldownTicks: Int = 80,
    @SerializedName("movement_lock") var movementLock: Boolean = false,
    var posture: Boolean = false,
    @SerializedName("ambient_only") var ambientOnly: Boolean = false,
    @SerializedName("loop_while_talking") var loopWhileTalking: Boolean = false,
    var enabled: Boolean = true,
) {
    fun normalized(): NpcEmoteDefinition = apply {
        id = cleanNpcEmoteToken(id).takeUnless { it == NpcEmoteCatalog.NONE }.orEmpty()
        animationId = animationId.trim().ifBlank { id.takeIf(String::isNotBlank)?.let { "emotecraft:$it" }.orEmpty() }
        if (animationId.isNotBlank()) animationId = NpcPlayerlikeAnimationRegistry.normalize(animationId)
        label = label.trim().ifBlank { id.replace('_', ' ').replaceFirstChar { char -> char.uppercase() } }
        description = description.trim().take(180)
        surfaces = surfaces.map(NpcEmoteSurfaces::normalize)
            .filter(NpcEmoteSurfaces::isValid)
            .distinct()
            .toMutableList()
        if (surfaces.isEmpty()) surfaces = mutableListOf(if (ambientOnly || posture) NpcEmoteSurfaces.AMBIENT_POSTURE else NpcEmoteSurfaces.CONVERSATION)
        if (ambientOnly) {
            surfaces = surfaces.filter { surface -> surface == NpcEmoteSurfaces.AMBIENT || surface == NpcEmoteSurfaces.AMBIENT_POSTURE || surface == NpcEmoteSurfaces.POKEMON }
                .ifEmpty { listOf(if (posture) NpcEmoteSurfaces.AMBIENT_POSTURE else NpcEmoteSurfaces.AMBIENT) }
                .toMutableList()
        }
        tags = tags.map(::cleanNpcEmoteToken).filter(String::isNotBlank).distinct().toMutableList()
        weight = weight.coerceIn(0.0, 1000.0)
        durationTicks = durationTicks.coerceIn(10, 20 * 30)
        cooldownTicks = cooldownTicks.coerceIn(0, 20 * 120)
        if (posture) movementLock = true
    }

    fun allows(surface: String): Boolean = NpcEmoteSurfaces.normalize(surface) in surfaces

    fun copyDefinition(): NpcEmoteDefinition = NpcEmoteDefinition(
        id = id,
        animationId = animationId,
        label = label,
        description = description,
        surfaces = surfaces.toMutableList(),
        tags = tags.toMutableList(),
        weight = weight,
        durationTicks = durationTicks,
        cooldownTicks = cooldownTicks,
        movementLock = movementLock,
        posture = posture,
        ambientOnly = ambientOnly,
        loopWhileTalking = loopWhileTalking,
        enabled = enabled,
    ).normalized()
}

object NpcEmoteDefaults {
    fun entries(): List<NpcEmoteDefinition> = listOf(
        emote("hi", "emotecraft:hi", "friendly hello", listOf(NpcEmoteSurfaces.CONVERSATION, NpcEmoteSurfaces.WORLD_CHAT, NpcEmoteSurfaces.MICRO), listOf("greeting", "friendly"), 70, 60, 80),
        emote("wave", "emotecraft:wave", "greeting or farewell", listOf(NpcEmoteSurfaces.CONVERSATION, NpcEmoteSurfaces.WORLD_CHAT, NpcEmoteSurfaces.MICRO, NpcEmoteSurfaces.POKEMON), listOf("greeting", "friendly", "farewell"), 80, 70, 80),
        emote("clap", "emotecraft:clap", "approval or celebration", listOf(NpcEmoteSurfaces.CONVERSATION, NpcEmoteSurfaces.WORLD_CHAT, NpcEmoteSurfaces.MICRO, NpcEmoteSurfaces.AMBIENT, NpcEmoteSurfaces.POKEMON), listOf("happy", "approval", "celebrate", "pokemon"), 65, 70, 100),
        emote("facepalm", "emotecraft:facepalm", "frustration or disbelief", listOf(NpcEmoteSurfaces.CONVERSATION, NpcEmoteSurfaces.WORLD_CHAT, NpcEmoteSurfaces.MICRO), listOf("frustrated", "confused", "disbelief"), 35, 70, 140),
        emote("shrug", "emotecraft:shrug", "uncertain or neutral reaction", listOf(NpcEmoteSurfaces.CONVERSATION, NpcEmoteSurfaces.WORLD_CHAT, NpcEmoteSurfaces.MICRO, NpcEmoteSurfaces.AMBIENT, NpcEmoteSurfaces.POKEMON), listOf("uncertain", "neutral", "confused"), 55, 60, 90),
        emote("time_check", "emotecraft:time-check", "waiting, work, or schedule awareness", listOf(NpcEmoteSurfaces.CONVERSATION, NpcEmoteSurfaces.WORLD_CHAT, NpcEmoteSurfaces.AMBIENT), listOf("waiting", "work", "schedule"), 40, 70, 120),
        emote("speaking", "emotecraft:speaking", "talking, explaining, or answering", listOf(NpcEmoteSurfaces.CONVERSATION, NpcEmoteSurfaces.WORLD_CHAT, NpcEmoteSurfaces.MICRO), listOf("talk", "speak", "explain", "answer", "neutral"), 85, 70, 0, loopWhileTalking = true),
        emote("head_scratches", "emotecraft:head_scratches", "thinking, awkwardness, or uncertainty", listOf(NpcEmoteSurfaces.CONVERSATION, NpcEmoteSurfaces.WORLD_CHAT, NpcEmoteSurfaces.MICRO, NpcEmoteSurfaces.AMBIENT, NpcEmoteSurfaces.POKEMON), listOf("thinking", "confused", "uncertain", "awkward"), 50, 80, 110),
        emote("hands_in_back", "emotecraft:hands_in_back", "calm formal attention", listOf(NpcEmoteSurfaces.CONVERSATION, NpcEmoteSurfaces.WORLD_CHAT, NpcEmoteSurfaces.MICRO, NpcEmoteSurfaces.AMBIENT), listOf("calm", "formal", "waiting", "polite", "duty"), 45, 90, 120),
        emote("threaten", "emotecraft:threaten", "warning, anger, or intimidation", listOf(NpcEmoteSurfaces.CONVERSATION, NpcEmoteSurfaces.WORLD_CHAT, NpcEmoteSurfaces.MICRO), listOf("angry", "warning", "hostile", "threat"), 18, 80, 180),
        emote("six_seven", "emotecraft:six_seven", "playful or silly reaction", listOf(NpcEmoteSurfaces.CONVERSATION, NpcEmoteSurfaces.WORLD_CHAT, NpcEmoteSurfaces.MICRO), listOf("playful", "silly", "joke", "confused"), 12, 70, 180),
        posture("sit", "emotecraft:sit", "simple seated ambient rest", 150),
        posture("criss_cross_sit", "emotecraft:criss_cross_sit", "cross-legged ambient rest", 160),
        posture("sit_cool", "emotecraft:sit-cool", "relaxed seated ambient rest", 150),
        posture("sit_cool_2", "emotecraft:sit-cool-2", "relaxed seated ambient rest variant", 150),
        posture("sit_cute", "emotecraft:sit-cute", "soft seated ambient rest", 150),
        posture("sit_lying_almost", "emotecraft:sit-lying-almost", "low seated ambient rest", 170),
        posture("sit_minimal", "emotecraft:sit-minimal", "small seated ambient rest", 140),
        posture("lay_on_back", "emotecraft:lay_on_back", "lying ambient rest", 180),
    ).map { emote -> emote.normalized() }

    private fun emote(
        id: String,
        animationId: String,
        description: String,
        surfaces: List<String>,
        tags: List<String>,
        weight: Int,
        durationTicks: Int,
        cooldownTicks: Int,
        loopWhileTalking: Boolean = false,
    ): NpcEmoteDefinition =
        NpcEmoteDefinition(id = id, animationId = animationId, description = description, surfaces = surfaces.toMutableList(), tags = tags.toMutableList(), weight = weight.toDouble(), durationTicks = durationTicks, cooldownTicks = cooldownTicks, loopWhileTalking = loopWhileTalking)

    private fun posture(id: String, animationId: String, description: String, durationTicks: Int): NpcEmoteDefinition =
        NpcEmoteDefinition(id = id, animationId = animationId, description = description, surfaces = mutableListOf(NpcEmoteSurfaces.AMBIENT_POSTURE), tags = mutableListOf("posture", "rest", "ambient"), weight = 25.0, durationTicks = durationTicks, cooldownTicks = 20 * 20, movementLock = true, posture = true, ambientOnly = true)
}

fun cleanNpcEmoteToken(value: String): String = value.trim().lowercase()
    .replace(Regex("[^a-z0-9_.:-]+"), "_")
    .replace(Regex("_+"), "_")
    .trim('_')
