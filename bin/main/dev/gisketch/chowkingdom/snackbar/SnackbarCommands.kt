package dev.gisketch.chowkingdom.snackbar

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.ResourceLocationArgument
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component

object SnackbarCommands {
    fun register(dispatcher: com.mojang.brigadier.CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("snackbar")
                .requires { source -> source.hasPermission(2) }
                .then(Commands.literal("clear").executes(::clear))
                .then(
                    Commands.argument("icon", ResourceLocationArgument.id())
                        .suggests { _, builder -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), builder) }
                        .then(
                            Commands.argument("title", StringArgumentType.string())
                                .then(
                                    Commands.argument("type", StringArgumentType.word())
                                        .suggests { _, builder -> SharedSuggestionProvider.suggest(SnackbarType.ids, builder) }
                                        .executes(::showWithoutContent),
                                )
                                .then(
                                    Commands.argument("content", StringArgumentType.string())
                                        .then(
                                            Commands.argument("type", StringArgumentType.word())
                                                .suggests { _, builder -> SharedSuggestionProvider.suggest(SnackbarType.ids, builder) }
                                                .executes(::show),
                                        ),
                                ),
                        ),
                ),
        )
    }

    private fun show(context: CommandContext<CommandSourceStack>): Int {
        return show(context, StringArgumentType.getString(context, "content"))
    }

    private fun showWithoutContent(context: CommandContext<CommandSourceStack>): Int {
        return show(context, "")
    }

    private fun show(context: CommandContext<CommandSourceStack>, content: String): Int {
        val player = context.source.playerOrException
        val type = SnackbarType.fromId(StringArgumentType.getString(context, "type"))
        SnackbarNetwork.send(
            player,
            ResourceLocationArgument.getId(context, "icon"),
            StringArgumentType.getString(context, "title"),
            content,
            type,
        )
        context.source.sendSuccess({ Component.literal("Snackbar sent.") }, false)
        return 1
    }

    private fun clear(context: CommandContext<CommandSourceStack>): Int {
        SnackbarNetwork.clear(context.source.playerOrException)
        context.source.sendSuccess({ Component.literal("Snackbars cleared.") }, false)
        return 1
    }
}