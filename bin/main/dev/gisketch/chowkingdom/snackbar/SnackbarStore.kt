package dev.gisketch.chowkingdom.snackbar

import com.google.gson.annotations.SerializedName
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.config.TomlConfigIO
import net.minecraft.server.level.ServerPlayer
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object SnackbarStore {
    private val knownPlayers: MutableSet<String> = linkedSetOf()
    private val pending: MutableMap<String, MutableList<StoredSnackbar>> = linkedMapOf()
    private var loaded = false

    private val file: Path
        get() = FMLPaths.CONFIGDIR.get().resolve(ChowKingdomMod.MOD_ID).resolve("snackbar").resolve("pending.toml")

    fun load() {
        file.parent.createDirectories()
        knownPlayers.clear()
        pending.clear()
        if (file.exists()) {
            try {
                val data = TomlConfigIO.read(file, SnackbarStoreData::class.java, ::SnackbarStoreData)
                data.knownPlayers.mapTo(knownPlayers) { it.lowercase() }
                data.pending.forEach { (playerId, notifications) ->
                    pending[playerId.lowercase()] = notifications.take(MAX_PENDING_PER_PLAYER).toMutableList()
                }
            } catch (exception: Exception) {
                ChowKingdomMod.LOGGER.warn("Failed to load snackbar store {}", file, exception)
            }
        }
        loaded = true
    }

    fun remember(player: ServerPlayer) {
        ensureLoaded()
        if (knownPlayers.add(player.uuid.toString().lowercase())) save()
    }

    fun knownPlayerIds(): Set<UUID> {
        ensureLoaded()
        return knownPlayers.mapNotNull { id -> runCatching { UUID.fromString(id) }.getOrNull() }.toSet()
    }

    fun queue(playerId: UUID, notification: SnackbarNotification) {
        ensureLoaded()
        val key = playerId.toString().lowercase()
        val list = pending.getOrPut(key) { mutableListOf() }
        list += StoredSnackbar.from(notification)
        while (list.size > MAX_PENDING_PER_PLAYER) list.removeAt(0)
        knownPlayers += key
        save()
    }

    fun drain(player: ServerPlayer): List<SnackbarNotification> {
        ensureLoaded()
        val key = player.uuid.toString().lowercase()
        val drained = pending.remove(key).orEmpty().map(StoredSnackbar::toNotification)
        if (drained.isNotEmpty()) save()
        return drained
    }

    private fun ensureLoaded() {
        if (!loaded) load()
    }

    private fun save() {
        file.parent.createDirectories()
        TomlConfigIO.write(file, SnackbarStoreData(knownPlayers.toList(), pending))
    }

    private const val MAX_PENDING_PER_PLAYER = 50
}

class SnackbarStoreData(
    @SerializedName("known_players") val knownPlayers: List<String> = emptyList(),
    @SerializedName("pending") val pending: Map<String, List<StoredSnackbar>> = emptyMap(),
)

class StoredSnackbar(
    @SerializedName("icon_kind") val iconKind: String = SnackbarIconKind.ITEM.id,
    @SerializedName("icon") val icon: String = SnackbarIcons.BATTLEPASS,
    @SerializedName("title") val title: String = "",
    @SerializedName("content") val content: String = "",
    @SerializedName("type") val type: String = SnackbarType.GENERIC.id,
    @SerializedName("sound") val sound: String = SnackbarSounds.GENERIC,
) {
    fun toNotification(): SnackbarNotification = SnackbarNotification(SnackbarIconKind.fromId(iconKind), icon, title, content, SnackbarType.fromId(type), sound)

    companion object {
        fun from(notification: SnackbarNotification): StoredSnackbar = StoredSnackbar(notification.iconKind.id, notification.icon, notification.title, notification.content, notification.type.id, notification.sound)
    }
}