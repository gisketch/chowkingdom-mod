package dev.gisketch.chowkingdom.randomtrainers

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

object RandomTrainerStore {
    private var loaded = false
    private var data = RandomTrainerWorldState()

    private val file: Path
        get() {
            val server = ServerLifecycleHooks.getCurrentServer()
            val root = if (server != null) server.getWorldPath(LevelResource.ROOT).resolve("data") else FMLPaths.CONFIGDIR.get()
            val extension = if (server != null) "json" else "toml"
            return root.resolve(ChowKingdomMod.MOD_ID).resolve("random_trainers").resolve("state.$extension")
        }

    fun load() {
        file.parent.createDirectories()
        data = if (file.exists()) TomlConfigIO.read(file, RandomTrainerWorldState::class.java, ::RandomTrainerWorldState) else RandomTrainerWorldState()
        loaded = true
    }

    fun defeated(player: ServerPlayer): Set<String> {
        ensureLoaded()
        return playerState(player).defeated.toSet()
    }

    fun hasDefeated(player: ServerPlayer, rosterId: String): Boolean {
        ensureLoaded()
        return cleanRandomTrainerId(rosterId) in playerState(player).defeated
    }

    fun recordResult(player: ServerPlayer, rosterId: String, won: Boolean) {
        ensureLoaded()
        val state = playerState(player)
        val clean = cleanRandomTrainerId(rosterId)
        state.encounters[clean] = (state.encounters[clean] ?: 0) + 1
        if (won) {
            state.wins += 1
            state.defeated += clean
        } else {
            state.losses += 1
        }
        state.lastResultAtMs = System.currentTimeMillis()
        save()
    }

    fun putBattle(uuid: UUID, context: RandomTrainerBattleContextState) {
        ensureLoaded()
        data.activeBattles[uuid.toString()] = context
        save()
    }

    fun takeBattle(uuid: UUID): RandomTrainerBattleContextState? {
        ensureLoaded()
        val context = data.activeBattles.remove(uuid.toString())
        if (context != null) save()
        return context
    }

    fun clearPlayerActiveBattles(player: ServerPlayer) {
        ensureLoaded()
        val removed = data.activeBattles.entries.removeIf { (_, context) -> context.playerUuid == player.stringUUID }
        if (removed) save()
    }

    fun status(player: ServerPlayer?): List<String> {
        ensureLoaded()
        val lines = mutableListOf<String>()
        lines += "Random trainer players tracked: ${data.players.size}"
        lines += "Active random trainer battles: ${data.activeBattles.size}"
        if (player != null) {
            val state = playerState(player)
            lines += "${player.gameProfile.name}: defeated=${state.defeated.size} wins=${state.wins} losses=${state.losses}"
        }
        return lines
    }

    private fun playerState(player: ServerPlayer): RandomTrainerPlayerState =
        data.players.getOrPut(player.stringUUID) { RandomTrainerPlayerState() }

    private fun ensureLoaded() {
        if (!loaded) load()
    }

    private fun save() {
        TomlConfigIO.write(file, data)
    }
}
