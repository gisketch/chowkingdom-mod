package dev.gisketch.chowkingdom.shops

import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.structure.Structure
import java.util.Locale

object ExplorerTargetCatalog {
    fun biomeIds(server: MinecraftServer): List<String> =
        server.registryAccess().registryOrThrow(Registries.BIOME).keySet().map(ResourceLocation::toString).sorted()

    fun structureIds(server: MinecraftServer): List<String> =
        server.registryAccess().registryOrThrow(Registries.STRUCTURE).keySet().map(ResourceLocation::toString).sorted()

    fun biomeReportLines(server: MinecraftServer): List<String> = reportLines("biomes", biomeIds(server), biomeCategoryMap(server))

    fun structureReportLines(server: MinecraftServer): List<String> = reportLines("structures", structureIds(server), structureCategoryMap(server))

    fun combinedReportLines(server: MinecraftServer): List<String> =
        biomeReportLines(server) + listOf("") + structureReportLines(server)

    fun biomeCategoryIds(server: MinecraftServer): List<String> =
        biomeCategoryMap(server).keys.sorted()

    fun structureCategoryIds(server: MinecraftServer): List<String> =
        structureCategoryMap(server).keys.sorted()

    fun resolveBiomeTargetIds(level: ServerLevel, rawTargets: List<String>): List<ResourceLocation> {
        val registry = level.registryAccess().registryOrThrow(Registries.BIOME)
        val ids = registry.keySet().map(ResourceLocation::toString).toSet()
        val categories = biomeCategoryMap(level.server)
        return cleanSpecs(rawTargets).flatMap { spec ->
            when {
                spec == "all" -> ids
                spec.startsWith("category:") -> categories[spec.removePrefix("category:")]?.targetIds.orEmpty()
                spec.startsWith("namespace:") -> ids.filter { id -> id.substringBefore(':') == spec.removePrefix("namespace:") }
                spec.startsWith("mod:") -> ids.filter { id -> id.substringBefore(':') == spec.removePrefix("mod:") }
                spec.startsWith("#") -> {
                    val tagId = parseLocation(spec.removePrefix("#")) ?: return@flatMap emptyList()
                    registry.getTag(TagKey.create(Registries.BIOME, tagId))
                        .map { tag -> tag.toList().mapNotNull { holder -> holder.idString() } }
                        .orElse(emptyList())
                }
                spec in ids -> listOf(spec)
                else -> emptyList()
            }
        }.distinct().sorted().mapNotNull(::parseLocation)
    }

    fun resolveStructureTargetIds(level: ServerLevel, rawTargets: List<String>): List<ResourceLocation> {
        val registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
        val ids = registry.keySet().map(ResourceLocation::toString).toSet()
        val categories = structureCategoryMap(level.server)
        return cleanSpecs(rawTargets).flatMap { spec ->
            when {
                spec == "all" -> ids
                spec.startsWith("category:") -> categories[spec.removePrefix("category:")]?.targetIds.orEmpty()
                spec.startsWith("namespace:") -> ids.filter { id -> id.substringBefore(':') == spec.removePrefix("namespace:") }
                spec.startsWith("mod:") -> ids.filter { id -> id.substringBefore(':') == spec.removePrefix("mod:") }
                spec.startsWith("#") -> {
                    val tagId = parseLocation(spec.removePrefix("#")) ?: return@flatMap emptyList()
                    registry.getTag(TagKey.create(Registries.STRUCTURE, tagId))
                        .map { tag -> tag.toList().mapNotNull { holder -> holder.idString() } }
                        .orElse(emptyList())
                }
                spec in ids -> listOf(spec)
                else -> emptyList()
            }
        }.distinct().sorted().mapNotNull(::parseLocation)
    }

    fun structureHolders(level: ServerLevel, rawTargets: List<String>): List<Holder<Structure>> {
        val registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
        return resolveStructureTargetIds(level, rawTargets).mapNotNull { id ->
            registry.getHolder(ResourceKey.create(Registries.STRUCTURE, id)).orElse(null)
        }
    }

    fun biomeMatches(holder: Holder<Biome>, rawTargets: List<String>, level: ServerLevel): Boolean {
        val allowed = resolveBiomeTargetIds(level, rawTargets).map(ResourceLocation::toString).toSet()
        return holder.idString() in allowed
    }

    fun biomeCategoryMap(server: MinecraftServer): Map<String, ExplorerCategory> {
        val registry = server.registryAccess().registryOrThrow(Registries.BIOME)
        return buildCategoryMap(
            ids = registry.keySet().map(ResourceLocation::toString).sorted(),
            tagSources = { id ->
                val key = parseLocation(id)?.let { ResourceKey.create(Registries.BIOME, it) } ?: return@buildCategoryMap emptyList()
                registry.getHolder(key).orElse(null)?.categoryTags().orEmpty()
            },
            fallback = ::biomeCategories,
        )
    }

    fun structureCategoryMap(server: MinecraftServer): Map<String, ExplorerCategory> {
        val registry = server.registryAccess().registryOrThrow(Registries.STRUCTURE)
        return buildCategoryMap(
            ids = registry.keySet().map(ResourceLocation::toString).sorted(),
            tagSources = { id ->
                val key = parseLocation(id)?.let { ResourceKey.create(Registries.STRUCTURE, it) } ?: return@buildCategoryMap emptyList()
                registry.getHolder(key).orElse(null)?.categoryTags().orEmpty()
            },
            fallback = ::structureCategories,
        )
    }

    fun biomeCategories(id: String): Set<String> {
        val namespace = id.substringBefore(':', "minecraft").lowercase(Locale.ROOT)
        val path = id.substringAfter(':', id).lowercase(Locale.ROOT)
        return buildSet {
            add("namespace:$namespace")
            if (namespace != "minecraft") add("modded")
            if (path.contains("ocean") || path.contains("beach") || path.contains("river")) add("water")
            if (path.contains("frozen") || path.contains("snow") || path.contains("ice") || path.contains("tundra")) add("cold")
            if (path.contains("desert") || path.contains("badlands") || path.contains("savanna")) add("hot")
            if (path.contains("forest") || path.contains("woods") || path.contains("grove") || path.contains("taiga")) add("forest")
            if (path.contains("jungle") || path.contains("bamboo")) add("jungle")
            if (path.contains("swamp") || path.contains("marsh")) add("swamp")
            if (path.contains("mountain") || path.contains("peak") || path.contains("slope") || path.contains("cliff")) add("mountain")
            if (path.contains("plains") || path.contains("meadow")) add("plains")
            if (path.contains("cave") || path.contains("deep_dark") || path.contains("dripstone") || path.contains("lush")) add("cave")
            if (path.contains("nether") || path.contains("crimson") || path.contains("warped") || path.contains("basalt") || path.contains("soul_sand")) add("nether")
            if (path.contains("end") || path.contains("chorus")) add("end")
            if (path.contains("cherry") || path.contains("mushroom") || path.contains("deep_dark") || path.contains("eroded")) add("rare")
        }
    }

    fun structureCategories(id: String): Set<String> {
        val namespace = id.substringBefore(':', "minecraft").lowercase(Locale.ROOT)
        val path = id.substringAfter(':', id).lowercase(Locale.ROOT)
        return buildSet {
            add("namespace:$namespace")
            if (namespace != "minecraft") add("modded")
            if (path.contains("village")) add("village")
            if (path.contains("dungeon") || path.contains("spawner")) add("dungeon")
            if (path.contains("temple") || path.contains("pyramid") || path.contains("hut")) add("temple")
            if (path.contains("ruin") || path.contains("trail")) add("ruins")
            if (path.contains("mineshaft")) add("mineshaft")
            if (path.contains("trial")) add("trial")
            if (path.contains("mansion")) add("mansion")
            if (path.contains("monument")) add("monument")
            if (path.contains("fortress") || path.contains("bastion")) add("nether")
            if (path.contains("ancient_city") || path.contains("stronghold") || path.contains("end_city")) add("rare")
            if (path.contains("shipwreck") || path.contains("ocean")) add("water")
        }
    }

    private fun reportLines(title: String, ids: List<String>, categories: Map<String, ExplorerCategory>): List<String> = buildList {
        add("$title:")
        ids.forEach { add("- $it") }
        add("")
        add("${title}_categories:")
        categories.toSortedMap().forEach { (category, value) ->
            val sourceText = value.sourceTags.sorted().joinToString(", ").ifBlank { "fallback" }
            add("[$category] ${value.targetIds.size} source=$sourceText")
            value.targetIds.sorted().forEach { add("- $it") }
        }
    }

    private fun buildCategoryMap(ids: List<String>, tagSources: (String) -> List<String>, fallback: (String) -> Set<String>): Map<String, ExplorerCategory> {
        val targetsByCategory = linkedMapOf<String, MutableSet<String>>()
        val sourcesByCategory = linkedMapOf<String, MutableSet<String>>()
        ids.forEach { id ->
            val tagPairs = tagSources(id).mapNotNull { tag ->
                val category = normalizeCategoryTag(tag) ?: return@mapNotNull null
                category to tag
            }
            val pairs = tagPairs.ifEmpty { fallback(id).map { category -> category to "fallback" } }
            pairs.forEach { (category, source) ->
                targetsByCategory.getOrPut(category) { linkedSetOf() } += id
                sourcesByCategory.getOrPut(category) { linkedSetOf() } += source
            }
        }
        return targetsByCategory.mapValues { (category, targets) ->
            ExplorerCategory(category, sourcesByCategory[category].orEmpty().toSet(), targets.sorted())
        }
    }

    private fun cleanSpecs(values: List<String>): List<String> =
        values.map { value -> value.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank).distinct()

    private fun parseLocation(raw: String): ResourceLocation? = runCatching { ResourceLocation.parse(raw) }.getOrNull()

    private fun normalizeCategoryTag(raw: String): String? {
        val location = parseLocation(raw) ?: return null
        val path = location.path.substringAfterLast('/')
            .removePrefix("is_")
            .removePrefix("has_")
            .removePrefix("in_")
            .removePrefix("on_")
            .removeSuffix("_biomes")
            .removeSuffix("_structures")
            .removeSuffix("_structure")
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
        return path.ifBlank { null }
    }

    private fun <T> Holder<T>.categoryTags(): List<String> =
        tags().map { tag -> tag.location().toString() }.toList()

    private fun <T> Holder<T>.idString(): String? =
        unwrapKey().map { key -> key.location().toString() }.orElseGet { runCatching { getRegisteredName() }.getOrDefault("") }.ifBlank { null }
}

data class ExplorerCategory(val id: String, val sourceTags: Set<String>, val targetIds: List<String>)
