package dev.gisketch.chowkingdom.wallets

import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent

object ChowcoinCommands {
    fun register() {
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        event.dispatcher.register(chowcoinRoot("chowcoin"))
        event.dispatcher.register(Commands.literal("chowkingdom").then(chowcoinRoot("chowcoin")))
        event.dispatcher.register(Commands.literal("ck").then(chowcoinRoot("chowcoin")))
    }

    private fun chowcoinRoot(name: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .requires { source -> source.hasPermission(2) }
        .then(amountCommand("add", ::add))
        .then(amountCommand("remove", ::remove))
        .then(amountCommand("set", ::set))

    private fun amountCommand(name: String, handler: (CommandContext<CommandSourceStack>, Long, ServerPlayer) -> Int): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name).then(
            Commands.argument("qty", LongArgumentType.longArg(0L)).then(
                Commands.argument("player", EntityArgument.player()).executes { context ->
                    handler(context, LongArgumentType.getLong(context, "qty"), EntityArgument.getPlayer(context, "player"))
                },
            ),
        )

    private fun add(context: CommandContext<CommandSourceStack>, amount: Long, player: ServerPlayer): Int {
        val total = ChowcoinStore.add(player, amount)
        ChowcoinNetwork.syncTo(player)
        context.source.sendSuccess({ Component.literal("Added $amount chowcoins to ${player.gameProfile.name}. Balance: $total") }, true)
        return 1
    }

    private fun remove(context: CommandContext<CommandSourceStack>, amount: Long, player: ServerPlayer): Int {
        val total = ChowcoinStore.set(player, (ChowcoinStore.get(player) - amount).coerceAtLeast(0L))
        ChowcoinNetwork.syncTo(player)
        context.source.sendSuccess({ Component.literal("Removed $amount chowcoins from ${player.gameProfile.name}. Balance: $total") }, true)
        return 1
    }

    private fun set(context: CommandContext<CommandSourceStack>, amount: Long, player: ServerPlayer): Int {
        val total = ChowcoinStore.set(player, amount)
        ChowcoinNetwork.syncTo(player)
        context.source.sendSuccess({ Component.literal("Set ${player.gameProfile.name} chowcoins to $total") }, true)
        return 1
    }
}