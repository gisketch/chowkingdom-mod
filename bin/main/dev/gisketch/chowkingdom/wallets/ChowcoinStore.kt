package dev.gisketch.chowkingdom.wallets

import com.google.gson.GsonBuilder
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.config.TomlConfigIO
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object ChowcoinStore {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val balances: MutableMap<String, Long> = linkedMapOf()
    private var loaded = false

    private val file: Path
        get() {
            val server = ServerLifecycleHooks.getCurrentServer()
            val root = if (server != null) {
                server.getWorldPath(LevelResource.ROOT).resolve("data")
            } else {
                FMLPaths.CONFIGDIR.get()
            }
            val extension = if (server != null) "json" else "toml"
            return root.resolve(ChowKingdomMod.MOD_ID).resolve("wallets").resolve("chowcoins.$extension")
        }

    fun load() {
        file.parent.createDirectories()
        balances.clear()

        if (file.exists()) {
            try {
                val data = TomlConfigIO.read(file, StoredChowcoinData::class.java, ::StoredChowcoinData)
                data.players.forEach { (playerId, balance) -> balances[playerId] = balance.coerceAtLeast(0L) }
            } catch (exception: Exception) {
                ChowKingdomMod.LOGGER.warn("Failed to load chowcoin store {}", file, exception)
            }
        }

        loaded = true
    }

    fun get(player: ServerPlayer): Long = get(player.uuid)

    fun get(playerId: UUID): Long {
        if (!loaded) load()
        return balances[playerId.toString()] ?: 0L
    }

    fun add(player: ServerPlayer, amount: Long): Long {
        if (!loaded) load()
        if (amount <= 0L) return get(player)
        val total = balances.getOrDefault(player.stringUUID, 0L) + amount
        balances[player.stringUUID] = total
        save()
        return total
    }

    fun add(playerId: UUID, amount: Long): Long {
        if (!loaded) load()
        if (amount <= 0L) return get(playerId)
        val key = playerId.toString()
        val total = balances.getOrDefault(key, 0L) + amount
        balances[key] = total
        save()
        return total
    }

    fun set(player: ServerPlayer, amount: Long): Long {
        if (!loaded) load()
        val total = amount.coerceAtLeast(0L)
        balances[player.stringUUID] = total
        save()
        return total
    }

    private fun save() {
        TomlConfigIO.write(file, StoredChowcoinData(balances))
    }

    private class StoredChowcoinData(
        var players: MutableMap<String, Long> = linkedMapOf(),
    )
}