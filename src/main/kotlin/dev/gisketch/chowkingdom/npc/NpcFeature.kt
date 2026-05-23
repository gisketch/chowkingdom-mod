package dev.gisketch.chowkingdom.npc

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import dev.gisketch.chowkingdom.ChowClockConfig
import dev.gisketch.chowkingdom.ChatGlyphs
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.bosses.BossEventsFeature
import dev.gisketch.chowkingdom.discord.DiscordRelay
import dev.gisketch.chowkingdom.gyms.GymBattleService
import dev.gisketch.chowkingdom.gyms.GymLeagueFeature
import dev.gisketch.chowkingdom.relicroulette.RelicRouletteFeature
import dev.gisketch.chowkingdom.roles.ClassLicenses
import dev.gisketch.chowkingdom.roles.ClassMentorQuestService
import dev.gisketch.chowkingdom.roles.ClassMentorTrainingResult
import dev.gisketch.chowkingdom.roles.BODY_MODEL_BOY
import dev.gisketch.chowkingdom.roles.BODY_MODEL_GIRL
import dev.gisketch.chowkingdom.roles.JobLevels
import dev.gisketch.chowkingdom.roles.MAX_FG_BOUNCE
import dev.gisketch.chowkingdom.roles.MAX_FG_BUST_SIZE
import dev.gisketch.chowkingdom.roles.MAX_FG_FLOPPY
import dev.gisketch.chowkingdom.roles.MIN_FG_BOUNCE
import dev.gisketch.chowkingdom.roles.MIN_FG_BUST_SIZE
import dev.gisketch.chowkingdom.roles.MIN_FG_FLOPPY
import dev.gisketch.chowkingdom.roles.PerformerPerks
import dev.gisketch.chowkingdom.roles.RoleClassEquipmentRules
import dev.gisketch.chowkingdom.roles.RoleDefinition
import dev.gisketch.chowkingdom.roles.RoleStore
import dev.gisketch.chowkingdom.roles.RolesConfig
import dev.gisketch.chowkingdom.roles.RolesNetwork
import dev.gisketch.chowkingdom.shops.StoreShopFeature
import dev.gisketch.chowkingdom.snackbar.SnackbarIcons
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import dev.gisketch.chowkingdom.tech.TechLicenseFeature
import dev.gisketch.chowkingdom.wallets.ChowcoinNetwork
import dev.gisketch.chowkingdom.wallets.ChowcoinStore
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.ServerChatEvent
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.max

object NpcFeature {
    private const val NPC_DIALOG_DURATION_TICKS = 100
    private const val NPC_DIALOG_KEEPALIVE_TICKS = 80
    private val BLOCKS: DeferredRegister<Block> = DeferredRegister.create(Registries.BLOCK, ChowKingdomMod.MOD_ID)
    private val ITEMS: DeferredRegister<Item> = DeferredRegister.create(Registries.ITEM, ChowKingdomMod.MOD_ID)
    private val ENTITIES: DeferredRegister<EntityType<*>> = DeferredRegister.create(Registries.ENTITY_TYPE, ChowKingdomMod.MOD_ID)
    private val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ChowKingdomMod.MOD_ID)
    private val realtimeDebugTargets: MutableMap<UUID, UUID> = linkedMapOf()
    private val clockDebugPlayers: MutableSet<UUID> = linkedSetOf()
    private val emoteDebugPlayers: MutableSet<UUID> = linkedSetOf()
    private val greetingRadiusPlayers: MutableMap<UUID, MutableSet<String>> = linkedMapOf()
    private val outgoingGiftApproaches: MutableMap<UUID, NpcOutgoingGiftApproach> = linkedMapOf()
    private val camperBalloonRefreshAt: MutableMap<UUID, Long> = linkedMapOf()
    private val questClaimBalloonRefreshAt: MutableMap<UUID, Long> = linkedMapOf()
    private val npcMicroInteractions: MutableMap<UUID, ActiveNpcMicroInteraction> = linkedMapOf()
    private val npcMicroInteractionCooldownUntil: MutableMap<String, Long> = linkedMapOf()
    private val npcMicroInteractionPairCooldownUntil: MutableMap<String, Long> = linkedMapOf()
    private val npcMicroInteractionAreaCooldownUntil: MutableMap<String, Long> = linkedMapOf()
    private val npcMicroInteractionDailyCounts: MutableMap<String, NpcDailyMicroInteractionCount> = linkedMapOf()
    private val playerMicroInteractionWitnessDay: MutableMap<UUID, Long> = linkedMapOf()
    private val playerMicroInteractionWitnessNudge: MutableMap<UUID, NpcMicroInteractionWitnessNudge> = linkedMapOf()
    private val npcMicroInteractionRecent: MutableMap<String, ArrayDeque<String>> = linkedMapOf()
    private val npcMicroInteractionDialogContext: MutableMap<UUID, NpcMicroInteractionDialogContext> = linkedMapOf()
    private val npcMicroInteractionScanCooldownUntil: MutableMap<UUID, Long> = linkedMapOf()
    private val npcAutoTaskCooldownUntil: MutableMap<UUID, Long> = linkedMapOf()
    private val npcAmbientActions: MutableMap<UUID, ActiveNpcAmbientAction> = linkedMapOf()
    private val npcAmbientCooldownUntil: MutableMap<UUID, Long> = linkedMapOf()
    private val npcAmbientMemories: MutableMap<UUID, NpcAmbientMemory> = linkedMapOf()
    private val npcSoloMomentRecent: MutableMap<String, ArrayDeque<String>> = linkedMapOf()
    private val pendingShopNpcs: MutableMap<UUID, String> = linkedMapOf()
    private val animationDebugTargets: MutableMap<UUID, UUID> = linkedMapOf()
    private val bossFightDebugTargets: MutableMap<UUID, UUID> = linkedMapOf()
    private val animationWearAliases = listOf("clear", "none", "hat", "helmet", "chestplate", "leggings", "boots", "sword", "left_sword", "offhand_sword", "left sword", "left minecraft:iron_sword", "offhand minecraft:iron_sword")
    private var workBypassEnabled = false
    private var debugTimeMultiplier: Int = 1

    val CAMPING_BLOCK: DeferredHolder<Block, CampingBlock> = BLOCKS.register("camping_block", Supplier { CampingBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_PLANKS).strength(1.5f).noOcclusion()) })
    val TOWN_CENTER_BLOCK: DeferredHolder<Block, TownCenterBlock> = BLOCKS.register("town_center_block", Supplier { TownCenterBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.STONE_BRICKS).strength(1.5f).noOcclusion()) })
    val CAMPING_BLOCK_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<CampingBlockEntity>> = BLOCK_ENTITIES.register(
        "camping_block",
        Supplier { BlockEntityType.Builder.of(::CampingBlockEntity, CAMPING_BLOCK.get()).build(null) },
    )
    val RENT_CONTRACT: DeferredHolder<Item, NpcRentContractItem> = ITEMS.register("rent_contract", Supplier { NpcRentContractItem(Item.Properties().stacksTo(1)) })
    val JOB_APPLICATION: DeferredHolder<Item, NpcJobApplicationItem> = ITEMS.register("job_application", Supplier { NpcJobApplicationItem(Item.Properties().stacksTo(1)) })
    val NPC_ENTITY: DeferredHolder<EntityType<*>, EntityType<ChowNpcEntity>> = ENTITIES.register(
        "npc",
        Supplier {
            EntityType.Builder.of(::ChowNpcEntity, MobCategory.CREATURE)
                .sized(0.6f, 1.8f)
                .clientTrackingRange(10)
                .updateInterval(1)
                .build("npc")
        },
    )

    fun register(modBus: IEventBus) {
        BLOCKS.register(modBus)
        ITEMS.register(modBus)
        ENTITIES.register(modBus)
        BLOCK_ENTITIES.register(modBus)
        NpcNetwork.register(modBus)
        NpcConfig.load()
        NpcBossMovesets.load()
        NpcCombatRollBridge.register()
        NpcPokemonCompanions.registerEvents()
        NpcPokemonBattleService.register()
        modBus.addListener(::registerAttributes)
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onAttackEntity)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onIncomingDamage)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onProjectileImpact)
        NeoForge.EVENT_BUS.addListener(::onLivingDamagePre)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onLivingChangeTarget)
        NeoForge.EVENT_BUS.addListener(::onLivingDeath)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedOut)
        NeoForge.EVENT_BUS.addListener(::onPlayerChangedDimension)
        NeoForge.EVENT_BUS.addListener(::onServerChat)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onBlockPlace)
        NeoForge.EVENT_BUS.addListener(::onBlockBreak)
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onRightClickBlock)
    }

    fun spawnFromCamp(level: Level, pos: BlockPos): Boolean {
        if (level.isClientSide || level !is ServerLevel) return false
        NpcConfig.load()
        NpcStore.load()
        NpcStore.setCampBlock(pos)
        val active = activeUnhousedCamper(level.server) ?: migrateLiveUnhousedCamper(level.server)
        if (active != null) {
            val npc = existingNpc(level.server, active.id)
            if (npc == null && NpcStore.isDead(active.id)) return spawnNpc(level, active, pos, markActiveCamper = true)
            return false
        }
        if (TechLicenseFeature.trySpawnPriorityCamper(level.server)) return true
        val cooldownUntil = NpcStore.camperCooldownUntilTick()
        if (cooldownUntil > level.dayTime) return false
        val definition = randomEligibleCamper(level) ?: return false
        return spawnNpc(level, definition, pos, markActiveCamper = true, announceCamperArrival = true)
    }

    fun interact(player: ServerPlayer, npc: ChowNpcEntity) {
        val definition = NpcConfig.get(npc.npcId) ?: return
        if (NpcBossFights.isActive(npc)) {
            if (!NpcBossFights.isDuelist(npc, player)) {
                SnackbarNetwork.send(player, SnackbarNotification.npc(definition.id, "NPC IN BATTLE", "${definition.displayName()} is locked in battle.", SnackbarType.ERROR, SnackbarSounds.ERROR))
            }
            return
        }
        if (NpcPokemonBattleService.isBattleLocked(npc)) {
            SnackbarNetwork.send(player, SnackbarNotification.npc(definition.id, "NPC IN BATTLE", "${definition.displayName()} is locked in a Pokemon battle.", SnackbarType.ERROR, SnackbarSounds.ERROR))
            return
        }
        if (tryOpenMicroInteractionInterruptDialog(player, npc, definition)) return
        if (GymLeagueFeature.tryOpenNpcDialog(player, npc, definition)) return
        val validHome = validHomePos(npc.level(), definition.id)
        npc.homePos = validHome
        val hasHome = validHome != null
        val activeTalk = NpcLlmService.talkSnapshot(definition.id)
        if (hasHome && activeTalk != null) {
            val friendship = NpcStore.friendshipSnapshot(definition.id, player)
            val otherNames = activeTalk.participants.filterNot { participant -> participant.uuid == player.uuid }.joinToString(", ") { participant -> participant.name }
            val message = if (activeTalk.contains(player.uuid)) {
                if (otherNames.isBlank()) "You are already talking with ${definition.name}." else "You are in the conversation with $otherNames."
            } else {
                val names = activeTalk.participants.joinToString(", ") { participant -> participant.name }
                "${definition.name} is currently talking to $names."
            }
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, message, false, friendship.level, dialogMode = "join", startTalkMode = activeTalk.contains(player.uuid), player = player))
            return
        }
        if (hasHome) {
            val pendingGift = NpcStore.pendingOutgoingGift(definition.id, player)
            if (pendingGift != null) {
                claimOutgoingGift(player, npc, definition, pendingGift)
                return
            }
            if (BossEventsFeature.tryOpenFinnDialog(player, npc, definition)) return
            if (NpcQuestService.tryOpenQuest(player, npc, definition)) return
        }
        val wasSleeping = npc.isSleeping
        val currentDay = NpcTime.day(player.level())
        val firstChatToday = NpcStore.markFirstChatIfNeeded(definition.id, player, currentDay)
        val firstChatFriendshipDelta = if (firstChatToday) NpcStore.effectiveFriendshipDelta(player, FIRST_DAILY_CHAT_FRIENDSHIP_DELTA) else 0
        val recognized = NpcStore.recognized(definition.id, player)
        val friendship = if (firstChatToday) NpcStore.adjustFriendship(definition.id, player, FIRST_DAILY_CHAT_FRIENDSHIP_DELTA, "first_daily_chat") else NpcStore.friendshipSnapshot(definition.id, player)
        if (firstChatFriendshipDelta != 0) showFriendshipDelta(npc.level() as? ServerLevel, npc, firstChatFriendshipDelta)
        if (wasSleeping) npc.stopSleeping()
        val contractGranted = definition.housing.canMoveIn && !hasHome && !hasRentContract(player, definition.id)
        if (contractGranted) {
            giveStack(player, createRentContract(definition.id))
            NpcStore.markContractGiven(definition.id)
            SnackbarNetwork.send(player, SnackbarNotification.npc(definition.id, "RENT CONTRACT RECEIVED", "Use it on a bed to give ${definition.name} a home.", SnackbarType.SUCCESS, SnackbarSounds.REWARD))
        }
        val camperReason = if (!hasHome) NpcStore.camperReturnReason(definition.id) else ""
        val interactionFocus = if (hasHome && !wasSleeping && !firstChatToday && recognized && !contractGranted) {
            NpcInteractionDirector.choose(player, npc, definition, friendship)
        } else {
            null
        }
        val message = if (wasSleeping) {
            friendshipMessage(definition.friendshipMessages.wake, friendship, player, definition)
        } else if (hasHome && firstChatToday) {
            friendshipMessage(definition.friendshipMessages.firstDailyChat, friendship, player, definition)
        } else if (hasHome) {
            interactionFocus?.fallback ?: friendshipMessage(definition.friendshipMessages.interact, friendship, player, definition)
        } else if (camperReason == "lost_house") {
            camperMessage(definition.camperMessages.lostHouseDialog, player, definition)
        } else {
            camperMessage(definition.camperMessages.needsHouseDialog, player, definition)
        }
        npc.startTalkingTo(player, NPC_DIALOG_DURATION_TICKS)
        NpcStore.recordConversation(definition.id, player, player.gameProfile.name, "interacts with ${definition.name}", "player_interact")
        DiscordRelay.npcInteraction(player, definition.name)
        val settings = NpcConfig.settings()
        val llmInput = when {
            !recognized -> firstMeetingPrompt(player, definition, hasHome, contractGranted)
            wasSleeping && settings.llmMessageUsage.wake -> "${player.gameProfile.name} woke you up. Reply naturally as ${definition.name}, with the context that you were just sleeping."
            hasHome && firstChatToday && settings.llmMessageUsage.firstDailyChat -> "${player.gameProfile.name} is talking to you for the first time today. Reply like a natural first daily greeting."
            hasHome && settings.llmMessageUsage.interact && !contractGranted -> interactionFocus?.prompt
                ?: "${player.gameProfile.name} interacted with you. Reply like a natural short NPC greeting or acknowledgement for this moment."
            !hasHome && camperReason == "lost_house" && settings.llmMessageUsage.camperLostHouse -> settings.campers.lostHouseLlmPrompt
            !hasHome && settings.llmMessageUsage.camperNeedsHouse -> settings.campers.needsHouseLlmPrompt
            else -> null
        }
        val friendlyBattleAvailable = hasHome && NpcPokemonBattleService.friendlyBattleAvailable(player, npc, definition)
        val retryBattleAvailable = hasHome && NpcQuestService.retryBattleAvailable(player, definition)
        if (settings.llm.enabled && llmInput != null) {
            val responseToken = NpcDialogTokens.next()
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, "...", contractGranted, friendship.level, closeOnly = !hasHome || contractGranted, closeLabel = if (!hasHome || contractGranted) "OKAY" else "BYE", responseToken = responseToken, friendshipDelta = firstChatFriendshipDelta, friendlyBattleAvailable = friendlyBattleAvailable, retryBattleAvailable = retryBattleAvailable, player = player))
            if (!recognized) NpcStore.markRecognized(definition.id, player)
            NpcLlmService.event(player, npc, definition, message, llmInput, npcRecordType = "npc_llm_interact", responseToken = responseToken)
            return
        }
        NpcStore.recordConversation(definition.id, player, definition.name, message, "npc_message")
        NpcNetwork.openDialog(player, dialogPayload(definition, npc, message, contractGranted, friendship.level, closeOnly = !hasHome || contractGranted, closeLabel = if (!hasHome || contractGranted) "OKAY" else "BYE", friendshipDelta = firstChatFriendshipDelta, friendlyBattleAvailable = friendlyBattleAvailable, retryBattleAvailable = retryBattleAvailable, player = player))
        if (!recognized) NpcStore.markRecognized(definition.id, player)
        relayNpcDialog(player, npc, definition, message)
    }

    fun handleDialogAction(player: ServerPlayer, npcId: String, action: String) {
        val definition = NpcConfig.get(npcId) ?: return
        val npc = existingNpc(player.server, definition.id) ?: return
        if (npc.level() != player.level() || player.distanceToSqr(npc) > NPC_DIALOG_ACTION_DISTANCE_SQR) return
        when (action.lowercase()) {
            "dialog_keepalive" -> {
                npc.continueTalkingTo(player, NPC_DIALOG_KEEPALIVE_TICKS)
                return
            }
            "dialog_close" -> {
                npc.stopTalkingTo(player)
                return
            }
        }
        val normalizedAction = action.lowercase()
        if (normalizedAction in setOf("quest_accept", "quest_decline", "quest_retry_battle") && !hasValidHomeForActions(player, definition)) return
        if (BossEventsFeature.handleFinnAction(player, npc, definition, action)) return
        if (GymLeagueFeature.handleAction(player, npc, definition, action)) return
        if (TechLicenseFeature.handleDialogAction(player, definition.id, action)) return
        if (NpcQuestService.handleAction(player, npc, definition, action)) return
        if (normalizedAction.startsWith("class_change:")) {
            if (!hasValidHomeForActions(player, definition)) return
            NpcLlmService.leaveDialog(player, definition.id)
            completeClassChange(player, npc, definition, action.substringAfter(':'))
            return
        }
        when (normalizedAction) {
            "cancel_llm", "leave_llm_dialog" -> NpcLlmService.leaveDialog(player, definition.id)
            "join_talk" -> if (hasValidHomeForActions(player, definition)) NpcLlmService.joinConversation(player, definition.id)
            "buy" -> {
                if (!hasValidHomeForActions(player, definition)) return
                NpcLlmService.leaveDialog(player, definition.id)
                openNpcShop(player, npc, definition)
            }
            "gift" -> {
                if (!hasValidHomeForActions(player, definition)) return
                NpcLlmService.leaveDialog(player, definition.id)
                giftToNpc(player, npc, definition)
            }
            "work" -> {
                if (!hasValidHomeForActions(player, definition)) return
                NpcLlmService.leaveDialog(player, definition.id)
                openWorkDialog(player, npc, definition)
            }
            "training" -> {
                if (!hasValidHomeForActions(player, definition)) return
                NpcLlmService.leaveDialog(player, definition.id)
                trainClass(player, npc, definition)
            }
            "npc_friendly_battle" -> {
                if (!hasValidHomeForActions(player, definition)) return
                if (!requireReadyWorkplace(player, npc, definition, "battle")) return
                NpcLlmService.leaveDialog(player, definition.id)
                val result = NpcPokemonBattleService.startFriendlyBattle(player, npc, definition)
                if (!result.started) SnackbarNetwork.send(player, SnackbarNotification.npc(definition.id, "BATTLE FAILED", result.message, SnackbarType.ERROR, SnackbarSounds.ERROR))
            }
            "class_change_offer" -> {
                if (!hasValidHomeForActions(player, definition)) return
                NpcLlmService.leaveDialog(player, definition.id)
                openClassChangeSelection(player, npc, definition)
            }
            "work_move" -> {
                if (!hasValidHomeForActions(player, definition)) return
                NpcLlmService.leaveDialog(player, definition.id)
                giveWorkApplication(player, npc, definition, moving = true)
            }
            "work_fire" -> {
                if (!hasValidHomeForActions(player, definition)) return
                NpcLlmService.leaveDialog(player, definition.id)
                fireNpcWorkplace(player, npc, definition)
            }
        }
    }

    private fun hasValidHomeForActions(player: ServerPlayer, definition: NpcDefinition): Boolean =
        validHomePos(player.level(), definition.id) != null

    fun hasReadyWorkplace(level: Level, definition: NpcDefinition): Boolean {
        if (workBypassEnabled) return true
        val workplace = NpcStore.workplacePos(definition.id) ?: return false
        return workBlockStatus(level, workplace, definition).ready
    }

    fun requireReadyWorkplace(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, actionName: String): Boolean {
        if (workBypassEnabled) return true
        val workplace = NpcStore.workplacePos(definition.id)
        if (workplace == null) {
            val friendship = NpcStore.friendshipSnapshot(definition.id, player)
            val message = if (NpcStore.workFired(definition.id)) {
                "I do not have a workplace right now."
            } else {
                "I need a workplace before I can $actionName."
            }
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, message, false, friendship.level, closeOnly = true, closeLabel = "OKAY"))
            return false
        }
        val workBlocks = workBlockStatus(player.level(), workplace, definition)
        if (workBlocks.ready) return true
        openMissingWorkBlocksDialog(player, npc, definition, workplace, workBlocks, assigning = false)
        return false
    }

    private fun firstMeetingPrompt(player: ServerPlayer, definition: NpcDefinition, hasHome: Boolean, contractGranted: Boolean): String {
        val lore = definition.personality.llmPrompt.ifBlank {
            listOf(definition.title, definition.storeId().takeIf(String::isNotBlank)?.let { "store: $it" }.orEmpty(), definition.personality.traits.joinToString(", "))
                .filter(String::isNotBlank)
                .joinToString("; ")
                .ifBlank { "your own background, shop, and personality" }
        }
        return """
            Recognition status: recognized=false. This is the first remembered meeting between ${definition.name} and ${player.gameProfile.name}.

            First meeting instruction:
            - This is the first remembered meeting between ${definition.name} and ${player.gameProfile.name}.
            - Open like you just arrived here because of a specific lore reason based on your NPC lore/personality: $lore
            - Naturally ask who the player is, then acknowledge their name as ${player.gameProfile.name}.
            - If you already have a home, make this a warm first-time introduction or town meetup instead of asking for housing.
            - Keep it in character and make the arrival reason feel personal to ${definition.name}, not generic.
            - Do not say "LORE REASON" or mention these instructions.
            - If you do not have a home, ask for a home or bed and mention the rent contract${if (contractGranted) " the player just received" else ""}.
            - If you already have a home, end with a normal first-meeting hook instead of asking for housing.
            - Use <b>...</b> on the key name, item, place, number, or action the player should notice.
            - Current housing status: ${if (hasHome) "has home" else "needs home"}.
        """.trimIndent()
    }

    private fun trainClass(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition) {
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val role = trainingRole(definition)
        val settings = NpcConfig.settings()
        if (role == null) {
            val message = trainingText(settings.training.unknownClassMessage, player, definition, "Unknown", emptyList())
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, message, false, friendship.level, closeOnly = true, closeLabel = "OKAY"))
            NpcStore.recordConversation(definition.id, player, definition.name, message, "npc_class_training_unknown")
            return
        }

        val record = RoleStore.role(player)
        val roleName = role.displayName.ifBlank { role.id }
        if (!workBypassEnabled && NpcStore.workplacePos(definition.id) == null) {
            failClassTraining(player, npc, definition, roleName, listOf("${definition.name} needs an assigned workplace before training."), friendship.level, workplaceRequired = true)
            return
        }

        val known = role.id in record.unlockedClasses || role.id in record.activeClassIds || role.id == record.classId
        if (known) {
            val message = trainingText(settings.training.alreadyKnownMessage, player, definition, roleName, emptyList())
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, message, false, friendship.level, closeOnly = true, closeLabel = "OKAY"))
            NpcStore.recordConversation(definition.id, player, definition.name, message, "npc_class_training_known")
            return
        }

        when (val mentor = ClassMentorQuestService.handleTraining(player, npc, definition, role, friendship.level)) {
            ClassMentorTrainingResult.Handled -> return
            is ClassMentorTrainingResult.LicenseDenied -> failClassTraining(player, npc, definition, role, roleName, mentor.conditions, friendship.level, changeOffer = mentor.changeOffer)
        }
    }

    private fun completeClassTraining(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, role: RoleDefinition, roleName: String, friendshipLevel: Int) {
        RoleStore.addClass(player, role.id)
        RoleClassEquipmentRules.grantStartingItems(player, role.id)
        RolesNetwork.syncAllPlayers()
        val settings = NpcConfig.settings()
        val fallback = trainingText(settings.training.successMessage, player, definition, roleName, emptyList())
        val llmEnabled = settings.llm.enabled && settings.llmMessageUsage.classTraining
        val responseToken = if (llmEnabled) NpcDialogTokens.next() else 0L
        NpcNetwork.openDialog(player, dialogPayload(definition, npc, if (llmEnabled) "..." else fallback, false, friendshipLevel, closeOnly = true, closeLabel = "OKAY", responseToken = responseToken))
        SnackbarNetwork.send(player, SnackbarNotification.npc(definition.id, "CLASS TRAINING COMPLETE", "Learned $roleName.", SnackbarType.SUCCESS, SnackbarSounds.REWARD))
        if (llmEnabled) {
            NpcLlmService.event(player, npc, definition, fallback, trainingText(settings.training.successLlmPrompt, player, definition, roleName, emptyList()), npcRecordType = "npc_class_training_success", responseToken = responseToken)
        } else {
            NpcStore.recordConversation(definition.id, player, definition.name, fallback, "npc_class_training_success")
            relayNpcDialog(player, npc, definition, fallback)
        }
    }

    private fun failClassTraining(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, roleName: String, conditions: List<String>, friendshipLevel: Int, workplaceRequired: Boolean = false) {
        failClassTraining(player, npc, definition, null, roleName, conditions, friendshipLevel, workplaceRequired)
    }

    private fun failClassTraining(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, role: RoleDefinition?, roleName: String, conditions: List<String>, friendshipLevel: Int, workplaceRequired: Boolean = false, changeOffer: dev.gisketch.chowkingdom.roles.ClassChangeOffer? = null) {
        val settings = NpcConfig.settings()
        val conditionText = conditions.ifEmpty { listOf("Unknown class license condition failed.") }
        val messageTemplate = if (workplaceRequired) settings.training.workplaceRequiredMessage else settings.training.failedMessage
        val promptTemplate = if (workplaceRequired) settings.training.workplaceRequiredLlmPrompt else settings.training.failedLlmPrompt
        val offerText = if (role != null && changeOffer != null) {
            " " + trainingText(settings.training.changeOfferMessage, player, definition, roleName, conditionText, changeOffer.cost, classClassificationLabel(role))
        } else ""
        val fallback = trainingText(messageTemplate, player, definition, roleName, conditionText) + " " + conditionText.joinToString(" ") + offerText
        val llmEnabled = settings.llm.enabled && settings.llmMessageUsage.classTraining
        val responseToken = if (llmEnabled) NpcDialogTokens.next() else 0L
        NpcNetwork.openDialog(
            player,
            dialogPayload(
                definition,
                npc,
                if (llmEnabled) "..." else fallback,
                false,
                friendshipLevel,
                closeOnly = changeOffer == null,
                closeLabel = "OKAY",
                responseToken = responseToken,
                classChangeAvailable = changeOffer != null,
                classChangeCost = changeOffer?.cost ?: 0L,
            ),
        )
        if (llmEnabled) {
            val prompt = trainingText(promptTemplate, player, definition, roleName, conditionText, changeOffer?.cost ?: 0L, role?.let(::classClassificationLabel).orEmpty()) +
                if (role != null && changeOffer != null) " " + trainingText(settings.training.changeOfferLlmPrompt, player, definition, roleName, conditionText, changeOffer.cost, classClassificationLabel(role), changeOffer.candidates.joinToString(", ") { candidate -> candidate.displayName }) else ""
            NpcLlmService.event(player, npc, definition, fallback, prompt, npcRecordType = "npc_class_training_failed", responseToken = responseToken)
        } else {
            NpcStore.recordConversation(definition.id, player, definition.name, fallback, "npc_class_training_failed")
            relayNpcDialog(player, npc, definition, fallback)
        }
    }

    private fun openClassChangeSelection(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition) {
        val role = trainingRole(definition) ?: return invalidClassChange(player, npc, definition)
        val offer = ClassLicenses.changeOffer(player, role) ?: return invalidClassChange(player, npc, definition)
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val roleName = role.displayName.ifBlank { role.id }
        val settings = NpcConfig.settings()
        val message = trainingText(NpcConfig.settings().training.changeSelectMessage, player, definition, roleName, emptyList(), offer.cost, classClassificationLabel(role))
        NpcNetwork.openDialog(
            player,
            dialogPayload(
                definition,
                npc,
                message,
                false,
                friendship.level,
                closeLabel = "CANCEL",
                dialogMode = "class_change",
                classChangeCost = offer.cost,
                classChangeOptions = offer.candidates.map { candidate ->
                    val lostUpgrades = ClassLicenses.invalidatedUpgradeClassesAfterChange(player, candidate.classId, role.id)
                    val warning = lostUpgrades.takeIf { it.isNotEmpty() }?.joinToString(", ") { lost -> lost.displayName }?.let { lostNames ->
                        trainingText(settings.training.changeLostUpgradesWarning, player, definition, roleName, emptyList(), offer.cost, classClassificationLabel(role), lostClasses = lostNames)
                    }.orEmpty()
                    NpcClassChangeOption(candidate.classId, candidate.displayName, warning)
                },
            ),
        )
    }

    private fun completeClassChange(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, oldClassId: String) {
        val role = trainingRole(definition) ?: return invalidClassChange(player, npc, definition)
        val offer = ClassLicenses.changeOffer(player, role) ?: return invalidClassChange(player, npc, definition)
        val candidate = offer.candidates.firstOrNull { candidate -> candidate.classId == oldClassId } ?: return invalidClassChange(player, npc, definition)
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val settings = NpcConfig.settings()
        val roleName = role.displayName.ifBlank { role.id }
        val balance = ChowcoinStore.get(player)
        if (balance < offer.cost) {
            val message = trainingText(settings.training.changeFailedFundsMessage, player, definition, roleName, emptyList(), offer.cost, classClassificationLabel(role))
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, message, false, friendship.level, closeOnly = true, closeLabel = "OKAY"))
            ChowcoinNetwork.syncTo(player)
            SnackbarNetwork.send(player, SnackbarNotification.texture(SnackbarIcons.CHOWCOIN_TEXTURE, "JOB CHANGE FAILED", "Need ${offer.cost} chowcoins.", SnackbarType.ERROR, SnackbarSounds.ERROR))
            return
        }
        val lostUpgrades = ClassLicenses.invalidatedUpgradeClassesAfterChange(player, candidate.classId, role.id)
        if (!RoleStore.replaceClass(player, candidate.classId, role.id, lostUpgrades.map { lost -> lost.classId }.toSet())) return invalidClassChange(player, npc, definition)
        ChowcoinStore.set(player, balance - offer.cost)
        RoleClassEquipmentRules.grantStartingItems(player, role.id)
        RolesNetwork.syncAllPlayers()
        ChowcoinNetwork.syncTo(player)
        val lostText = lostUpgrades.joinToString(", ") { lost -> lost.displayName }
        val warningText = lostText.takeIf(String::isNotBlank)?.let { lost -> " Removed $lost." }.orEmpty()
        val message = trainingText(settings.training.changeSuccessMessage, player, definition, roleName, emptyList(), offer.cost, classClassificationLabel(role), lostClasses = lostText) + warningText
        NpcNetwork.openDialog(player, dialogPayload(definition, npc, message, false, friendship.level, closeOnly = true, closeLabel = "OKAY"))
        SnackbarNetwork.send(player, SnackbarNotification.texture(SnackbarIcons.CHOWCOIN_TEXTURE, "JOB CHANGE COMPLETE", "Replaced ${candidate.displayName} with $roleName for ${offer.cost} chowcoins.$warningText", SnackbarType.SUCCESS, SnackbarSounds.REWARD))
        NpcStore.recordConversation(definition.id, player, definition.name, message, "npc_class_change_success")
        relayNpcDialog(player, npc, definition, message)
    }

    private fun invalidClassChange(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition) {
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val roleName = trainingRole(definition)?.displayName?.ifBlank { trainingRole(definition)?.id.orEmpty() } ?: "Unknown"
        val message = trainingText(NpcConfig.settings().training.changeInvalidMessage, player, definition, roleName, emptyList())
        NpcNetwork.openDialog(player, dialogPayload(definition, npc, message, false, friendship.level, closeOnly = true, closeLabel = "OKAY"))
    }

    private fun trainingRole(definition: NpcDefinition): RoleDefinition? = definition.classId.trim().takeIf(String::isNotBlank)?.let(RolesConfig::roleClass)

    private fun trainingText(template: String, player: ServerPlayer, definition: NpcDefinition, roleName: String, conditions: List<String>, cost: Long = 0L, classification: String = "", changeOptions: String = "", lostClasses: String = ""): String = template
        .replace("{player}", player.gameProfile.name)
        .replace("{npc}", definition.name)
        .replace("{class}", roleName)
        .replace("{conditions}", conditions.joinToString("; ").ifBlank { "None" })
        .replace("{overall_level}", JobLevels.overallLevel(player).toString())
        .replace("{cost}", cost.toString())
        .replace("{classification}", classification)
        .replace("{change_options}", changeOptions.ifBlank { "None" })
        .replace("{lost_classes}", lostClasses.ifBlank { "None" })

    private fun classClassificationLabel(role: RoleDefinition): String = if (RolesConfig.isStarterClass(role.id)) "starter" else "upgrade"

    private fun openWorkDialog(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition) {
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val workplace = NpcStore.workplacePos(definition.id)
        if (workplace == null) {
            giveWorkApplication(player, npc, definition, moving = false)
            return
        }
        npc.startTalkingTo(player, NPC_DIALOG_DURATION_TICKS)
        val fallback = "My workplace is at ${workplace.toShortString()}. Move it, or fire me from this post?"
        val responseToken = NpcDialogTokens.next()
        NpcNetwork.openDialog(player, dialogPayload(definition, npc, if (workLlmEnabled { it.workManage }) "..." else fallback, false, friendship.level, responseToken = responseToken, dialogMode = "work"))
        if (workLlmEnabled { it.workManage }) {
            NpcLlmService.event(player, npc, definition, fallback, NpcConfig.settings().work.manageLlmPrompt.replace("{workplace}", workplace.toShortString()), npcRecordType = "npc_work_manage", responseToken = responseToken)
        }
    }

    private fun giveWorkApplication(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, moving: Boolean) {
        giveStack(player, createJobApplication(definition.id))
        npc.startTalkingTo(player, NPC_DIALOG_DURATION_TICKS)
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val fallback = if (moving) {
            "Okay. Right-click the new work block with that application."
        } else {
            "Use this job application on my work block. I will work around it."
        }
        val llmEnabled = workLlmEnabled { usage -> if (moving) usage.workMove else usage.workApplication }
        val responseToken = NpcDialogTokens.next()
        NpcNetwork.openDialog(player, dialogPayload(definition, npc, if (llmEnabled) "..." else fallback, false, friendship.level, closeOnly = true, closeLabel = "OKAY", responseToken = responseToken))
        if (llmEnabled) {
            val prompt = if (moving) NpcConfig.settings().work.moveLlmPrompt else NpcConfig.settings().work.applicationLlmPrompt
            NpcLlmService.event(player, npc, definition, fallback, prompt, npcRecordType = if (moving) "npc_work_move" else "npc_work_application", responseToken = responseToken)
        }
        SnackbarNetwork.send(player, SnackbarNotification.npc(definition.id, "JOB APPLICATION RECEIVED", "Right-click a work block for ${definition.name}.", SnackbarType.SUCCESS, SnackbarSounds.REWARD))
    }

    private fun fireNpcWorkplace(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition) {
        NpcStore.clearWorkplace(definition.id, fired = true)
        npc.navigation.stop()
        npc.startTalkingTo(player, NPC_DIALOG_DURATION_TICKS)
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val fallback = "Okay. I am unemployed for now."
        val responseToken = NpcDialogTokens.next()
        NpcNetwork.openDialog(player, dialogPayload(definition, npc, if (workLlmEnabled { it.workFire }) "..." else fallback, false, friendship.level, closeOnly = true, closeLabel = "OKAY", responseToken = responseToken))
        if (workLlmEnabled { it.workFire }) {
            NpcLlmService.event(player, npc, definition, fallback, NpcConfig.settings().work.fireLlmPrompt, npcRecordType = "npc_work_fire", responseToken = responseToken)
        }
        SnackbarNetwork.send(player, SnackbarNotification.npc(definition.id, "NPC FIRED", "${definition.name} has no workplace now.", SnackbarType.GENERIC, SnackbarSounds.GENERIC))
    }

    private fun workLlmEnabled(flag: (NpcLlmMessageUsageDefinition) -> Boolean): Boolean {
        val settings = NpcConfig.settings()
        return settings.llm.enabled && flag(settings.llmMessageUsage)
    }

    private fun openNpcShop(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition) {
        val currentHour = NpcTime.hour(player.level())
        val storeId = definition.storeId().lowercase()
        if (storeId.isBlank()) {
            npcSnackbar(player, definition.name, "No shop ready.", SnackbarType.ERROR)
            pendingShopNpcs.remove(player.uuid)
            return
        }
        val lockedLicense = TechLicenseFeature.shopLock(player, definition)
        if (lockedLicense != null) {
            val friendship = NpcStore.friendshipSnapshot(definition.id, player)
            val message = "Earn <b>${lockedLicense.displayName}</b> before using my shop."
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, message, false, friendship.level, closeOnly = true, closeLabel = "OKAY", player = player))
            return
        }
        if (!workBypassEnabled && NpcStore.workplacePos(definition.id) == null) {
            val friendship = NpcStore.friendshipSnapshot(definition.id, player)
            val message = if (NpcStore.workFired(definition.id)) "I do not have a job right now." else "I need a workplace before I can open shop."
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, message, false, friendship.level, closeOnly = true, closeLabel = "OKAY"))
            return
        }
        if (NpcTime.activityAt(definition.schedule, player.level()) != "work") {
            val friendship = NpcStore.friendshipSnapshot(definition.id, player)
            val nextOpen = nextWorkOpening(definition, currentHour)
            val fallback = if (nextOpen == null) "My shop is closed right now." else "My shop is closed right now. Come back around ${nextOpen.toString().padStart(2, '0')}:00."
            val responseToken = NpcDialogTokens.next()
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, "...", false, friendship.level, closeOnly = true, closeLabel = "OKAY", responseToken = responseToken))
            if (NpcConfig.settings().llm.enabled) {
                NpcLlmService.event(
                    player,
                    npc,
                    definition,
                    fallback,
                    "${player.gameProfile.name} tried to open your shop, but you are not working right now. Current hour is ${currentHour.toString().padStart(2, '0')}:00. Your next work opening is ${nextOpen?.toString()?.padStart(2, '0') ?: "unknown"}:00. Reply in-character and tell them when to expect the shop to open.",
                    npcRecordType = "npc_shop_closed",
                    responseToken = responseToken,
                )
            } else {
                NpcNetwork.sendTalkResponse(player, definition.id, fallback, responseToken)
                NpcStore.recordConversation(definition.id, player, definition.name, fallback, "npc_shop_closed")
            }
            return
        }
        val workplace = NpcStore.workplacePos(definition.id)
        if (!workBypassEnabled && workplace != null) {
            val workBlocks = workBlockStatus(player.level(), workplace, definition)
            if (!workBlocks.ready) {
                openMissingWorkBlocksDialog(player, npc, definition, workplace, workBlocks, assigning = false)
                return
            }
        }
        if (!StoreShopFeature.openStore(player, storeId, definition.storeStockKey(), "${definition.name}'s stock")) {
            npcSnackbar(player, definition.name, "No shop ready.", SnackbarType.ERROR)
            pendingShopNpcs.remove(player.uuid)
            return
        }
        pendingShopNpcs[player.uuid] = definition.id
    }

    private fun nextWorkOpening(definition: NpcDefinition, currentHour: Int): Int? {
        val workStarts = definition.schedule.activities.filter { entry -> entry.activity == "work" }.map { entry -> entry.fromHour }
        if (workStarts.isEmpty()) return null
        return workStarts.filter { hour -> hour > currentHour }.minOrNull() ?: workStarts.minOrNull()
    }

    private fun openMissingWorkBlocksDialog(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, workplace: BlockPos, status: NpcWorkBlockStatus, assigning: Boolean) {
        npc.startTalkingTo(player, NPC_DIALOG_DURATION_TICKS)
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val missing = formatMissingWorkBlocks(status)
        val requirements = formatRequiredWorkBlocks(status)
        val fallback = if (assigning) {
            "I need $missing near this workplace before I can start working."
        } else {
            "I cannot open shop yet. I am missing $missing near my workplace."
        }
        val llmEnabled = workLlmEnabled { usage -> usage.workMissingBlocks }
        val responseToken = if (llmEnabled) NpcDialogTokens.next() else 0L
        NpcNetwork.openDialog(player, dialogPayload(definition, npc, if (llmEnabled) "..." else fallback, false, friendship.level, closeOnly = true, closeLabel = "OKAY", responseToken = responseToken))
        if (llmEnabled) {
            val prompt = NpcConfig.settings().work.missingWorkBlocksLlmPrompt
                .replace("{missing}", missing)
                .replace("{requirements}", requirements)
                .replace("{workplace}", workplace.toShortString())
                .replace("{action}", if (assigning) "assigning a new workplace" else "opening shop during work hours")
            NpcLlmService.event(player, npc, definition, fallback, prompt, npcRecordType = "npc_work_blocks_missing", responseToken = responseToken)
        } else {
            NpcStore.recordConversation(definition.id, player, definition.name, fallback, "npc_work_blocks_missing")
        }
        npcSnackbar(player, definition.name, "Missing: $missing", SnackbarType.ERROR)
    }

    private fun workBlockStatus(level: Level, center: BlockPos, definition: NpcDefinition): NpcWorkBlockStatus {
        val counts = definition.workBlocks.map { requirement ->
            NpcWorkBlockCount(requirement, countWorkBlocks(level, center, definition, requirement) + countWorkEntities(level, center, definition, requirement))
        }
        return NpcWorkBlockStatus(counts)
    }

    private fun countWorkBlocks(level: Level, center: BlockPos, definition: NpcDefinition, requirement: NpcWorkBlockRequirementDefinition): Int {
        val radius = definition.jobDefinition.workScanRadius
        val yRadius = max(3, radius / 2)
        var count = 0
        val beds = linkedSetOf<BlockPos>()
        BlockPos.betweenClosed(center.offset(-radius, -yRadius, -radius), center.offset(radius, yRadius, radius)).forEach { mutablePos ->
            val pos = mutablePos.immutable()
            val state = level.getBlockState(pos)
            if (!matchesWorkBlock(state, requirement.id)) return@forEach
            if (state.`is`(BlockTags.BEDS)) beds += canonicalBedPos(level, pos) else count++
        }
        return count + beds.size
    }

    private fun countWorkEntities(level: Level, center: BlockPos, definition: NpcDefinition, requirement: NpcWorkBlockRequirementDefinition): Int {
        val radius = definition.jobDefinition.workScanRadius.toDouble()
        val yRadius = max(3, definition.jobDefinition.workScanRadius / 2).toDouble()
        val box = AABB(center.x - radius, center.y - yRadius, center.z - radius, center.x + radius + 1.0, center.y + yRadius + 1.0, center.z + radius + 1.0)
        return level.getEntitiesOfClass(Entity::class.java, box) { entity -> matchesWorkEntity(entity.type, requirement.id) }.size
    }

    private fun matchesWorkBlock(state: BlockState, raw: String): Boolean {
        if (raw.startsWith("#")) {
            val id = runCatching { ResourceLocation.parse(raw.removePrefix("#")) }.getOrNull() ?: return false
            return state.`is`(TagKey.create(Registries.BLOCK, id))
        }
        return BuiltInRegistries.BLOCK.getKey(state.block).toString() == raw
    }

    private fun matchesWorkEntity(type: EntityType<*>, raw: String): Boolean {
        if (raw.startsWith("#")) {
            val id = runCatching { ResourceLocation.parse(raw.removePrefix("#")) }.getOrNull() ?: return false
            return type.`is`(TagKey.create(Registries.ENTITY_TYPE, id))
        }
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString() == raw
    }

    private fun formatMissingWorkBlocks(status: NpcWorkBlockStatus): String = status.counts
        .filter { count -> count.missing > 0 }
        .joinToString(", ") { count -> "${count.missing} ${count.requirement.label()}" }
        .ifBlank { "nothing" }

    private fun formatRequiredWorkBlocks(status: NpcWorkBlockStatus): String = status.counts
        .joinToString(", ") { count -> "${count.present}/${count.requirement.count} ${count.requirement.label()}" }
        .ifBlank { "none" }

    fun syncFriends(player: ServerPlayer) {
        val currentHour = NpcTime.hour(player.level())
        val entries = NpcConfig.all().sortedBy { definition -> definition.name }.mapNotNull { definition ->
            existingNpc(player.server, definition.id) ?: return@mapNotNull null
            val friendship = NpcStore.friendshipSnapshot(definition.id, player)
            val giftPeriod = NpcTime.periodForReset(player.level().dayTime, definition.gifts.resetHour)
            val giftCount = NpcStore.giftCount(definition.id, player, giftPeriod)
            val giftLimit = definition.gifts.dailyLimit.coerceAtLeast(0)
            val quest = NpcQuestService.friendSummary(player, definition)
            NpcFriendEntryPayload(
                npcId = definition.id,
                name = definition.name,
                title = definition.title,
                friendshipPoints = friendship.points,
                friendshipLevel = friendship.level,
                giftStatus = if (giftLimit <= 0) "Gifts unavailable" else if (giftCount >= giftLimit) "Gifted today ($giftCount/$giftLimit)" else "Gift available ($giftCount/$giftLimit)",
                shopStatus = friendShopStatus(definition, currentHour, player),
                missionStatus = quest.status,
                aliveStatus = friendAliveStatus(player.server, definition),
                missionProgress = quest.progress,
                missionGoal = quest.goal,
            )
        }
        NpcNetwork.syncFriends(player, NpcFriendsSyncPayload(entries))
    }

    private fun friendAliveStatus(server: MinecraftServer, definition: NpcDefinition): String = when {
        existingNpc(server, definition.id)?.isAlive == true -> "Alive"
        NpcStore.isDead(definition.id) -> "Dead"
        else -> "Away"
    }

    private fun friendShopStatus(definition: NpcDefinition, currentHour: Int, player: ServerPlayer): String {
        if (definition.storeId().isBlank()) return "No shop"
        return if (NpcTime.activityAt(definition.schedule, player.level()) == "work") {
            val workplace = NpcStore.workplacePos(definition.id) ?: return if (NpcStore.workFired(definition.id)) "Unemployed" else "No workplace"
            val workBlocks = workBlockStatus(player.level(), workplace, definition)
            if (!workBlocks.ready) return "Missing ${formatMissingWorkBlocks(workBlocks)}"
            val close = currentWorkClose(definition, currentHour)
            if (close == null) "Shop Open" else "Shop Open (closes at ${formatHour(close)})"
        } else {
            val open = nextWorkOpening(definition, currentHour)
            if (open == null) "Shop closed" else "Shop closed (opens at ${formatHour(open)})"
        }
    }

    private fun currentWorkClose(definition: NpcDefinition, currentHour: Int): Int? = definition.schedule.activities
        .firstOrNull { entry -> entry.activity == "work" && hourInRange(currentHour, entry.fromHour, entry.toHour) }
        ?.toHour

    private fun hourInRange(hour: Int, from: Int, to: Int): Boolean = if (from <= to) hour in from until to else hour >= from || hour < to

    private fun formatHour(hour: Int): String {
        val normalized = Math.floorMod(hour, 24)
        val displayHour = when (val value = normalized % 12) {
            0 -> 12
            else -> value
        }
        val suffix = if (normalized < 12) "AM" else "PM"
        return "$displayHour:00 $suffix"
    }

    fun onStorePurchase(player: ServerPlayer, storeId: String, stockKey: String, quantity: Int, itemName: String, totalCost: Long) {
        val normalizedStoreId = storeId.lowercase()
        val normalizedStockKey = stockKey.lowercase()
        val definition = pendingShopNpcs.remove(player.uuid)
            ?.let(NpcConfig::get)
            ?.takeIf { npc -> npc.storeId().equals(normalizedStoreId, ignoreCase = true) }
            ?: NpcConfig.all().firstOrNull { npc -> npc.storeId().equals(normalizedStoreId, ignoreCase = true) && npc.storeStockKey().equals(normalizedStockKey, ignoreCase = true) }
            ?: return
        val npc = existingNpc(player.server, definition.id) ?: return
        if (npc.level() != player.level()) return
        if (player.distanceToSqr(npc) > NPC_DIALOG_ACTION_DISTANCE_SQR) return
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val message = friendshipMessage(definition.shopMessages.forQuantity(quantity), friendship, player, definition, itemName, quantity = quantity, totalCost = totalCost)
        npc.startTalkingTo(player, NPC_DIALOG_DURATION_TICKS)
        NpcStore.recordConversation(definition.id, player, player.gameProfile.name, "buys $quantity $itemName from ${definition.name}", "player_shop_buy")
        if (NpcConfig.settings().llm.enabled && NpcConfig.settings().llmMessageUsage.shop) {
            val responseToken = NpcDialogTokens.next()
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, "...", false, friendship.level, closeOnly = true, closeLabel = "OKAY", responseToken = responseToken))
            NpcLlmService.event(
                player,
                npc,
                definition,
                message,
                "${player.gameProfile.name} bought $quantity x $itemName from your shop for $totalCost chowcoins. Reply with a short in-character shop follow-up.",
                npcRecordType = "npc_shop_message",
                responseToken = responseToken,
            )
            return
        }
        NpcStore.recordConversation(definition.id, player, definition.name, message, "npc_shop_message")
        NpcNetwork.openDialog(player, dialogPayload(definition, npc, message, false, friendship.level, closeOnly = true, closeLabel = "OKAY"))
    }

    private fun giftToNpc(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition) {
        val stack = giftStack(player)
        if (stack.isEmpty) {
            if (NpcConfig.settings().llm.enabled && NpcConfig.settings().llmMessageUsage.gift) {
                val friendship = NpcStore.friendshipSnapshot(definition.id, player)
                val fallback = "Hold an item if you want to give me something."
                val responseToken = NpcDialogTokens.next()
                NpcNetwork.openDialog(player, dialogPayload(definition, npc, "...", false, friendship.level, closeOnly = true, closeLabel = "OKAY", responseToken = responseToken))
                NpcLlmService.event(
                    player,
                    npc,
                    definition,
                    fallback,
                    "${player.gameProfile.name} tried to gift you, but they are not holding an item. Reply naturally and tell them they need to hold the gift item.",
                    npcRecordType = "npc_gift_unavailable",
                    responseToken = responseToken,
                )
                return
            }
            npcSnackbar(player, definition.name, "Hold an item to gift.", SnackbarType.ERROR)
            return
        }
        if (RelicRouletteFeature.rejectTransfer(player, stack, "gifts")) return
        val limit = definition.gifts.dailyLimit
        val period = NpcTime.periodForReset(player.level().dayTime, definition.gifts.resetHour)
        if (!NpcStore.recordGiftIfAllowed(definition.id, player, period, limit)) {
            if (NpcConfig.settings().llm.enabled && NpcConfig.settings().llmMessageUsage.gift) {
                val friendship = NpcStore.friendshipSnapshot(definition.id, player)
                val fallback = "I can't receive another gift today. Come back after ${definition.gifts.resetHour.toString().padStart(2, '0')}:00."
                val responseToken = NpcDialogTokens.next()
                NpcNetwork.openDialog(player, dialogPayload(definition, npc, "...", false, friendship.level, closeOnly = true, closeLabel = "OKAY", responseToken = responseToken))
                NpcLlmService.event(
                    player,
                    npc,
                    definition,
                    fallback,
                    "${player.gameProfile.name} tried to gift you ${stack.hoverName.string}, but your gift limit for today is already reached. Your next gift reset is ${definition.gifts.resetHour.toString().padStart(2, '0')}:00. Reply with a good in-character reason that you cannot receive another gift today.",
                    npcRecordType = "npc_gift_unavailable",
                    responseToken = responseToken,
                )
                return
            }
            npcSnackbar(player, definition.name, "Can receive another gift at ${definition.gifts.resetHour.toString().padStart(2, '0')}:00.", SnackbarType.ERROR)
            return
        }
        val itemName = stack.hoverName.string
        val configuredMood = configuredGiftMood(definition, stack)
        if (configuredMood == null && NpcConfig.settings().llm.enabled && NpcConfig.settings().llmMessageUsage.gift) {
            if (!player.abilities.instabuild) stack.shrink(1)
            val responseToken = NpcDialogTokens.next()
            val friendship = NpcStore.friendshipSnapshot(definition.id, player)
            val fallbackMood = "neutral"
            val fallback = friendshipMessage(definition.friendshipMessages.gift, friendship, player, definition, itemName, fallbackMood)
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, "...", false, friendship.level, closeOnly = true, closeLabel = "OKAY", responseToken = responseToken))
            NpcLlmService.giftSentiment(
                player,
                npc,
                definition,
                fallback,
                giftSentimentPrompt(definition, player, itemName),
                responseToken,
            ) { result ->
                val mood = normalizeGiftMood(result.giftSentiment)
                finishGiftToNpc(player, npc, definition, itemName, mood, result.message, responseToken, result.emote)
                if (result.memorable.isNotBlank()) NpcStore.recordPlayerMemory(player, "llm_memorable", result.memorable)
            }
            return
        }
        val mood = configuredMood ?: "neutral"
        val friendshipDelta = PerformerPerks.giftFriendshipDelta(player, mood, giftFriendshipDelta(mood))
        val appliedFriendshipDelta = NpcStore.effectiveFriendshipDelta(player, friendshipDelta)
        val friendship = NpcStore.adjustFriendship(definition.id, player, friendshipDelta, "gift_$mood")
        showFriendshipDelta(npc.level() as? ServerLevel, npc, appliedFriendshipDelta)
        val message = friendshipMessage(definition.friendshipMessages.gift, friendship, player, definition, itemName, mood)
        if (!player.abilities.instabuild) stack.shrink(1)
        NpcStore.recordConversation(definition.id, player, player.gameProfile.name, "gifts $itemName to ${definition.name}", "player_gift")
        NpcStore.recordPlayerMemory(player, "gift_to_npc", "${player.gameProfile.name} gave $itemName to ${definition.name}; reaction mood was $mood.")
        if (NpcConfig.settings().llm.enabled && NpcConfig.settings().llmMessageUsage.gift) {
            val responseToken = NpcDialogTokens.next()
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, "...", false, friendship.level, closeOnly = true, closeLabel = "OKAY", responseToken = responseToken, friendshipDelta = appliedFriendshipDelta))
            NpcLlmService.event(
                player,
                npc,
                definition,
                message,
                "${player.gameProfile.name} gifted you $itemName. Gift mood is $mood. Reply with a short in-character gift reaction.",
                npcRecordType = "npc_gift_$mood",
                responseToken = responseToken,
            )
            return
        }
        NpcStore.recordConversation(definition.id, player, definition.name, message, "npc_gift_$mood")
        NpcNetwork.openDialog(player, dialogPayload(definition, npc, message, false, friendship.level, closeOnly = true, closeLabel = "OKAY", friendshipDelta = appliedFriendshipDelta))
        relayNpcDialog(player, npc, definition, message)
    }

    private fun finishGiftToNpc(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, itemName: String, mood: String, message: String, responseToken: Long, emote: String = NpcEmoteCatalog.NONE) {
        val friendshipDelta = PerformerPerks.giftFriendshipDelta(player, mood, giftFriendshipDelta(mood))
        val appliedFriendshipDelta = NpcStore.effectiveFriendshipDelta(player, friendshipDelta)
        val friendship = NpcStore.adjustFriendship(definition.id, player, friendshipDelta, "gift_$mood")
        showFriendshipDelta(npc.level() as? ServerLevel, npc, appliedFriendshipDelta)
        val fallback = friendshipMessage(definition.friendshipMessages.gift, friendship, player, definition, itemName, mood)
        val reply = message.trim().ifBlank { fallback }
        npc.startTalkingTo(player, NPC_DIALOG_DURATION_TICKS)
        NpcEmoteController.tryPlayOrDefaultSpeaking(npc, NpcEmoteSurfaces.CONVERSATION, emote, "npc_gift_$mood")
        NpcStore.recordConversation(definition.id, player, player.gameProfile.name, "gifts $itemName to ${definition.name}", "player_gift")
        NpcStore.recordPlayerMemory(player, "gift_to_npc", "${player.gameProfile.name} gave $itemName to ${definition.name}; reaction mood was $mood.")
        NpcStore.recordConversation(definition.id, player, definition.name, reply, "npc_gift_$mood")
        NpcNetwork.openDialog(player, dialogPayload(definition, npc, reply, false, friendship.level, closeOnly = true, closeLabel = "OKAY", friendshipDelta = appliedFriendshipDelta))
        relayNpcDialog(player, npc, definition, reply)
    }

    private fun giftStack(player: ServerPlayer): ItemStack = if (!player.mainHandItem.isEmpty) player.mainHandItem else player.offhandItem

    private fun configuredGiftMood(definition: NpcDefinition, stack: ItemStack): String? = when {
        matchesGift(definition.gifts.loved, stack) -> "loved"
        matchesGift(definition.gifts.liked, stack) -> "liked"
        matchesGift(definition.gifts.disliked, stack) -> "disliked"
        else -> null
    }

    private fun normalizeGiftMood(raw: String): String = when (raw.trim().lowercase()) {
        "loved", "love" -> "loved"
        "liked", "like" -> "liked"
        "disliked", "dislike" -> "disliked"
        else -> "neutral"
    }

    private fun giftSentimentPrompt(definition: NpcDefinition, player: ServerPlayer, itemName: String): String = definition.gifts.llmSentimentPrompt
        .replace("{player}", player.gameProfile.name)
        .replace("{npc}", definition.name)
        .replace("{item}", itemName)

    private fun matchesGift(values: List<String>, stack: ItemStack): Boolean = values.any { raw ->
        if (raw.startsWith("#")) {
            val id = runCatching { ResourceLocation.parse(raw.removePrefix("#")) }.getOrNull() ?: return@any false
            stack.`is`(TagKey.create(Registries.ITEM, id))
        } else {
            BuiltInRegistries.ITEM.getKey(stack.item).toString() == raw
        }
    }

    private fun giftFriendshipDelta(mood: String): Int = when (mood) {
        "loved" -> 50
        "liked" -> 25
        "disliked" -> -50
        else -> 5
    }

    private fun npcSnackbar(player: ServerPlayer, title: String, content: String, type: SnackbarType) {
        SnackbarNetwork.send(player, SnackbarNotification.item(SnackbarIcons.ERROR, title, content, type, SnackbarSounds.forType(type)))
    }

    fun dialogPayload(definition: NpcDefinition, npc: ChowNpcEntity, message: String, contractGranted: Boolean, friendshipLevel: Int, closeOnly: Boolean = false, closeLabel: String = "BYE", responseToken: Long = 0L, dialogMode: String = "normal", startTalkMode: Boolean = false, friendshipDelta: Int = 0, classChangeAvailable: Boolean = false, classChangeCost: Long = 0L, classChangeOptions: List<NpcClassChangeOption> = emptyList(), quizChoices: List<NpcQuizChoice> = emptyList(), bossContractsAvailable: Boolean = BossEventsFeature.contractsAvailable(definition), bossClaimAvailable: Boolean = false, leagueAvailable: Boolean = GymLeagueFeature.leagueAvailable(definition), challengeAvailable: Boolean = false, challengeDisabledReason: String = "", friendlyBattleAvailable: Boolean = false, retryBattleAvailable: Boolean = false, leagueCompassAvailable: Boolean = false, player: ServerPlayer? = null): NpcDialogPayload {
        val techOption = player?.let { TechLicenseFeature.dialogOption(it, definition) }
        val shopAvailable = definition.storeId().isNotBlank() && (player == null || TechLicenseFeature.shopLock(player, definition) == null)
        return NpcDialogPayload(
        definition.id,
        if (workBypassEnabled) "${definition.name} WORK OFF NPC" else definition.name,
        definition.title,
        message,
        contractGranted,
        closeOnly = closeOnly,
        closeLabel = closeLabel,
        friendshipLevel = friendshipLevel,
        friendshipDelta = friendshipDelta,
        npcEntityId = npc.id,
        animalesePitch = definition.voice.animalesePitch,
        animalesePitchMultiplier = definition.voice.pitch,
        animaleseVolume = definition.voice.volume,
        animaleseRadius = definition.voice.radius,
        talkEnabled = NpcConfig.settings().llm.enabled,
        responseToken = responseToken,
        dialogMode = dialogMode,
        startTalkMode = startTalkMode,
        trainingAvailable = trainingRole(definition) != null,
        classChangeAvailable = classChangeAvailable,
        classChangeCost = classChangeCost,
        classChangeOptions = classChangeOptions,
        quizChoices = quizChoices,
        bossContractsAvailable = bossContractsAvailable,
        bossClaimAvailable = bossClaimAvailable,
        leagueAvailable = leagueAvailable,
        challengeAvailable = challengeAvailable,
        challengeDisabledReason = challengeDisabledReason,
        friendlyBattleAvailable = friendlyBattleAvailable,
        retryBattleAvailable = retryBattleAvailable,
        leagueCompassAvailable = leagueCompassAvailable,
        shopAvailable = shopAvailable,
        techLicenseAvailable = techOption != null,
        techLicenseId = techOption?.licenseId.orEmpty(),
        techLicenseLabel = techOption?.label.orEmpty(),
        techLicenseIconItem = techOption?.iconItem.orEmpty(),
        techLicenseCost = techOption?.cost ?: 0L,
    )
    }

    private fun friendshipMessage(set: NpcFriendshipMessageSet, friendship: NpcFriendshipSnapshot, player: ServerPlayer, definition: NpcDefinition, itemName: String = "", mood: String = "", quantity: Int = 0, totalCost: Long = 0L): String {
        val pool = set.forCategory(friendship.category)
        return pool[player.random.nextInt(pool.size)]
            .replace("{player}", player.gameProfile.name)
            .replace("{npc}", definition.name)
            .replace("{item}", itemName)
            .replace("{mood}", mood)
            .replace("{quantity}", quantity.toString())
            .replace("{total}", totalCost.toString())
            .replace("{friendship_level}", friendship.level.toString())
            .replace("{friendship_points}", friendship.points.toString())
    }

    private fun camperMessage(pool: List<String>, player: ServerPlayer, definition: NpcDefinition): String {
        val messages = pool.ifEmpty { listOf("I need a bed before I can settle in.") }
        val selected = messages[player.random.nextInt(messages.size)]
        return selected
            .replace("{player}", player.gameProfile.name)
            .replace("{npc}", definition.name)
    }

    private fun relayNpcDialog(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, message: String) {
        val level = npc.level() as? ServerLevel ?: return
        if (!NpcEmoteController.isActive(npc)) NpcEmoteController.tryPlayDefaultSpeaking(npc, NpcEmoteSurfaces.CONVERSATION, "npc_dialog")
        sendNpcBalloon(level, npc, message, excludePlayer = player.uuid)
        relayNpcDialogToDiscord(player, definition, message)
    }

    fun relayNpcDialogToDiscord(player: ServerPlayer, definition: NpcDefinition, message: String) {
        DiscordRelay.npcDialog(player, definition.id, definition.name, message)
    }

    private fun relayNpcHurtMessage(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition) {
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val message = friendshipMessage(definition.friendshipMessages.hurt, friendship, player, definition)
        if (NpcConfig.settings().llm.enabled && NpcConfig.settings().llmMessageUsage.hurt) {
            NpcLlmService.event(
                player,
                npc,
                definition,
                message,
                "${player.gameProfile.name} hurt you. Reply with a short in-character hurt reaction that matches your current health and relationship.",
                sendTalkResponse = false,
                excludePlayerFromBalloon = false,
                npcRecordType = "npc_hurt_message",
            )
            return
        }
        NpcStore.recordConversation(definition.id, player, definition.name, message, "npc_hurt_message")
        val level = npc.level() as? ServerLevel ?: return
        sendNpcBalloon(level, npc, message)
    }

    fun showBalloonToNearby(level: ServerLevel, npc: ChowNpcEntity, message: String, durationTicks: Int = 90, excludePlayer: UUID? = null): Int = sendNpcBalloon(level, npc, message, durationTicks, excludePlayer)

    fun showBalloonToNearbyExcept(level: ServerLevel, npc: ChowNpcEntity, message: String, durationTicks: Int = 90, excludePlayers: Set<UUID>): Int {
        if (GymBattleService.isBattleLocked(npc)) return 0
        var recipients = 0
        level.players().forEach { listener ->
            if (listener.uuid !in excludePlayers && listener.distanceToSqr(npc.x, npc.y, npc.z) <= NPC_DIALOG_HEAR_RADIUS * NPC_DIALOG_HEAR_RADIUS) {
                NpcNetwork.showBalloon(listener, npc.id, message, durationTicks)
                recipients++
            }
        }
        return recipients
    }

    private fun sendNpcBalloon(level: ServerLevel, npc: ChowNpcEntity, message: String, durationTicks: Int = 90, excludePlayer: UUID? = null): Int {
        if (GymBattleService.isBattleLocked(npc)) return 0
        var recipients = 0
        level.players().forEach { listener ->
            if (listener.uuid != excludePlayer && listener.distanceToSqr(npc.x, npc.y, npc.z) <= NPC_DIALOG_HEAR_RADIUS * NPC_DIALOG_HEAR_RADIUS) {
                NpcNetwork.showBalloon(listener, npc.id, message, durationTicks)
                recipients++
            }
        }
        return recipients
    }

    private fun showFriendshipDelta(level: ServerLevel?, npc: ChowNpcEntity, delta: Int) {
        if (level == null || delta == 0) return
        val marker = if (delta > 0) "@heart.png" else "@angry.png"
        val sign = if (delta > 0) "+" else ""
        sendNpcBalloon(level, npc, "$marker $sign$delta", NPC_FRIENDSHIP_DELTA_BALLOON_TICKS)
    }

    fun assignHome(player: ServerPlayer, npcId: String, bedPos: BlockPos, stack: ItemStack): Boolean {
        val definition = NpcConfig.get(npcId) ?: run {
            player.displayClientMessage(Component.literal("Unknown NPC '$npcId'."), true)
            return false
        }
        if (!definition.housing.canMoveIn) {
            player.displayClientMessage(Component.literal("${definition.name} cannot move in."), true)
            return false
        }
        val homePos = canonicalBedPos(player.level(), bedPos)
        val currentOwner = homeOwnerAtBed(player.level(), homePos)
        if (currentOwner != null && currentOwner != npcId) {
            val ownerName = NpcConfig.get(currentOwner)?.name ?: currentOwner
            npcSnackbar(player, definition.name, "That bed already belongs to $ownerName.", SnackbarType.ERROR)
            return false
        }
        val npc = existingNpc(player.server, npcId)
        if (npc == null || npc.level() != player.level() || npc.distanceToSqr(homePos.x + 0.5, homePos.y.toDouble(), homePos.z + 0.5) > CONTRACT_BED_ASSIGN_RADIUS_SQR) {
            npcSnackbar(player, definition.name, "${definition.name} needs to be near the bed.", SnackbarType.ERROR)
            return false
        }
        NpcStore.setHome(npcId, homePos)
        NpcStore.clearActiveCamper(npcId)
        NpcStore.recordGlobalEvent("npc_new_resident", "${definition.name} became a resident on day ${NpcTime.day(player.level())}; ${player.gameProfile.name} gave them a home.")
        scheduleNextCamper(player.level())
        npc.let { npc ->
            npc.homePos = homePos.immutable()
            npc.campPos = npc.campPos ?: NpcStore.campPos(npcId)
            npc.startTalkingTo(player, NPC_DIALOG_DURATION_TICKS)
            val friendship = NpcStore.friendshipSnapshot(definition.id, player)
            val fallback = "Thank you, {player}. This bed feels like home already.".replace("{player}", player.gameProfile.name)
            val responseToken = NpcDialogTokens.next()
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, "...", false, friendship.level, closeOnly = true, closeLabel = "OKAY", responseToken = responseToken))
            if (NpcConfig.settings().llm.enabled && NpcConfig.settings().llmMessageUsage.assignedHouse) {
                NpcLlmService.event(
                    player,
                    npc,
                    definition,
                    fallback,
                    NpcConfig.settings().campers.assignedHouseLlmPrompt,
                    npcRecordType = "npc_assigned_house",
                    responseToken = responseToken,
                )
            } else {
                NpcNetwork.sendTalkResponse(player, definition.id, fallback, responseToken)
                NpcStore.recordConversation(definition.id, player, definition.name, fallback, "npc_assigned_house")
                relayNpcDialog(player, npc, definition, fallback)
            }
        }
        if (!player.abilities.instabuild) stack.shrink(1)
        SnackbarNetwork.send(player, SnackbarNotification.item(BuiltInRegistries.ITEM.getKey(RENT_CONTRACT.get()).toString(), "HOME ASSIGNED", "${definition.name} now lives here", SnackbarType.SUCCESS, SnackbarSounds.REWARD))
        return true
    }

    fun assignWorkplace(player: ServerPlayer, npcId: String, workplacePos: BlockPos, stack: ItemStack): Boolean {
        val definition = NpcConfig.get(npcId) ?: run {
            player.displayClientMessage(Component.literal("Unknown NPC '$npcId'."), true)
            return false
        }
        val npc = existingNpc(player.server, npcId)
        if (npc == null || npc.level() != player.level() || npc.distanceToSqr(workplacePos.x + 0.5, workplacePos.y.toDouble(), workplacePos.z + 0.5) > WORKPLACE_ASSIGN_RADIUS_SQR) {
            npcSnackbar(player, definition.name, "${definition.name} needs to be near the work block.", SnackbarType.ERROR)
            return false
        }
        val workBlocks = workBlockStatus(player.level(), workplacePos, definition)
        if (!workBlocks.ready) {
            openMissingWorkBlocksDialog(player, npc, definition, workplacePos, workBlocks, assigning = true)
            return false
        }
        NpcStore.setWorkplace(npcId, workplacePos)
        NpcStore.recordGlobalEvent("npc_workplace_assigned", "${definition.name} got a workplace at ${workplacePos.toShortString()} from ${player.gameProfile.name}.")
        npc.startTalkingTo(player, NPC_DIALOG_DURATION_TICKS)
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val fallback = "Got it. I will work around this block."
        val responseToken = NpcDialogTokens.next()
        NpcNetwork.openDialog(player, dialogPayload(definition, npc, if (workLlmEnabled { it.assignedWorkplace }) "..." else fallback, false, friendship.level, closeOnly = true, closeLabel = "OKAY", responseToken = responseToken))
        if (workLlmEnabled { it.assignedWorkplace }) {
            NpcLlmService.event(player, npc, definition, fallback, NpcConfig.settings().work.assignedWorkplaceLlmPrompt, npcRecordType = "npc_assigned_workplace", responseToken = responseToken)
        }
        if (!player.abilities.instabuild) stack.shrink(1)
        SnackbarNetwork.send(player, SnackbarNotification.item(BuiltInRegistries.ITEM.getKey(JOB_APPLICATION.get()).toString(), "WORKPLACE ASSIGNED", "${definition.name} now works here", SnackbarType.SUCCESS, SnackbarSounds.REWARD))
        return true
    }

    fun prepareSmartBrainTick(entity: ChowNpcEntity): Boolean {
        NpcEmoteController.tick(entity)
        if (NpcBossFights.isActive(entity)) {
            NpcEmoteController.cancel(entity, "boss_active")
            return true
        }
        val definition = NpcConfig.get(entity.npcId) ?: return false
        entity.homePos = validHomePos(entity.level(), definition.id)
        entity.campPos = entity.campPos ?: NpcStore.campPos(definition.id)
        tryShowCamperHousingBalloon(entity, definition)
        return true
    }

    fun smartBrainDefinition(entity: ChowNpcEntity): NpcDefinition? = NpcConfig.get(entity.npcId)

    fun canOfferNpcQuests(npc: ChowNpcEntity, definition: NpcDefinition): Boolean = canOfferNpcQuests(npc.level(), definition)

    fun canOfferNpcQuests(level: Level, definition: NpcDefinition): Boolean {
        val hasHome = !definition.housing.canMoveIn || validHomePos(level, definition.id) != null
        return hasHome && NpcStore.workplacePos(definition.id) != null
    }

    fun tickSmartBrainQuestClaim(npc: ChowNpcEntity): Boolean = smartBrainDefinition(npc)?.let { definition -> BossEventsFeature.tryShowFinnBossBalloon(npc, definition) || tryQuestClaimApproach(npc, definition) } ?: false

    fun tickSmartBrainRentContractFollow(npc: ChowNpcEntity): Boolean = smartBrainDefinition(npc)?.let { definition -> tryFollowRentContractHolder(npc, definition) } ?: false

    fun tickSmartBrainJobApplicationFollow(npc: ChowNpcEntity): Boolean = smartBrainDefinition(npc)?.let { definition -> tryFollowJobApplicationHolder(npc, definition) } ?: false

    fun tickSmartBrainNpcMicroInteraction(npc: ChowNpcEntity): Boolean = smartBrainDefinition(npc)?.let { definition -> tryNpcMicroInteraction(npc, definition) } ?: false

    fun tickSmartBrainOutgoingGift(npc: ChowNpcEntity): Boolean = smartBrainDefinition(npc)?.let { definition -> tryOutgoingGift(npc, definition) } ?: false

    fun tickSmartBrainQuestOffer(npc: ChowNpcEntity): Boolean = smartBrainDefinition(npc)?.let { definition -> BossEventsFeature.tryShowFinnBossBalloon(npc, definition) || NpcQuestService.tryShowOfferBalloon(npc, definition) } ?: false

    fun tickSmartBrainGreeting(npc: ChowNpcEntity): Boolean = smartBrainDefinition(npc)?.let { definition -> !needsCamperHousingBalloon(npc, definition) && tryGreetNearbyPlayer(npc, definition) } ?: false

    fun tickSmartBrainTalkingPause(npc: ChowNpcEntity): Boolean {
        if (!npc.isTalking()) return false
        npc.navigation.stop()
        return true
    }

    fun tickSmartBrainAmbientLife(npc: ChowNpcEntity): Boolean {
        val definition = smartBrainDefinition(npc) ?: return false
        val level = npc.level() as? ServerLevel ?: return false
        val activity = activityFor(npc, definition)
        if (npc.isSleeping || npc.isTalking() || activity == "sleep") {
            npcAmbientActions.remove(npc.uuid)
            NpcEmoteController.cancelPosture(npc, "ambient_interrupted")
            return false
        }
        npcAmbientActions[npc.uuid]?.let { action ->
            if (action.activity != activity || level.gameTime >= action.untilTick || !npc.isAlive) {
                finishAmbientAction(level, npc)
                return false
            }
            tickAmbientAction(level, npc, action)
            return true
        }
        if (!npc.navigation.isDone) return false
        val cooldown = npcAmbientCooldownUntil[npc.uuid] ?: 0L
        if (level.gameTime < cooldown) return false
        val action = selectAmbientAction(level, npc, definition, activity) ?: return false
        startAmbientAction(level, npc, definition, action)
        return true
    }

    private fun tryFollowRentContractHolder(npc: ChowNpcEntity, definition: NpcDefinition): Boolean {
        if (npc.homePos != null) return false
        val level = npc.level() as? ServerLevel ?: return false
        val holder = level.players()
            .filter { player -> player.isAlive && !player.isSpectator && player.distanceToSqr(npc) <= CONTRACT_FOLLOW_SCAN_RADIUS_SQR }
            .firstOrNull { player -> playerHoldsRentContract(player, definition.id) }
            ?: return false
        if (npc.isSleeping) npc.stopSleeping()
        npc.debugActivity = "camp"
        npc.debugGoal = "follow_contract"
        npc.debugTargetPos = holder.blockPosition().immutable()
        npc.lookControl.setLookAt(holder, 30.0f, 30.0f)
        if (npc.distanceToSqr(holder) > CONTRACT_FOLLOW_STOP_DISTANCE_SQR) {
            npc.navigation.moveTo(holder.x, holder.y, holder.z, 0.95)
        } else {
            npc.navigation.stop()
        }
        return true
    }

    private fun playerHoldsRentContract(player: ServerPlayer, npcId: String): Boolean {
        return NpcRentContractData.readNpcId(player.mainHandItem) == npcId || NpcRentContractData.readNpcId(player.offhandItem) == npcId
    }

    private fun tryFollowJobApplicationHolder(npc: ChowNpcEntity, definition: NpcDefinition): Boolean {
        val level = npc.level() as? ServerLevel ?: return false
        val holder = level.players()
            .filter { player -> player.isAlive && !player.isSpectator && player.distanceToSqr(npc) <= CONTRACT_FOLLOW_SCAN_RADIUS_SQR }
            .firstOrNull { player -> playerHoldsJobApplication(player, definition.id) }
            ?: return false
        if (npc.isSleeping) npc.stopSleeping()
        npc.debugActivity = "work"
        npc.debugGoal = "follow_job_application"
        npc.debugTargetPos = holder.blockPosition().immutable()
        npc.lookControl.setLookAt(holder, 30.0f, 30.0f)
        if (npc.distanceToSqr(holder) > CONTRACT_FOLLOW_STOP_DISTANCE_SQR) {
            npc.navigation.moveTo(holder.x, holder.y, holder.z, 0.95)
        } else {
            npc.navigation.stop()
        }
        return true
    }

    private fun playerHoldsJobApplication(player: ServerPlayer, npcId: String): Boolean {
        return NpcJobApplicationData.readNpcId(player.mainHandItem) == npcId || NpcJobApplicationData.readNpcId(player.offhandItem) == npcId
    }

    private fun tryQuestClaimApproach(npc: ChowNpcEntity, definition: NpcDefinition): Boolean {
        if (npc.isSleeping) return false
        val player = NpcQuestService.readyClaimPlayer(npc, definition, NPC_QUEST_CLAIM_SCAN_RADIUS) ?: return false
        npc.debugActivity = "quest"
        npc.debugGoal = "claim_reward"
        npc.debugTargetPos = player.blockPosition().immutable()
        npc.lookControl.setLookAt(player, 30.0f, 30.0f)
        if (npc.distanceToSqr(player) > NPC_QUEST_CLAIM_DISTANCE_SQR) {
            npc.navigation.moveTo(player.x, player.y, player.z, NPC_QUEST_CLAIM_FOLLOW_SPEED)
        } else {
            npc.navigation.stop()
            val now = npc.level().gameTime
            if ((questClaimBalloonRefreshAt[npc.uuid] ?: Long.MIN_VALUE) <= now) {
                NpcQuestService.showReadyClaimBalloon(player, npc, definition)
                questClaimBalloonRefreshAt[npc.uuid] = now + NPC_QUEST_CLAIM_BALLOON_COOLDOWN_TICKS
            }
        }
        return true
    }

    private fun tryShowCamperHousingBalloon(npc: ChowNpcEntity, definition: NpcDefinition) {
        if (!needsCamperHousingBalloon(npc, definition)) return
        val level = npc.level() as? ServerLevel ?: return
        val greeting = NpcConfig.settings().greetings
        val radiusSqr = greeting.radius * greeting.radius
        val player = level.players()
            .asSequence()
            .filter { player -> player.isAlive && !player.isSpectator && player.distanceToSqr(npc.x, npc.y, npc.z) <= radiusSqr }
            .minByOrNull { player -> player.distanceToSqr(npc.x, npc.y, npc.z) }
            ?: return
        val nextAt = camperBalloonRefreshAt[npc.uuid] ?: 0L
        if (level.gameTime < nextAt) return
        val pool = if (NpcStore.camperReturnReason(definition.id) == "lost_house") definition.camperMessages.lostHouseBalloon else definition.camperMessages.needsHouseBalloon
        sendNpcBalloon(level, npc, NpcNetwork.goldBalloon(camperMessage(pool, player, definition)), NPC_CAMPER_HOUSING_BALLOON_TICKS)
        camperBalloonRefreshAt[npc.uuid] = level.gameTime + NPC_CAMPER_HOUSING_BALLOON_REFRESH_TICKS
    }

    private fun needsCamperHousingBalloon(npc: ChowNpcEntity, definition: NpcDefinition): Boolean {
        if (!definition.housing.canMoveIn) return false
        return validHomePos(npc.level(), definition.id) == null && !NpcStore.contractGiven(definition.id)
    }

    private fun tryOutgoingGift(npc: ChowNpcEntity, definition: NpcDefinition): Boolean {
        val config = definition.gifts.outgoing
        if (!config.enabled || npc.isSleeping) return false
        val level = npc.level() as? ServerLevel ?: return false
        outgoingGiftApproaches[npc.uuid]?.let { approach ->
            val player = level.server.playerList.getPlayer(approach.playerId)
            if (player == null || player.level() != level || !player.isAlive || player.isSpectator || level.gameTime - approach.startedAtTick > config.followSeconds * 20L) {
                outgoingGiftApproaches.remove(npc.uuid)
                return false
            }
            npc.debugActivity = "gift"
            npc.debugGoal = "deliver_gift"
            npc.debugTargetPos = player.blockPosition().immutable()
            npc.lookControl.setLookAt(player, 30.0f, 30.0f)
            if (npc.distanceToSqr(player) > NPC_GIFT_DELIVERY_DISTANCE_SQR) {
                npc.navigation.moveTo(player.x, player.y, player.z, NPC_GIFT_FOLLOW_SPEED)
            } else {
                npc.navigation.stop()
            }
            return true
        }

        if (npc.isTalking()) return false
        if (isAutoTaskCoolingDown(npc)) return false

        val radiusSqr = config.radius * config.radius
        val day = NpcTime.day(level)
        val hour = NpcTime.hour(level)
        val pendingPlayer = level.players()
            .asSequence()
            .filter { player -> player.isAlive && !player.isSpectator && player.distanceToSqr(npc.x, npc.y, npc.z) <= radiusSqr }
            .mapNotNull { player -> NpcStore.pendingOutgoingGiftReady(definition.id, player, day)?.let { gift -> player to gift } }
            .minByOrNull { (player, _) -> player.distanceToSqr(npc.x, npc.y, npc.z) }
        if (pendingPlayer != null) {
            NpcStore.markPendingOutgoingGiftReminder(definition.id, pendingPlayer.first, day)
            startOutgoingGiftApproach(npc, level, definition, config, pendingPlayer.first)
            return true
        }
        val player = level.players()
            .asSequence()
            .filter { player -> player.isAlive && !player.isSpectator && player.distanceToSqr(npc.x, npc.y, npc.z) <= radiusSqr }
            .filter { player -> NpcStore.friendshipSnapshot(definition.id, player).level >= config.minFriendshipLevel }
            .filter { player -> NpcStore.outgoingGiftReady(definition.id, player, day, hour, randomOutgoingGiftHour(definition, level.random)) }
            .minByOrNull { player -> player.distanceToSqr(npc.x, npc.y, npc.z) }
            ?: return false
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val gift = selectOutgoingGift(config, friendship.level, level.random) ?: return false
        NpcStore.setPendingOutgoingGift(definition.id, player, day, gift.item, gift.qty)
        startOutgoingGiftApproach(npc, level, definition, config, player)
        return true
    }

    private fun startOutgoingGiftApproach(npc: ChowNpcEntity, level: ServerLevel, definition: NpcDefinition, config: NpcOutgoingGiftsDefinition, player: ServerPlayer) {
        outgoingGiftApproaches[npc.uuid] = NpcOutgoingGiftApproach(player.uuid, level.gameTime)
        val offer = outgoingGiftOfferMessage(config, player, definition)
        sendNpcBalloon(level, npc, offer, config.followSeconds * 20)
        npc.startTalkingTo(player, config.followSeconds * 20)
        markAutoTaskCooldown(npc, config.followSeconds * 20L)
    }

    private fun randomOutgoingGiftHour(definition: NpcDefinition, random: net.minecraft.util.RandomSource): Int {
        val awakeHours = (0..23).filter { hour -> definition.schedule.activityAtHour(hour) != "sleep" }
        if (awakeHours.isEmpty()) return random.nextInt(24)
        return awakeHours[random.nextInt(awakeHours.size)]
    }

    private fun selectOutgoingGift(config: NpcOutgoingGiftsDefinition, friendshipLevel: Int, random: net.minecraft.util.RandomSource): NpcOutgoingGiftEntryDefinition? {
        val pool = (if (friendshipLevel >= config.rareFriendshipLevel && config.rarePool.isNotEmpty()) config.rarePool else config.pool)
            .filter { gift -> outgoingGiftStack(gift) != null }
        val totalWeight = pool.sumOf { gift -> gift.weight.coerceAtLeast(0) }
        if (totalWeight <= 0) return null
        var roll = random.nextInt(totalWeight)
        pool.forEach { gift ->
            roll -= gift.weight.coerceAtLeast(0)
            if (roll < 0) return gift
        }
        return pool.lastOrNull()
    }

    private fun claimOutgoingGift(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, gift: NpcPendingOutgoingGift) {
        val stack = outgoingGiftStack(gift) ?: return
        val itemName = stack.hoverName.string
        val quantity = stack.count
        giveStack(player, stack)
        SnackbarNetwork.send(player, SnackbarNotification.item(BuiltInRegistries.ITEM.getKey(stack.item).toString(), "GIFT RECEIVED", "$quantity x $itemName from ${definition.name}", SnackbarType.SUCCESS, SnackbarSounds.REWARD))
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        NpcStore.clearPendingOutgoingGift(definition.id, player, NpcTime.day(player.level()))
        outgoingGiftApproaches.remove(npc.uuid)
        NpcStore.recordConversation(definition.id, player, definition.name, "gives $quantity x $itemName to ${player.gameProfile.name}", "npc_outgoing_gift_item")
        NpcStore.recordConversation(definition.id, player, player.gameProfile.name, "receives $quantity x $itemName from ${definition.name}", "player_received_npc_gift")
        NpcStore.recordPlayerMemory(player, "gift_from_npc", "${definition.name} gave ${player.gameProfile.name} $quantity x $itemName.")
        val fallback = outgoingGiftFallback(definition.gifts.outgoing, player, definition, itemName, quantity)
        if (NpcConfig.settings().llm.enabled && definition.gifts.outgoing.llmEnabled) {
            val responseToken = NpcDialogTokens.next()
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, "...", false, friendship.level, closeOnly = true, closeLabel = "THANKS", responseToken = responseToken))
            NpcLlmService.event(
                player,
                npc,
                definition,
                fallback,
                outgoingGiftPrompt(definition.gifts.outgoing, player, definition, itemName, quantity),
                inputLabel = "Gift claim",
                sendTalkResponse = true,
                showBalloon = false,
                excludePlayerFromBalloon = false,
                npcRecordType = "npc_outgoing_gift",
                responseToken = responseToken,
            )
            return
        }
        npc.startTalkingTo(player, NPC_DIALOG_DURATION_TICKS)
        NpcStore.recordConversation(definition.id, player, definition.name, fallback, "npc_outgoing_gift")
        NpcNetwork.openDialog(player, dialogPayload(definition, npc, fallback, false, friendship.level, closeOnly = true, closeLabel = "THANKS"))
        relayNpcDialog(player, npc, definition, fallback)
    }

    private fun outgoingGiftStack(gift: NpcOutgoingGiftEntryDefinition): ItemStack? {
        return outgoingGiftStack(gift.item, gift.qty)
    }

    private fun outgoingGiftStack(gift: NpcPendingOutgoingGift): ItemStack? {
        return outgoingGiftStack(gift.item, gift.qty)
    }

    private fun outgoingGiftStack(itemId: String, qty: Int): ItemStack? {
        val item = runCatching { ResourceLocation.parse(itemId) }.getOrNull()
            ?.let { id -> BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR) }
            ?: Items.AIR
        if (item == Items.AIR) return null
        return ItemStack(item, qty.coerceIn(1, item.defaultMaxStackSize))
    }

    private fun outgoingGiftOfferMessage(config: NpcOutgoingGiftsDefinition, player: ServerPlayer, definition: NpcDefinition): String = config.offerMessages.randomOrNull()
        ?.let { outgoingGiftText(it, player, definition, "", 0) }
        ?: "@gift.png Hey ${player.gameProfile.name}!"

    private fun outgoingGiftFallback(config: NpcOutgoingGiftsDefinition, player: ServerPlayer, definition: NpcDefinition, itemName: String, quantity: Int): String = config.fallbackMessages.randomOrNull()
        ?.let { outgoingGiftText(it, player, definition, itemName, quantity) }
        ?: "I brought you $quantity x $itemName, ${player.gameProfile.name}."

    private fun outgoingGiftPrompt(config: NpcOutgoingGiftsDefinition, player: ServerPlayer, definition: NpcDefinition, itemName: String, quantity: Int): String = outgoingGiftText(config.llmPrompt, player, definition, itemName, quantity)

    private fun outgoingGiftText(template: String, player: ServerPlayer, definition: NpcDefinition, itemName: String, quantity: Int): String = template
        .replace("{player}", player.gameProfile.name)
        .replace("{npc}", definition.name)
        .replace("{item}", itemName)
        .replace("{quantity}", quantity.toString())

    private fun tryGreetNearbyPlayer(npc: ChowNpcEntity, definition: NpcDefinition): Boolean {
        val level = npc.level() as? ServerLevel ?: return false
        val greeting = NpcConfig.settings().greetings
        val radiusSqr = greeting.radius * greeting.radius
        val playersInRadius = level.players()
            .filter { player -> player.isAlive && !player.isSpectator && player.distanceToSqr(npc.x, npc.y, npc.z) <= radiusSqr }
        updateGreetingRadiusState(npc, definition, playersInRadius)
        if (BossEventsFeature.hasFinnBossBalloonPriority(npc, definition)) return false
        if (npc.isTalking() || npc.isSleeping) return false
        if (isAutoTaskCoolingDown(npc)) return false
        val player = playersInRadius
            .asSequence()
            .minByOrNull { player -> player.distanceToSqr(npc.x, npc.y, npc.z) }
            ?: return false
        val day = NpcTime.day(level)
        val nowMs = System.currentTimeMillis()
        if (!NpcStore.canShowGreeting(definition.id, player, day, nowMs)) return false
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val baseMessage = friendshipMessage(definition.friendshipMessages.greeting, friendship, player, definition)
        val message = NpcNetwork.goldBalloon(baseMessage)
        val durationTicks = greeting.balloonDurationSeconds * 20
        NpcStore.markGreetingShown(definition.id, player, day, nowMs + greeting.cooldownSeconds * 1000L)
        if (NpcConfig.settings().llm.enabled && NpcConfig.settings().llmMessageUsage.greeting) {
            markAutoTaskCooldown(npc, durationTicks.toLong())
            NpcLlmService.event(
                player,
                npc,
                definition,
                message,
                "${player.gameProfile.name} walked near you. Reply with a very short first-greeting balloon. Prefix the reply with @gold.",
                sendTalkResponse = false,
                excludePlayerFromBalloon = false,
                npcRecordType = "npc_greeting_balloon",
            )
            return true
        }
        sendNpcBalloon(level, npc, message, durationTicks)
        npc.startTalkingTo(player, durationTicks)
        markAutoTaskCooldown(npc, durationTicks.toLong())
        NpcStore.recordConversation(definition.id, player, definition.name, baseMessage, "npc_greeting_balloon")
        return true
    }

    private fun tryNpcMicroInteraction(npc: ChowNpcEntity, definition: NpcDefinition): Boolean {
        val level = npc.level() as? ServerLevel ?: return false
        npcMicroInteractions[npc.uuid]?.let { active ->
            val other = findNpc(level.server, active.partnerId) ?: return finishNpcMicroInteraction(npc.uuid, active.partnerId)
            if (!npc.isAlive || !other.isAlive || npc.isSleeping || other.isSleeping || level.gameTime >= active.untilTick) return finishNpcMicroInteraction(npc.uuid, active.partnerId)
            npc.debugActivity = "interaction"
            npc.debugGoal = "talk_npc"
            npc.debugTargetPos = other.blockPosition().immutable()
            npc.lookControl.setLookAt(other, 30.0f, 30.0f)
            if (npc.distanceToSqr(other) > NPC_MICRO_INTERACTION_DISTANCE_SQR) {
                npc.navigation.moveTo(other.x, other.y, other.z, NPC_MICRO_INTERACTION_SPEED)
            } else {
                npc.navigation.stop()
                if (!active.emotePlayed) {
                    NpcEmoteController.tryPlayOrDefaultSpeaking(npc, NpcEmoteSurfaces.MICRO, active.emote, "npc_micro_interaction", loopUntilTick = active.untilTick)
                    active.emotePlayed = true
                }
            }
            showMicroInteractionBalloonToClosePlayers(level, npc, active)
            return true
        }

        val settings = NpcConfig.settings().npcInteractions
        val npcActivity = activityFor(npc, definition)
        val plazaMeetup = npcActivity == "meetup"
        val trainerMeetup = npcActivity == "pokemon_roam"
        if (!settings.enabled || npc.isSleeping || npc.isTalking() || (!trainerMeetup && NpcTime.activityAt(definition.schedule, level) == "sleep")) return false
        if (isAutoTaskCoolingDown(npc)) return false
        if (isMicroInteractionScanCoolingDown(npc)) return false
        val witnesses = microInteractionWitnesses(level, npc, settings)
        if (settings.witnessRequired && witnesses.isEmpty()) return false
        if (settings.witnessRequired && witnesses.any { player -> !hasWitnessedMicroInteractionToday(level, player) } && witnesses.none { player -> witnessNudgeReady(level, player, settings) }) return false
        if (!canStartMicroInteractionFor(level, definition, settings)) return false
        val areaKey = npcMicroInteractionAreaKey(level, npc, npcActivity)
        if ((npcMicroInteractionAreaCooldownUntil[areaKey] ?: 0L) > level.gameTime) return false
        val radius = when {
            plazaMeetup -> plazaMeetupRadius().toDouble()
            trainerMeetup -> TRAINER_MEETUP_SCAN_RADIUS
            else -> settings.radius
        }
        val radiusSqr = radius * radius
        val scanBox = npc.boundingBox.inflate(radius.coerceAtLeast(1.0))
        val other = level.getEntities(NPC_ENTITY.get(), scanBox) { other ->
            other.uuid != npc.uuid &&
                other.isAlive &&
                !other.isSleeping &&
                !other.isTalking() &&
                other.distanceToSqr(npc) <= radiusSqr &&
                other.npcId != npc.npcId &&
                other.uuid !in npcMicroInteractions &&
                other.uuid !in outgoingGiftApproaches &&
                (!trainerMeetup || GymLeagueFeature.isTrainerNpc(other.npcId))
        }.asSequence()
            .mapNotNull { other -> NpcConfig.get(other.npcId)?.takeIf { otherDefinition ->
                val otherActivity = activityFor(other, otherDefinition)
                otherActivity != "sleep" &&
                    canStartMicroInteractionFor(level, otherDefinition, settings) &&
                    (npcMicroInteractionPairCooldownUntil[npcMicroInteractionPairKey(definition.id, otherDefinition.id)] ?: 0L) <= level.dayTime
            }?.let { other to it } }
            .minByOrNull { (other, _) -> other.distanceToSqr(npc) }
        if (other == null) {
            markMicroInteractionScanCooldown(npc)
            return false
        }
        startNpcMicroInteraction(level, npc, definition, other.first, other.second, settings, areaKey)
        return true
    }

    private fun finishNpcMicroInteraction(firstId: UUID, secondId: UUID): Boolean {
        npcMicroInteractions.remove(firstId)
        npcMicroInteractions.remove(secondId)
        return false
    }

    private fun microInteractionWitnesses(level: ServerLevel, npc: ChowNpcEntity, settings: NpcInteractionSettingsDefinition): List<ServerPlayer> {
        val radius = minOf(settings.witnessRadius, NPC_BALLOON_CLOSE_RADIUS)
        val radiusSqr = radius * radius
        return level.players()
            .filter { player -> player.isAlive && !player.isSpectator && player.distanceToSqr(npc.x, npc.y, npc.z) <= radiusSqr }
    }

    private fun hasWitnessedMicroInteractionToday(level: ServerLevel, player: ServerPlayer): Boolean =
        playerMicroInteractionWitnessDay[player.uuid] == NpcTime.day(level)

    private fun witnessNudgeReady(level: ServerLevel, player: ServerPlayer, settings: NpcInteractionSettingsDefinition): Boolean {
        if (hasWitnessedMicroInteractionToday(level, player)) return true
        val day = NpcTime.day(level)
        val existing = playerMicroInteractionWitnessNudge[player.uuid]
        if (existing != null && existing.day == day) return level.gameTime >= existing.readyAtTick
        val delaySeconds = if (settings.firstWitnessNudgeMinSeconds == settings.firstWitnessNudgeMaxSeconds) {
            settings.firstWitnessNudgeMinSeconds
        } else {
            settings.firstWitnessNudgeMinSeconds + level.random.nextInt(settings.firstWitnessNudgeMaxSeconds - settings.firstWitnessNudgeMinSeconds + 1)
        }
        playerMicroInteractionWitnessNudge[player.uuid] = NpcMicroInteractionWitnessNudge(day, level.gameTime + delaySeconds * 20L)
        return delaySeconds <= 0
    }

    private fun markWitnessedMicroInteraction(level: ServerLevel, player: ServerPlayer) {
        playerMicroInteractionWitnessDay[player.uuid] = NpcTime.day(level)
        playerMicroInteractionWitnessNudge.remove(player.uuid)
    }

    private fun canStartMicroInteractionFor(level: ServerLevel, definition: NpcDefinition, settings: NpcInteractionSettingsDefinition): Boolean {
        if ((npcMicroInteractionCooldownUntil[definition.id] ?: 0L) > level.dayTime) return false
        val budget = if (GymLeagueFeature.isTrainerNpc(definition.id)) settings.trainerDailyParticipationBudget else settings.dailyParticipationBudget
        if (budget <= 0) return false
        val day = NpcTime.day(level)
        val count = npcMicroInteractionDailyCounts[definition.id]?.takeIf { it.day == day }?.count ?: 0
        return count < budget
    }

    private fun markMicroInteractionParticipation(level: ServerLevel, definition: NpcDefinition) {
        val day = NpcTime.day(level)
        val current = npcMicroInteractionDailyCounts[definition.id]
        npcMicroInteractionDailyCounts[definition.id] = if (current == null || current.day != day) {
            NpcDailyMicroInteractionCount(day, 1)
        } else {
            current.copy(count = current.count + 1)
        }
    }

    private fun npcMicroInteractionAreaKey(level: ServerLevel, npc: ChowNpcEntity, activity: String): String {
        val dimension = level.dimension().location().toString()
        val center = if (activity == NpcScheduleDefinition.MEETUP_ACTIVITY) plazaMeetupTarget() else null
        val pos = center ?: npc.blockPosition()
        val cellX = Math.floorDiv(pos.x, NPC_MICRO_INTERACTION_AREA_CELL_SIZE)
        val cellZ = Math.floorDiv(pos.z, NPC_MICRO_INTERACTION_AREA_CELL_SIZE)
        return "$dimension:$activity:$cellX:$cellZ"
    }

    private fun startNpcMicroInteraction(level: ServerLevel, first: ChowNpcEntity, firstDefinition: NpcDefinition, second: ChowNpcEntity, secondDefinition: NpcDefinition, settings: NpcInteractionSettingsDefinition, areaKey: String) {
        val durationTicks = settings.durationSeconds * 20L
        val exchange = selectNpcMicroInteractionExchange(level, firstDefinition, secondDefinition, settings)
        val firstMessage = exchange?.let { renderNpcMicroInteractionLine(it.line, firstDefinition, secondDefinition, it.topic) }
            ?: npcMicroInteractionMessage(firstDefinition, secondDefinition, settings)
        val secondMessage = exchange?.let { renderNpcMicroInteractionLine(it.response, secondDefinition, firstDefinition, it.topic) }
            ?: npcMicroInteractionMessage(secondDefinition, firstDefinition, settings)
        val firstEmote = exchange?.sourceEmote?.takeIf(String::isNotBlank).orEmpty()
        val secondEmote = exchange?.targetEmote?.takeIf(String::isNotBlank).orEmpty()
        val now = level.gameTime
        val untilTick = now + durationTicks
        val topic = exchange?.topic.orEmpty()
        npcMicroInteractions[first.uuid] = ActiveNpcMicroInteraction(second.uuid, secondDefinition.id, secondDefinition.name, firstMessage, secondMessage, firstEmote, topic, untilTick)
        npcMicroInteractions[second.uuid] = ActiveNpcMicroInteraction(first.uuid, firstDefinition.id, firstDefinition.name, secondMessage, firstMessage, secondEmote, topic, untilTick)
        rememberNpcMicroInteractionDialogContext(first.uuid, second.uuid, secondDefinition.id, secondDefinition.name, firstMessage, secondMessage, topic, untilTick)
        rememberNpcMicroInteractionDialogContext(second.uuid, first.uuid, firstDefinition.id, firstDefinition.name, secondMessage, firstMessage, topic, untilTick)
        markAutoTaskCooldown(first, durationTicks)
        markAutoTaskCooldown(second, durationTicks)
        val cooldownHours = if (settings.cooldownMinHours == settings.cooldownMaxHours) settings.cooldownMinHours else settings.cooldownMinHours + level.random.nextInt(settings.cooldownMaxHours - settings.cooldownMinHours + 1)
        val cooldownUntil = NpcTime.addHours(level.dayTime, cooldownHours)
        npcMicroInteractionCooldownUntil[firstDefinition.id] = cooldownUntil
        npcMicroInteractionCooldownUntil[secondDefinition.id] = cooldownUntil
        if (settings.pairCooldownHours > 0) {
            npcMicroInteractionPairCooldownUntil[npcMicroInteractionPairKey(firstDefinition.id, secondDefinition.id)] = NpcTime.addHours(level.dayTime, settings.pairCooldownHours)
        }
        markMicroInteractionParticipation(level, firstDefinition)
        markMicroInteractionParticipation(level, secondDefinition)
        val areaCooldownSeconds = if (settings.areaCooldownMinSeconds == settings.areaCooldownMaxSeconds) settings.areaCooldownMinSeconds else settings.areaCooldownMinSeconds + level.random.nextInt(settings.areaCooldownMaxSeconds - settings.areaCooldownMinSeconds + 1)
        if (areaCooldownSeconds > 0) npcMicroInteractionAreaCooldownUntil[areaKey] = level.gameTime + areaCooldownSeconds * 20L
        exchange?.let { rememberNpcMicroInteraction(firstDefinition.id, secondDefinition.id, it) }
        val topicText = topic.takeIf(String::isNotBlank)?.let { " about $it" }.orEmpty()
        NpcStore.recordGlobalEvent("npc_micro_interaction", "${firstDefinition.name} chatted with ${secondDefinition.name}$topicText near ${first.blockPosition().toShortString()}.")
    }

    private fun showMicroInteractionBalloonToClosePlayers(level: ServerLevel, npc: ChowNpcEntity, active: ActiveNpcMicroInteraction) {
        level.players().forEach { listener ->
            if (listener.uuid in active.shownToPlayers) return@forEach
            if (listener.distanceToSqr(npc.x, npc.y, npc.z) > NPC_BALLOON_CLOSE_RADIUS_SQR) return@forEach
            NpcNetwork.showBalloon(listener, npc.id, active.message, NPC_MICRO_INTERACTION_BALLOON_TICKS)
            active.shownToPlayers += listener.uuid
            markWitnessedMicroInteraction(level, listener)
        }
    }

    private fun npcMicroInteractionMessage(definition: NpcDefinition, otherDefinition: NpcDefinition, settings: NpcInteractionSettingsDefinition): String {
        val pool = (settings.messages + definition.npcInteractionMessages + definition.npcInteractionMessages).ifEmpty { listOf("Talking with {other}...") }
        return pool.randomOrNull()
            ?.replace("{npc}", definition.name)
            ?.replace("{other}", otherDefinition.name)
            ?: "Talking with ${otherDefinition.name}..."
    }

    private fun selectNpcMicroInteractionExchange(level: ServerLevel, firstDefinition: NpcDefinition, secondDefinition: NpcDefinition, settings: NpcInteractionSettingsDefinition): NpcMicroInteractionExchangeDefinition? {
        val content = NpcConfig.microInteractions()
        val firstTags = npcMicroInteractionTags(firstDefinition)
        val secondTags = npcMicroInteractionTags(secondDefinition)
        val spawnedNpcIds = spawnedNpcIds(level.server)
        val candidates = buildList {
            addAll(firstDefinition.npcInteractionExchanges.filter { exchange -> exchange.matches(firstDefinition, secondDefinition, firstTags, secondTags, spawnedNpcIds) })
            addAll(content.exchanges.filter { exchange -> exchange.matches(firstDefinition, secondDefinition, firstTags, secondTags, spawnedNpcIds) })
            if ("pokemon_trainer" in firstTags || "pokemon_trainer" in secondTags) {
                addAll(content.trainerExchanges.filter { exchange -> exchange.matches(firstDefinition, secondDefinition, firstTags, secondTags, spawnedNpcIds) })
            }
            if (isEmpty()) {
                addAll(secondDefinition.npcInteractionExchanges.filter { exchange -> exchange.matches(secondDefinition, firstDefinition, secondTags, firstTags, spawnedNpcIds) }.map { exchange ->
                    NpcMicroInteractionExchangeDefinition(
                        id = "${exchange.id}_reversed",
                        topic = exchange.topic,
                        line = exchange.response,
                        response = exchange.line,
                        weight = exchange.weight,
                        requiredSpawnedIds = exchange.requiredSpawnedIds,
                    ).normalized("reversed_${exchange.id}")
                })
            }
        }.filter { exchange -> exchange.weight > 0.0 && exchange.line.isNotBlank() && exchange.response.isNotBlank() }
        if (candidates.isEmpty()) return null
        val recent = npcMicroInteractionRecent[npcMicroInteractionPairKey(firstDefinition.id, secondDefinition.id)].orEmpty()
        val fresh = candidates.filterNot { exchange -> exchange.id in recent || "topic:${exchange.topic}" in recent }
            .ifEmpty { candidates.filterNot { exchange -> exchange.id in recent } }
            .ifEmpty { candidates }
        return weightedNpcMicroInteraction(level, fresh)
    }

    private fun NpcMicroInteractionExchangeDefinition.matches(source: NpcDefinition, target: NpcDefinition, sourceTagSet: Set<String>, targetTagSet: Set<String>, spawnedNpcIds: Set<String>): Boolean {
        if (sourceIds.isNotEmpty() && cleanNpcMicroInteractionToken(source.id) !in sourceIds) return false
        if (targetIds.isNotEmpty() && cleanNpcMicroInteractionToken(target.id) !in targetIds) return false
        if (sourceTags.isNotEmpty() && sourceTags.none { tag -> tag in sourceTagSet }) return false
        if (targetTags.isNotEmpty() && targetTags.none { tag -> tag in targetTagSet }) return false
        if (requiredSpawnedIds.isNotEmpty() && requiredSpawnedIds.any { npcId -> npcId !in spawnedNpcIds }) return false
        return true
    }

    private fun weightedNpcMicroInteraction(level: ServerLevel, candidates: List<NpcMicroInteractionExchangeDefinition>): NpcMicroInteractionExchangeDefinition? {
        val totalWeight = candidates.sumOf { exchange -> exchange.weight.coerceAtLeast(0.0) }
        if (totalWeight <= 0.0) return candidates.randomOrNull()
        var roll = level.random.nextDouble() * totalWeight
        candidates.forEach { exchange ->
            roll -= exchange.weight.coerceAtLeast(0.0)
            if (roll <= 0.0) return exchange
        }
        return candidates.lastOrNull()
    }

    private fun renderNpcMicroInteractionLine(text: String, speaker: NpcDefinition, other: NpcDefinition, topic: String): String = text
        .replace("{npc}", speaker.name)
        .replace("{other}", other.name)
        .replace("{class}", speaker.classId.ifBlank { speaker.title })
        .replace("{store}", speaker.storeId())
        .replace("{topic}", topic)

    private fun tryOpenMicroInteractionInterruptDialog(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition): Boolean {
        val level = npc.level() as? ServerLevel ?: return false
        val context = npcMicroInteractionDialogContext(npc, level) ?: return false
        context.partnerEntityId?.let { partnerId -> finishNpcMicroInteraction(npc.uuid, partnerId) }
        npc.navigation.stop()
        npc.startTalkingTo(player, NPC_DIALOG_DURATION_TICKS)
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val fallback = microInteractionInterruptFallback(context)
        val responseToken = if (NpcConfig.settings().llm.enabled) NpcDialogTokens.next() else 0L
        val validHome = validHomePos(npc.level(), definition.id)
        npc.homePos = validHome
        val hasHome = validHome != null
        val friendlyBattleAvailable = hasHome && NpcPokemonBattleService.friendlyBattleAvailable(player, npc, definition)
        val retryBattleAvailable = hasHome && NpcQuestService.retryBattleAvailable(player, definition)
        val closeOnly = !hasHome
        val closeLabel = if (hasHome) "BYE" else "OKAY"
        NpcStore.recordConversation(definition.id, player, player.gameProfile.name, "interrupts ${definition.name}'s conversation with ${context.partnerName}", "player_interrupt_npc_micro_interaction")
        if (responseToken != 0L) {
            NpcNetwork.openDialog(player, dialogPayload(definition, npc, "...", false, friendship.level, closeOnly = closeOnly, closeLabel = closeLabel, responseToken = responseToken, friendlyBattleAvailable = friendlyBattleAvailable, retryBattleAvailable = retryBattleAvailable, player = player))
            NpcLlmService.event(
                player = player,
                npc = npc,
                definition = definition,
                fallbackMessage = fallback,
                input = microInteractionInterruptPrompt(player, definition, context),
                npcRecordType = "npc_micro_interaction_interrupt",
                responseToken = responseToken,
            )
            return true
        }
        NpcStore.recordConversation(definition.id, player, definition.name, fallback, "npc_micro_interaction_interrupt")
        NpcNetwork.openDialog(player, dialogPayload(definition, npc, fallback, false, friendship.level, closeOnly = closeOnly, closeLabel = closeLabel, friendlyBattleAvailable = friendlyBattleAvailable, retryBattleAvailable = retryBattleAvailable, player = player))
        relayNpcDialog(player, npc, definition, fallback)
        return true
    }

    private fun npcMicroInteractionDialogContext(npc: ChowNpcEntity, level: ServerLevel): NpcMicroInteractionDialogContext? {
        npcMicroInteractions[npc.uuid]?.let { active ->
            if (level.gameTime < active.untilTick) {
                return NpcMicroInteractionDialogContext(active.partnerId, active.partnerNpcId, active.partnerName, active.topic, active.message, active.partnerMessage, active.untilTick + NPC_MICRO_INTERACTION_INTERRUPT_MEMORY_TICKS)
            }
        }
        val recent = npcMicroInteractionDialogContext[npc.uuid] ?: return null
        if (level.gameTime <= recent.expiresAtTick) return recent
        npcMicroInteractionDialogContext.remove(npc.uuid)
        return null
    }

    private fun rememberNpcMicroInteractionDialogContext(npcId: UUID, partnerEntityId: UUID, partnerNpcId: String, partnerName: String, message: String, partnerMessage: String, topic: String, untilTick: Long) {
        npcMicroInteractionDialogContext[npcId] = NpcMicroInteractionDialogContext(
            partnerEntityId = partnerEntityId,
            partnerNpcId = partnerNpcId,
            partnerName = partnerName,
            topic = topic,
            ownMessage = message,
            partnerMessage = partnerMessage,
            expiresAtTick = untilTick + NPC_MICRO_INTERACTION_INTERRUPT_MEMORY_TICKS,
        )
    }

    private fun microInteractionInterruptFallback(context: NpcMicroInteractionDialogContext): String {
        val partnerLine = context.partnerMessage.takeIf(String::isNotBlank)
        val topic = context.topic.replace('_', ' ').ifBlank { "that" }
        return if (partnerLine != null) {
            "${context.partnerName} just said, \"$partnerLine\". I was thinking about $topic."
        } else {
            "I was just talking with ${context.partnerName} about $topic."
        }
    }

    private fun microInteractionInterruptPrompt(player: ServerPlayer, definition: NpcDefinition, context: NpcMicroInteractionDialogContext): String {
        val topic = context.topic.ifBlank { "their recent conversation" }
        return """
            ${player.gameProfile.name} interrupted you right after you were talking with ${context.partnerName}.
            Topic id: $topic.
            You said: "${context.ownMessage}"
            ${context.partnerName} said: "${context.partnerMessage}"
            Reply as ${definition.name} in 1-2 short in-character sentences.
            Naturally reference what was just said and, if it fits, ask the player what they think.
            Do not use a generic greeting and do not ignore the interrupted conversation.
        """.trimIndent()
    }

    private fun rememberNpcMicroInteraction(firstId: String, secondId: String, exchange: NpcMicroInteractionExchangeDefinition) {
        val recent = npcMicroInteractionRecent.getOrPut(npcMicroInteractionPairKey(firstId, secondId)) { ArrayDeque() }
        recent.addLast(exchange.id)
        if (exchange.topic.isNotBlank()) recent.addLast("topic:${exchange.topic}")
        while (recent.size > NPC_MICRO_INTERACTION_RECENT_MEMORY) recent.removeFirst()
    }

    private fun npcMicroInteractionPairKey(firstId: String, secondId: String): String = listOf(
        cleanNpcMicroInteractionToken(firstId),
        cleanNpcMicroInteractionToken(secondId),
    ).sorted().joinToString("|")

    private fun npcMicroInteractionTags(definition: NpcDefinition): Set<String> = buildSet {
        add(cleanNpcMicroInteractionToken(definition.id))
        addAll(definition.interactionTags)
        definition.classId.takeIf(String::isNotBlank)?.let { classId ->
            add(cleanNpcMicroInteractionToken(classId))
            add("mentor")
        }
        definition.storeId().takeIf(String::isNotBlank)?.let { store -> add("store_$store") }
        val title = definition.title.lowercase()
        when {
            definition.id.startsWith("gym_") -> add("gym_leader")
            definition.id.startsWith("trainer_") -> add("rival")
            definition.id.startsWith("elite_") || definition.id.contains("_elite_") -> add("elite_four")
            definition.id.contains("champion") -> add("champion")
            definition.id == "prof_chowfan" -> add("professor")
        }
        if (definition.id.startsWith("gym_") || definition.id.startsWith("trainer_") || definition.id.startsWith("elite_") || definition.id.startsWith("johto_") || definition.id.startsWith("hoenn_") || definition.id == "prof_chowfan" || title.contains("gym") || title.contains("elite") || title.contains("champion") || title.contains("rival")) {
            add("pokemon_trainer")
        } else {
            add("town")
        }
    }

    private fun isAutoTaskCoolingDown(npc: ChowNpcEntity): Boolean {
        val level = npc.level()
        val untilTick = npcAutoTaskCooldownUntil[npc.uuid] ?: return false
        if (level.gameTime < untilTick) return true
        npcAutoTaskCooldownUntil.remove(npc.uuid)
        return false
    }

    private fun markAutoTaskCooldown(npc: ChowNpcEntity, taskTicks: Long = 0L) {
        val level = npc.level()
        val extraTicks = NPC_AUTO_TASK_COOLDOWN_MIN_TICKS + level.random.nextInt((NPC_AUTO_TASK_COOLDOWN_MAX_TICKS - NPC_AUTO_TASK_COOLDOWN_MIN_TICKS + 1).toInt())
        npcAutoTaskCooldownUntil[npc.uuid] = level.gameTime + taskTicks + extraTicks
    }

    private fun isMicroInteractionScanCoolingDown(npc: ChowNpcEntity): Boolean {
        val level = npc.level()
        val untilTick = npcMicroInteractionScanCooldownUntil[npc.uuid] ?: return false
        if (level.gameTime < untilTick) return true
        npcMicroInteractionScanCooldownUntil.remove(npc.uuid)
        return false
    }

    private fun markMicroInteractionScanCooldown(npc: ChowNpcEntity) {
        val level = npc.level()
        val delayTicks = NPC_MICRO_INTERACTION_SCAN_MIN_TICKS + level.random.nextInt((NPC_MICRO_INTERACTION_SCAN_MAX_TICKS - NPC_MICRO_INTERACTION_SCAN_MIN_TICKS + 1).toInt())
        npcMicroInteractionScanCooldownUntil[npc.uuid] = level.gameTime + delayTicks
    }

    fun ambientFocus(npc: ChowNpcEntity): NpcAmbientFocus? {
        val level = npc.level() as? ServerLevel ?: return null
        npcAmbientActions[npc.uuid]?.let { action ->
            return NpcAmbientFocus(action.activity, action.goal, action.targetLabel, action.topic, action.line)
        }
        val memory = npcAmbientMemories[npc.uuid] ?: return null
        if (level.gameTime <= memory.expiresAtTick) {
            return NpcAmbientFocus(memory.activity, memory.goal, memory.targetLabel, memory.topic, memory.line)
        }
        npcAmbientMemories.remove(npc.uuid)
        return null
    }

    fun recentMicroInteractionFocus(npc: ChowNpcEntity): NpcRecentMicroInteractionFocus? {
        val level = npc.level() as? ServerLevel ?: return null
        val context = npcMicroInteractionDialogContext(npc, level) ?: return null
        return NpcRecentMicroInteractionFocus(context.partnerName, context.topic, context.ownMessage, context.partnerMessage)
    }

    private fun startAmbientAction(level: ServerLevel, npc: ChowNpcEntity, definition: NpcDefinition, action: ActiveNpcAmbientAction) {
        npcAmbientActions[npc.uuid] = action
        rememberAmbientAction(level, npc, action)
        action.soloMomentId.takeIf(String::isNotBlank)?.let { rememberSoloMoment(definition.id, it, action.topic) }
        if (action.line.isNotBlank()) {
            showAmbientLine(level, npc, definition, action)
        }
        tickAmbientAction(level, npc, action)
    }

    private fun tickAmbientAction(level: ServerLevel, npc: ChowNpcEntity, action: ActiveNpcAmbientAction) {
        npc.debugActivity = action.activity
        npc.debugGoal = action.goal
        npc.debugTargetPos = action.targetPos?.immutable()
        if (action.line.isNotBlank()) showAmbientLineToClosePlayers(level, npc, action)
        action.lookPos?.let { look -> npc.lookControl.setLookAt(look.x, look.y, look.z, 30.0f, 30.0f) }
        val target = action.targetPos
        if (target == null) {
            npc.navigation.stop()
            playAmbientActionEmote(npc, action)
            return
        }
        val targetX = target.x + 0.5
        val targetY = target.y.toDouble()
        val targetZ = target.z + 0.5
        if (npc.distanceToSqr(targetX, targetY, targetZ) <= NPC_AMBIENT_REACH_DISTANCE_SQR) {
            if (action.arrivedAtTick == 0L) {
                action.arrivedAtTick = level.gameTime
                action.untilTick = minOf(action.untilTick, level.gameTime + ambientArrivalDwellTicks(level))
                rememberAmbientAction(level, npc, action)
            }
            npc.navigation.stop()
            npc.lookControl.setLookAt(targetX, targetY + 1.0, targetZ, 30.0f, 30.0f)
            playAmbientActionEmote(npc, action)
            return
        }
        if (npc.navigation.isDone || level.gameTime >= action.nextRepathTick) {
            npc.navigation.moveTo(targetX, targetY, targetZ, NPC_AMBIENT_SPEED)
            action.nextRepathTick = level.gameTime + ambientRepathDelay(level)
        }
        if (level.gameTime % 40L == 0L) rememberAmbientAction(level, npc, action)
    }

    private fun ambientArrivalDwellTicks(level: ServerLevel): Long =
        NPC_AMBIENT_ARRIVAL_DWELL_MIN_TICKS + level.random.nextInt((NPC_AMBIENT_ARRIVAL_DWELL_MAX_TICKS - NPC_AMBIENT_ARRIVAL_DWELL_MIN_TICKS + 1).toInt())

    private fun ambientRepathDelay(level: ServerLevel): Long =
        NPC_AMBIENT_REPATH_MIN_TICKS + level.random.nextInt((NPC_AMBIENT_REPATH_MAX_TICKS - NPC_AMBIENT_REPATH_MIN_TICKS + 1).toInt())


    private fun playAmbientActionEmote(npc: ChowNpcEntity, action: ActiveNpcAmbientAction) {
        if (action.emotePlayed || action.emote.isBlank()) return
        NpcEmoteController.tryPlay(npc, action.emoteSurface, action.emote, "ambient_${action.goal}")
        action.emotePlayed = true
    }

    private fun finishAmbientAction(level: ServerLevel, npc: ChowNpcEntity) {
        npcAmbientActions.remove(npc.uuid)?.let { action -> rememberAmbientAction(level, npc, action) }
        NpcEmoteController.cancelPosture(npc, "ambient_finished")
        npcAmbientCooldownUntil[npc.uuid] = level.gameTime + NPC_AMBIENT_COOLDOWN_MIN_TICKS + level.random.nextInt((NPC_AMBIENT_COOLDOWN_MAX_TICKS - NPC_AMBIENT_COOLDOWN_MIN_TICKS + 1).toInt())
    }

    private fun rememberAmbientAction(level: ServerLevel, npc: ChowNpcEntity, action: ActiveNpcAmbientAction) {
        npcAmbientMemories[npc.uuid] = NpcAmbientMemory(
            activity = action.activity,
            goal = action.goal,
            targetLabel = action.targetLabel,
            topic = action.topic,
            line = action.line,
            expiresAtTick = level.gameTime + NPC_AMBIENT_MEMORY_TICKS,
        )
    }

    private fun selectAmbientAction(level: ServerLevel, npc: ChowNpcEntity, definition: NpcDefinition, activity: String): ActiveNpcAmbientAction? {
        val moment = if (level.random.nextInt(100) < NPC_AMBIENT_SOLO_MOMENT_CHANCE) selectSoloMoment(level, definition, activity) else null
        val durationTicks = NPC_AMBIENT_MIN_TICKS + level.random.nextInt((NPC_AMBIENT_MAX_TICKS - NPC_AMBIENT_MIN_TICKS + 1).toInt())
        val untilTick = level.gameTime + durationTicks
        selectAmbientEmoteOnly(level, npc, activity, untilTick)?.let { action -> return action.withMoment(moment, definition) }
        val movementUntilTick = level.gameTime + NPC_AMBIENT_TRAVEL_MIN_TICKS + level.random.nextInt((NPC_AMBIENT_TRAVEL_MAX_TICKS - NPC_AMBIENT_TRAVEL_MIN_TICKS + 1).toInt())
        val action = when (activity) {
            "pokemon_roam" -> pokemonAmbientAction(level, npc, definition, activity, movementUntilTick)
            NpcScheduleDefinition.MEETUP_ACTIVITY -> plazaAmbientAction(npc, activity, movementUntilTick)
            "home" -> homeAmbientAction(npc, definition, activity, movementUntilTick)
            "work" -> workAmbientAction(level, npc, definition, activity, movementUntilTick)
            else -> roamAmbientAction(npc, definition, activity, movementUntilTick)
        } ?: return null
        return action.withMoment(moment, definition).withAutoAmbientEmote(level)
    }

    private fun selectAmbientEmoteOnly(level: ServerLevel, npc: ChowNpcEntity, activity: String, untilTick: Long): ActiveNpcAmbientAction? {
        if (level.random.nextInt(100) >= NPC_AMBIENT_EMOTE_ONLY_CHANCE) return null
        val surface = when {
            activity == "pokemon_roam" -> NpcEmoteSurfaces.POKEMON
            activity == "home" || activity == "work" || activity == NpcScheduleDefinition.MEETUP_ACTIVITY -> {
                if (level.random.nextInt(100) < NPC_AMBIENT_POSTURE_EMOTE_CHANCE) NpcEmoteSurfaces.AMBIENT_POSTURE else NpcEmoteSurfaces.AMBIENT
            }
            else -> NpcEmoteSurfaces.AMBIENT
        }
        val topic = if (surface == NpcEmoteSurfaces.POKEMON) "pokemon" else activity
        val emote = NpcEmoteCatalog.choose(surface, topic, level.random)
        if (emote == NpcEmoteCatalog.NONE) return null
        val label = when (surface) {
            NpcEmoteSurfaces.AMBIENT_POSTURE -> "resting spot"
            NpcEmoteSurfaces.POKEMON -> "nearby Pokemon"
            else -> "nearby paths"
        }
        return ActiveNpcAmbientAction(activity = activity, goal = "ambient_emote", topic = topic, targetLabel = label, targetPos = null, lookPos = null, untilTick = untilTick, emote = emote, emoteSurface = surface)
    }

    private fun pokemonAmbientAction(level: ServerLevel, npc: ChowNpcEntity, definition: NpcDefinition, activity: String, untilTick: Long): ActiveNpcAmbientAction? {
        val target = randomPokemonRoamTarget(npc, definition)
        if (target != null) {
            return ambientMove(activity, target.kind.id, target.kind.topic, target.kind.label, target.pos, untilTick)
        }
        return roamAmbientAction(npc, definition, activity, untilTick)
    }

    private fun plazaAmbientAction(npc: ChowNpcEntity, activity: String, untilTick: Long): ActiveNpcAmbientAction? {
        val target = randomPlazaTarget(npc) ?: return null
        return ambientMove(activity, "town_roam", "plaza", "the plaza", target, untilTick)
    }

    private fun homeAmbientAction(npc: ChowNpcEntity, definition: NpcDefinition, activity: String, untilTick: Long): ActiveNpcAmbientAction? {
        npc.homePos?.let { home ->
            val target = randomRoamTargetNear(npc, home, NPC_HOME_AMBIENT_RADIUS) ?: home
            return ambientMove(activity, "home_chore", "home", "home", target, untilTick)
        }
        return roamAmbientAction(npc, definition, activity, untilTick)?.copy(goal = "missing_home_roam", topic = "missing_home", targetLabel = "camp")
    }

    private fun workAmbientAction(level: ServerLevel, npc: ChowNpcEntity, definition: NpcDefinition, activity: String, untilTick: Long): ActiveNpcAmbientAction? {
        val workplace = NpcStore.workplacePos(definition.id)
        if (workplace != null) {
            val target = randomWorkplaceTarget(npc, workplace) ?: workplace.above()
            return ambientMove(activity, "work_chore", "work", "workplace", target, untilTick)
        }
        observePokemonAction(level, npc, activity, untilTick)?.let { return it.copy(goal = "seek_workplace_pokemon") }
        randomAmbientBlockTarget(npc, npc.homePos ?: npc.campPos ?: npc.blockPosition())?.let { (target, label) ->
            return ambientMove(activity, "seek_workplace", "missing_workplace", label, target, untilTick)
        }
        plazaAmbientAction(npc, activity, untilTick)?.let { return it.copy(goal = "seek_workplace", topic = "missing_workplace", targetLabel = "town center") }
        return roamAmbientAction(npc, definition, activity, untilTick)?.copy(goal = "seek_workplace", topic = "missing_workplace", targetLabel = "nearby paths")
    }

    private fun roamAmbientAction(npc: ChowNpcEntity, definition: NpcDefinition, activity: String, untilTick: Long): ActiveNpcAmbientAction? {
        randomAmbientBlockTarget(npc, npc.homePos ?: npc.campPos ?: npc.blockPosition())?.let { (target, label) ->
            return ambientMove(activity, "observe_block", "observed_object", label, target, untilTick)
        }
        val target = randomRoamTarget(npc, definition) ?: return null
        return ambientMove(activity, "ambient_roam", "roam", "nearby paths", target, untilTick)
    }

    private fun observePokemonAction(level: ServerLevel, npc: ChowNpcEntity, activity: String, untilTick: Long): ActiveNpcAmbientAction? {
        val pokemon = nearestWildPokemon(level, npc) ?: return null
        val species = pokemon.displayName?.string?.takeIf(String::isNotBlank) ?: "Pokemon"
        return ambientMove(activity, "observe_pokemon", "pokemon", species, pokemon.blockPosition(), untilTick)
    }

    private fun ambientMove(activity: String, goal: String, topic: String, targetLabel: String, target: BlockPos, untilTick: Long): ActiveNpcAmbientAction =
        ActiveNpcAmbientAction(activity = activity, goal = goal, topic = topic, targetLabel = targetLabel, targetPos = target, lookPos = Vec3.atCenterOf(target), untilTick = untilTick)

    private fun ActiveNpcAmbientAction.withMoment(moment: NpcSoloMomentDefinition?, definition: NpcDefinition): ActiveNpcAmbientAction {
        if (moment == null) return this
        val topic = moment.topic.ifBlank { this.topic }
        val momentEmote = moment.emote.takeIf(String::isNotBlank)
        val surface = momentEmote?.let { emote -> NpcEmoteCatalog.surfaceFor(emote, listOf(NpcEmoteSurfaces.AMBIENT, NpcEmoteSurfaces.AMBIENT_POSTURE, NpcEmoteSurfaces.POKEMON)) } ?: emoteSurface
        return copy(topic = topic, line = renderSoloMomentLine(moment.line, definition, activity, targetLabel, topic), soloMomentId = moment.id, emote = momentEmote ?: emote, emoteSurface = surface)
    }

    private fun ActiveNpcAmbientAction.withAutoAmbientEmote(level: ServerLevel): ActiveNpcAmbientAction {
        if (emote.isNotBlank() || level.random.nextInt(100) >= NPC_AMBIENT_MOVE_EMOTE_CHANCE) return this
        val surface = if (topic == "pokemon") NpcEmoteSurfaces.POKEMON else NpcEmoteSurfaces.AMBIENT
        val selected = NpcEmoteCatalog.choose(surface, topic, level.random)
        if (selected == NpcEmoteCatalog.NONE) return this
        return copy(emote = selected, emoteSurface = surface)
    }

    private fun showAmbientLine(level: ServerLevel, npc: ChowNpcEntity, definition: NpcDefinition, action: ActiveNpcAmbientAction) {
        showAmbientLineToClosePlayers(level, npc, action)
        val targetText = action.targetLabel.ifBlank { action.goal.replace('_', ' ') }
        NpcStore.recordGlobalEvent("npc_solo_moment", "${definition.name} was ${action.goal.replace('_', ' ')} near $targetText.")
    }

    private fun showAmbientLineToClosePlayers(level: ServerLevel, npc: ChowNpcEntity, action: ActiveNpcAmbientAction) {
        level.players().forEach { listener ->
            if (listener.uuid in action.shownToPlayers) return@forEach
            if (listener.distanceToSqr(npc.x, npc.y, npc.z) > NPC_BALLOON_CLOSE_RADIUS_SQR) return@forEach
            NpcNetwork.showBalloon(listener, npc.id, action.line, NPC_AMBIENT_BALLOON_TICKS)
            action.shownToPlayers += listener.uuid
        }
    }

    private fun selectSoloMoment(level: ServerLevel, definition: NpcDefinition, activity: String): NpcSoloMomentDefinition? {
        val tags = npcMicroInteractionTags(definition)
        val recent = npcSoloMomentRecent[definition.id].orEmpty()
        val spawnedNpcIds = spawnedNpcIds(level.server)
        val candidates = NpcConfig.microInteractions().soloMoments
            .filter { moment -> moment.matchesSoloMoment(definition, tags, activity, spawnedNpcIds) && moment.weight > 0.0 && moment.line.isNotBlank() }
        val fresh = candidates.filterNot { moment -> moment.id in recent || "topic:${moment.topic}" in recent }
            .ifEmpty { candidates.filterNot { moment -> moment.id in recent } }
            .ifEmpty { candidates }
        if (fresh.isEmpty()) return null
        val totalWeight = fresh.sumOf { moment -> moment.weight.coerceAtLeast(0.0) }
        if (totalWeight <= 0.0) return fresh.randomOrNull()
        var roll = level.random.nextDouble() * totalWeight
        fresh.forEach { moment ->
            roll -= moment.weight.coerceAtLeast(0.0)
            if (roll <= 0.0) return moment
        }
        return fresh.lastOrNull()
    }

    private fun NpcSoloMomentDefinition.matchesSoloMoment(definition: NpcDefinition, sourceTagSet: Set<String>, activity: String, spawnedNpcIds: Set<String>): Boolean {
        if (sourceIds.isNotEmpty() && cleanNpcMicroInteractionToken(definition.id) !in sourceIds) return false
        if (sourceTags.isNotEmpty() && sourceTags.none { tag -> tag in sourceTagSet }) return false
        if (activities.isNotEmpty() && cleanNpcMicroInteractionToken(activity) !in activities) return false
        if (requiredSpawnedIds.isNotEmpty() && requiredSpawnedIds.any { npcId -> npcId !in spawnedNpcIds }) return false
        return true
    }

    private fun rememberSoloMoment(npcId: String, momentId: String, topic: String) {
        val recent = npcSoloMomentRecent.getOrPut(npcId) { ArrayDeque() }
        recent.addLast(momentId)
        if (topic.isNotBlank()) recent.addLast("topic:$topic")
        while (recent.size > NPC_SOLO_MOMENT_RECENT_MEMORY) recent.removeFirst()
    }

    private fun renderSoloMomentLine(text: String, definition: NpcDefinition, activity: String, target: String, topic: String): String = text
        .replace("{npc}", definition.name)
        .replace("{activity}", activity)
        .replace("{target}", target)
        .replace("{topic}", topic)

    private fun updateGreetingRadiusState(npc: ChowNpcEntity, definition: NpcDefinition, playersInRadius: List<ServerPlayer>) {
        val current = playersInRadius.map { player -> player.stringUUID }.toSet()
        val previous = greetingRadiusPlayers.getOrPut(npc.uuid) { linkedSetOf() }
        previous.filter { playerId -> playerId !in current }.forEach { playerId -> NpcStore.clearGreetingCooldown(definition.id, playerId) }
        previous.clear()
        previous.addAll(current)
    }

    fun moveToActivityTarget(entity: ChowNpcEntity, definition: NpcDefinition, activity: String) {
        if (activity == "pokemon_roam") {
            val target = randomPokemonRoamTarget(entity, definition)
                ?: randomRoamTarget(entity, definition)?.let { pos -> PokemonRoamTarget(pos, PokemonRoamTargetKind.ROAM) }
                ?: return
            if (entity.isSleeping) entity.stopSleeping()
            entity.debugActivity = activity
            entity.debugGoal = if (target.kind == PokemonRoamTargetKind.POKEMON) "pokemon_watch" else target.kind.id
            entity.debugTargetPos = target.pos.immutable()
            entity.navigation.moveTo(target.pos.x + 0.5, target.pos.y.toDouble(), target.pos.z + 0.5, 0.75)
            return
        }
        if (activity == NpcScheduleDefinition.MEETUP_ACTIVITY) {
            val target = randomPlazaTarget(entity) ?: return
            if (entity.isSleeping) entity.stopSleeping()
            entity.debugActivity = activity
            entity.debugGoal = "plaza"
            entity.debugTargetPos = target.immutable()
            entity.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 0.8)
            return
        }
        if (activity == "sleep" && entity.homePos != null) {
            val home = entity.homePos ?: return
            val target = sleepingBedPos(entity.level(), home)
            entity.debugActivity = activity
            entity.debugGoal = if (entity.distanceToSqr(target.x + 0.5, target.y.toDouble(), target.z + 0.5) <= SLEEP_REACH_DISTANCE_SQR) "sleep" else "go_sleep"
            entity.debugTargetPos = target.immutable()
            if (entity.debugGoal == "sleep") {
                entity.navigation.stop()
                if (!entity.isSleeping) {
                    entity.startSleeping(target)
                    alignSleepingNpc(entity, target)
                }
            } else {
                if (entity.isSleeping) entity.stopSleeping()
                entity.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 0.8)
            }
            return
        }
        if (activity == "home" && entity.homePos != null) {
            if (entity.isSleeping) entity.stopSleeping()
            val target = entity.homePos ?: return
            entity.debugActivity = activity
            entity.debugGoal = "home"
            entity.debugTargetPos = target.immutable()
            entity.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 0.8)
            return
        }
        if (activity == "work") {
            val workplace = NpcStore.workplacePos(definition.id)
            if (workplace == null) {
                if (entity.isSleeping) entity.stopSleeping()
                entity.debugActivity = activity
                entity.debugGoal = if (NpcStore.workFired(definition.id)) "unemployed" else "no_workplace"
                entity.debugTargetPos = null
                entity.navigation.stop()
                return
            }
            val target = randomWorkplaceTarget(entity, workplace) ?: workplace.above()
            if (entity.isSleeping) entity.stopSleeping()
            entity.debugActivity = activity
            entity.debugGoal = "workplace"
            entity.debugTargetPos = target.immutable()
            entity.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 0.8)
            return
        }
        val target = randomRoamTarget(entity, definition) ?: return
        if (entity.isSleeping) entity.stopSleeping()
        entity.debugActivity = activity
        entity.debugGoal = "roam"
        entity.debugTargetPos = target.immutable()
        entity.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 0.8)
    }

    fun activityFor(entity: ChowNpcEntity, definition: NpcDefinition): String {
        if (definition.isTrainerNpc()) return "pokemon_roam"
        return NpcTime.activityAt(definition.schedule, entity.level())
    }

    private fun NpcDefinition.isTrainerNpc(): Boolean = GymLeagueFeature.isTrainerNpc(id)

    private fun randomPokemonRoamTarget(entity: ChowNpcEntity, definition: NpcDefinition): PokemonRoamTarget? {
        val level = entity.level() as? ServerLevel ?: return null
        val roll = level.random.nextInt(100)
        if (roll < 45) {
            randomRoamTarget(entity, definition)?.let { return PokemonRoamTarget(it, PokemonRoamTargetKind.ROAM) }
        }
        val pokemon = nearestWildPokemon(level, entity)
        if (pokemon != null && roll < 70) {
            if (level.random.nextInt(100) < 40) showTrainerPokemonBalloon(level, entity, definition, pokemon)
            return PokemonRoamTarget(BlockPos.containing(pokemon.x, pokemon.y, pokemon.z), PokemonRoamTargetKind.POKEMON)
        }
        if (roll < 85) {
            randomAmbientBlockTarget(entity, entity.homePos ?: entity.campPos ?: entity.blockPosition())?.let { (target, _) ->
                return PokemonRoamTarget(target, PokemonRoamTargetKind.OBJECT)
            }
        }
        if (roll < 95) {
            val trainer = nearestTrainerNpc(level, entity)
            if (trainer != null) return PokemonRoamTarget(trainer.blockPosition(), PokemonRoamTargetKind.TRAINER)
        }
        return randomRoamTarget(entity, definition)?.let { PokemonRoamTarget(it, PokemonRoamTargetKind.ROAM) }
    }

    private fun nearestWildPokemon(level: ServerLevel, entity: ChowNpcEntity): Entity? {
        val box = entity.boundingBox.inflate(TRAINER_POKEMON_SCAN_RADIUS)
        return level.getEntities(entity, box) { candidate ->
            candidate.isAlive && candidate.uuid != entity.uuid && isPokemonEntity(candidate)
        }.minByOrNull { candidate -> candidate.distanceToSqr(entity) }
    }

    private fun nearestTrainerNpc(level: ServerLevel, entity: ChowNpcEntity): ChowNpcEntity? {
        val box = entity.boundingBox.inflate(TRAINER_MEETUP_SCAN_RADIUS)
        return level.getEntities(NPC_ENTITY.get(), box) { other ->
            other.uuid != entity.uuid && other.isAlive && !other.isSleeping && !other.isTalking() && GymLeagueFeature.isTrainerNpc(other.npcId)
        }.minByOrNull { other -> other.distanceToSqr(entity) }
    }

    private fun isPokemonEntity(entity: Entity): Boolean =
        entity.javaClass.name == "com.cobblemon.mod.common.entity.pokemon.PokemonEntity"

    private fun showTrainerPokemonBalloon(level: ServerLevel, npc: ChowNpcEntity, definition: NpcDefinition, pokemon: Entity) {
        if (npc.tickCount % 80 != 0) return
        if (!GymLeagueFeature.shouldShowTrainerRoamBalloon(definition.id, level.server)) return
        val species = pokemon.displayName?.string?.takeIf(String::isNotBlank) ?: "that Pokemon"
        val message = TRAINER_POKEMON_BALLOONS.random()
            .replace("{pokemon}", species)
            .replace("{npc}", definition.name)
        showBalloonToNearby(level, npc, NpcNetwork.goldBalloon("@poke_ball.png $message"), 80)
    }

    private fun randomPlazaTarget(entity: ChowNpcEntity): BlockPos? {
        val center = plazaMeetupTarget() ?: return null
        val radius = plazaMeetupRadius()
        return randomRoamTargetNear(entity, center, radius, minimumTownTravelDistance(radius)) ?: randomRoamTargetNear(entity, center, radius) ?: center
    }

    private fun plazaMeetupTarget(): BlockPos? = NpcStore.townCenterPos() ?: NpcStore.campBlockPos()

    private fun plazaMeetupRadius(): Int = if (NpcStore.townCenterPos() != null) NpcStore.townCenterRadius() else NPC_PLAZA_CAMP_FALLBACK_RADIUS

    private fun townActivityRadius(): Int = (NpcStore.townCenterPos()?.let { NpcStore.townCenterRadius() } ?: NPC_TOWN_ACTIVITY_FALLBACK_RADIUS).coerceIn(8, 64)

    private fun minimumTownTravelDistance(radius: Int): Int = (radius / 2).coerceIn(8, 24)

    private fun onServerStarted(event: ServerStartedEvent) {
        NpcConfig.load()
        NpcBossMovesets.load()
        NpcStore.load()
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        tickDebugTime(event.server)
        tickNpcRespawns(event.server)
        tickCamperSpawner(event.server)
        tickClockDebug(event.server)
        tickRealtimeDebug(event.server)
        NpcQuestService.tick(event.server)
        NpcWorldChatService.tick(event.server)
        NpcPokemonCompanions.tick(event.server)
    }

    fun plazaMeetupStartHour(): Int = meetupEntries().minOfOrNull { entry -> entry.fromHour } ?: NPC_DEFAULT_MEETUP_START_HOUR

    fun isPlazaMeetupHour(level: Level): Boolean {
        val hour = NpcTime.hour(level)
        return meetupEntries().any { entry -> entry.includes(hour) }
    }

    fun isNpcMeetupHour(level: Level, definition: NpcDefinition): Boolean {
        return definition.schedule.activityAtHour(NpcTime.hour(level)) == NpcScheduleDefinition.MEETUP_ACTIVITY
    }

    private fun meetupEntries(): List<NpcScheduleEntryDefinition> = NpcConfig.all().flatMap { definition -> definition.schedule.meetupEntries() }

    private fun meetupWindowLabel(): String {
        val ranges = meetupEntries()
            .map { entry -> "${entry.fromHour.toString().padStart(2, '0')}:00-${entry.toHour.toString().padStart(2, '0')}:00" }
            .distinct()
        return ranges.ifEmpty { listOf("${NPC_DEFAULT_MEETUP_START_HOUR}:00-${NPC_DEFAULT_MEETUP_END_HOUR}:00") }.joinToString(",")
    }

    private fun onLivingDamagePre(event: LivingDamageEvent.Pre) {
        if (NpcPokemonCompanions.isCompanion(event.entity)) {
            event.newDamage = 0.0f
            return
        }
        val battleNpc = event.entity as? ChowNpcEntity
        if (battleNpc != null && shouldProtectPokemonBattleNpc(battleNpc)) {
            event.newDamage = 0.0f
            if (battleNpc.health < battleNpc.maxHealth) battleNpc.setHealth(battleNpc.maxHealth)
            return
        }
        val targetPlayer = event.entity as? ServerPlayer
        if (targetPlayer != null && NpcBossFights.handlePlayerDamagePre(targetPlayer, event.source, event.newDamage)) {
            event.newDamage = 0.0f
            return
        }
        if (NpcBossFights.shouldBlockDamage(event.entity, event.source.entity, event.source.directEntity)) {
            event.newDamage = 0.0f
            return
        }
        val npc = event.entity as? ChowNpcEntity ?: return
        NpcEmoteController.cancel(npc, "damage")
        if (NpcBossFights.isActive(npc)) {
            NpcBossFights.handleNpcDamage(npc, event.source.entity as? ServerPlayer, event.newDamage)
            event.newDamage = 0.0f
            return
        }
        val player = event.source.entity as? ServerPlayer ?: return
        val definition = NpcConfig.get(npc.npcId) ?: return
        val hitCount = NpcStore.recordHurt(definition.id, player, System.currentTimeMillis())
        NpcStore.adjustFriendship(definition.id, player, FRIENDSHIP_HIT_DELTA, "hit")
        showFriendshipDelta(npc.level() as? ServerLevel, npc, FRIENDSHIP_HIT_DELTA)
        NpcStore.recordConversation(definition.id, player, player.gameProfile.name, "hurts ${definition.name}", "player_hurt")
        if (hitCount % HURT_MESSAGE_INTERVAL == 0) {
            relayNpcHurtMessage(player, npc, definition)
            NpcSmartBrainOverrides.startHurtResponse(npc, player)
        }
    }

    private fun onAttackEntity(event: AttackEntityEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (NpcBossFights.shouldBlockDuelInteraction(player, player, event.target)) {
            event.isCanceled = true
            return
        }
        if (NpcPokemonCompanions.isCompanion(event.target)) {
            event.isCanceled = true
            return
        }
        val battleNpc = event.target as? ChowNpcEntity
        if (battleNpc != null && shouldProtectPokemonBattleNpc(battleNpc)) {
            event.isCanceled = true
            return
        }
        val npc = event.target as? ChowNpcEntity ?: return
        NpcEmoteController.cancel(npc, "attack_attempt")
        if (!NpcBossFights.handleNpcAttackAttempt(npc, player)) return
        event.isCanceled = true
    }

    private fun onIncomingDamage(event: LivingIncomingDamageEvent) {
        if (NpcPokemonCompanions.isCompanion(event.entity)) {
            event.isCanceled = true
            event.amount = 0.0f
            return
        }
        val npc = event.entity as? ChowNpcEntity
        if (npc != null && shouldProtectPokemonBattleNpc(npc)) {
            event.isCanceled = true
            event.amount = 0.0f
            if (npc.health < npc.maxHealth) npc.setHealth(npc.maxHealth)
            return
        }
        if (npc != null && NpcBossFights.handleNpcAttackAttempt(npc, event.source.entity as? ServerPlayer)) {
            event.isCanceled = true
            event.amount = 0.0f
            return
        }
        if (npc != null) NpcEmoteController.cancel(npc, "incoming_damage")
        if (!NpcBossFights.shouldBlockDamage(event.entity, event.source.entity, event.source.directEntity)) return
        event.isCanceled = true
        event.amount = 0.0f
    }

    private fun onLivingChangeTarget(event: LivingChangeTargetEvent) {
        val target = event.newAboutToBeSetTarget ?: return
        if (NpcPokemonCompanions.isCompanion(target) || NpcPokemonCompanions.isCompanion(event.entity)) {
            event.newAboutToBeSetTarget = null
            return
        }
        if (NpcBossFights.shouldBlockDuelInteraction(event.entity, event.entity, target)) event.newAboutToBeSetTarget = null
    }

    private fun onProjectileImpact(event: ProjectileImpactEvent) {
        val hit = event.rayTraceResult as? EntityHitResult ?: return
        val owner = event.projectile.owner
        if (!NpcBossFights.shouldBlockDuelInteraction(owner, event.projectile, hit.entity)) return
        event.isCanceled = true
        event.projectile.discard()
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        NpcStore.recordGlobalEvent("player_join", "${player.gameProfile.name} joined the server")
        NpcMissionHooks.refreshFriendshipProgress(player)
        NpcQuestService.syncTo(player)
    }

    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        removeBossFightDebugEntity(player)
        NpcBossFights.cancelForPlayer(player, "Opponent left.")
        NpcStore.recordGlobalEvent("player_leave", "${player.gameProfile.name} left the server")
        NpcConfig.all().forEach { definition -> NpcLlmService.cancel(player, definition.id) }
        removeAnimationDebugEntity(player)
        playerMicroInteractionWitnessNudge.remove(player.uuid)
        emoteDebugPlayers.remove(player.uuid)
    }

    private fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        val player = event.entity as? ServerPlayer ?: return
        removeBossFightDebugEntity(player)
        NpcBossFights.cancelForPlayer(player, "Opponent left the arena.")
    }

    private fun onServerChat(event: ServerChatEvent) {
        NpcWorldChatService.onMinecraftChat(event.player, event.rawText)
    }

    private fun onLivingDeath(event: LivingDeathEvent) {
        val npc = event.entity as? ChowNpcEntity
        if (npc != null) {
            if (shouldProtectPokemonBattleNpc(npc)) {
                event.isCanceled = true
                npc.setHealth(npc.maxHealth)
                return
            }
            NpcBossFights.clear(npc)
            NpcSmartBrainOverrides.clear(npc)
            val definition = NpcConfig.get(npc.npcId) ?: return
            if (GymLeagueFeature.isTrainerNpc(definition.id) && GymLeagueFeature.handleTrainerDeath(npc, definition)) return
            npc.server?.let { server -> NpcPokemonCompanions.removeForNpc(server, definition.id) }
            val killer = event.source.entity as? ServerPlayer
            val deathText = killer?.let { "${definition.name} died, killed by ${it.gameProfile.name}" } ?: "${definition.name} died"
            NpcStore.recordGlobalEvent("npc_death", deathText)
            npc.server?.let { server -> announceNpcDeath(server, definition, killer, deathText) }
            killer?.let { player ->
                NpcStore.adjustFriendship(definition.id, player, FRIENDSHIP_KILL_DELTA, "kill")
                showFriendshipDelta(npc.level() as? ServerLevel, npc, FRIENDSHIP_KILL_DELTA)
                NpcStore.recordConversation(definition.id, player, definition.name, deathText, "npc_death")
            }
            if (validHomePos(npc.level(), definition.id) == null) {
                val camp = npc.campPos ?: NpcStore.campBlockPos()
                if (camp != null) {
                    NpcStore.setActiveCamper(definition.id, camp)
                    NpcStore.markCamperReturnReason(definition.id, "")
                }
            }
            NpcStore.markDead(definition.id, nextRespawnDay(npc.level().dayTime))
            return
        }
        val player = event.entity as? ServerPlayer
        if (player != null) {
            removeBossFightDebugEntity(player)
            NpcBossFights.cancelForPlayer(player, "Opponent fell.")
            val deathMessage = event.source.getLocalizedDeathMessage(player).string
            NpcStore.recordGlobalEvent("player_death", deathMessage)
            NpcStore.recordGlobalMemory("player_death", deathMessage)
            NpcStore.recordPlayerMemory(player, "death", deathMessage)
            return
        }

        val killer = event.source.entity as? ServerPlayer ?: return
        if (event.entity.maxHealth < NOTABLE_KILL_HEALTH_THRESHOLD) return
        val targetName = event.entity.displayName?.string ?: event.entity.type.description.string
        val killMessage = "${killer.gameProfile.name} defeated $targetName"
        NpcStore.recordGlobalEvent("notable_kill", killMessage)
        NpcStore.recordGlobalMemory("notable_kill", killMessage)
        NpcStore.recordPlayerMemory(killer, "notable_kill", killMessage)
    }

    private fun shouldProtectPokemonBattleNpc(npc: ChowNpcEntity): Boolean =
        NpcConfig.settings().protectNpcsDuringPokemonBattles && (GymBattleService.isBattleLocked(npc) || NpcPokemonBattleService.isBattleLocked(npc))

    private fun announceNpcDeath(server: MinecraftServer, definition: NpcDefinition, killer: ServerPlayer?, deathText: String) {
        val content = killer?.let { "Killed by ${it.gameProfile.name}" } ?: "No killer recorded"
        SnackbarNetwork.sendToAllKnown(
            server,
            SnackbarNotification.npc(definition.id, "${definition.name.uppercase()} DIED", content, SnackbarType.ERROR, SnackbarSounds.ERROR),
        )
        server.playerList.broadcastSystemMessage(
            ChatGlyphs.chowKingdomPrefix()
                .append(Component.literal(definition.name).withStyle(ChatFormatting.RED))
                .append(Component.literal(" died").withStyle(ChatFormatting.GRAY))
                .apply {
                    if (killer != null) {
                        append(Component.literal(", killed by ").withStyle(ChatFormatting.GRAY))
                        append(Component.literal(killer.gameProfile.name).withStyle(ChatFormatting.YELLOW))
                    }
                    append(Component.literal(".").withStyle(ChatFormatting.GRAY))
                },
            false,
        )
        DiscordRelay.npcDeath(server, definition.id, definition.name, deathText)
    }

    private fun onBlockBreak(event: BlockEvent.BreakEvent) {
        val breaker = event.player as? ServerPlayer
        if (breaker != null && NpcBossFights.shouldBlockArenaBlockChange(breaker, event.pos)) {
            event.isCanceled = true
            SnackbarNetwork.send(breaker, SnackbarNotification.item(SnackbarIcons.ERROR, "BOSS ARENA LOCKED", "Terrain edits are blocked during the duel.", SnackbarType.ERROR, SnackbarSounds.ERROR))
            return
        }
        val level = event.level as? ServerLevel ?: return
        if (!event.state.`is`(BlockTags.BEDS)) return
        val npcId = homeOwnerAtBed(level, event.pos) ?: return
        NpcStore.clearHome(npcId)
        val camp = NpcStore.campBlockPos()
        if (camp != null) {
            NpcStore.setActiveCamper(npcId, camp)
            NpcStore.markCamperReturnReason(npcId, "lost_house")
        }
        existingNpc(level.server, npcId)?.let { npc ->
            npc.homePos = null
            if (camp != null) {
                val definition = NpcConfig.get(npcId) ?: return@let
                val listener = level.players().firstOrNull()
                npc.campPos = camp.immutable()
                npc.navigation.stop()
                npc.moveTo(camp.x + 0.5, camp.y + 1.0, camp.z + 0.5, level.random.nextFloat() * 360.0f, 0.0f)
                if (listener != null) sendNpcBalloon(level, npc, NpcNetwork.goldBalloon(camperMessage(definition.camperMessages.lostHouseBalloon, listener, definition)), 120)
            }
        }
        NpcStore.recordGlobalEvent("npc_home_lost", "$npcId lost assigned bed")
    }

    private fun onBlockPlace(event: BlockEvent.EntityPlaceEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (!NpcBossFights.shouldBlockArenaBlockChange(player, event.pos)) return
        event.isCanceled = true
        SnackbarNetwork.send(player, SnackbarNotification.item(SnackbarIcons.ERROR, "BOSS ARENA LOCKED", "Terrain edits are blocked during the duel.", SnackbarType.ERROR, SnackbarSounds.ERROR))
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        event.dispatcher.register(npcRoot("npc"))
        event.dispatcher.register(ckRoot("ck"))
        event.dispatcher.register(ckRoot("chowkingdom"))
        event.dispatcher.register(llmRoot())
        NpcQuestDebugCommands.register(event.dispatcher)
    }

    private fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (event.hand != InteractionHand.MAIN_HAND) return
        val player = event.entity
        val stack = player.getItemInHand(event.hand)
        if (!player.level().isClientSide && player is ServerPlayer && isArenaMutatingUse(stack) && NpcBossFights.shouldBlockArenaBlockChange(player, event.pos)) {
            event.isCanceled = true
            event.cancellationResult = InteractionResult.FAIL
            SnackbarNetwork.send(player, SnackbarNotification.item(SnackbarIcons.ERROR, "BOSS ARENA LOCKED", "Terrain edits are blocked during the duel.", SnackbarType.ERROR, SnackbarSounds.ERROR))
            return
        }
        val jobNpcId = NpcJobApplicationData.readNpcId(stack)
        if (jobNpcId.isNotBlank()) {
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
            if (player.level().isClientSide) return
            player as? ServerPlayer ?: return
            assignWorkplace(player, jobNpcId, event.pos, stack)
            return
        }
        val npcId = NpcRentContractData.readNpcId(stack)
        if (npcId.isBlank()) return
        if (!player.level().getBlockState(event.pos).`is`(BlockTags.BEDS)) return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        if (player.level().isClientSide) return
        player as? ServerPlayer ?: return
        assignHome(player, npcId, event.pos, stack)
    }

    private fun isArenaMutatingUse(stack: ItemStack): Boolean {
        val item = stack.item
        return item == Items.WATER_BUCKET ||
            item == Items.LAVA_BUCKET ||
            item == Items.POWDER_SNOW_BUCKET ||
            item == Items.FLINT_AND_STEEL ||
            item == Items.FIRE_CHARGE
    }

    fun homeOwnerAtBed(level: Level, pos: BlockPos): String? {
        val canonical = canonicalBedPos(level, pos)
        val paired = pairedBedHalfPos(level, pos)
        return NpcStore.homeOwnerAt(canonical) ?: NpcStore.homeOwnerAt(pos) ?: paired?.let(NpcStore::homeOwnerAt)
    }

    private fun validHomePos(level: Level, npcId: String): BlockPos? {
        val home = NpcStore.homePos(npcId) ?: return null
        if (isBedPairPresent(level, home)) return canonicalBedPos(level, home)
        NpcStore.clearHome(npcId)
        NpcStore.recordGlobalEvent("npc_home_missing", "$npcId assigned bed is missing")
        return null
    }

    private fun isBedPairPresent(level: Level, pos: BlockPos): Boolean {
        if (level.getBlockState(pos).`is`(BlockTags.BEDS)) return true
        val paired = pairedBedHalfPos(level, pos) ?: return false
        return level.getBlockState(paired).`is`(BlockTags.BEDS)
    }

    private fun canonicalBedPos(level: Level, pos: BlockPos): BlockPos {
        val state = level.getBlockState(pos)
        if (!state.`is`(BlockTags.BEDS)) return pos
        return if (state.getValue(BedBlock.PART) == BedPart.FOOT) pos.relative(state.getValue(BedBlock.FACING)) else pos
    }

    private fun pairedBedHalfPos(level: Level, pos: BlockPos): BlockPos? {
        val state = level.getBlockState(pos)
        if (!state.`is`(BlockTags.BEDS)) return null
        val facing = state.getValue(BedBlock.FACING)
        return if (state.getValue(BedBlock.PART) == BedPart.FOOT) pos.relative(facing) else pos.relative(facing.opposite)
    }

    private fun sleepingBedPos(level: Level, pos: BlockPos): BlockPos {
        val state = level.getBlockState(pos)
        if (!state.`is`(BlockTags.BEDS)) return pos
        return if (state.getValue(BedBlock.PART) == BedPart.FOOT) pos.relative(state.getValue(BedBlock.FACING)) else pos
    }

    private fun alignSleepingNpc(entity: ChowNpcEntity, bedPos: BlockPos) {
        val state = entity.level().getBlockState(bedPos)
        if (!state.`is`(BlockTags.BEDS)) return
        entity.setPos(
            bedPos.x + 0.5,
            bedPos.y + SLEEP_BED_Y_OFFSET,
            bedPos.z + 0.5,
        )
    }

    private fun npcRoot(name: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .then(Commands.literal("reload").requires { source -> source.hasPermission(2) }.executes(::reloadCommand))
        .then(
            Commands.literal("quest")
                .requires { source -> source.hasPermission(2) }
                .then(
                    Commands.literal("finish")
                        .then(
                            Commands.argument("id", StringArgumentType.word())
                                .suggests(::suggestNpcIds)
                                .executes(::questFinishCommand),
                        ),
                ),
        )
        .then(
            Commands.literal("debug")
                .requires { source -> source.hasPermission(2) }
                .executes(::debugCommand)
                .then(
                    Commands.literal("clock")
                        .executes(::debugClockCommand),
                )
                .then(
                    Commands.literal("llm")
                        .executes(::debugLlmCommand),
                )
                .then(
                    Commands.literal("emote")
                        .executes(::debugEmoteCommand),
                )
                .then(
                    Commands.literal("time")
                        .then(Commands.argument("multiplier", IntegerArgumentType.integer(1, 240)).executes(::debugTimeCommand)),
                )
                .then(
                    Commands.literal("balloon")
                        .then(
                            Commands.argument("id", StringArgumentType.word())
                                .suggests(::suggestNpcIds)
                                .then(Commands.argument("message", StringArgumentType.greedyString()).executes(::debugBalloonCommand)),
                        ),
                )
                .then(Commands.literal("quest_roll").executes(::debugQuestRollCommand)),
        )
        .then(
            Commands.literal("fight")
                .requires { source -> source.hasPermission(2) }
                .executes(::fightCommand)
                .then(
                    Commands.argument("class_id", StringArgumentType.word())
                        .suggests(::suggestBossClassIds)
                        .executes(::fightClassCommand),
                ),
        )
        .then(animationRoot("animation"))
        .then(animationRoot("animations"))
        .then(emoteRoot("emote"))
        .then(emoteRoot("emotes"))
        .then(
            Commands.literal("work")
                .requires { source -> source.hasPermission(2) }
                .then(Commands.literal("toggle").executes(::workToggleCommand)),
        )
        .then(
            Commands.literal("spawn")
                .requires { source -> source.hasPermission(2) }
                .then(Commands.literal("when").executes(::spawnWhenCommand))
                .then(
                    Commands.argument("id", StringArgumentType.word())
                        .suggests { _, builder -> SharedSuggestionProvider.suggest(NpcConfig.all().map { definition -> definition.id }, builder) }
                        .executes(::spawnCommand),
                ),
        )
        .then(
            Commands.literal("respawn")
                .requires { source -> source.hasPermission(2) }
                .then(
                    Commands.literal("status")
                        .then(
                            Commands.argument("id", StringArgumentType.word())
                                .suggests(::suggestNpcIds)
                                .executes(::respawnStatusCommand),
                        ),
                )
                .then(
                    Commands.argument("id", StringArgumentType.word())
                        .suggests(::suggestNpcIds)
                        .executes(::respawnCommand),
                ),
        )
        .then(
            Commands.literal("plaza")
                .requires { source -> source.hasPermission(2) }
                .executes(::plazaInfoCommand)
                .then(Commands.literal("set").executes(::plazaSetCommand))
                .then(Commands.literal("clear").executes(::plazaClearCommand))
                .then(Commands.literal("radius").then(Commands.argument("blocks", IntegerArgumentType.integer(4, 64)).executes(::plazaRadiusCommand))),
        )
        .then(
            Commands.literal("clear")
                .requires { source -> source.hasPermission(4) }
                .then(
                    Commands.literal("all")
                        .then(Commands.literal("confirm").executes(::clearAllNpcsCommand)),
                )
                .then(
                    Commands.argument("id", StringArgumentType.word())
                        .suggests(::suggestNpcIds)
                        .then(Commands.literal("confirm").executes(::clearNpcCommand)),
                ),
        )
        .then(
            Commands.literal("friendship")
                .requires { source -> source.hasPermission(2) }
                .then(
                    Commands.literal("get")
                        .then(
                            Commands.argument("id", StringArgumentType.word())
                                .suggests(::suggestNpcIds)
                                .then(Commands.argument("player", EntityArgument.player()).executes(::friendshipGetCommand)),
                        ),
                )
                .then(
                    Commands.literal("set")
                        .then(
                            Commands.argument("id", StringArgumentType.word())
                                .suggests(::suggestNpcIds)
                                .then(
                                    Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("points", IntegerArgumentType.integer(NpcFriendshipLevels.MIN_POINTS, NpcFriendshipLevels.MAX_POINTS)).executes(::friendshipSetCommand)),
                                ),
                        ),
                )
                .then(
                    Commands.literal("add")
                        .then(
                            Commands.argument("id", StringArgumentType.word())
                                .suggests(::suggestNpcIds)
                                .then(
                                    Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("delta", IntegerArgumentType.integer(-2000, 2000)).executes(::friendshipAddCommand)),
                                ),
                        ),
                )
        )

    private fun ckRoot(name: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .then(npcRoot("npc"))
        .then(
            Commands.literal("camping")
                .requires { source -> source.hasPermission(2) }
                .then(Commands.literal("set").executes(::campingSetCommand)),
        )
        .then(
            Commands.literal("town_center")
                .requires { source -> source.hasPermission(2) }
                .then(
                    Commands.literal("set")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 64)).executes(::townCenterSetCommand)),
                ),
        )

    private fun suggestNpcIds(context: CommandContext<CommandSourceStack>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder) =
        SharedSuggestionProvider.suggest(NpcConfig.all().map { definition -> definition.id }, builder)

    private fun suggestBossClassIds(context: CommandContext<CommandSourceStack>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder) =
        SharedSuggestionProvider.suggest((NpcBossMovesets.ids() + NpcConfig.all().map { definition -> definition.classId }.filter(String::isNotBlank)).distinct().sorted(), builder)

    private fun suggestLlmPresets(context: CommandContext<CommandSourceStack>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder) =
        SharedSuggestionProvider.suggest(NpcConfig.llmPresetNames(), builder)

    private fun llmRoot(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("llm")
        .requires { source -> source.hasPermission(2) }
        .executes(::llmStatusCommand)
        .then(Commands.literal("token").executes(::llmTokenCommand))
        .then(
            Commands.literal("switch")
                .then(
                    Commands.argument("name", StringArgumentType.word())
                        .suggests(::suggestLlmPresets)
                        .executes(::llmSwitchCommand),
                ),
        )

    private fun workToggleCommand(context: CommandContext<CommandSourceStack>): Int {
        workBypassEnabled = !workBypassEnabled
        val state = if (workBypassEnabled) "ON" else "OFF"
        val marker = if (workBypassEnabled) " Dialogs show WORK OFF NPC while active." else ""
        context.source.sendSuccess({ Component.literal("NPC work bypass $state.$marker").withStyle(if (workBypassEnabled) ChatFormatting.GOLD else ChatFormatting.GRAY) }, true)
        return 1
    }

    private fun animationRoot(name: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .requires { source -> source.hasPermission(2) }
        .then(Commands.literal("debug").executes(::animationDebugCommand))
        .then(
            Commands.literal("custom_animation")
                .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(::animationCustomAnimationCommand)),
        )
        .then(
            Commands.literal("playerlike")
                .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(::animationPlayerlikeCommand)),
        )
        .then(
            Commands.literal("body")
                .then(
                    Commands.argument("model", StringArgumentType.word())
                        .suggests(::suggestNpcBodyModels)
                        .then(
                            Commands.argument("bust_size", FloatArgumentType.floatArg(MIN_FG_BUST_SIZE.toFloat(), MAX_FG_BUST_SIZE.toFloat()))
                                .executes(::animationBodyCommand)
                                .then(
                                    Commands.argument("bounce", FloatArgumentType.floatArg(MIN_FG_BOUNCE.toFloat(), MAX_FG_BOUNCE.toFloat()))
                                        .then(Commands.argument("floppy", FloatArgumentType.floatArg(MIN_FG_FLOPPY.toFloat(), MAX_FG_FLOPPY.toFloat())).executes(::animationBodyCommand)),
                                ),
                        ),
                ),
        )
        .then(Commands.literal("list").executes(::animationListCommand))
        .then(Commands.literal("reload").executes(::animationReloadCommand))
        .then(
            Commands.literal("wear")
                .then(Commands.argument("item", StringArgumentType.greedyString()).suggests(::suggestAnimationWearables).executes(::animationWearCommand)),
        )
        .then(
            Commands.literal("itemrot")
                .then(Commands.literal("reset").executes(::animationItemRotResetCommand))
                .then(
                    Commands.argument("x", FloatArgumentType.floatArg(-3600.0f, 3600.0f))
                        .then(
                            Commands.argument("y", FloatArgumentType.floatArg(-3600.0f, 3600.0f))
                                .then(Commands.argument("z", FloatArgumentType.floatArg(-3600.0f, 3600.0f)).executes(::animationItemRotCommand)),
                        ),
                ),
        )
        .then(
            Commands.literal("itempos")
                .then(Commands.literal("reset").executes(::animationItemPosResetCommand))
                .then(
                    Commands.argument("x", FloatArgumentType.floatArg(-16.0f, 16.0f))
                        .then(
                            Commands.argument("y", FloatArgumentType.floatArg(-16.0f, 16.0f))
                                .then(Commands.argument("z", FloatArgumentType.floatArg(-16.0f, 16.0f)).executes(::animationItemPosCommand)),
                        ),
                ),
        )
        .then(
            Commands.literal("itemscale")
                .then(Commands.literal("reset").executes(::animationItemScaleResetCommand))
                .then(Commands.argument("scale", FloatArgumentType.floatArg(0.05f, 5.0f)).executes(::animationItemScaleCommand)),
        )
        .then(Commands.literal("itemrotorder").then(Commands.argument("order", StringArgumentType.word()).suggests(::suggestAnimationItemRotOrders).executes(::animationItemRotOrderCommand)))
        .then(Commands.literal("itemrotspace").then(Commands.argument("space", StringArgumentType.word()).suggests(::suggestAnimationItemRotSpaces).executes(::animationItemRotSpaceCommand)))
        .then(Commands.literal("idle").executes(::animationIdleCommand))
        .then(Commands.literal("walk").executes(::animationWalkCommand))
        .then(Commands.literal("attack").executes(::animationAttackCommand))
        .then(Commands.argument("animation", StringArgumentType.greedyString()).suggests(::suggestCustomAnimationIds).executes(::animationPlayCommand))

    private fun suggestCustomAnimationIds(context: CommandContext<CommandSourceStack>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder) =
        SharedSuggestionProvider.suggest((NpcAnimationRegistry.ids() + NpcPlayerlikeAnimationRegistry.ids() + listOf("attack", "slash", "dagger", "stab")).distinct(), builder)

    private fun emoteRoot(name: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .requires { source -> source.hasPermission(2) }
        .then(Commands.literal("list").executes(::emoteListCommand))
        .then(Commands.literal("reload").executes(::emoteReloadCommand))
        .then(Commands.literal("test").then(Commands.argument("id", StringArgumentType.word()).suggests(::suggestNpcEmoteIds).executes(::emoteTestCommand)))

    private fun suggestNpcEmoteIds(context: CommandContext<CommandSourceStack>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder) =
        SharedSuggestionProvider.suggest(NpcEmoteCatalog.ids(), builder)

    private fun suggestAnimationWearables(context: CommandContext<CommandSourceStack>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder): java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
        animationWearAliases.forEach(builder::suggest)
        return SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), builder)
    }

    private fun suggestAnimationItemRotOrders(context: CommandContext<CommandSourceStack>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder) =
        SharedSuggestionProvider.suggest(listOf("xyz", "xzy", "yxz", "yzx", "zxy", "zyx"), builder)

    private fun suggestAnimationItemRotSpaces(context: CommandContext<CommandSourceStack>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder) =
        SharedSuggestionProvider.suggest(listOf("socket", "item"), builder)

    private fun suggestNpcBodyModels(context: CommandContext<CommandSourceStack>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder) =
        SharedSuggestionProvider.suggest(listOf(BODY_MODEL_GIRL, BODY_MODEL_BOY), builder)

    private fun llmStatusCommand(context: CommandContext<CommandSourceStack>): Int {
        val settings = NpcConfig.settings().llm
        context.source.sendSuccess({ Component.literal("NPC LLM preset=${settings.activePreset} enabled=${settings.enabled} provider=${settings.provider} model=${settings.model} available=${NpcConfig.llmPresetNames().joinToString(", ")}").withStyle(ChatFormatting.GOLD) }, false)
        return 1
    }

    private fun llmSwitchCommand(context: CommandContext<CommandSourceStack>): Int {
        val result = NpcConfig.switchLlmPreset(StringArgumentType.getString(context, "name"))
        if (result.success) context.source.sendSuccess({ Component.literal(result.message).withStyle(ChatFormatting.GREEN) }, true) else context.source.sendFailure(Component.literal(result.message))
        return if (result.success) 1 else 0
    }

    private fun llmTokenCommand(context: CommandContext<CommandSourceStack>): Int {
        val lines = NpcLlmService.usageLines()
        lines.forEachIndexed { index, line ->
            val style = if (index == 0) ChatFormatting.GOLD else ChatFormatting.GRAY
            context.source.sendSuccess({ Component.literal(line).withStyle(style) }, false)
        }
        return lines.size
    }

    private fun reloadCommand(context: CommandContext<CommandSourceStack>): Int {
        ChowClockConfig.load()
        NpcConfig.load()
        val movesets = NpcBossMovesets.load()
        context.source.sendSuccess({ Component.literal("Reloaded ${NpcConfig.all().size} NPC definition(s), ${movesets.size} boss moveset(s), ${NpcEmoteCatalog.all().size} emote(s). Clock source: ${ChowClockConfig.sourceName()}.") }, true)
        return NpcConfig.all().size + movesets.size + NpcEmoteCatalog.all().size
    }

    private fun questFinishCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val npcId = StringArgumentType.getString(context, "id")
        val definition = NpcConfig.get(npcId)
        if (definition == null || !NpcQuestService.debugFinish(player, definition.id)) {
            context.source.sendFailure(Component.literal("No active NPC quest for $npcId."))
            return 0
        }
        context.source.sendSuccess({ Component.literal("Finished active NPC quest for ${definition.name}.").withStyle(ChatFormatting.GREEN) }, true)
        return 1
    }

    private fun plazaInfoCommand(context: CommandContext<CommandSourceStack>): Int {
        val center = NpcStore.townCenterPos()
        val fallback = NpcStore.campBlockPos()
        val active = center ?: fallback
        val label = when {
            center != null -> "town_center"
            fallback != null -> "camping_block_fallback"
            else -> "none"
        }
        context.source.sendSuccess({ Component.literal("NPC plaza center=$label pos=${active?.toShortString() ?: "unset"} radius=${plazaMeetupRadius()} meetup=${meetupWindowLabel()}") }, false)
        return 1
    }

    private fun plazaSetCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val pos = player.blockPosition()
        NpcStore.setTownCenter(pos)
        context.source.sendSuccess({ Component.literal("NPC town center set to ${pos.toShortString()} radius=${NpcStore.townCenterRadius()}.") }, true)
        return 1
    }

    private fun campingSetCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val pos = player.blockPosition()
        NpcStore.setCampBlock(pos)
        val spawned = spawnFromCamp(player.level(), pos)
        val message = if (spawned) {
            "NPC camping point set to ${pos.toShortString()}. A traveler has arrived at camp."
        } else {
            "NPC camping point set to ${pos.toShortString()}. No new traveler is waiting right now."
        }
        context.source.sendSuccess({ Component.literal(message) }, true)
        return 1
    }

    private fun townCenterSetCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val pos = player.blockPosition()
        val radius = IntegerArgumentType.getInteger(context, "radius")
        NpcStore.setTownCenter(pos, radius)
        context.source.sendSuccess({ Component.literal("NPC town center set to ${pos.toShortString()} radius=${NpcStore.townCenterRadius()}.") }, true)
        return radius
    }

    private fun plazaClearCommand(context: CommandContext<CommandSourceStack>): Int {
        NpcStore.clearTownCenter()
        context.source.sendSuccess({ Component.literal("NPC town center cleared. Meetup falls back to camping block if one exists.") }, true)
        return 1
    }

    private fun plazaRadiusCommand(context: CommandContext<CommandSourceStack>): Int {
        val radius = IntegerArgumentType.getInteger(context, "blocks")
        NpcStore.setTownCenterRadius(radius)
        context.source.sendSuccess({ Component.literal("NPC town center radius set to ${NpcStore.townCenterRadius()} blocks.") }, true)
        return radius
    }

    private fun fightCommand(context: CommandContext<CommandSourceStack>): Int {
        NpcConfig.load()
        NpcBossMovesets.load()
        val player = context.source.playerOrException
        val npc = lookedAtNpc(player) ?: run {
            context.source.sendFailure(Component.literal("No Chow Kingdom NPC under crosshair."))
            return 0
        }
        val definition = NpcConfig.get(npc.npcId) ?: run {
            context.source.sendFailure(Component.literal("Unknown NPC '${npc.npcId}'."))
            return 0
        }
        val result = NpcBossFights.start(player, npc, definition)
        if (result.success) context.source.sendSuccess({ Component.literal(result.message).withStyle(ChatFormatting.RED) }, true) else context.source.sendFailure(Component.literal(result.message))
        return if (result.success) 1 else 0
    }

    private fun fightClassCommand(context: CommandContext<CommandSourceStack>): Int {
        NpcConfig.load()
        NpcBossMovesets.load()
        val player = context.source.playerOrException
        val classId = StringArgumentType.getString(context, "class_id")
        val moveset = NpcBossMovesets.get(classId) ?: run {
            context.source.sendFailure(Component.literal("Unknown NPC boss class '$classId'. Available: ${NpcBossMovesets.ids().joinToString(", ")}"))
            return 0
        }
        val requestedClassId = NpcBossMovesets.normalizeId(classId)
        removeBossFightDebugEntity(player)
        val level = player.level() as? ServerLevel ?: return 0
        val spawnPos = animationDebugSpawnPos(level, player)
        val npc = NPC_ENTITY.get().create(level) ?: return 0
        val debugNpcId = bossFightDebugNpcId(player, requestedClassId.ifBlank { moveset.id })
        val debugSkin = bossFightDebugSkinDefinition(requestedClassId) ?: bossFightDebugSkinDefinition(moveset.id)
        npc.configureBossDebug(debugNpcId, "${debugSkin?.displayName() ?: moveset.displayName} Test NPC", player.blockPosition(), debugSkin?.bodyType ?: NpcBodyTypes.NORMAL)
        npc.setNoAi(false)
        npc.moveTo(spawnPos.x + 0.5, spawnPos.y.toDouble(), spawnPos.z + 0.5, player.yRot + 180.0f, 0.0f)
        level.addFreshEntity(npc)
        bossFightDebugTargets[player.uuid] = npc.uuid
        val result = NpcBossFights.startDebug(player, npc, moveset, debugSkin)
        if (!result.success) {
            bossFightDebugTargets.remove(player.uuid)
            npc.discard()
            context.source.sendFailure(Component.literal(result.message))
            return 0
        }
        context.source.sendSuccess({ Component.literal(result.message).withStyle(ChatFormatting.RED) }, true)
        return 1
    }

    private fun debugCommand(context: CommandContext<CommandSourceStack>): Int {
        NpcConfig.load()
        val player = context.source.playerOrException
        val npc = lookedAtNpc(player) ?: run {
            realtimeDebugTargets.remove(player.uuid)
            context.source.sendFailure(Component.literal("No Chow Kingdom NPC under crosshair."))
            return 0
        }
        if (realtimeDebugTargets[player.uuid] == npc.uuid) {
            realtimeDebugTargets.remove(player.uuid)
            player.sendSystemMessage(Component.literal("NPC realtime debug disabled.").withStyle(ChatFormatting.GRAY))
            return 1
        }
        realtimeDebugTargets[player.uuid] = npc.uuid
        val definition = NpcConfig.get(npc.npcId)
        val camp = npc.campPos?.toShortString() ?: "unset"
        val home = npc.homePos?.toShortString() ?: "unset"
        val target = npc.debugTargetPos?.toShortString() ?: "none"
        val navigation = if (npc.navigation.isDone) "idle" else "moving"
        val routine = "every ${definition?.jobDefinition?.scanIntervalTicks ?: 60} ticks: schedule -> target -> path"
        listOf(
            Component.literal("NPC REALTIME DEBUG: ${definition?.displayName() ?: npc.npcId}").withStyle(ChatFormatting.GOLD),
            Component.literal("id=${npc.npcId} store=${definition?.storeId()?.ifBlank { "none" } ?: "unknown"} body=${npc.bodyType} nav=$navigation"),
            Component.literal("activity=${npc.debugActivity} task=${npc.debugGoal} target=$target routine=$routine"),
            Component.literal("camp=$camp home=$home store=${definition?.storeId().orEmpty().ifBlank { "none" }}"),
            Component.literal("Actionbar will update live. Run /npc debug on the same NPC again to stop.").withStyle(ChatFormatting.DARK_GRAY),
        ).forEach(player::sendSystemMessage)
        return 1
    }

    private fun debugClockCommand(context: CommandContext<CommandSourceStack>): Int {
        ChowClockConfig.load()
        val player = context.source.playerOrException
        if (!clockDebugPlayers.add(player.uuid)) {
            clockDebugPlayers.remove(player.uuid)
            player.sendSystemMessage(Component.literal("CK clock debug disabled.").withStyle(ChatFormatting.GRAY))
            return 1
        }
        player.sendSystemMessage(Component.literal("CK CLOCK DEBUG: ${clockDebugLine(player.level())}").withStyle(ChatFormatting.GOLD))
        player.sendSystemMessage(Component.literal("Actionbar will update live. Run /npc debug clock again to stop.").withStyle(ChatFormatting.DARK_GRAY))
        return 1
    }

    private fun debugLlmCommand(context: CommandContext<CommandSourceStack>): Int {
        val settings = NpcConfig.settings().llm
        context.source.sendSuccess({ Component.literal("NPC LLM DEBUG preset=${settings.activePreset} enabled=${settings.enabled} provider=${settings.provider} model=${settings.model}").withStyle(ChatFormatting.GOLD) }, false)
        val lines = NpcLlmService.debugErrorLines()
        if (lines.isEmpty()) {
            context.source.sendSuccess({ Component.literal("No recent LLM errors recorded.").withStyle(ChatFormatting.GRAY) }, false)
            return 1
        }
        lines.forEach { line -> context.source.sendSuccess({ Component.literal(line).withStyle(ChatFormatting.GRAY) }, false) }
        return lines.size
    }

    private fun debugEmoteCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        if (!emoteDebugPlayers.add(player.uuid)) {
            emoteDebugPlayers.remove(player.uuid)
            player.sendSystemMessage(Component.literal("NPC emote debug disabled.").withStyle(ChatFormatting.GRAY))
            return 1
        }
        player.sendSystemMessage(Component.literal("NPC EMOTE DEBUG enabled. You will see every emote attempt: requested id, surface, source, and result. Run /npc debug emote again to stop.").withStyle(ChatFormatting.GOLD))
        return 1
    }

    fun debugNpcEmoteAttempt(npc: ChowNpcEntity, surface: String, requestedId: String, source: String, result: NpcEmotePlayResult) {
        val server = (npc.level() as? ServerLevel)?.server ?: return
        if (emoteDebugPlayers.isEmpty()) return
        val requested = requestedId.trim().ifBlank { "none" }
        val status = if (result.played) "played" else result.reason
        val line = "NPC EMOTE ${npc.npcId}: requested=$requested resolved=${result.id} surface=${NpcEmoteSurfaces.normalize(surface)} source=$source -> $status"
        val style = when {
            result.played -> ChatFormatting.AQUA
            result.reason == "none" -> ChatFormatting.GRAY
            else -> ChatFormatting.RED
        }
        val iterator = emoteDebugPlayers.iterator()
        while (iterator.hasNext()) {
            val player = server.playerList.getPlayer(iterator.next()) ?: run {
                iterator.remove()
                continue
            }
            player.sendSystemMessage(Component.literal(line).withStyle(style))
        }
    }

    private fun debugTimeCommand(context: CommandContext<CommandSourceStack>): Int {
        debugTimeMultiplier = IntegerArgumentType.getInteger(context, "multiplier")
        val message = if (debugTimeMultiplier == 1) "NPC debug time speed reset." else "NPC debug time speed set to ${debugTimeMultiplier}x."
        context.source.sendSuccess({ Component.literal(message) }, true)
        return debugTimeMultiplier
    }

    private fun debugBalloonCommand(context: CommandContext<CommandSourceStack>): Int {
        val id = StringArgumentType.getString(context, "id")
        val message = StringArgumentType.getString(context, "message")
        val definition = NpcConfig.get(id) ?: run {
            context.source.sendFailure(Component.literal("Unknown NPC '$id'."))
            return 0
        }
        val npc = existingNpc(context.source.server, id) ?: run {
            context.source.sendFailure(Component.literal("${definition.displayName()} is not currently spawned."))
            return 0
        }
        val level = npc.level() as? ServerLevel ?: return 0
        val recipients = sendNpcBalloon(level, npc, message, 120)
        if (recipients == 0) {
            context.source.sendFailure(Component.literal("No players are within ${NPC_DIALOG_HEAR_RADIUS.toInt()} blocks of ${definition.displayName()}."))
            return 0
        }
        context.source.sendSuccess({ Component.literal("Sent ${definition.displayName()} balloon to $recipients nearby player(s).") }, true)
        return recipients
    }

    private fun debugQuestRollCommand(context: CommandContext<CommandSourceStack>): Int {
        NpcConfig.load()
        val player = context.source.playerOrException
        val npc = lookedAtNpc(player) ?: run {
            context.source.sendFailure(Component.literal("No Chow Kingdom NPC under crosshair."))
            return 0
        }
        val definition = NpcConfig.get(npc.npcId) ?: run {
            context.source.sendFailure(Component.literal("Unknown NPC '${npc.npcId}'."))
            return 0
        }
        if (definition.missions.pool.none { mission -> mission.id.isNotBlank() }) {
            context.source.sendFailure(Component.literal("${definition.displayName()} has no NPC quest pool entries."))
            return 0
        }
        if (!NpcQuestService.debugRollQuest(player, npc, definition)) {
            context.source.sendFailure(Component.literal("Could not roll a quest for ${definition.displayName()}."))
            return 0
        }
        context.source.sendSuccess({ Component.literal("Rolled a random quest offer for ${definition.displayName()}. Run again to reroll.").withStyle(ChatFormatting.GOLD) }, true)
        return 1
    }

    private fun respawnStatusCommand(context: CommandContext<CommandSourceStack>): Int {
        NpcConfig.load()
        val id = StringArgumentType.getString(context, "id")
        val definition = NpcConfig.get(id) ?: run {
            context.source.sendFailure(Component.literal("Unknown NPC '$id'."))
            return 0
        }
        val server = context.source.server
        val dayTime = server.overworld().dayTime
        val now = NpcTime.at(dayTime)
        val currentDay = now.day
        val currentHour = now.hour
        val respawnDay = NpcStore.respawnDay(id)
        val liveNpc = existingNpc(server, id)
        val home = NpcStore.homePos(id)
        val validHome = validHomePos(server.overworld(), id)
        val ready = NpcStore.isDead(id) && liveNpc == null && respawnReady(dayTime, respawnDay) && validHome != null
        listOf(
            Component.literal("NPC RESPAWN: ${definition.displayName()}").withStyle(ChatFormatting.GOLD),
            Component.literal("live=${liveNpc != null} dead=${NpcStore.isDead(id)} currentDay=$currentDay currentHour=$currentHour respawnDay=$respawnDay ready=$ready"),
            Component.literal("home=${home?.toShortString() ?: "unset"} validHome=${validHome?.toShortString() ?: "no"}"),
            Component.literal("Rule: respawns at valid home bed when current day is due and hour is $NPC_RESPAWN_HOUR or later."),
        ).forEach(context.source::sendSystemMessage)
        return if (ready) 1 else 0
    }

    private fun respawnCommand(context: CommandContext<CommandSourceStack>): Int {
        NpcConfig.load()
        val id = StringArgumentType.getString(context, "id")
        val definition = NpcConfig.get(id) ?: run {
            context.source.sendFailure(Component.literal("Unknown NPC '$id'."))
            return 0
        }
        val server = context.source.server
        if (existingNpc(server, id) != null) {
            context.source.sendFailure(Component.literal("${definition.displayName()} already exists."))
            return 0
        }
        val home = validHomePos(server.overworld(), id) ?: run {
            context.source.sendFailure(Component.literal("${definition.displayName()} has no valid home bed."))
            return 0
        }
        return if (respawnNpc(server.overworld(), definition, home)) {
            context.source.sendSuccess({ Component.literal("Respawned ${definition.displayName()}.") }, true)
            1
        } else {
            context.source.sendFailure(Component.literal("Could not respawn ${definition.displayName()}."))
            0
        }
    }

    private fun clearAllNpcsCommand(context: CommandContext<CommandSourceStack>): Int {
        val server = context.source.server
        val removedEntities = removeLiveNpcs(server, null)
        NpcConfig.all().forEach { definition -> NpcPokemonCompanions.removeForNpc(server, definition.id) }
        val removedContracts = removeRentContracts(server, null)
        val backup = NpcStore.backupAndClearAll()
        realtimeDebugTargets.clear()
        greetingRadiusPlayers.clear()
        animationDebugTargets.clear()
        bossFightDebugTargets.clear()
        context.source.sendSuccess({
            Component.literal("DANGER: cleared all NPC state, $removedEntities live NPC(s), and $removedContracts rent contract(s). Backup: $backup").withStyle(ChatFormatting.RED)
        }, true)
        return removedEntities
    }

    private fun clearNpcCommand(context: CommandContext<CommandSourceStack>): Int {
        val id = StringArgumentType.getString(context, "id")
        val definition = NpcConfig.get(id)
        if (definition == null) {
            context.source.sendFailure(Component.literal("Unknown NPC '$id'."))
            return 0
        }
        val server = context.source.server
        val removedEntities = removeLiveNpcs(server, id)
        NpcPokemonCompanions.removeForNpc(server, id)
        val removedContracts = removeRentContracts(server, id)
        val backup = NpcStore.backupAndClearNpc(id)
        realtimeDebugTargets.clear()
        greetingRadiusPlayers.clear()
        bossFightDebugTargets.clear()
        context.source.sendSuccess({
            Component.literal("DANGER: cleared ${definition.displayName()} state, $removedEntities live NPC(s), and $removedContracts rent contract(s). Backup: $backup").withStyle(ChatFormatting.RED)
        }, true)
        return removedEntities
    }

    private fun removeLiveNpcs(server: MinecraftServer, npcId: String?): Int {
        var removed = 0
        server.allLevels.forEach { level ->
            level.getEntities(NPC_ENTITY.get()) { npc -> npcId == null || npc.npcId == npcId }.forEach { entity ->
                NpcBossFights.clear(entity)
                NpcSmartBrainOverrides.clear(entity)
                entity.discard()
                removed++
            }
        }
        return removed
    }

    private fun removeRentContracts(server: MinecraftServer, npcId: String?): Int {
        var removed = 0
        server.playerList.players.forEach { player ->
            for (slot in 0 until player.inventory.containerSize) {
                val stack = player.inventory.getItem(slot)
                val contractNpcId = NpcRentContractData.readNpcId(stack)
                if (contractNpcId.isNotBlank() && (npcId == null || contractNpcId == npcId)) {
                    removed += stack.count
                    player.inventory.setItem(slot, ItemStack.EMPTY)
                }
            }
        }
        return removed
    }

    private fun friendshipGetCommand(context: CommandContext<CommandSourceStack>): Int {
        val definition = npcDefinitionArgument(context) ?: return 0
        val player = EntityArgument.getPlayer(context, "player")
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        context.source.sendSuccess({ Component.literal("${definition.displayName()} / ${player.gameProfile.name}: ${friendship.points} points, Lv.${friendship.level}, ${friendship.category.id}.") }, false)
        return friendship.points
    }

    private fun friendshipSetCommand(context: CommandContext<CommandSourceStack>): Int {
        val definition = npcDefinitionArgument(context) ?: return 0
        val player = EntityArgument.getPlayer(context, "player")
        val points = IntegerArgumentType.getInteger(context, "points")
        val friendship = NpcStore.setFriendship(definition.id, player, points, "command_set")
        context.source.sendSuccess({ Component.literal("Set ${definition.displayName()} / ${player.gameProfile.name} to ${friendship.points} points, Lv.${friendship.level}.") }, true)
        return friendship.points
    }

    private fun friendshipAddCommand(context: CommandContext<CommandSourceStack>): Int {
        val definition = npcDefinitionArgument(context) ?: return 0
        val player = EntityArgument.getPlayer(context, "player")
        val delta = IntegerArgumentType.getInteger(context, "delta")
        val appliedDelta = NpcStore.effectiveFriendshipDelta(player, delta)
        val friendship = NpcStore.adjustFriendship(definition.id, player, delta, "command_add")
        context.source.sendSuccess({ Component.literal("Adjusted ${definition.displayName()} / ${player.gameProfile.name} by $appliedDelta to ${friendship.points} points, Lv.${friendship.level}.") }, true)
        return friendship.points
    }

    private fun npcDefinitionArgument(context: CommandContext<CommandSourceStack>): NpcDefinition? {
        val id = StringArgumentType.getString(context, "id")
        val definition = NpcConfig.get(id)
        if (definition == null) context.source.sendFailure(Component.literal("Unknown NPC '$id'."))
        return definition
    }

    private fun animationDebugCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val removed = removeAnimationDebugEntity(player)
        if (removed) {
            context.source.sendSuccess({ Component.literal("Removed animation debug Steve.").withStyle(ChatFormatting.GRAY) }, true)
            return 1
        }
        val level = player.level() as? ServerLevel ?: return 0
        val spawnPos = animationDebugSpawnPos(level, player)
        val npc = NPC_ENTITY.get().create(level) ?: return 0
        npc.configureAnimationDebug(player.blockPosition())
        npc.setNoAi(true)
        npc.moveTo(spawnPos.x + 0.5, spawnPos.y.toDouble(), spawnPos.z + 0.5, player.yRot + 180.0f, 0.0f)
        level.addFreshEntity(npc)
        animationDebugTargets[player.uuid] = npc.uuid
        context.source.sendSuccess({ Component.literal("Spawned animation debug Steve. Use /npc animation custom_animation true, then idle, walk, or attack.").withStyle(ChatFormatting.GREEN) }, true)
        return 1
    }

    private fun animationCustomAnimationCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val enabled = BoolArgumentType.getBool(context, "enabled")
        val npc = animationCommandTarget(player) ?: run {
            context.source.sendFailure(Component.literal("No animation debug Steve or Chow Kingdom NPC under crosshair."))
            return 0
        }
        npc.setCustomAnimationMode(enabled)
        context.source.sendSuccess({ Component.literal("${animationTargetName(npc)} custom_animation=$enabled.") }, true)
        return 1
    }

    private fun animationPlayerlikeCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val enabled = BoolArgumentType.getBool(context, "enabled")
        val npc = animationCommandTarget(player) ?: run {
            context.source.sendFailure(Component.literal("No animation debug Steve or Chow Kingdom NPC under crosshair."))
            return 0
        }
        npc.setPlayerlikeAnimationMode(enabled)
        context.source.sendSuccess({ Component.literal("${animationTargetName(npc)} playerlike=$enabled. Use /npc animations list for Better Combat animation ids.").withStyle(if (enabled) ChatFormatting.AQUA else ChatFormatting.GRAY) }, true)
        return 1
    }

    private fun animationBodyCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val npc = animationCommandTarget(player) ?: run {
            context.source.sendFailure(Component.literal("No animation debug Steve or Chow Kingdom NPC under crosshair."))
            return 0
        }
        val model = StringArgumentType.getString(context, "model").lowercase()
        if (model != BODY_MODEL_GIRL && model != BODY_MODEL_BOY) {
            context.source.sendFailure(Component.literal("Body model must be '$BODY_MODEL_GIRL' or '$BODY_MODEL_BOY'."))
            return 0
        }
        val bustSize = FloatArgumentType.getFloat(context, "bust_size").toDouble()
        val bounce = runCatching { FloatArgumentType.getFloat(context, "bounce").toDouble() }.getOrDefault(npc.fgBounce)
        val floppy = runCatching { FloatArgumentType.getFloat(context, "floppy").toDouble() }.getOrDefault(npc.fgFloppy)
        npc.updateFemaleGenderBody(model, bustSize, bounce, floppy)
        context.source.sendSuccess({ Component.literal("${animationTargetName(npc)} body_model=${npc.bodyModel} fg_bust_size=${npc.fgBustSize} fg_bounce=${npc.fgBounce} fg_floppy=${npc.fgFloppy}.").withStyle(ChatFormatting.LIGHT_PURPLE) }, true)
        return 1
    }

    private fun emoteListCommand(context: CommandContext<CommandSourceStack>): Int {
        val lines = NpcEmoteCatalog.debugLines()
        context.source.sendSuccess({ Component.literal("NPC emotes: ${NpcEmoteCatalog.debugStatus()} ${NpcEmotecraftBridge.debugStatus()}").withStyle(ChatFormatting.AQUA) }, false)
        lines.take(40).forEach { line -> context.source.sendSuccess({ Component.literal(line).withStyle(ChatFormatting.GRAY) }, false) }
        if (lines.size > 40) context.source.sendSuccess({ Component.literal("...and ${lines.size - 40} more.").withStyle(ChatFormatting.DARK_GRAY) }, false)
        return lines.size
    }

    private fun emoteReloadCommand(context: CommandContext<CommandSourceStack>): Int {
        NpcEmoteCatalog.reload()
        val playerlikeAnimations = NpcPlayerlikeAnimationRegistry.reload()
        context.source.sendSuccess({ Component.literal("Reloaded ${NpcEmoteCatalog.all().size} NPC emote(s) and ${playerlikeAnimations.size} playerlike animation id(s). ${NpcEmotecraftBridge.debugStatus()}").withStyle(ChatFormatting.GREEN) }, true)
        return NpcEmoteCatalog.all().size
    }

    private fun emoteTestCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val requested = StringArgumentType.getString(context, "id")
        val npc = animationCommandTarget(player) ?: run {
            context.source.sendFailure(Component.literal("No animation debug Steve or Chow Kingdom NPC under crosshair."))
            return 0
        }
        val surface = NpcEmoteCatalog.surfaceFor(requested, listOf(NpcEmoteSurfaces.CONVERSATION, NpcEmoteSurfaces.AMBIENT, NpcEmoteSurfaces.AMBIENT_POSTURE, NpcEmoteSurfaces.POKEMON, NpcEmoteSurfaces.MICRO)) ?: NpcEmoteSurfaces.CONVERSATION
        val result = NpcEmoteController.tryPlay(npc, surface, requested, "command", force = true)
        if (!result.played) {
            context.source.sendFailure(Component.literal("Could not play NPC emote '$requested' on ${animationTargetName(npc)}: ${result.reason}."))
            return 0
        }
        context.source.sendSuccess({ Component.literal("Played NPC emote ${result.id} on ${animationTargetName(npc)} surface=$surface.").withStyle(ChatFormatting.AQUA) }, true)
        return 1
    }

    private fun animationListCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val npc = animationCommandTarget(player)
        val playerlike = npc?.playerlikeAnimation == true
        val animations = if (playerlike) NpcPlayerlikeAnimationRegistry.ids() else NpcAnimationRegistry.ids()
        val label = if (playerlike) "playerlike PlayerAnimator/Emotecraft" else "Gecko"
        val suffix = if (playerlike) " ${NpcEmotecraftBridge.debugStatus()}" else ""
        context.source.sendSuccess({ Component.literal("$label NPC animation id(s): ${animations.joinToString(", ")}$suffix").withStyle(if (playerlike) ChatFormatting.AQUA else ChatFormatting.GREEN) }, false)
        return animations.size
    }

    private fun animationReloadCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        NpcEmoteCatalog.reload()
        val animations = NpcAnimationRegistry.reload()
        val playerlikeAnimations = NpcPlayerlikeAnimationRegistry.reload()
        NpcNetwork.reloadAnimations(player)
        context.source.sendSuccess({ Component.literal("Reloaded ${animations.size} Gecko id(s), ${playerlikeAnimations.size} playerlike id(s), and ${NpcEmoteCatalog.all().size} emote catalog id(s). ${NpcEmotecraftBridge.debugStatus()} Client resource reload requested.").withStyle(ChatFormatting.GREEN) }, true)
        return animations.size + playerlikeAnimations.size
    }

    private fun animationPlayCommand(context: CommandContext<CommandSourceStack>): Int {
        val requested = StringArgumentType.getString(context, "animation")
        return playAnimation(context, requested)
    }

    private fun animationWearCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val npc = animationCommandTarget(player) ?: run {
            context.source.sendFailure(Component.literal("No animation debug Steve or Chow Kingdom NPC under crosshair."))
            return 0
        }
        val requested = StringArgumentType.getString(context, "item")
        if (requested.equals("clear", ignoreCase = true) || requested.equals("none", ignoreCase = true)) {
            animationWearSlots.forEach { slot -> npc.setItemSlot(slot, ItemStack.EMPTY) }
            context.source.sendSuccess({ Component.literal("Cleared wearables on ${animationTargetName(npc)}.").withStyle(ChatFormatting.GRAY) }, true)
            return 1
        }
        val wearable = animationWearable(requested) ?: run {
            context.source.sendFailure(Component.literal("Unknown wearable '$requested'. Try hat, chestplate, boots, or a valid item id."))
            return 0
        }
        npc.setItemSlot(wearable.slot, wearable.stack.copy())
        val suffix = if (npc.customAnimation && wearable.slot != EquipmentSlot.MAINHAND && wearable.slot != EquipmentSlot.OFFHAND) " Gecko custom renderer does not draw vanilla armor layers yet." else ""
        context.source.sendSuccess({ Component.literal("Equipped ${wearable.id} on ${animationTargetName(npc)} ${wearable.slot.name.lowercase()}.${suffix}").withStyle(ChatFormatting.GREEN) }, true)
        return 1
    }

    private fun animationItemRotCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val npc = animationCommandTarget(player) ?: run {
            context.source.sendFailure(Component.literal("No animation debug Steve or Chow Kingdom NPC under crosshair."))
            return 0
        }
        val x = FloatArgumentType.getFloat(context, "x")
        val y = FloatArgumentType.getFloat(context, "y")
        val z = FloatArgumentType.getFloat(context, "z")
        npc.setHeldItemDebugRotation(x, y, z)
        context.source.sendSuccess({ Component.literal("Set held item debug rot on ${animationTargetName(npc)}: x=$x y=$y z=$z. Applied after base item adapter in X -> Y -> Z order.").withStyle(ChatFormatting.GOLD) }, true)
        return 1
    }

    private fun animationItemRotResetCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val npc = animationCommandTarget(player) ?: run {
            context.source.sendFailure(Component.literal("No animation debug Steve or Chow Kingdom NPC under crosshair."))
            return 0
        }
        npc.resetHeldItemDebugTransform()
        context.source.sendSuccess({ Component.literal("Reset held item debug transform on ${animationTargetName(npc)}.").withStyle(ChatFormatting.GRAY) }, true)
        return 1
    }

    private fun animationItemPosCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val npc = animationCommandTarget(player) ?: run {
            context.source.sendFailure(Component.literal("No animation debug Steve or Chow Kingdom NPC under crosshair."))
            return 0
        }
        val x = FloatArgumentType.getFloat(context, "x")
        val y = FloatArgumentType.getFloat(context, "y")
        val z = FloatArgumentType.getFloat(context, "z")
        npc.setHeldItemDebugPosition(x, y, z)
        context.source.sendSuccess({ Component.literal("Set held item debug pos on ${animationTargetName(npc)}: x=$x y=$y z=$z in ${npc.heldItemDebugRotSpace} space.").withStyle(ChatFormatting.GOLD) }, true)
        return 1
    }

    private fun animationItemPosResetCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val npc = animationCommandTarget(player) ?: run {
            context.source.sendFailure(Component.literal("No animation debug Steve or Chow Kingdom NPC under crosshair."))
            return 0
        }
        npc.setHeldItemDebugPosition(0.0f, 0.0f, 0.0f)
        context.source.sendSuccess({ Component.literal("Reset held item debug position on ${animationTargetName(npc)}.").withStyle(ChatFormatting.GRAY) }, true)
        return 1
    }

    private fun animationItemScaleCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val npc = animationCommandTarget(player) ?: run {
            context.source.sendFailure(Component.literal("No animation debug Steve or Chow Kingdom NPC under crosshair."))
            return 0
        }
        val scale = FloatArgumentType.getFloat(context, "scale")
        npc.updateHeldItemDebugScale(scale)
        context.source.sendSuccess({ Component.literal("Set held item debug scale on ${animationTargetName(npc)}: ${npc.heldItemDebugScale}.").withStyle(ChatFormatting.GOLD) }, true)
        return 1
    }

    private fun animationItemScaleResetCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val npc = animationCommandTarget(player) ?: run {
            context.source.sendFailure(Component.literal("No animation debug Steve or Chow Kingdom NPC under crosshair."))
            return 0
        }
        npc.updateHeldItemDebugScale(1.0f)
        context.source.sendSuccess({ Component.literal("Reset held item debug scale on ${animationTargetName(npc)}.").withStyle(ChatFormatting.GRAY) }, true)
        return 1
    }

    private fun animationItemRotOrderCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val npc = animationCommandTarget(player) ?: run {
            context.source.sendFailure(Component.literal("No animation debug Steve or Chow Kingdom NPC under crosshair."))
            return 0
        }
        val order = StringArgumentType.getString(context, "order")
        if (!npc.setHeldItemDebugRotationOrder(order)) {
            context.source.sendFailure(Component.literal("Invalid order '$order'. Use xyz, xzy, yxz, yzx, zxy, or zyx."))
            return 0
        }
        context.source.sendSuccess({ Component.literal("Set held item debug rotation order on ${animationTargetName(npc)}: ${npc.heldItemDebugRotOrder}.").withStyle(ChatFormatting.GOLD) }, true)
        return 1
    }

    private fun animationItemRotSpaceCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val npc = animationCommandTarget(player) ?: run {
            context.source.sendFailure(Component.literal("No animation debug Steve or Chow Kingdom NPC under crosshair."))
            return 0
        }
        val space = StringArgumentType.getString(context, "space")
        if (!npc.setHeldItemDebugRotationSpace(space)) {
            context.source.sendFailure(Component.literal("Invalid space '$space'. Use socket or item."))
            return 0
        }
        context.source.sendSuccess({ Component.literal("Set held item debug rotation space on ${animationTargetName(npc)}: ${npc.heldItemDebugRotSpace}.").withStyle(ChatFormatting.GOLD) }, true)
        return 1
    }

    private fun animationIdleCommand(context: CommandContext<CommandSourceStack>): Int {
        return playAnimation(context, ChowNpcEntity.CUSTOM_ANIMATION_IDLE)
    }

    private fun animationWalkCommand(context: CommandContext<CommandSourceStack>): Int {
        return playAnimation(context, ChowNpcEntity.CUSTOM_ANIMATION_WALK)
    }

    private fun animationAttackCommand(context: CommandContext<CommandSourceStack>): Int {
        return playAnimation(context, ChowNpcEntity.CUSTOM_ANIMATION_ATTACK)
    }

    private fun animationWearable(requested: String): AnimationWearable? {
        val rawNormalized = requested.trim().lowercase()
        val leftHand = rawNormalized.startsWith("left ") || rawNormalized.startsWith("offhand ") || rawNormalized.startsWith("left_") || rawNormalized.startsWith("offhand_")
        val normalized = rawNormalized
            .removePrefix("left ")
            .removePrefix("offhand ")
            .removePrefix("left_")
            .removePrefix("offhand_")
        val alias = when (normalized) {
            "hat", "helmet" -> AnimationWearable(EquipmentSlot.HEAD, ItemStack(Items.IRON_HELMET), "minecraft:iron_helmet")
            "chestplate" -> AnimationWearable(EquipmentSlot.CHEST, ItemStack(Items.IRON_CHESTPLATE), "minecraft:iron_chestplate")
            "leggings" -> AnimationWearable(EquipmentSlot.LEGS, ItemStack(Items.IRON_LEGGINGS), "minecraft:iron_leggings")
            "boots" -> AnimationWearable(EquipmentSlot.FEET, ItemStack(Items.IRON_BOOTS), "minecraft:iron_boots")
            "sword" -> AnimationWearable(if (leftHand) EquipmentSlot.OFFHAND else EquipmentSlot.MAINHAND, ItemStack(Items.IRON_SWORD), "minecraft:iron_sword")
            else -> null
        }
        if (alias != null) return alias
        val id = runCatching { ResourceLocation.parse(if (normalized.contains(':')) normalized else "minecraft:$normalized") }.getOrNull() ?: return null
        val item = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR)
        if (item === Items.AIR) return null
        return AnimationWearable(if (leftHand) EquipmentSlot.OFFHAND else animationWearSlot(id.path), ItemStack(item), id.toString())
    }

    private fun animationWearSlot(itemPath: String): EquipmentSlot = when {
        itemPath.endsWith("_chestplate") || itemPath == "elytra" -> EquipmentSlot.CHEST
        itemPath.endsWith("_leggings") -> EquipmentSlot.LEGS
        itemPath.endsWith("_boots") -> EquipmentSlot.FEET
        itemPath.contains("sword") || itemPath.endsWith("_axe") || itemPath.endsWith("_trident") -> EquipmentSlot.MAINHAND
        else -> EquipmentSlot.HEAD
    }

    private fun playAnimation(context: CommandContext<CommandSourceStack>, animationId: String): Int {
        val player = context.source.playerOrException
        val npc = animationCommandTarget(player) ?: run {
            context.source.sendFailure(Component.literal("No animation debug Steve or Chow Kingdom NPC under crosshair."))
            return 0
        }
        if (npc.playerlikeAnimation) {
            val animation = NpcPlayerlikeAnimationRegistry.resolve(animationId) ?: run {
                context.source.sendFailure(Component.literal("Unknown playerlike NPC animation '$animationId'. Available: ${NpcPlayerlikeAnimationRegistry.ids().joinToString(", ")}"))
                return 0
            }
            if (!npc.playPlayerlikeAnimation(animation.id)) {
                context.source.sendFailure(Component.literal("Invalid playerlike NPC animation '${animation.id}'."))
                return 0
            }
            val status = if (NpcEmotecraftBridge.isEmotecraftId(animation.id)) " ${NpcEmotecraftBridge.debugStatus()}" else ""
            context.source.sendSuccess({ Component.literal("Queued ${animation.id} playerlike animation on ${animationTargetName(npc)}.$status If it does not move, check client log for CKDM playerlike animation warnings.").withStyle(ChatFormatting.AQUA) }, true)
            return 1
        }
        val animation = NpcAnimationRegistry.resolve(animationId) ?: run {
            context.source.sendFailure(Component.literal("Invalid NPC animation '$animationId'."))
            return 0
        }
        if (!npc.playCustomAnimation(animation.id)) {
            context.source.sendFailure(Component.literal("Invalid NPC animation '${animation.id}'."))
            return 0
        }
        context.source.sendSuccess({ Component.literal("Playing ${animation.id} animation on ${animationTargetName(npc)}.") }, true)
        return 1
    }

    private fun animationCommandTarget(player: ServerPlayer): ChowNpcEntity? {
        val debugNpc = animationDebugTargets[player.uuid]
            ?.let { entityId -> findNpc(player.server, entityId) }
            ?.takeIf { npc -> npc.isAlive && npc.npcId == ChowNpcEntity.ANIMATION_DEBUG_NPC_ID }
        if (debugNpc != null) return debugNpc
        animationDebugTargets.remove(player.uuid)
        return lookedAtNpc(player)
    }

    private fun removeAnimationDebugEntity(player: ServerPlayer): Boolean {
        val entityId = animationDebugTargets.remove(player.uuid) ?: return false
        val npc = findNpc(player.server, entityId)?.takeIf { entity -> entity.npcId == ChowNpcEntity.ANIMATION_DEBUG_NPC_ID } ?: return false
        npc.discard()
        return true
    }

    private fun removeBossFightDebugEntity(player: ServerPlayer): Boolean {
        val entityId = bossFightDebugTargets.remove(player.uuid) ?: return false
        val npc = findNpc(player.server, entityId) ?: return false
        NpcBossFights.clear(npc)
        NpcSmartBrainOverrides.clear(npc)
        npc.discard()
        return true
    }

    private fun bossFightDebugNpcId(player: ServerPlayer, classId: String): String =
        "boss_debug_${NpcBossMovesets.normalizeId(classId)}_${player.uuid.toString().take(8)}"

    private fun bossFightDebugSkinDefinition(classId: String): NpcDefinition? {
        val normalizedClassId = NpcBossMovesets.normalizeId(classId)
        return NpcConfig.all().firstOrNull { definition -> NpcBossMovesets.normalizeId(definition.classId) == normalizedClassId && definition.skin.isNotBlank() }
    }

    private fun animationDebugSpawnPos(level: ServerLevel, player: ServerPlayer): BlockPos {
        val base = player.blockPosition().relative(player.direction, 2)
        return listOf(base, base.above(), base.below(), player.blockPosition()).firstOrNull { pos -> canSpawnNpcAt(level, pos) } ?: player.blockPosition()
    }

    private fun animationTargetName(npc: ChowNpcEntity): String = if (npc.npcId == ChowNpcEntity.ANIMATION_DEBUG_NPC_ID) "animation debug Steve" else npc.npcId

    private val animationWearSlots = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)

    private data class AnimationWearable(val slot: EquipmentSlot, val stack: ItemStack, val id: String)

    private fun tickDebugTime(server: MinecraftServer) {
        if (debugTimeMultiplier <= 1) return
        server.allLevels.forEach { level -> level.setDayTime(level.dayTime + debugTimeMultiplier - 1L) }
    }

    private fun tickNpcRespawns(server: MinecraftServer) {
        if (server.tickCount % RESPAWN_SCAN_INTERVAL_TICKS != 0) return
        val dayTime = server.overworld().dayTime
        NpcStore.deadNpcIds().forEach { npcId ->
            if (!respawnReady(dayTime, NpcStore.respawnDay(npcId))) return@forEach
            val definition = NpcConfig.get(npcId) ?: return@forEach
            if (existingNpc(server, npcId) != null) {
                NpcStore.clearDead(npcId)
                return@forEach
            }
            if (NpcStore.homePos(npcId) == null) {
                val camp = NpcStore.campBlockPos() ?: NpcStore.campPos(npcId) ?: return@forEach
                spawnNpc(server.overworld(), definition, camp, markActiveCamper = true)
                return@forEach
            }
            val home = validHomePos(server.overworld(), npcId) ?: return@forEach
            respawnNpc(server.overworld(), definition, home)
        }
    }

    private fun tickCamperSpawner(server: MinecraftServer) {
        if (server.tickCount % RESPAWN_SCAN_INTERVAL_TICKS != 0) return
        val camp = NpcStore.campBlockPos() ?: return
        val level = server.overworld()
        if (activeUnhousedCamper(server) ?: migrateLiveUnhousedCamper(server) != null) return
        if (TechLicenseFeature.trySpawnPriorityCamper(server)) return
        val cooldownUntil = NpcStore.camperCooldownUntilTick()
        if (cooldownUntil > level.dayTime) return
        val definition = randomEligibleCamper(level) ?: return
        spawnNpc(level, definition, camp, markActiveCamper = true, announceCamperArrival = true)
    }

    private fun activeUnhousedCamper(server: MinecraftServer): NpcDefinition? {
        val activeId = NpcStore.activeCamperId().ifBlank { return null }
        if (GymLeagueFeature.isTrainerNpc(activeId)) {
            NpcStore.clearActiveCamper(activeId)
            return null
        }
        val definition = NpcConfig.get(activeId) ?: return null
        if (validHomePos(server.overworld(), activeId) != null) {
            NpcStore.clearActiveCamper(activeId)
            return null
        }
        return definition
    }

    private fun migrateLiveUnhousedCamper(server: MinecraftServer): NpcDefinition? {
        NpcConfig.all().forEach { definition ->
            if (GymLeagueFeature.isTrainerNpc(definition.id)) return@forEach
            val npc = existingNpc(server, definition.id) ?: return@forEach
            if (validHomePos(npc.level(), definition.id) != null) return@forEach
            val camp = npc.campPos ?: NpcStore.campBlockPos() ?: npc.blockPosition()
            NpcStore.setActiveCamper(definition.id, camp)
            npc.campPos = camp.immutable()
            return definition
        }
        return null
    }

    private fun randomEligibleCamper(level: ServerLevel): NpcDefinition? {
        val candidates = normalEligibleCampers(level)
        if (candidates.isEmpty()) return null
        return candidates[level.random.nextInt(candidates.size)]
    }

    private fun normalEligibleCampers(level: ServerLevel): List<NpcDefinition> =
        NpcConfig.all()
            .filterNot { definition -> GymLeagueFeature.isTrainerNpc(definition.id) }
            .filterNot { definition -> TechLicenseFeature.isTechExpertNpc(definition.id) }
            .filter { definition -> definition.housing.canMoveIn }
            .filter { definition -> NpcStore.homePos(definition.id) == null }
            .filter { definition -> existingNpc(level.server, definition.id) == null || NpcStore.isDead(definition.id) }

    private fun scheduleNextCamper(level: Level) {
        val settings = NpcConfig.settings().campers
        val hours = if (settings.cooldownMinHours == settings.cooldownMaxHours) {
            settings.cooldownMinHours
        } else {
            settings.cooldownMinHours + level.random.nextInt(settings.cooldownMaxHours - settings.cooldownMinHours + 1)
        }
        NpcStore.setCamperCooldown(NpcTime.addHours(level.dayTime, hours))
    }

    private fun respawnReady(dayTime: Long, respawnDay: Long): Boolean {
        return NpcTime.readyAtOrAfterHour(dayTime, respawnDay, NPC_RESPAWN_HOUR)
    }

    private fun nextRespawnDay(dayTime: Long): Long {
        return NpcTime.nextDayAtHour(dayTime, NPC_RESPAWN_HOUR)
    }

    private fun tickRealtimeDebug(server: MinecraftServer) {
        if (server.tickCount % REALTIME_DEBUG_INTERVAL_TICKS != 0) return
        val iterator = realtimeDebugTargets.iterator()
        while (iterator.hasNext()) {
            val (playerId, npcId) = iterator.next()
            val player = server.playerList.getPlayer(playerId) ?: run {
                iterator.remove()
                continue
            }
            val npc = findNpc(server, npcId) ?: run {
                player.displayClientMessage(Component.literal("NPC debug target missing.").withStyle(ChatFormatting.RED), true)
                iterator.remove()
                continue
            }
            if (!npc.isAlive) {
                iterator.remove()
                continue
            }
            player.displayClientMessage(Component.literal(realtimeDebugLine(npc)).withStyle(ChatFormatting.AQUA), true)
        }
    }

    private fun tickClockDebug(server: MinecraftServer) {
        if (server.tickCount % REALTIME_DEBUG_INTERVAL_TICKS != 0) return
        val iterator = clockDebugPlayers.iterator()
        while (iterator.hasNext()) {
            val playerId = iterator.next()
            val player = server.playerList.getPlayer(playerId) ?: run {
                iterator.remove()
                continue
            }
            player.displayClientMessage(Component.literal(clockDebugLine(player.level())).withStyle(ChatFormatting.YELLOW), true)
        }
    }

    private fun clockDebugLine(level: Level): String {
        val clock = NpcTime.at(level.dayTime)
        val daylight = level.gameRules.getBoolean(GameRules.RULE_DAYLIGHT)
        val speed = if (debugTimeMultiplier > 1) " debug=${debugTimeMultiplier}x" else ""
        return "time=${clock.displayTime()} hour=${clock.hour.toString().padStart(2, '0')} day=${clock.day} raw=${clock.rawDayTime} tick=${clock.tickOfCycle} source=${ChowClockConfig.sourceName()} daylight=$daylight$speed"
    }

    private fun realtimeDebugLine(npc: ChowNpcEntity): String {
        val nav = if (npc.navigation.isDone) "idle" else "moving"
        val target = npc.debugTargetPos?.toShortString() ?: "none"
        val clock = NpcTime.at(npc.level().dayTime)
        val time = if (debugTimeMultiplier > 1) " time=${debugTimeMultiplier}x" else ""
        return "${npc.npcId} | ${clock.displayTime()} tick=${clock.tickOfCycle} activity=${npc.debugActivity} task=${npc.debugGoal} nav=$nav target=$target$time"
    }

    private fun spawnWhenCommand(context: CommandContext<CommandSourceStack>): Int {
        NpcConfig.load()
        NpcStore.load()
        val server = context.source.server
        val camp = NpcStore.campBlockPos()
        if (camp == null) {
            context.source.sendSuccess({ Component.literal("No camper can spawn: camping point is unset. Use /ck camping set.") }, false)
            return 1
        }
        val active = activeUnhousedCamper(server) ?: migrateLiveUnhousedCamper(server)
        if (active != null) {
            context.source.sendSuccess({ Component.literal("Active camper: ${active.name} is waiting at camp. House them before another camper can spawn.") }, false)
            return 1
        }
        TechLicenseFeature.nextPriorityCamper(server)?.let { priority ->
            context.source.sendSuccess({ Component.literal("Next priority camper: ${priority.npcName} for ${priority.licenseName}. Camp is free; spawn check is ready now.") }, false)
            return 1
        }
        val level = server.overworld()
        val candidates = normalEligibleCampers(level).sortedBy { definition -> definition.name.lowercase() }
        if (candidates.isEmpty()) {
            context.source.sendSuccess({ Component.literal("No eligible campers remain.") }, false)
            return 1
        }
        val cooldownUntil = NpcStore.camperCooldownUntilTick()
        val timing = if (cooldownUntil > level.dayTime) {
            "Cooldown ends in ${formatSpawnDelay(cooldownUntil - level.dayTime)}."
        } else {
            "Spawn check is ready now."
        }
        context.source.sendSuccess({ Component.literal("Next normal camper: random from ${candidates.size} eligible NPC(s): ${camperPoolLabel(candidates)}. $timing") }, false)
        return 1
    }

    private fun camperPoolLabel(candidates: List<NpcDefinition>): String {
        val names = candidates.take(8).joinToString(", ") { definition -> definition.name }
        return if (candidates.size > 8) "$names, ..." else names
    }

    private fun formatSpawnDelay(ticks: Long): String {
        val remaining = ticks.coerceAtLeast(0L)
        val days = remaining / 24000L
        val hours = (remaining % 24000L) / 1000L
        val parts = mutableListOf<String>()
        if (days > 0) parts += "$days in-game day${if (days == 1L) "" else "s"}"
        if (hours > 0) parts += "$hours hour${if (hours == 1L) "" else "s"}"
        return parts.joinToString(" ").ifBlank { "less than 1 in-game hour" }
    }

    private fun spawnCommand(context: CommandContext<CommandSourceStack>): Int {
        NpcConfig.load()
        val player = context.source.playerOrException
        val id = StringArgumentType.getString(context, "id")
        val definition = NpcConfig.get(id) ?: run {
            context.source.sendFailure(Component.literal("Unknown NPC '$id'."))
            return 0
        }
        val camp = player.blockPosition()
        return if (spawnNpc(player.level() as ServerLevel, definition, camp)) {
            context.source.sendSuccess({ Component.literal("Spawned ${definition.displayName()}.") }, true)
            1
        } else {
            context.source.sendFailure(Component.literal("${definition.displayName()} already exists."))
            0
        }
    }

    private fun registerAttributes(event: EntityAttributeCreationEvent) {
        event.put(
            NPC_ENTITY.get(),
            Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 2.0)
                .add(Attributes.FOLLOW_RANGE, 24.0)
                .build(),
        )
    }

    private fun spawnNpc(level: ServerLevel, definition: NpcDefinition, campPos: BlockPos, markActiveCamper: Boolean = false, announceCamperArrival: Boolean = false): Boolean {
        if (existingNpc(level.server, definition.id) != null) return false
        val npc = NPC_ENTITY.get().create(level) ?: return false
        val spawnPos = npcSpawnAroundCamp(level, campPos) ?: return false
        npc.configure(definition, campPos)
        npc.moveTo(spawnPos.x + 0.5, spawnPos.y.toDouble(), spawnPos.z + 0.5, level.random.nextFloat() * 360.0f, 0.0f)
        level.addFreshEntity(npc)
        NpcPokemonCompanions.ensureFor(npc, definition)
        NpcStore.setEntity(definition.id, npc.uuid, campPos)
        NpcStore.clearDead(definition.id)
        NpcStore.recordGlobalEvent("npc_spawned", "${definition.name} appeared near the camping block on day ${NpcTime.day(level)}.")
        if (markActiveCamper) {
            NpcStore.setActiveCamper(definition.id, campPos)
            level.players().firstOrNull()?.let { player ->
                val message = NpcNetwork.goldBalloon(camperMessage(definition.camperMessages.needsHouseBalloon, player, definition))
                sendNpcBalloon(level, npc, message, 120)
            }
            if (announceCamperArrival) announceCamperArrival(level, definition)
        }
        return true
    }

    fun hasActiveUnhousedCamper(server: MinecraftServer): Boolean =
        (activeUnhousedCamper(server) ?: migrateLiveUnhousedCamper(server)) != null

    fun spawnConfiguredNpcAt(level: ServerLevel, definition: NpcDefinition, anchor: BlockPos): Boolean =
        spawnNpc(level, definition, anchor, markActiveCamper = false, announceCamperArrival = false)

    fun spawnConfiguredNpcAsCamper(level: ServerLevel, definition: NpcDefinition, anchor: BlockPos, announceCamperArrival: Boolean = false): Boolean =
        spawnNpc(level, definition, anchor, markActiveCamper = true, announceCamperArrival = announceCamperArrival)

    fun existingConfiguredNpc(server: MinecraftServer, npcId: String): ChowNpcEntity? = existingNpc(server, npcId)

    private fun announceCamperArrival(level: ServerLevel, definition: NpcDefinition) {
        val notification = SnackbarNotification.npc(
            definition.id,
            "NEW CAMPER AT THE CAMPING BLOCK",
            "${definition.name} is looking for a place to stay. Talk to them to welcome them and offer a rent contract.",
            SnackbarType.SUCCESS,
            SnackbarSounds.REWARD,
        )
        SnackbarNetwork.sendToAllKnown(level.server, notification)
        DiscordRelay.npcCamperArrived(level.server, definition.id, definition.name)
    }

    private fun npcSpawnAroundCamp(level: ServerLevel, campPos: BlockPos): BlockPos? {
        val offsets = (-NPC_CAMP_SPAWN_RADIUS..NPC_CAMP_SPAWN_RADIUS).flatMap { dx ->
            (-NPC_CAMP_SPAWN_RADIUS..NPC_CAMP_SPAWN_RADIUS).map { dz -> dx to dz }
        }.filter { (dx, dz) -> dx != 0 || dz != 0 }
            .sortedWith(compareBy<Pair<Int, Int>> { (dx, dz) -> dx * dx + dz * dz }.thenBy { it.first }.thenBy { it.second })
        offsets.forEach { (dx, dz) ->
            val base = campPos.offset(dx, 0, dz)
            listOf(base, base.above(), base.below()).forEach { candidate ->
                if (canSpawnNpcAt(level, candidate)) return candidate
            }
        }
        return null
    }

    private fun canSpawnNpcAt(level: ServerLevel, pos: BlockPos): Boolean {
        val below = pos.below()
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty
    }

    private fun respawnNpc(level: ServerLevel, definition: NpcDefinition, homePos: BlockPos): Boolean {
        val npc = NPC_ENTITY.get().create(level) ?: return false
        val camp = NpcStore.campPos(definition.id) ?: homePos
        npc.configure(definition, camp)
        npc.homePos = homePos.immutable()
        npc.moveTo(homePos.x + 0.5, homePos.y + 1.0, homePos.z + 0.5, level.random.nextFloat() * 360.0f, 0.0f)
        level.addFreshEntity(npc)
        NpcPokemonCompanions.ensureFor(npc, definition)
        NpcStore.setEntity(definition.id, npc.uuid, camp)
        NpcStore.clearDead(definition.id)
        NpcStore.recordGlobalEvent("npc_respawn", "${definition.name} respawned at home bed")
        return true
    }

    private fun createRentContract(npcId: String): ItemStack = NpcRentContractData.forNpc(ItemStack(RENT_CONTRACT.get()), npcId)

    private fun createJobApplication(npcId: String): ItemStack = NpcJobApplicationData.forNpc(ItemStack(JOB_APPLICATION.get()), npcId)

    private fun hasRentContract(player: ServerPlayer, npcId: String): Boolean {
        for (slot in 0 until player.inventory.containerSize) {
            if (NpcRentContractData.readNpcId(player.inventory.getItem(slot)) == npcId) return true
        }
        return false
    }

    private fun giveStack(player: ServerPlayer, stack: ItemStack) {
        val working = stack.copy()
        player.inventory.add(working)
        if (!working.isEmpty) player.drop(working, false)
    }

    fun existingNpc(server: MinecraftServer, npcId: String): ChowNpcEntity? {
        val storedId = NpcStore.entityUuid(npcId)
        val byStore = storedId?.let { uuid -> findNpc(server, uuid) }
        if (byStore != null && byStore.isAlive) return byStore
        return server.allLevels.asSequence()
            .flatMap { level -> level.getEntities(NPC_ENTITY.get()) { npc -> npc.npcId == npcId && npc.isAlive }.asSequence() }
            .firstOrNull()
    }

    fun existingNpcs(server: MinecraftServer, npcId: String): List<ChowNpcEntity> = server.allLevels.asSequence()
        .flatMap { level -> level.getEntities(NPC_ENTITY.get()) { npc -> npc.npcId == npcId && npc.isAlive }.asSequence() }
        .toList()

    private fun spawnedNpcIds(server: MinecraftServer): Set<String> = server.allLevels.asSequence()
        .flatMap { level -> level.getEntities(NPC_ENTITY.get()) { npc -> npc.isAlive }.asSequence() }
        .map { npc -> cleanNpcMicroInteractionToken(npc.npcId) }
        .filter(String::isNotBlank)
        .toSet()

    private fun findNpc(server: MinecraftServer, entityId: UUID): ChowNpcEntity? = server.allLevels.asSequence()
        .mapNotNull { level -> level.getEntity(entityId) as? ChowNpcEntity }
        .firstOrNull()

    fun lookedAtNpc(player: ServerPlayer): ChowNpcEntity? {
        val level = player.level() as? ServerLevel ?: return null
        val start = player.getEyePosition(0.0f)
        val look = player.lookAngle.normalize()
        val end = start.add(look.scale(DEBUG_REACH))
        val scanBox = AABB(start, end).inflate(1.5)
        return level.getEntities(NPC_ENTITY.get(), scanBox) { npc -> npc.isAlive }.asSequence()
            .mapNotNull { npc -> npcLookHit(start, look, npc) }
            .minWithOrNull(compareBy<NpcLookHit> { it.distanceSqr }.thenBy { it.along })
            ?.npc
    }

    private fun npcLookHit(start: Vec3, look: Vec3, npc: ChowNpcEntity): NpcLookHit? {
        val center = npc.position().add(0.0, npc.bbHeight.toDouble() * 0.5, 0.0)
        val offset = center.subtract(start)
        val along = offset.dot(look)
        if (along < 0.0 || along > DEBUG_REACH) return null
        val closest = start.add(look.scale(along))
        val distanceSqr = center.distanceToSqr(closest)
        return if (distanceSqr <= DEBUG_AIM_RADIUS * DEBUG_AIM_RADIUS) NpcLookHit(npc, along, distanceSqr) else null
    }

    private fun randomRoamTarget(entity: ChowNpcEntity, definition: NpcDefinition): BlockPos? {
        val base = entity.homePos ?: entity.campPos ?: plazaMeetupTarget() ?: entity.blockPosition()
        val radius = max(definition.jobDefinition.roamRadius, townActivityRadius())
        return randomRoamTargetNear(entity, base, radius, minimumTownTravelDistance(radius))
            ?: randomRoamTargetNear(entity, base, radius)
    }

    private fun randomRoamTargetNear(entity: ChowNpcEntity, base: BlockPos, radius: Int, minTravelDistance: Int = 0): BlockPos? {
        val random = entity.random
        val clampedRadius = radius.coerceIn(1, 64)
        val minDistanceSqr = minTravelDistance.toDouble() * minTravelDistance.toDouble()
        repeat(32) {
            val x = base.x + random.nextInt(clampedRadius * 2 + 1) - clampedRadius
            val z = base.z + random.nextInt(clampedRadius * 2 + 1) - clampedRadius
            val y = entity.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, base.y, z)).y
            val pos = BlockPos(x, y, z)
            if (minTravelDistance > 0 && entity.distanceToSqr(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5) < minDistanceSqr) return@repeat
            if (entity.navigation.createPath(pos, 0) != null) return pos
        }
        return null
    }

    private fun randomAmbientBlockTarget(entity: ChowNpcEntity, base: BlockPos): Pair<BlockPos, String>? {
        val level = entity.level()
        val random = entity.random
        val radius = NPC_AMBIENT_OBJECT_SCAN_RADIUS
        repeat(24) {
            val x = base.x + random.nextInt(radius * 2 + 1) - radius
            val z = base.z + random.nextInt(radius * 2 + 1) - radius
            val y = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos(x, base.y, z)).y
            val surface = BlockPos(x, y, z)
            val observed = sequenceOf(surface, surface.below(), surface.above())
                .mapNotNull { pos -> ambientBlockLabel(level.getBlockState(pos))?.let { label -> pos to label } }
                .firstOrNull()
                ?: return@repeat
            val target = pathableAmbientTarget(entity, observed.first) ?: return@repeat
            val label = observed.second
            return target to label
        }
        return null
    }

    private fun ambientBlockLabel(state: BlockState): String? = when {
        state.`is`(BlockTags.FLOWERS) -> "flowers"
        state.`is`(BlockTags.LEAVES) || state.`is`(BlockTags.LOGS) -> "trees"
        state.`is`(Blocks.WATER) -> "water"
        state.`is`(Blocks.CAMPFIRE) || state.`is`(Blocks.SOUL_CAMPFIRE) -> "campfire"
        state.`is`(Blocks.CHEST) || state.`is`(Blocks.BARREL) -> "storage"
        state.`is`(Blocks.CRAFTING_TABLE) -> "work table"
        state.`is`(Blocks.FURNACE) || state.`is`(Blocks.SMOKER) || state.`is`(Blocks.BLAST_FURNACE) -> "work station"
        state.`is`(BlockTags.BEDS) -> "bed"
        else -> null
    }

    private fun pathableAmbientTarget(entity: ChowNpcEntity, blockPos: BlockPos): BlockPos? {
        val candidates = sequenceOf(
            blockPos.above(),
            blockPos.north(),
            blockPos.south(),
            blockPos.east(),
            blockPos.west(),
            blockPos.north().above(),
            blockPos.south().above(),
            blockPos.east().above(),
            blockPos.west().above(),
        )
        return candidates.firstOrNull { pos -> entity.navigation.createPath(pos, 0) != null }
    }

    private fun randomWorkplaceTarget(entity: ChowNpcEntity, center: BlockPos): BlockPos? {
        val radius = townActivityRadius()
        return randomRoamTargetNear(entity, center, radius, minimumTownTravelDistance(radius))
            ?: randomRoamTargetNear(entity, center, radius)
    }

    private data class NpcLookHit(val npc: ChowNpcEntity, val along: Double, val distanceSqr: Double)

    private data class NpcOutgoingGiftApproach(val playerId: UUID, val startedAtTick: Long)

    private data class PokemonRoamTarget(val pos: BlockPos, val kind: PokemonRoamTargetKind)

    private enum class PokemonRoamTargetKind(val id: String, val topic: String, val label: String) {
        POKEMON("pokemon_watch", "pokemon", "nearby Pokemon"),
        TRAINER("trainer_meetup", "trainer", "nearby trainer"),
        OBJECT("observe_block", "observed_object", "nearby objects"),
        ROAM("roam", "roam", "nearby paths"),
    }

    private data class NpcWorkBlockStatus(val counts: List<NpcWorkBlockCount>) {
        val ready: Boolean = counts.all { count -> count.present >= count.requirement.count }
    }

    private data class NpcWorkBlockCount(val requirement: NpcWorkBlockRequirementDefinition, val present: Int) {
        val missing: Int = (requirement.count - present).coerceAtLeast(0)
    }

    private data class ActiveNpcMicroInteraction(
        val partnerId: UUID,
        val partnerNpcId: String,
        val partnerName: String,
        val message: String,
        val partnerMessage: String,
        val emote: String,
        val topic: String,
        val untilTick: Long,
        val shownToPlayers: MutableSet<UUID> = linkedSetOf(),
        var emotePlayed: Boolean = false,
    )

    private data class NpcDailyMicroInteractionCount(val day: Long, val count: Int)

    private data class NpcMicroInteractionWitnessNudge(val day: Long, val readyAtTick: Long)

    private data class NpcMicroInteractionDialogContext(
        val partnerEntityId: UUID?,
        val partnerNpcId: String,
        val partnerName: String,
        val topic: String,
        val ownMessage: String,
        val partnerMessage: String,
        val expiresAtTick: Long,
    )

    private data class ActiveNpcAmbientAction(
        val activity: String,
        val goal: String,
        val topic: String,
        val targetLabel: String,
        val targetPos: BlockPos?,
        val lookPos: Vec3?,
        var untilTick: Long,
        val line: String = "",
        val soloMomentId: String = "",
        val emote: String = "",
        val emoteSurface: String = NpcEmoteSurfaces.AMBIENT,
        var arrivedAtTick: Long = 0L,
        var nextRepathTick: Long = 0L,
        var emotePlayed: Boolean = false,
        val shownToPlayers: MutableSet<UUID> = linkedSetOf(),
    )

    private data class NpcAmbientMemory(
        val activity: String,
        val goal: String,
        val targetLabel: String,
        val topic: String,
        val line: String,
        val expiresAtTick: Long,
    )

    private const val DEBUG_REACH = 12.0
    private const val DEBUG_AIM_RADIUS = 1.5
    private const val REALTIME_DEBUG_INTERVAL_TICKS = 10
    private const val SLEEP_REACH_DISTANCE_SQR = 4.0
    private const val SLEEP_BED_Y_OFFSET = 0.75
    private const val NPC_DIALOG_HEAR_RADIUS = 30.0
    private const val NPC_BALLOON_CLOSE_RADIUS = 8.0
    private const val NPC_BALLOON_CLOSE_RADIUS_SQR = NPC_BALLOON_CLOSE_RADIUS * NPC_BALLOON_CLOSE_RADIUS
    private const val NPC_DIALOG_ACTION_DISTANCE_SQR = 64.0
    private const val HURT_MESSAGE_INTERVAL = 3
    private const val NPC_CAMP_SPAWN_RADIUS = 2
    private const val CONTRACT_FOLLOW_SCAN_RADIUS_SQR = 32.0 * 32.0
    private const val CONTRACT_FOLLOW_STOP_DISTANCE_SQR = 2.5 * 2.5
    private const val NPC_QUEST_CLAIM_SCAN_RADIUS = 32.0
    private const val NPC_QUEST_CLAIM_DISTANCE_SQR = 2.5 * 2.5
    private const val NPC_QUEST_CLAIM_FOLLOW_SPEED = 0.95
    private const val NPC_QUEST_CLAIM_BALLOON_COOLDOWN_TICKS = 100L
    private const val NPC_GIFT_DELIVERY_DISTANCE_SQR = 2.5 * 2.5
    private const val NPC_GIFT_FOLLOW_SPEED = 0.95
    private const val NPC_MICRO_INTERACTION_DISTANCE_SQR = 2.5 * 2.5
    private const val NPC_MICRO_INTERACTION_SPEED = 0.75
    private const val NPC_MICRO_INTERACTION_BALLOON_TICKS = 100
private const val NPC_MICRO_INTERACTION_AREA_CELL_SIZE = 24
private const val NPC_MICRO_INTERACTION_RECENT_MEMORY = 48
private const val NPC_MICRO_INTERACTION_INTERRUPT_MEMORY_TICKS = 20L * 30L
private const val NPC_MICRO_INTERACTION_SCAN_MIN_TICKS = 20L
private const val NPC_MICRO_INTERACTION_SCAN_MAX_TICKS = 60L
private const val NPC_AUTO_TASK_COOLDOWN_MIN_TICKS = 200L
    private const val NPC_AUTO_TASK_COOLDOWN_MAX_TICKS = 300L
    private const val NPC_AMBIENT_MIN_TICKS = 80L
    private const val NPC_AMBIENT_MAX_TICKS = 220L
    private const val NPC_AMBIENT_TRAVEL_MIN_TICKS = 500L
    private const val NPC_AMBIENT_TRAVEL_MAX_TICKS = 900L
    private const val NPC_AMBIENT_COOLDOWN_MIN_TICKS = 60L
    private const val NPC_AMBIENT_COOLDOWN_MAX_TICKS = 140L
    private const val NPC_AMBIENT_MEMORY_TICKS = 20L * 45L
    private const val NPC_AMBIENT_REPATH_MIN_TICKS = 20L
    private const val NPC_AMBIENT_REPATH_MAX_TICKS = 45L
    private const val NPC_AMBIENT_ARRIVAL_DWELL_MIN_TICKS = 25L
    private const val NPC_AMBIENT_ARRIVAL_DWELL_MAX_TICKS = 70L
    private const val NPC_AMBIENT_REACH_DISTANCE_SQR = 2.25 * 2.25
    private const val NPC_AMBIENT_SPEED = 0.75
    private const val NPC_AMBIENT_BALLOON_TICKS = 100
    private const val NPC_AMBIENT_SOLO_MOMENT_CHANCE = 28
    private const val NPC_AMBIENT_EMOTE_ONLY_CHANCE = 22
    private const val NPC_AMBIENT_MOVE_EMOTE_CHANCE = 32
    private const val NPC_AMBIENT_POSTURE_EMOTE_CHANCE = 45
    private const val NPC_AMBIENT_OBJECT_SCAN_RADIUS = 9
    private const val NPC_HOME_AMBIENT_RADIUS = 4
    private const val NPC_SOLO_MOMENT_RECENT_MEMORY = 12
    private const val NPC_DEFAULT_MEETUP_START_HOUR = 15
    private const val NPC_DEFAULT_MEETUP_END_HOUR = 20
    private const val NPC_PLAZA_CAMP_FALLBACK_RADIUS = 10
    private const val NPC_TOWN_ACTIVITY_FALLBACK_RADIUS = 32
    private const val TRAINER_POKEMON_SCAN_RADIUS = 10.0
    private const val TRAINER_MEETUP_SCAN_RADIUS = 12.0
    private val TRAINER_POKEMON_BALLOONS = listOf(
        "Hold still, {pokemon}. I am checking your stance.",
        "{pokemon}, good footwork.",
        "Easy now. I am just studying your form.",
        "That is a clean battle posture.",
        "Strong eyes. You have trained before.",
        "Interesting movement. I should remember that.",
        "No rush, little battler. Show me how you move.",
        "That one has tournament nerves.",
    )
    private const val CONTRACT_BED_ASSIGN_RADIUS_SQR = 7.0 * 7.0
    private const val WORKPLACE_ASSIGN_RADIUS_SQR = 8.0 * 8.0
    private const val NPC_CAMPER_HOUSING_BALLOON_TICKS = 120
    private const val NPC_CAMPER_HOUSING_BALLOON_REFRESH_TICKS = 80L
    private const val NPC_FRIENDSHIP_DELTA_BALLOON_TICKS = 50
    private const val FRIENDSHIP_HIT_DELTA = -10
    private const val FRIENDSHIP_KILL_DELTA = -300
    private const val FIRST_DAILY_CHAT_FRIENDSHIP_DELTA = 25
    private const val NOTABLE_KILL_HEALTH_THRESHOLD = 100.0f
    private const val RESPAWN_SCAN_INTERVAL_TICKS = 20
    private const val NPC_RESPAWN_HOUR = 5
}

class NpcAmbientFocus(
    val activity: String,
    val goal: String,
    val targetLabel: String,
    val topic: String,
    val line: String,
)

class NpcRecentMicroInteractionFocus(
    val partnerName: String,
    val topic: String,
    val ownMessage: String,
    val partnerMessage: String,
)
