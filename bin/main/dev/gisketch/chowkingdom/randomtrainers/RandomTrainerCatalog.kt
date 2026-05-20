package dev.gisketch.chowkingdom.randomtrainers

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.config.TomlConfigIO
import net.minecraft.server.level.ServerPlayer
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.math.abs
import kotlin.random.Random

object RandomTrainerCatalog {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private var loaded = false
    private var settings = RandomTrainerSettings()
    private var trainers: List<RandomTrainerDefinition> = emptyList()
    private var stats = RandomTrainerCatalogStats()

    private val root: Path
        get() = FMLPaths.CONFIGDIR.get().resolve(ChowKingdomMod.MOD_ID).resolve("random_trainers")
    private val catalogDir: Path
        get() = root.resolve("catalog")
    private val settingsFile: Path
        get() = root.resolve("settings.toml")
    private val generationSeedFile: Path
        get() = root.resolve("generation_seed.toml")

    fun settings(): RandomTrainerSettings {
        ensureLoaded()
        return settings
    }

    fun stats(): RandomTrainerCatalogStats {
        ensureLoaded()
        return stats
    }

    fun all(): List<RandomTrainerDefinition> {
        ensureLoaded()
        return trainers
    }

    fun byId(id: String): RandomTrainerDefinition? {
        ensureLoaded()
        return trainers.firstOrNull { it.id == cleanRandomTrainerId(id) }
    }

    fun reload(): Int {
        loaded = false
        load()
        return trainers.size
    }

    fun load() {
        root.createDirectories()
        catalogDir.createDirectories()
        if (!settingsFile.exists()) TomlConfigIO.write(settingsFile, RandomTrainerSettings())
        settings = TomlConfigIO.read(settingsFile, RandomTrainerSettings::class.java, ::RandomTrainerSettings)
        settings.allowedDimensions = settings.allowedDimensions.map(::cleanDimensionId).filter(String::isNotBlank).distinct().toMutableList()
        settings.blockedDimensions = settings.blockedDimensions.map(::cleanDimensionId).filter(String::isNotBlank).distinct().toMutableList()
        TomlConfigIO.write(settingsFile, settings)
        ensureDefaultGenerationSeed()
        val seed = TomlConfigIO.read(generationSeedFile, RandomTrainerGenerationSeed::class.java, ::RandomTrainerGenerationSeed).normalized()
        val imported = loadConfiguredDefinitions()
        val importedById = linkedMapOf<String, RandomTrainerDefinition>()
        var invalid = 0
        imported.forEach { definition ->
            val normalized = definition.normalized()
            if (normalized.id.isBlank() || normalized.team.isEmpty()) {
                invalid += 1
            } else if (isBlockedWildTrainerTitle(normalized.title)) {
                invalid += 1
            } else if (isMultiTrainerDefinition(normalized.title, normalized.name)) {
                invalid += 1
            } else {
                importedById[normalized.id] = normalized
            }
        }
        val generatedById = linkedMapOf<String, RandomTrainerDefinition>()
        val generatedSize = generatedCatalogSize()
        generatedDefaults(generatedSize, seed).forEach { generated ->
            if (importedById.size + generatedById.size >= generatedSize) return@forEach
            if (isBlockedWildTrainerTitle(generated.title)) return@forEach
            if (isMultiTrainerDefinition(generated.title, generated.name)) return@forEach
            if (generated.id !in importedById) generatedById[generated.id] = generated
        }
        trainers = (importedById.values + generatedById.values).sortedBy { it.id }
        stats = RandomTrainerCatalogStats(
            trainerCount = trainers.size,
            importedCount = importedById.size,
            generatedCount = generatedById.size,
            invalidCount = invalid,
            spawnableCount = trainers.count { it.spawnable },
        )
        loaded = true
    }

    fun pickFor(player: ServerPlayer, topLevel: Int, excludedDefeated: Set<String>): RandomTrainerDefinition? {
        ensureLoaded()
        val spawnable = trainers.filter { it.spawnable }
        if (spawnable.isEmpty()) return null
        val exclusion = if (excludedDefeated.size >= spawnable.size) emptySet() else excludedDefeated
        val maxDiff = settings.maxLevelDiff.coerceAtLeast(1)
        val level = topLevel.coerceIn(1, 100)
        val candidates = spawnable.filter { trainer ->
            trainer.id !in exclusion && level in (trainer.minLevel - maxDiff)..(trainer.maxLevel + maxDiff)
        }.ifEmpty { spawnable.filter { it.id !in exclusion } }.ifEmpty { spawnable }
        val weights = candidates.map { trainer ->
            val center = (trainer.minLevel + trainer.maxLevel) / 2
            val levelWeight = (maxDiff + 10 - abs(center - level)).coerceAtLeast(1).toDouble()
            (levelWeight * tierWeight(level, trainer.tier)).toInt().coerceAtLeast(1)
        }
        val total = weights.sum().coerceAtLeast(1)
        var roll = player.random.nextInt(total)
        candidates.forEachIndexed { index, trainer ->
            roll -= weights[index]
            if (roll < 0) return trainer
        }
        return candidates.lastOrNull()
    }

    fun spawnSuggestions(): List<String> {
        ensureLoaded()
        return trainers.map { it.id }.sorted()
    }

    fun titleGenderPairs(): List<RandomTrainerTitleGenderSummary> {
        ensureLoaded()
        return trainers
            .groupingBy { RandomTrainerTitleGenderKey(it.title, it.gender, it.skinFolder) }
            .eachCount()
            .map { (key, count) -> RandomTrainerTitleGenderSummary(key.title, key.gender, key.skinFolder, count) }
            .sortedWith(compareBy<RandomTrainerTitleGenderSummary> { it.title }.thenBy { it.gender }.thenBy { it.skinFolder })
    }

    fun scaledTrainerJson(definition: RandomTrainerDefinition, topLevel: Int): JsonObject {
        val level = topLevel.coerceIn(definition.minLevel, definition.maxLevel).coerceIn(1, 100)
        val team = scaledTeam(definition, level)
        return JsonObject().apply {
            addProperty("name", definition.displayName())
            add("ai", JsonObject().apply { addProperty("type", "rct") })
            add("bag", JsonArray())
            addProperty("battleTheme", "")
            add("team", JsonArray().apply { team.forEach(::add) })
        }
    }

    fun importRct(source: Path): Int {
        ensureLoaded()
        if (!source.exists()) return 0
        val targetDir = catalogDir.resolve("imported_rct")
        targetDir.createDirectories()
        val files = if (Files.isDirectory(source)) {
            Files.walk(source).use { stream ->
                stream.filter { path -> Files.isRegularFile(path) && path.extension.equals("json", ignoreCase = true) }.toList()
            }
        } else {
            listOf(source).filter { it.extension.equals("json", ignoreCase = true) }
        }
        var imported = 0
        files.forEach { file ->
            val rootJson = runCatching { file.bufferedReader().use(JsonParser::parseReader).asJsonObject }.getOrNull() ?: return@forEach
            val relative = if (Files.isDirectory(source)) source.relativize(file).toString() else file.fileName.toString()
            val definition = convertRctTrainer(rootJson, relative) ?: return@forEach
            val target = targetDir.resolve("${definition.id}.toml")
            TomlConfigIO.write(target, definition)
            imported += 1
        }
        reload()
        return imported
    }

    private fun ensureLoaded() {
        if (!loaded) load()
    }

    private fun generatedCatalogSize(): Int {
        return settings.generatedCatalogSize.coerceAtLeast(0)
    }

    private fun ensureDefaultGenerationSeed() {
        if (generationSeedFile.exists()) return
        val seed = javaClass.classLoader
            .getResourceAsStream("data/${ChowKingdomMod.MOD_ID}/random_trainers/default_generation_seed.json")
            ?.bufferedReader()
            ?.use { reader -> gson.fromJson(reader, RandomTrainerGenerationSeed::class.java) }
            ?: RandomTrainerGenerationSeed()
        TomlConfigIO.write(generationSeedFile, seed)
    }

    private fun loadConfiguredDefinitions(): List<RandomTrainerDefinition> {
        val loadedDefinitions = mutableListOf<RandomTrainerDefinition>()
        Files.walk(catalogDir).use { stream ->
            stream.filter { path -> Files.isRegularFile(path) && path.extension.lowercase() in setOf("toml", "json") }
                .forEach { path ->
                    val definition = runCatching {
                        TomlConfigIO.read(path, RandomTrainerDefinition::class.java, ::RandomTrainerDefinition)
                            .normalized(path.nameWithoutExtension)
                    }.getOrNull()
                    if (definition != null) loadedDefinitions += definition
                }
        }
        return loadedDefinitions
    }

    private fun scaledTeam(definition: RandomTrainerDefinition, targetLevel: Int): List<JsonObject> {
        val desiredSize = when {
            targetLevel < 10 -> 2
            targetLevel < 25 -> 3
            targetLevel < 45 -> 4
            targetLevel < 70 -> 5
            else -> 6
        }.coerceAtMost(definition.team.size.coerceAtLeast(1))
        return definition.team.take(desiredSize).mapIndexed { index, pokemon ->
            pokemonJson(pokemon, (targetLevel + index - desiredSize / 2).coerceIn(1, 100))
        }
    }

    private fun pokemonJson(pokemon: RandomTrainerPokemonDefinition, level: Int): JsonObject = JsonObject().apply {
        addProperty("species", normalizeSpecies(pokemon.species))
        addProperty("gender", pokemon.gender.ifBlank { "GENDERLESS" })
        addProperty("level", level.coerceIn(1, 100))
        addProperty("nature", pokemon.nature)
        addProperty("ability", pokemon.ability)
        add("moveset", JsonArray().apply { pokemon.moveset.forEach(::add) })
        add("ivs", statsJson(31, 31, 31, 31, 31, 31))
        add("evs", statsJson(0, 0, 0, 0, 0, 0))
        addProperty("shiny", pokemon.shiny)
        addProperty("heldItem", pokemon.heldItem)
        add("aspects", JsonArray().apply { pokemon.aspects.forEach(::add) })
    }

    private fun statsJson(hp: Int, atk: Int, def: Int, spa: Int, spd: Int, spe: Int): JsonObject = JsonObject().apply {
        addProperty("hp", hp)
        addProperty("atk", atk)
        addProperty("def", def)
        addProperty("spa", spa)
        addProperty("spd", spd)
        addProperty("spe", spe)
    }

    private fun convertRctTrainer(root: JsonObject, relativePath: String): RandomTrainerDefinition? {
        val team = root.getAsJsonArray("team") ?: return null
        val pokemon = team.mapNotNull { element ->
            val obj = element.asJsonObject
            val species = obj.string("species")
            if (species.isBlank()) null else RandomTrainerPokemonDefinition(
                species = species,
                level = obj.int("level", 5),
                gender = obj.string("gender").ifBlank { "GENDERLESS" },
                nature = obj.string("nature"),
                ability = obj.string("ability"),
                moveset = obj.arrayStrings("moveset").toMutableList(),
                heldItem = obj.string("heldItem"),
                shiny = obj.boolean("shiny"),
                aspects = obj.arrayStrings("aspects").toMutableList(),
            ).normalized()
        }
        if (pokemon.isEmpty()) return null
        val baseId = cleanRandomTrainerId(relativePath.removeSuffix(".json"))
        val name = textValue(root["name"]).ifBlank { baseId.replace('_', ' ') }
        val parentTitle = relativePath.split('\\', '/').dropLast(1).lastOrNull()?.replace('_', ' ').orEmpty()
        val title = parentTitle.ifBlank { inferTitleFromName(name) }
        if (isBlockedWildTrainerTitle(title)) return null
        if (isMultiTrainerDefinition(title, name)) return null
        val gender = normalizeTrainerGender("any", title)
        return RandomTrainerDefinition(
            id = "rct_$baseId",
            name = name,
            title = title,
            gender = gender,
            archetype = title,
            source = "rct_import",
            skinFolder = "${cleanRandomTrainerId(title)}/$gender",
            tier = normalizeTrainerTier("", pokemon.minOf { it.level }, pokemon.maxOf { it.level }, title),
            spawnable = !isUniqueTrainerTitle(title),
            minLevel = pokemon.minOf { it.level }.coerceIn(1, 100),
            maxLevel = pokemon.maxOf { it.level }.coerceIn(1, 100),
            team = pokemon.toMutableList(),
            dialogue = mutableListOf("I have trained this team for the road. Challenge me if you are ready."),
        ).normalized("rct_$baseId")
    }

    private fun textValue(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonPrimitive) return element.asString
        if (!element.isJsonObject) return ""
        val obj = element.asJsonObject
        return obj.string("text").ifBlank { obj.string("literal") }.ifBlank { obj.string("key") }
    }

    private fun JsonObject.string(key: String): String = get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.int(key: String, fallback: Int): Int = get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt ?: fallback

    private fun JsonObject.boolean(key: String): Boolean = get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: false

    private fun JsonObject.arrayStrings(key: String): List<String> {
        val array = getAsJsonArray(key) ?: return emptyList()
        return array.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotBlank) }
    }

    private fun generatedDefaults(target: Int, seed: RandomTrainerGenerationSeed): List<RandomTrainerDefinition> {
        val archetypes = seed.archetypes
        val names = seed.names
        if (target <= 0 || archetypes.isEmpty() || names.isEmpty()) return emptyList()
        val result = ArrayList<RandomTrainerDefinition>(target)
        var index = 0
        while (result.size < target) {
            val archetype = archetypes[index % archetypes.size]
            val name = names[(index / archetypes.size) % names.size]
            val variant = index / (archetypes.size * names.size)
            val displayName = if (variant == 0) "${archetype.title} $name" else "${archetype.title} $name ${variant + 1}"
            val id = cleanRandomTrainerId("${archetype.id}_${name}_${variant + 1}")
            result += RandomTrainerDefinition(
                id = id,
                name = displayName,
                title = archetype.title,
                gender = archetype.gender,
                archetype = archetype.id,
                region = archetype.region,
            source = "generated_lore",
                skinSet = archetype.skinSet,
                skinFolder = archetype.skinFolder,
                tier = archetype.tier,
                spawnable = archetype.spawnable,
                height = archetype.height,
                weight = archetype.weight,
                bustStyle = archetype.bustStyle,
                minLevel = archetype.minLevel,
                maxLevel = archetype.maxLevel,
                team = generatedTeam(archetype, index).toMutableList(),
                dialogue = mutableListOf(archetype.dialogue),
            ).normalized(id)
            index += 1
        }
        return result
    }

    private fun generatedTeam(archetype: RandomTrainerArchetypeDefinition, index: Int): List<RandomTrainerPokemonDefinition> {
        val random = Random(archetype.id.hashCode() * 31 + index)
        val pool = archetype.species
        val size = 6.coerceAtMost(pool.size)
        return List(size) { slot ->
            RandomTrainerPokemonDefinition(
                species = pool[(slot + random.nextInt(pool.size)) % pool.size],
                level = (archetype.minLevel + slot * 2).coerceIn(1, archetype.maxLevel),
                moveset = mutableListOf(),
            ).normalized()!!
        }
    }

    private fun tierWeight(playerLevel: Int, tier: String): Double {
        val target = when {
            playerLevel < 20 -> 0
            playerLevel < 45 -> 1
            playerLevel < 70 -> 2
            else -> 3
        }
        val value = when (tier) {
            "low" -> 0
            "mid" -> 1
            "high" -> 2
            "very_high" -> 3
            else -> 4
        }
        if (value == 4) return 0.05
        val skew = settings.tierWeightSkew.coerceIn(0.0, 1.0)
        return (1.0 - abs(target - value) * skew).coerceAtLeast(0.1)
    }

    private fun inferTitleFromName(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.size < 2) return "RCT Trainer"
        return words.dropLast(1).joinToString(" ").ifBlank { "RCT Trainer" }
    }
}

class RandomTrainerTitleGenderSummary(
    val title: String,
    val gender: String,
    val skinFolder: String,
    val count: Int,
)

private data class RandomTrainerTitleGenderKey(
    val title: String,
    val gender: String,
    val skinFolder: String,
)
