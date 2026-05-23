package dev.gisketch.chowkingdom.config

import com.electronwill.nightconfig.core.UnmodifiableConfig
import com.electronwill.nightconfig.core.file.FileConfig
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.gisketch.chowkingdom.ChowKingdomMod
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

object TomlConfigIO {
    private const val BACKUP_DIR = "json-backup"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun migrateModConfigTree() {
        val root = FMLPaths.CONFIGDIR.get().resolve(ChowKingdomMod.MOD_ID)
        if (!root.exists()) return
        val paths = mutableListOf<Path>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root && isConfigBackupDirectory(dir)) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    paths.add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                    ChowKingdomMod.LOGGER.warn("Skipping unreadable config path during migration: {}", file, exc)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        paths.asSequence()
            .filter { path -> Files.isRegularFile(path) }
            .filter { path -> path.extension.equals("json", ignoreCase = true) }
            .sortedByDescending { path -> path.toString() }
            .forEach { jsonPath ->
                val tomlPath = jsonPath.parent.resolve("${jsonPath.nameWithoutExtension}.toml")
                migrateJson(jsonPath, tomlPath, root, headerFor(tomlPath))
            }
    }

    fun <T> read(path: Path, type: Class<T>, fallback: () -> T, header: String = headerFor(path), comments: Map<String, String> = emptyMap()): T {
        migrateLegacyFor(path, header, comments)
        return try {
            if (!path.exists()) return fallback()
            val element = readElement(path)
            gson.fromJson(element, type) ?: fallback()
        } catch (exception: Exception) {
            ChowKingdomMod.LOGGER.warn("Failed to load TOML config {}", path, exception)
            fallback()
        }
    }

    fun readObject(path: Path, header: String = headerFor(path), comments: Map<String, String> = emptyMap()): JsonObject? {
        migrateLegacyFor(path, header, comments)
        if (!path.exists()) return null
        return readElement(path).takeIf { it.isJsonObject }?.asJsonObject
    }

    fun write(path: Path, value: Any, header: String = headerFor(path), comments: Map<String, String> = emptyMap()) {
        writeElement(path, gson.toJsonTree(value), header, comments)
    }

    fun writeJson(path: Path, json: String, header: String = headerFor(path), comments: Map<String, String> = emptyMap()) {
        writeElement(path, JsonParser.parseString(json), header, comments)
    }

    private fun migrateLegacyFor(path: Path, header: String, comments: Map<String, String>) {
        if (!path.extension.equals("toml", ignoreCase = true)) return
        val jsonPath = path.parent.resolve("${path.nameWithoutExtension}.json")
        if (!jsonPath.exists()) return
        val root = FMLPaths.CONFIGDIR.get().resolve(ChowKingdomMod.MOD_ID)
        migrateJson(jsonPath, path, root, header, comments)
    }

    private fun migrateJson(jsonPath: Path, tomlPath: Path, root: Path, header: String, comments: Map<String, String> = emptyMap()) {
        try {
            if (!tomlPath.exists()) {
                val element = jsonPath.bufferedReader().use(JsonParser::parseReader)
                writeElement(tomlPath, element, header, comments)
            }
            backupJson(jsonPath, root)
        } catch (exception: Exception) {
            ChowKingdomMod.LOGGER.warn("Failed to migrate JSON config {} to TOML", jsonPath, exception)
        }
    }

    private fun backupJson(jsonPath: Path, root: Path) {
        val relative = if (jsonPath.startsWith(root)) root.relativize(jsonPath) else jsonPath.fileName
        val backupRoot = root.resolve(BACKUP_DIR)
        val target = uniqueBackupPath(backupRoot.resolve(relative))
        target.parent.createDirectories()
        Files.move(jsonPath, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun uniqueBackupPath(path: Path): Path {
        if (!path.exists()) return path
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return path.parent.resolve("${path.nameWithoutExtension}-$timestamp.${path.extension}")
    }

    private fun isConfigBackupDirectory(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        return name == BACKUP_DIR || name.startsWith("catalog_backup_")
    }

    private fun readElement(path: Path): JsonElement {
        if (!path.extension.equals("toml", ignoreCase = true)) {
            return path.bufferedReader().use(JsonParser::parseReader)
        }
        FileConfig.of(path).use { config ->
            config.load()
            return toJson(config)
        }
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull.INSTANCE
        is UnmodifiableConfig -> toJson(value.valueMap())
        is Map<*, *> -> JsonObject().apply {
            value.entries.forEach { (key, entryValue) -> add(key.toString(), toJson(entryValue)) }
        }
        is Iterable<*> -> JsonArray().apply { value.forEach { add(toJson(it)) } }
        is Array<*> -> JsonArray().apply { value.forEach { add(toJson(it)) } }
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Char -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun writeElement(path: Path, element: JsonElement, header: String, comments: Map<String, String>) {
        path.parent.createDirectories()
        if (!path.extension.equals("toml", ignoreCase = true)) {
            Files.createTempFile(path.parent, path.nameWithoutExtension, ".json.tmp").also { temp ->
                temp.bufferedWriter().use { writer -> gson.toJson(element, writer) }
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            return
        }
        Files.createTempFile(path.parent, path.nameWithoutExtension, ".toml.tmp").also { temp ->
            temp.bufferedWriter().use { writer -> writer.write(toToml(element, header, comments)) }
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun toToml(element: JsonElement, header: String, comments: Map<String, String>): String {
        val builder = StringBuilder()
        header.lines().map(String::trim).filter(String::isNotBlank).forEach { line -> builder.append("# ").append(line).append('\n') }
        if (builder.isNotEmpty()) builder.append('\n')
        if (element.isJsonObject) {
            appendObject(builder, element.asJsonObject, emptyList(), comments)
        } else {
            builder.append("value = ").append(tomlValue(element, 0)).append('\n')
        }
        return builder.toString()
    }

    private fun appendObject(builder: StringBuilder, obj: JsonObject, path: List<String>, comments: Map<String, String>) {
        obj.entrySet()
            .filterNot { (_, value) -> value.isJsonNull || value.isJsonObject }
            .forEach { (key, value) -> appendEntry(builder, key, value, path, comments) }

        obj.entrySet()
            .filter { (_, value) -> value.isJsonObject }
            .forEach { (key, value) ->
                if (builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
                builder.append('\n')
                appendComment(builder, key, path, comments)
                builder.append('[').append((path + key).joinToString(".") { tomlKey(it) }).append("]\n")
                appendObject(builder, value.asJsonObject, path + key, comments)
            }
    }

    private fun appendEntry(builder: StringBuilder, key: String, value: JsonElement, path: List<String>, comments: Map<String, String>) {
        appendComment(builder, key, path, comments)
        builder.append(tomlKey(key)).append(" = ").append(tomlValue(value, 0)).append('\n')
    }

    private fun appendComment(builder: StringBuilder, key: String, path: List<String>, comments: Map<String, String>) {
        val fullKey = (path + key).joinToString(".")
        val text = comments[fullKey] ?: comments[key] ?: "Config value: $fullKey."
        builder.append("# ").append(text).append('\n')
    }

    private fun tomlValue(element: JsonElement, indent: Int): String = when {
        element.isJsonNull -> "\"\""
        element.isJsonPrimitive -> primitiveValue(element.asJsonPrimitive)
        element.isJsonObject -> inlineTable(element.asJsonObject, indent)
        element.isJsonArray -> arrayValue(element.asJsonArray, indent)
        else -> "\"\""
    }

    private fun primitiveValue(value: JsonPrimitive): String = when {
        value.isBoolean -> value.asBoolean.toString()
        value.isNumber -> value.asString
        value.isString -> "\"${escapeString(value.asString)}\""
        else -> "\"${escapeString(value.asString)}\""
    }

    private fun arrayValue(array: JsonArray, indent: Int): String {
        if (array.isEmpty) return "[]"
        if (array.all { it.isJsonPrimitive }) return array.joinToString(prefix = "[", postfix = "]") { tomlValue(it, indent) }
        val nextIndent = " ".repeat(indent + 2)
        val closingIndent = " ".repeat(indent)
        return array.joinToString(prefix = "[\n", separator = ",\n", postfix = ",\n$closingIndent]") { element ->
            "$nextIndent${tomlValue(element, indent + 2)}"
        }
    }

    private fun inlineTable(obj: JsonObject, indent: Int): String = obj.entrySet()
        .filterNot { (_, value) -> value.isJsonNull }
        .joinToString(prefix = "{ ", postfix = " }") { (key, value) -> "${tomlKey(key)} = ${tomlValue(value, indent)}" }

    private fun tomlKey(key: String): String = if (key.matches(Regex("[A-Za-z0-9_-]+"))) key else "\"${escapeString(key)}\""

    private fun escapeString(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
    }

    private fun headerFor(path: Path): String = "Chow Kingdom config: ${path.fileName}. Edit while the game is stopped, then run reload commands or restart."
}
