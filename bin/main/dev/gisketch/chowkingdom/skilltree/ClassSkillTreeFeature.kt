package dev.gisketch.chowkingdom.skilltree

import com.mojang.brigadier.arguments.StringArgumentType
import dev.gisketch.chowkingdom.ChowKingdomMod
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent

object ClassSkillTreeFeature {
    fun register(modBus: IEventBus) {
        ClassSkillTrees.register()
        ClassSkillTreeNetwork.register(modBus)
        if (FMLEnvironment.dist.isClient) registerClientHooks()
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedOut)
    }

    private fun registerClientHooks() {
        runCatching {
            val client = Class.forName("dev.gisketch.chowkingdom.skilltree.ClassSkillTreeClient")
            client.getMethod("register").invoke(client.getField("INSTANCE").get(null))
        }.onFailure { exception ->
            ChowKingdomMod.LOGGER.warn("Failed to register CKDM class skill tree client hooks", exception)
        }
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        PuffishSkillsBridge.clearAll()
        event.server.playerList.players.forEach(ClassSkillTrees::reconcile)
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        ClassSkillTrees.reconcile(event.entity as? ServerPlayer ?: return)
    }

    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        PuffishSkillsBridge.clear(event.entity as? ServerPlayer ?: return)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("ckskills").executes { context ->
                val player = context.source.playerOrException
                ClassSkillTreeNetwork.syncTo(player, openScreen = true)
                1
            },
        )
        event.dispatcher.register(
            Commands.literal("ck")
                .then(
                    Commands.literal("skills")
                        .executes { context ->
                            val player = context.source.playerOrException
                            ClassSkillTreeNetwork.syncTo(player, openScreen = true)
                            1
                        }
                        .then(Commands.literal("sync").requires { source -> source.hasPermission(2) }
                            .executes { context ->
                                val player = context.source.playerOrException
                                ClassSkillTrees.reconcile(player)
                                ClassSkillTreeNetwork.syncTo(player, openScreen = true)
                                context.source.sendSuccess({ Component.literal("Synced class skills for ${player.gameProfile.name}.") }, false)
                                1
                            }
                            .then(Commands.argument("player", EntityArgument.player()).executes { context ->
                                val player = EntityArgument.getPlayer(context, "player")
                                ClassSkillTrees.reconcile(player)
                                ClassSkillTreeNetwork.syncTo(player, openScreen = false)
                                context.source.sendSuccess({ Component.literal("Synced class skills for ${player.gameProfile.name}.") }, true)
                                1
                            }))
                        .then(Commands.literal("reset").requires { source -> source.hasPermission(2) }
                            .then(Commands.argument("player", EntityArgument.player())
                                .executes { context ->
                                    val player = EntityArgument.getPlayer(context, "player")
                                    ClassSkillTrees.reset(player)
                                    context.source.sendSuccess({ Component.literal("Reset all class skills for ${player.gameProfile.name}.") }, true)
                                    1
                                }
                                .then(Commands.argument("root", StringArgumentType.word()).suggests { context, builder ->
                                    val player = EntityArgument.getPlayer(context, "player")
                                    SharedSuggestionProvider.suggest(dev.gisketch.chowkingdom.roles.RoleStore.activeClassIds(player).mapNotNull { ClassSkillTrees.rootForClass(it)?.id }, builder)
                                }.executes { context ->
                                    val player = EntityArgument.getPlayer(context, "player")
                                    val root = StringArgumentType.getString(context, "root")
                                    ClassSkillTrees.reset(player, root)
                                    context.source.sendSuccess({ Component.literal("Reset $root class skills for ${player.gameProfile.name}.") }, true)
                                    1
                                }))),
                ),
        )
    }
}
