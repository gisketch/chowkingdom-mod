package dev.gisketch.chowkingdom.randomtrainers

import java.util.Locale

class RandomTrainerSettings(
    var enabled: Boolean = true,
    var naturalSpawning: Boolean = true,
    var generatedCatalogSize: Int = 3000,
    var globalSpawnChance: Double = 0.85,
    var spawnIntervalTicks: Int = 180,
    var spawnIntervalTicksMaximum: Int = 2400,
    var despawnTicksIfUnseen: Int = 6000,
    var minHorizontalDistanceToPlayers: Int = 25,
    var maxHorizontalDistanceToPlayers: Int = 70,
    var maxVerticalDistanceToPlayers: Int = 30,
    var uniqueTrainerRadius: Int = 500,
    var maxTrainersPerPlayer: Int = 12,
    var maxTrainersTotal: Int = 60,
    var maxLevelDiff: Int = 25,
    var tierWeightSkew: Double = 0.45,
    var allowedDimensions: MutableList<String> = mutableListOf("minecraft:overworld"),
    var blockedDimensions: MutableList<String> = mutableListOf(),
    var battleFormat: String = "GEN_9_SINGLES",
    var maxItemUses: Int = 0,
    var healPlayers: Boolean = false,
    var llmDialogue: Boolean = true,
)

class RandomTrainerGenerationSeed(
    var names: MutableList<String> = mutableListOf(),
    var archetypes: MutableList<RandomTrainerArchetypeDefinition> = mutableListOf(),
) {
    fun normalized(): RandomTrainerGenerationSeed {
        names = names.map { it.trim() }.filter { it.isNotBlank() }.distinct().toMutableList()
        archetypes = archetypes.mapNotNull { it.normalized() }.toMutableList()
        return this
    }
}

class RandomTrainerArchetypeDefinition(
    var id: String = "",
    var title: String = "",
    var gender: String = "any",
    var region: String = "",
    var minLevel: Int = 1,
    var maxLevel: Int = 100,
    var tier: String = "",
    var spawnable: Boolean = true,
    var skinSet: String = "",
    var skinFolder: String = "",
    var height: Double = 1.0,
    var weight: Double = 1.0,
    var bustStyle: String = "",
    var dialogue: String = "",
    var species: MutableList<String> = mutableListOf(),
) {
    fun normalized(): RandomTrainerArchetypeDefinition? {
        id = cleanRandomTrainerId(id.ifBlank { title })
        title = title.trim().ifBlank { id.replace('_', ' ').titlecaseWords() }
        gender = normalizeTrainerGender(gender, title)
        region = region.trim()
        minLevel = minLevel.coerceIn(1, 100)
        maxLevel = maxLevel.coerceIn(minLevel, 100)
        tier = normalizeTrainerTier(tier, minLevel, maxLevel, title)
        skinSet = cleanRandomTrainerId(skinSet)
        skinFolder = cleanSkinPath(skinFolder.ifBlank { "${cleanRandomTrainerId(title)}/$gender" })
        height = normalizeTrainerBodyScale(height)
        weight = normalizeTrainerBodyScale(weight)
        bustStyle = if (gender == "female") cleanRandomTrainerId(bustStyle).ifBlank { "standard" } else ""
        dialogue = dialogue.trim().ifBlank { "I am a $title from ${region.ifBlank { "the road" }}. My team is ready for a clean battle." }
        species = species.map(::normalizeSpecies).filter { it.isNotBlank() }.distinct().toMutableList()
        return takeIf { id.isNotBlank() && species.isNotEmpty() }
    }
}

class RandomTrainerDefinition(
    var id: String = "",
    var name: String = "",
    var title: String = "",
    var gender: String = "any",
    var archetype: String = "",
    var region: String = "",
    var category: String = "",
    var source: String = "generated",
    var skinSet: String = "",
    var skinFolder: String = "",
    var tier: String = "",
    var spawnable: Boolean = true,
    var height: Double = 1.0,
    var weight: Double = 1.0,
    var bustStyle: String = "",
    var minLevel: Int = 1,
    var maxLevel: Int = 100,
    var team: MutableList<RandomTrainerPokemonDefinition> = mutableListOf(),
    var dialogue: MutableList<String> = mutableListOf(),
) {
    fun normalized(fallbackId: String = id): RandomTrainerDefinition {
        id = cleanRandomTrainerId(id.ifBlank { fallbackId })
        name = name.trim().ifBlank { title.trim().ifBlank { id.replace('_', ' ').titlecaseWords() } }
        title = title.trim().ifBlank { archetype.trim().ifBlank { "Trainer" } }
        gender = normalizeTrainerGender(gender, title)
        archetype = cleanRandomTrainerId(archetype.ifBlank { title })
        region = region.trim()
        category = cleanRandomTrainerId(category.ifBlank { categoryForTitle(title) })
        source = source.trim().ifBlank { "generated" }
        skinSet = cleanRandomTrainerId(skinSet)
        skinFolder = cleanSkinPath(skinFolder.ifBlank { "${cleanRandomTrainerId(title)}/$gender" })
        minLevel = minLevel.coerceIn(1, 100)
        maxLevel = maxLevel.coerceIn(minLevel, 100)
        tier = normalizeTrainerTier(tier, minLevel, maxLevel, title)
        spawnable = spawnable && !isUniqueTrainerTitle(title)
        height = normalizeTrainerBodyScale(height)
        weight = normalizeTrainerBodyScale(weight)
        bustStyle = if (gender == "female") cleanRandomTrainerId(bustStyle).ifBlank { "standard" } else ""
        team = team.mapNotNull { pokemon -> pokemon.normalized() }.toMutableList()
        dialogue = dialogue.map { it.trim() }.filter { it.isNotBlank() }.take(12).toMutableList()
        return this
    }

    fun displayName(): String = name.ifBlank { title.ifBlank { id } }

    fun skinPath(): String = skinSet.ifBlank { skinFolder }
}

class RandomTrainerPokemonDefinition(
    var species: String = "",
    var level: Int = 5,
    var gender: String = "GENDERLESS",
    var nature: String = "",
    var ability: String = "",
    var moveset: MutableList<String> = mutableListOf(),
    var heldItem: String = "",
    var shiny: Boolean = false,
    var aspects: MutableList<String> = mutableListOf(),
) {
    fun normalized(): RandomTrainerPokemonDefinition? {
        species = normalizeSpecies(species)
        if (species.isBlank()) return null
        level = level.coerceIn(1, 100)
        gender = gender.trim().uppercase(Locale.ROOT).ifBlank { "GENDERLESS" }
        nature = nature.trim()
        ability = ability.trim()
        moveset = moveset.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct().take(4).toMutableList()
        heldItem = heldItem.trim()
        aspects = aspects.map { it.trim() }.filter { it.isNotBlank() }.distinct().toMutableList()
        return this
    }
}

class RandomTrainerCatalogStats(
    var trainerCount: Int = 0,
    var importedCount: Int = 0,
    var generatedCount: Int = 0,
    var invalidCount: Int = 0,
    var spawnableCount: Int = 0,
)

class RandomTrainerWorldState(
    var players: MutableMap<String, RandomTrainerPlayerState> = linkedMapOf(),
    var activeBattles: MutableMap<String, RandomTrainerBattleContextState> = linkedMapOf(),
)

class RandomTrainerPlayerState(
    var defeated: MutableSet<String> = linkedSetOf(),
    var encounters: MutableMap<String, Int> = linkedMapOf(),
    var wins: Int = 0,
    var losses: Int = 0,
    var lastResultAtMs: Long = 0L,
)

class RandomTrainerBattleContextState(
    var playerUuid: String = "",
    var playerName: String = "",
    var entityUuid: String = "",
    var instanceId: String = "",
    var rosterId: String = "",
    var trainerName: String = "",
)

internal fun cleanRandomTrainerId(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9_.:-]+"), "_")
    .trim('_')

internal fun cleanSkinPath(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .replace('\\', '/')
    .split('/')
    .map(::cleanRandomTrainerId)
    .filter(String::isNotBlank)
    .joinToString("/")

internal fun cleanDimensionId(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9_.:/-]+"), "_")
    .trim('_')

internal fun normalizeTrainerTier(value: String, minLevel: Int, maxLevel: Int, title: String): String {
    val clean = cleanRandomTrainerId(value)
    if (clean in setOf("low", "mid", "high", "very_high", "unique")) return clean
    if (isUniqueTrainerTitle(title)) return "unique"
    val center = (minLevel + maxLevel) / 2
    return when {
        center < 20 -> "low"
        center < 45 -> "mid"
        center < 70 -> "high"
        else -> "very_high"
    }
}

internal fun normalizeTrainerBodyScale(value: Double): Double =
    value.takeIf { it.isFinite() && it in 0.6..1.4 } ?: 1.0

internal fun isBlockedWildTrainerTitle(title: String): Boolean {
    val clean = cleanRandomTrainerId(title)
    val parts = clean.split('_').filter(String::isNotBlank).toSet()
    return listOf(
        "aaron",
        "agatha",
        "bertha",
        "blaine",
        "blue",
        "bruno",
        "buck",
        "bugsy",
        "byron",
        "candice",
        "cheryl",
        "clair",
        "cynthia",
        "crasher_wake",
        "dawn",
        "erika",
        "fantina",
        "flannery",
        "flint",
        "gardenia",
        "giovanni",
        "glacia",
        "grimsley",
        "juan",
        "jupiter",
        "koga",
        "lance",
        "liza",
        "lorelei",
        "lucas",
        "lucian",
        "maylene",
        "maxie",
        "misty",
        "norman",
        "phoebe",
        "pryce",
        "roark",
        "roxanne",
        "sabrina",
        "sidney",
        "steven",
        "tate",
        "thorton",
        "volkner",
        "wallace",
        "whitney",
        "winona",
        "rival",
        "leader",
        "gym_leader",
        "elite_four",
        "champion",
    ).any { token ->
        clean == token ||
            token in parts ||
            parts.any { part -> part == token || part.matches(Regex("${Regex.escape(token)}\\d+")) } ||
            (token.contains('_') && clean.contains(token))
    }
}

internal fun isMultiTrainerDefinition(title: String, name: String): Boolean {
    val titleId = cleanRandomTrainerId(title)
    val nameId = cleanRandomTrainerId(name)
    if (Regex("\\b(and|&)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) return true
    return listOf(
        "cool_couple",
        "crush_kin",
        "double_team",
        "interviewer",
        "interviewers",
        "old_couple",
        "sis_and_bro",
        "sr_and_jr",
        "twins",
        "young_couple",
    ).any { token ->
        titleId == token || titleId.contains(token) || nameId == token || nameId.contains(token)
    }
}

internal fun isUniqueTrainerTitle(title: String): Boolean {
    val clean = cleanRandomTrainerId(title)
    val parts = clean.split('_').filter(String::isNotBlank).toSet()
    return listOf(
        "rival",
        "leader",
        "gym_leader",
        "elite_four",
        "champion",
        "professor",
        "prof",
        "tower_tycoon",
        "frontier_brain",
        "commander",
        "boss",
        "admin",
        "executive",
        "red",
        "blue",
        "cynthia",
        "lance",
        "giovanni",
        "steven",
        "wallace",
    ).any { token ->
        clean == token || token in parts || (token.contains('_') && clean.contains(token))
    }
}

internal fun normalizeTrainerGender(value: String, title: String): String {
    val clean = value.trim().lowercase(Locale.ROOT)
    if (clean in setOf("male", "female")) return clean
    val titleId = cleanRandomTrainerId(title)
    return when {
        titleId.endsWith("_f") || titleId.endsWith("_female") || titleId.contains("girl") || titleId.contains("lady") || titleId.contains("lass") || titleId.contains("beauty") || titleId.contains("picnicker") || titleId.contains("waitress") -> "female"
        titleId.endsWith("_m") || titleId.endsWith("_male") || titleId.contains("boy") || titleId.contains("gentleman") || titleId.contains("black_belt") || titleId.contains("hiker") || titleId.contains("sailor") || titleId.contains("waiter") -> "male"
        else -> "any"
    }
}

internal fun categoryForTitle(title: String): String {
    val clean = cleanRandomTrainerId(title)
    return when {
        isUniqueTrainerTitle(title) -> "unique"
        listOf("rocket", "magma", "aqua", "galactic", "plasma", "flare", "skull", "yell", "star", "grunt").any { clean.contains(it) } -> "team"
        listOf("frontier", "tower", "factory", "arcade", "castle").any { clean.contains(it) } -> "battle_facility"
        listOf("bug", "bird", "fisher", "swimmer", "hiker", "black_belt", "psychic").any { clean.contains(it) } -> "specialist"
        else -> "route_trainer"
    }
}

internal fun normalizeSpecies(value: String): String {
    val clean = value.trim().lowercase(Locale.ROOT).replace(' ', '_')
    if (clean.isBlank()) return ""
    return if (clean.contains(':')) clean else "cobblemon:$clean"
}

private fun String.titlecaseWords(): String = split(Regex("[_\\s]+"))
    .filter { it.isNotBlank() }
    .joinToString(" ") { word -> word.replaceFirstChar { char -> char.titlecase(Locale.ROOT) } }
