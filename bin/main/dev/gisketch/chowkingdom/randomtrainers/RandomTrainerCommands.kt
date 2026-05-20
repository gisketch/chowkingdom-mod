package dev.gisketch.chowkingdom.randomtrainers

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.nio.file.Path

object RandomTrainerCommands {
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        event.dispatcher.register(root("ck"))
        event.dispatcher.register(root("chowkingdom"))
    }

    private fun root(name: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .then(
            Commands.literal("randomtrainers")
                .then(Commands.literal("stats").executes(::stats))
                .then(Commands.literal("validate").requires { it.hasPermission(2) }.executes(::validate))
                .then(Commands.literal("reload").requires { it.hasPermission(2) }.executes(::reload))
                .then(Commands.literal("extract").requires { it.hasPermission(2) }.executes(::extract))
                .then(
                    Commands.literal("spawn")
                        .requires { it.hasPermission(2) }
                        .executes { context -> spawn(context, "") }
                        .then(
                            Commands.argument("roster", StringArgumentType.word())
                                .suggests { _, builder -> SharedSuggestionProvider.suggest(RandomTrainerCatalog.spawnSuggestions(), builder) }
                                .executes { context -> spawn(context, StringArgumentType.getString(context, "roster")) },
                        ),
                )
                .then(Commands.literal("despawnall").requires { it.hasPermission(2) }.executes(::despawnAll))
                .then(
                    Commands.literal("import")
                        .requires { it.hasPermission(2) }
                        .then(
                            Commands.literal("rct")
                                .then(Commands.argument("path", StringArgumentType.greedyString()).executes(::importRct)),
                        ),
                ),
        )

    private fun stats(context: CommandContext<CommandSourceStack>): Int {
        val stats = RandomTrainerCatalog.stats()
        context.source.sendSuccess(
            {
                Component.literal(
                    "Random trainers: ${stats.trainerCount} rosters (${stats.spawnableCount} spawnable, ${stats.importedCount} imported, ${stats.generatedCount} generated, ${stats.invalidCount} invalid), spawned=${RandomTrainerSpawner.spawnedCount()}",
                )
            },
            false,
        )
        val player = context.source.player
        RandomTrainerStore.status(player).forEach { line -> context.source.sendSuccess({ Component.literal(line) }, false) }
        return stats.trainerCount
    }

    private fun validate(context: CommandContext<CommandSourceStack>): Int {
        val stats = RandomTrainerCatalog.stats()
        val target = RandomTrainerCatalog.settings().generatedCatalogSize.coerceAtLeast(0)
        val valid = stats.trainerCount >= target && stats.invalidCount == 0
        context.source.sendSuccess(
            { Component.literal("Random trainer catalog ${if (valid) "valid" else "invalid"}: ${stats.trainerCount} loaded, generated_prefill=$target, invalid=${stats.invalidCount}.") },
            false,
        )
        return if (valid) stats.trainerCount else 0
    }

    private fun reload(context: CommandContext<CommandSourceStack>): Int {
        val count = RandomTrainerCatalog.reload()
        RandomTrainerStore.load()
        context.source.sendSuccess({ Component.literal("Reloaded $count random trainer roster(s).") }, true)
        return count
    }

    private fun extract(context: CommandContext<CommandSourceStack>): Int {
        val pairs = RandomTrainerCatalog.titleGenderPairs()
        context.source.sendSuccess({ Component.literal("Random trainer title/gender/skin folders (${pairs.size}):") }, false)
        pairs.forEach { pair ->
            context.source.sendSuccess(
                { Component.literal("${pair.title} | ${pair.gender} | skins=${pair.skinFolder} | count=${pair.count}") },
                false,
            )
        }
        return pairs.size
    }

    private fun spawn(context: CommandContext<CommandSourceStack>, rosterId: String): Int {
        val player = context.source.playerOrException
        val spawned = RandomTrainerSpawner.debugSpawn(player, rosterId)
        context.source.sendSuccess({ Component.literal(if (spawned) "Spawned random trainer." else "Could not spawn random trainer.") }, true)
        return if (spawned) 1 else 0
    }

    private fun despawnAll(context: CommandContext<CommandSourceStack>): Int {
        val count = RandomTrainerSpawner.despawnAll(context.source.server)
        context.source.sendSuccess({ Component.literal("Despawned $count random trainer(s).") }, true)
        return count
    }

    private fun importRct(context: CommandContext<CommandSourceStack>): Int {
        val path = Path.of(StringArgumentType.getString(context, "path").trim('"'))
        val count = RandomTrainerCatalog.importRct(path)
        context.source.sendSuccess({ Component.literal("Imported $count RCT trainer roster(s).") }, true)
        return count
    }
}
