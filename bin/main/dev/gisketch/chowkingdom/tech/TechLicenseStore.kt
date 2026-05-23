package dev.gisketch.chowkingdom.tech

import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.config.TomlConfigIO
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object TechLicenseStore {
    private var data = StoredTechLicenseData()
    private var loaded = false

    private val file: Path
        get() {
            val server = ServerLifecycleHooks.getCurrentServer()
            val root = server?.getWorldPath(LevelResource.ROOT)?.resolve("data") ?: FMLPaths.CONFIGDIR.get()
            val extension = if (server != null) "json" else "toml"
            return root.resolve(ChowKingdomMod.MOD_ID).resolve("tech_licenses").resolve("state.$extension")
        }

    fun load() {
        file.parent.createDirectories()
        data = if (file.exists()) {
            TomlConfigIO.read(file, StoredTechLicenseData::class.java, ::StoredTechLicenseData)
        } else {
            StoredTechLicenseData()
        }
        loaded = true
    }

    fun has(player: ServerPlayer, licenseId: String): Boolean = has(player.uuid, licenseId)

    fun has(playerId: UUID, licenseId: String): Boolean {
        if (!loaded) load()
        val id = TechLicenseConfig.normalizeId(licenseId)
        return data.players[playerId.toString()]?.licenses?.get(id)?.granted == true
    }

    fun playerLicenseIds(player: ServerPlayer): Set<String> {
        if (!loaded) load()
        return data.players[player.uuid.toString()]?.licenses
            ?.filterValues { record -> record.granted }
            ?.keys
            ?.toSet()
            .orEmpty()
    }

    fun grant(player: ServerPlayer, licenseId: String, source: String): Boolean {
        if (!loaded) load()
        val id = TechLicenseConfig.normalizeId(licenseId)
        if (id.isBlank()) return false
        val record = data.players.getOrPut(player.uuid.toString()) { PlayerTechLicenseRecord() }
        if (record.licenses[id]?.granted == true) return false
        record.licenses[id] = TechLicenseRecord(true, source.trim().ifBlank { "unknown" }, System.currentTimeMillis())
        save()
        return true
    }

    fun revoke(player: ServerPlayer, licenseId: String): Boolean {
        if (!loaded) load()
        val id = TechLicenseConfig.normalizeId(licenseId)
        val record = data.players[player.uuid.toString()] ?: return false
        val existing = record.licenses[id] ?: return false
        if (!existing.granted) return false
        existing.granted = false
        save()
        return true
    }

    fun quest(player: ServerPlayer, licenseId: String): PlayerTechLicenseQuestState? {
        if (!loaded) load()
        return data.questStates[player.uuid.toString()]?.get(TechLicenseConfig.normalizeId(licenseId))
    }

    fun putQuest(player: ServerPlayer, state: PlayerTechLicenseQuestState) {
        if (!loaded) load()
        val id = TechLicenseConfig.normalizeId(state.licenseId)
        if (id.isBlank()) return
        state.licenseId = id
        data.questStates.getOrPut(player.uuid.toString()) { linkedMapOf() }[id] = state
        save()
    }

    fun saveQuest(player: ServerPlayer, state: PlayerTechLicenseQuestState) {
        putQuest(player, state)
    }

    fun saveQuests() {
        if (!loaded) load()
        save()
    }

    fun completeQuest(player: ServerPlayer, licenseId: String) {
        if (!loaded) load()
        val id = TechLicenseConfig.normalizeId(licenseId)
        data.questStates[player.uuid.toString()]?.remove(id)
        data.completedQuestIds.getOrPut(player.uuid.toString()) { linkedSetOf() }.add(id)
        save()
    }

    fun spawned(licenseId: String): Boolean {
        if (!loaded) load()
        return TechLicenseConfig.normalizeId(licenseId) in data.spawnedLicenseIds
    }

    fun pending(licenseId: String): Boolean {
        if (!loaded) load()
        return TechLicenseConfig.normalizeId(licenseId) in data.pendingLicenseIds
    }

    fun markPending(licenseId: String): Boolean {
        if (!loaded) load()
        val id = TechLicenseConfig.normalizeId(licenseId)
        if (id.isBlank() || id in data.spawnedLicenseIds) return false
        val changed = data.pendingLicenseIds.add(id)
        if (changed) save()
        return changed
    }

    fun markSpawned(licenseId: String): Boolean {
        if (!loaded) load()
        val id = TechLicenseConfig.normalizeId(licenseId)
        if (id.isBlank()) return false
        val changed = data.spawnedLicenseIds.add(id)
        data.pendingLicenseIds.remove(id)
        if (changed) save() else if (id !in data.pendingLicenseIds) save()
        return changed
    }

    fun pendingLicenseIds(): Set<String> {
        if (!loaded) load()
        return data.pendingLicenseIds.toSet()
    }

    fun retainPending(validLicenseIds: Set<String>): Boolean {
        if (!loaded) load()
        val changed = data.pendingLicenseIds.retainAll(validLicenseIds)
        if (changed) save()
        return changed
    }

    fun resetThresholdState(licenseId: String): Boolean {
        if (!loaded) load()
        val id = TechLicenseConfig.normalizeId(licenseId)
        val changed = data.pendingLicenseIds.remove(id) || data.spawnedLicenseIds.remove(id)
        if (changed) save()
        return changed
    }

    private fun save() {
        TomlConfigIO.write(file, data)
    }
}

class PlayerTechLicenseRecord(
    var licenses: MutableMap<String, TechLicenseRecord> = linkedMapOf(),
)

class TechLicenseRecord(
    var granted: Boolean = true,
    var source: String = "",
    var grantedAtEpochMillis: Long = 0L,
)

class PlayerTechLicenseQuestState(
    var licenseId: String = "",
    var npcId: String = "",
    var stepIndex: Int = 0,
    var progress: Int = 0,
    var startedAtTick: Long = 0L,
    var stepStartedAtTick: Long = 0L,
    var timedEventTicks: MutableList<Long> = mutableListOf(),
    var paid: Boolean = false,
)

private class StoredTechLicenseData(
    var players: MutableMap<String, PlayerTechLicenseRecord> = linkedMapOf(),
    var questStates: MutableMap<String, MutableMap<String, PlayerTechLicenseQuestState>> = linkedMapOf(),
    var completedQuestIds: MutableMap<String, MutableSet<String>> = linkedMapOf(),
    var pendingLicenseIds: MutableSet<String> = linkedSetOf(),
    var spawnedLicenseIds: MutableSet<String> = linkedSetOf(),
)
