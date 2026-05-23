package dev.gisketch.chowkingdom.tech

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.gisketch.chowkingdom.battlepass.BattlepassNetwork
import dev.gisketch.chowkingdom.npc.NpcConfig
import dev.gisketch.chowkingdom.npc.NpcDefinition
import dev.gisketch.chowkingdom.npc.NpcFeature
import dev.gisketch.chowkingdom.npc.NpcStore
import dev.gisketch.chowkingdom.shipping.ShippingBinStore
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarPayload
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.neoforged.bus.api.IEventBus
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.Locale

object TechLicenseFeature {
    const val TECH_LICENSE_UNLOCKED_EVENT = "gisketchs_chowkingdom_mod:tech_license_unlocked"
    private const val DENIAL_COOLDOWN_TICKS = 40L
    private const val CLIENT_DENIAL_COOLDOWN_MS = 1_500L
    private const val DIALOG_ACTION_COOLDOWN_TICKS = 10L
    private val recentDenials: MutableMap<String, Long> = linkedMapOf()
    private val recentClientDenials: MutableMap<String, Long> = linkedMapOf()
    private val recentDialogActions: MutableMap<String, Long> = linkedMapOf()

    fun register(modBus: IEventBus) {
        TechLicenseNetwork.register(modBus)
        TechLicenseConfig.load()
        TechLicenseStore.load()
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onRightClickItem)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onRightClickBlock)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onUseItemStart)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onBlockPlace)
        NeoForge.EVENT_BUS.addListener(::onEquipmentChange)
        NeoForge.EVENT_BUS.addListener(::onItemTooltip)
    }

    fun currentShippingTotal(): Long = ShippingBinStore.totalChowcoinsSold()

    fun thresholdReached(license: TechLicenseDefinition): Boolean =
        ShippingBinStore.totalChowcoinsSold() >= license.thresholdChowcoins || TechLicenseStore.pending(license.id) || TechLicenseStore.spawned(license.id)

    fun checkShippingUnlocks(server: MinecraftServer) {
        if (!TechLicenseConfig.enabled()) return
        val total = ShippingBinStore.totalChowcoinsSold()
        val reached = TechLicenseConfig.all().filter { license -> total >= license.thresholdChowcoins }
        TechLicenseStore.retainPending(reached.map { it.id }.toSet())
        reached
            .filter { license -> !TechLicenseStore.spawned(license.id) }
            .forEach { license -> TechLicenseStore.markPending(license.id) }
        markAlreadyPresentExpertsSpawned(server)
        spawnPending(server)
    }

    fun dialogOption(player: ServerPlayer, definition: NpcDefinition): TechLicenseDialogOption? {
        if (!TechLicenseConfig.enabled()) return null
        val license = TechLicenseConfig.forNpc(definition.id) ?: return null
        if (!thresholdReached(license) || TechLicenseStore.has(player, license.id)) return null
        return TechLicenseDialogOption(
            licenseId = license.id,
            label = license.buttonLabel,
            iconItem = license.iconItem,
            cost = license.feeChowcoins,
        )
    }

    fun handleDialogAction(player: ServerPlayer, npcId: String, action: String): Boolean {
        val normalized = action.lowercase(Locale.ROOT)
        if (!normalized.startsWith("tech_license:")) return false
        val definition = NpcConfig.get(npcId) ?: return true
        val npc = NpcFeature.existingConfiguredNpc(player.server, definition.id) ?: return true
        if (npc.level() != player.level() || player.distanceToSqr(npc) > 9.0 * 9.0) return true
        val now = player.level().gameTime
        val key = "${player.uuid}:$npcId:$normalized"
        val previous = recentDialogActions[key]
        if (previous != null && now - previous < DIALOG_ACTION_COOLDOWN_TICKS) return true
        recentDialogActions[key] = now
        recentDialogActions.entries.removeIf { (_, tick) -> now - tick > 20L * 10L }
        TechLicenseQuestService.handle(player, npc, definition, normalized.substringAfter(':'))
        return true
    }

    fun canSelectMission(player: ServerPlayer, missionRequiredLicense: String): Boolean {
        val licenseId = TechLicenseConfig.normalizeId(missionRequiredLicense)
        return licenseId.isBlank() || TechLicenseStore.has(player, licenseId)
    }

    fun shopLock(player: ServerPlayer, definition: NpcDefinition): TechLicenseDefinition? {
        if (!TechLicenseConfig.enabled()) return null
        val license = TechLicenseConfig.forNpc(definition.id) ?: return null
        return license.takeUnless { TechLicenseStore.has(player, it.id) }
    }

    fun shopLock(player: ServerPlayer, storeId: String): TechLicenseDefinition? {
        if (!TechLicenseConfig.enabled()) return null
        val normalizedStore = storeId.trim().lowercase(Locale.ROOT)
        val license = TechLicenseConfig.all().firstOrNull { license ->
            val definition = NpcConfig.get(license.npcId) ?: return@firstOrNull false
            definition.storeId().equals(normalizedStore, ignoreCase = true)
        } ?: return null
        return license.takeUnless { TechLicenseStore.has(player, it.id) }
    }

    fun shopLock(player: ServerPlayer, storeId: String, stockKey: String): TechLicenseDefinition? {
        if (!TechLicenseConfig.enabled()) return null
        val npcId = stockKey.trim().lowercase(Locale.ROOT).removePrefix("npc_").takeIf(String::isNotBlank) ?: return null
        val license = TechLicenseConfig.forNpc(npcId) ?: return null
        val definition = NpcConfig.get(npcId) ?: return null
        if (!definition.storeId().equals(storeId.trim(), ignoreCase = true)) return null
        return license.takeUnless { TechLicenseStore.has(player, it.id) }
    }

    fun isTechExpertNpc(npcId: String): Boolean {
        if (!TechLicenseConfig.enabled()) return false
        val normalized = npcId.trim().lowercase(Locale.ROOT)
        return TechLicenseConfig.all().any { license -> license.npcId == normalized }
    }

    fun nextPriorityCamper(server: MinecraftServer): TechLicensePriorityCamper? {
        if (!TechLicenseConfig.enabled()) return null
        val pending = TechLicenseStore.pendingLicenseIds()
        if (pending.isEmpty()) return null
        val total = ShippingBinStore.totalChowcoinsSold()
        return TechLicenseConfig.all()
            .asSequence()
            .filter { license -> license.id in pending && total >= license.thresholdChowcoins && !TechLicenseStore.spawned(license.id) }
            .sortedBy { license -> license.thresholdChowcoins }
            .mapNotNull { license ->
                val definition = NpcConfig.get(license.npcId) ?: return@mapNotNull null
                if (NpcStore.homePos(definition.id) != null) return@mapNotNull null
                if (NpcStore.activeCamperId().equals(definition.id, ignoreCase = true)) return@mapNotNull null
                if (NpcFeature.existingConfiguredNpc(server, definition.id) != null && !NpcStore.isDead(definition.id)) return@mapNotNull null
                TechLicensePriorityCamper(license.id, license.displayName, license.thresholdChowcoins, total, definition.id, definition.name)
            }
            .firstOrNull()
    }

    fun trySpawnPriorityCamper(server: MinecraftServer): Boolean {
        val level = server.overworld()
        val camp = NpcStore.campBlockPos() ?: return false
        if (NpcFeature.hasActiveUnhousedCamper(server)) return false
        val priority = nextPriorityCamper(server) ?: return false
        val definition = NpcConfig.get(priority.npcId) ?: return false
        if (!NpcFeature.spawnConfiguredNpcAsCamper(level, definition, camp, announceCamperArrival = true)) return false
        TechLicenseStore.markSpawned(priority.licenseId)
        SnackbarNetwork.sendToAllKnown(
            server,
            SnackbarNotification.npc(definition.id, "TECH EXPERT UNLOCKED", "${definition.name} can certify ${priority.licenseName}.", SnackbarType.SUCCESS, SnackbarSounds.REWARD),
        )
        return true
    }

    private fun markAlreadyPresentExpertsSpawned(server: MinecraftServer) {
        val total = ShippingBinStore.totalChowcoinsSold()
        TechLicenseConfig.all()
            .filter { license -> TechLicenseStore.pending(license.id) && total >= license.thresholdChowcoins && !TechLicenseStore.spawned(license.id) }
            .forEach { license ->
                val definition = NpcConfig.get(license.npcId) ?: return@forEach
                val activeCamper = NpcStore.activeCamperId().equals(definition.id, ignoreCase = true)
                val housed = NpcStore.homePos(definition.id) != null
                val live = NpcFeature.existingConfiguredNpc(server, definition.id) != null && !NpcStore.isDead(definition.id)
                if (activeCamper || housed || live) TechLicenseStore.markSpawned(license.id)
            }
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        TechLicenseConfig.load()
        TechLicenseStore.load()
        checkShippingUnlocks(event.server)
        TechLicenseNetwork.syncAllPlayers()
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        if (!TechLicenseConfig.enabled()) return
        val interval = TechLicenseConfig.settings().retryPendingSpawnIntervalTicks.coerceAtLeast(20)
        if (event.server.tickCount % interval != 0) return
        checkShippingUnlocks(event.server)
    }

    private fun spawnPending(server: MinecraftServer) {
        trySpawnPriorityCamper(server)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("ck")
                .then(
                    Commands.literal("techlicenses")
                        .requires { source -> source.hasPermission(2) }
                        .then(Commands.literal("reload").executes(::reload))
                        .then(Commands.literal("check").executes(::check))
                        .then(Commands.literal("audit").then(Commands.argument("license", StringArgumentType.word()).suggests(::suggestLicenses).executes(::audit)))
                        .then(Commands.literal("status").then(Commands.argument("player", EntityArgument.player()).executes(::status)))
                        .then(Commands.literal("grant").then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("license", StringArgumentType.word()).suggests(::suggestLicenses).executes(::grant))))
                        .then(Commands.literal("revoke").then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("license", StringArgumentType.word()).suggests(::suggestLicenses).executes(::revoke))))
                        .then(Commands.literal("resetspawn").then(Commands.argument("license", StringArgumentType.word()).suggests(::suggestLicenses).executes(::resetSpawn))),
                ),
        )
    }

    private fun reload(context: CommandContext<CommandSourceStack>): Int {
        TechLicenseConfig.load()
        TechLicenseStore.load()
        NpcConfig.load()
        TechLicenseNetwork.syncAllPlayers()
        context.source.sendSuccess({ Component.literal("Reloaded ${TechLicenseConfig.all().size} tech license(s).") }, true)
        return TechLicenseConfig.all().size.coerceAtLeast(1)
    }

    private fun check(context: CommandContext<CommandSourceStack>): Int {
        checkShippingUnlocks(context.source.server)
        context.source.sendSuccess({ Component.literal("Checked tech license unlocks at ${ShippingBinStore.totalChowcoinsSold()} shipped Chowcoins.") }, true)
        return 1
    }

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val player = EntityArgument.getPlayer(context, "player")
        val licenses = TechLicenseConfig.all().joinToString(", ") { license -> "${license.id}=${TechLicenseStore.has(player, license.id)}" }
        context.source.sendSuccess({ Component.literal("${player.gameProfile.name}: $licenses") }, false)
        return 1
    }

    private fun grant(context: CommandContext<CommandSourceStack>): Int {
        val player = EntityArgument.getPlayer(context, "player")
        val licenseId = StringArgumentType.getString(context, "license")
        val license = TechLicenseConfig.get(licenseId) ?: return unknownLicense(context, licenseId)
        val changed = TechLicenseStore.grant(player, license.id, "admin")
        if (changed) {
            SnackbarNetwork.send(player, SnackbarNotification.item(license.iconItem, "TECH LICENSE UNLOCKED", license.displayName, SnackbarType.SUCCESS, SnackbarSounds.REWARD))
            BattlepassNetwork.syncAllPlayers()
            TechLicenseNetwork.syncTo(player)
        }
        context.source.sendSuccess({ Component.literal("${if (changed) "Granted" else "Already had"} ${license.displayName} for ${player.gameProfile.name}.") }, true)
        return if (changed) 1 else 0
    }

    private fun revoke(context: CommandContext<CommandSourceStack>): Int {
        val player = EntityArgument.getPlayer(context, "player")
        val licenseId = StringArgumentType.getString(context, "license")
        val license = TechLicenseConfig.get(licenseId) ?: return unknownLicense(context, licenseId)
        val changed = TechLicenseStore.revoke(player, license.id)
        if (changed) TechLicenseNetwork.syncTo(player)
        context.source.sendSuccess({ Component.literal("${if (changed) "Revoked" else "No active"} ${license.displayName} for ${player.gameProfile.name}.") }, true)
        return if (changed) 1 else 0
    }

    private fun resetSpawn(context: CommandContext<CommandSourceStack>): Int {
        val licenseId = StringArgumentType.getString(context, "license")
        val license = TechLicenseConfig.get(licenseId) ?: return unknownLicense(context, licenseId)
        val changed = TechLicenseStore.resetThresholdState(license.id)
        context.source.sendSuccess({ Component.literal("${if (changed) "Reset" else "No state for"} ${license.displayName} spawn threshold.") }, true)
        return if (changed) 1 else 0
    }

    private fun audit(context: CommandContext<CommandSourceStack>): Int {
        val licenseId = StringArgumentType.getString(context, "license")
        val license = TechLicenseConfig.get(licenseId) ?: return unknownLicense(context, licenseId)
        val itemMatches = BuiltInRegistries.ITEM.keySet().mapNotNull { id ->
            if (license.gateNamespaces.any { namespace -> id.namespace == namespace }) id.toString() else null
        }
        val blockMatches = BuiltInRegistries.BLOCK.keySet().mapNotNull { id ->
            if (license.gateNamespaces.any { namespace -> id.namespace == namespace }) id.toString() else null
        }
        val warningTerms = listOf("drygmy", "flight", "ritual", "source", "wixie", "starbuncle", "bookwyrm", "jar", "spell", "break", "place", "exchange", "interact")
        val warnings = (itemMatches + blockMatches).filter { id -> warningTerms.any { term -> id.contains(term, ignoreCase = true) } }.take(40)
        context.source.sendSuccess(
            {
                Component.literal("${license.displayName}: ${itemMatches.size} item(s), ${blockMatches.size} block(s). Audit hits: ${warnings.joinToString(", ").ifBlank { "none" }}")
            },
            false,
        )
        return (itemMatches.size + blockMatches.size).coerceAtLeast(1)
    }

    private fun unknownLicense(context: CommandContext<CommandSourceStack>, licenseId: String): Int {
        context.source.sendFailure(Component.literal("Unknown tech license '$licenseId'. Known: ${TechLicenseConfig.all().joinToString(", ") { it.id }}"))
        return 0
    }

    private fun suggestLicenses(context: CommandContext<CommandSourceStack>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder) =
        SharedSuggestionProvider.suggest(TechLicenseConfig.all().map { it.id }, builder)

    private fun onRightClickItem(event: PlayerInteractEvent.RightClickItem) {
        if (event.entity !is ServerPlayer) {
            val lockInfo = TechLicenseClientState.lockInfo(event.itemStack, allowConfiguredExemptions = !isEquippable(event.itemStack)) ?: return
            event.isCanceled = true
            event.cancellationResult = InteractionResult.FAIL
            denyClient(lockInfo)
            return
        }
        val player = event.entity as? ServerPlayer ?: return
        val denial = denialForItem(player, event.itemStack, allowConfiguredExemptions = !isEquippable(event.itemStack)) ?: return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.FAIL
        deny(player, denial)
    }

    private fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (event.entity !is ServerPlayer) {
            val lockInfo = TechLicenseClientState.lockInfo(event.itemStack, allowConfiguredExemptions = !isEquippable(event.itemStack)) ?: return
            event.isCanceled = true
            event.cancellationResult = InteractionResult.FAIL
            denyClient(lockInfo)
            return
        }
        val player = event.entity as? ServerPlayer ?: return
        val blockDenial = denialForBlock(player, player.level().getBlockState(event.pos).block)
        val itemDenial = denialForItem(player, event.itemStack, allowConfiguredExemptions = !isEquippable(event.itemStack))
        val denial = blockDenial ?: itemDenial ?: return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.FAIL
        deny(player, denial)
    }

    private fun onUseItemStart(event: LivingEntityUseItemEvent.Start) {
        val player = event.entity as? ServerPlayer ?: return
        val denial = denialForItem(player, event.item, allowConfiguredExemptions = true) ?: return
        event.isCanceled = true
        event.duration = 0
        deny(player, denial)
    }

    private fun onBlockPlace(event: BlockEvent.EntityPlaceEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val denial = denialForBlock(player, event.placedBlock.block) ?: return
        event.isCanceled = true
        deny(player, denial)
    }

    private fun onEquipmentChange(event: LivingEquipmentChangeEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (event.slot !in setOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) return
        val denial = denialForItem(player, event.to, allowConfiguredExemptions = false) ?: return
        player.setItemSlot(event.slot, ItemStack.EMPTY)
        giveBack(player, event.to)
        deny(player, denial)
    }

    private fun onItemTooltip(event: ItemTooltipEvent) {
        val stack = event.itemStack
        if (stack.isEmpty) return
        if (event.entity !is ServerPlayer) {
            val lockInfo = TechLicenseClientState.lockInfo(stack) ?: return
            event.toolTip.add(1.coerceAtMost(event.toolTip.size), Component.literal(lockInfo.message).withStyle(ChatFormatting.DARK_RED))
            return
        }
        val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        val license = licenseForItemId(id) ?: return
        val serverPlayer = event.entity as? ServerPlayer
        if (serverPlayer != null && TechLicenseStore.has(serverPlayer, license.id)) return
        event.toolTip.add(1.coerceAtMost(event.toolTip.size), Component.literal("Must unlock ${license.displayName}.").withStyle(ChatFormatting.DARK_RED))
    }

    private fun denialForItem(player: ServerPlayer, stack: ItemStack, allowConfiguredExemptions: Boolean): TechGateDenial? {
        if (stack.isEmpty) return null
        val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        val license = licenseForItemId(id) ?: return null
        if (matchesAny(id, license.alwaysBannedItems)) return TechGateDenial(license, "Item is disabled by server config.")
        if (allowConfiguredExemptions && matchesAny(id, license.allowedWithoutLicense)) return null
        if (TechLicenseStore.has(player, license.id)) return null
        return TechGateDenial(license, "${license.displayName} required.")
    }

    private fun denialForBlock(player: ServerPlayer, block: Block): TechGateDenial? {
        val id = BuiltInRegistries.BLOCK.getKey(block).toString()
        val license = licenseForBlockId(id) ?: return null
        if (matchesAny(id, license.alwaysBannedBlocks)) return TechGateDenial(license, "Block is disabled by server config.")
        if (matchesAny(id, license.allowedBlocksWithoutLicense)) return null
        if (TechLicenseStore.has(player, license.id)) return null
        return TechGateDenial(license, "${license.displayName} required.")
    }

    private fun licenseForItemId(id: String): TechLicenseDefinition? =
        TechLicenseConfig.all().firstOrNull { license -> license.gateNamespaces.any { namespace -> id.substringBefore(':') == namespace } }

    private fun licenseForBlockId(id: String): TechLicenseDefinition? =
        TechLicenseConfig.all().firstOrNull { license -> license.gateNamespaces.any { namespace -> id.substringBefore(':') == namespace } }

    private fun matchesAny(id: String, patterns: List<String>): Boolean = patterns.any { pattern -> matchesPattern(id, pattern) }

    private fun matchesPattern(id: String, pattern: String): Boolean {
        val clean = pattern.trim().lowercase(Locale.ROOT)
        if (clean.isBlank()) return false
        if (clean.endsWith(":*")) return id.substringBefore(':') == clean.substringBefore(':')
        if ('*' !in clean) return id == clean
        val regex = clean.split('*').joinToString(".*") { part -> Regex.escape(part) }.toRegex()
        return regex.matches(id)
    }

    private fun deny(player: ServerPlayer, denial: TechGateDenial) {
        val now = player.level().gameTime
        val key = "${player.uuid}:${denial.license.id}"
        val previous = recentDenials[key]
        if (previous != null && now - previous < DENIAL_COOLDOWN_TICKS) return
        recentDenials[key] = now
        recentDenials.entries.removeIf { (_, tick) -> now - tick > DENIAL_COOLDOWN_TICKS * 6 }
        SnackbarNetwork.send(player, SnackbarNotification.item(denial.license.iconItem, denial.license.displayName.uppercase(Locale.ROOT), denial.message, SnackbarType.ERROR, SnackbarSounds.ERROR))
    }

    private fun denyClient(lockInfo: TechLicenseItemLockInfo) {
        val now = System.currentTimeMillis()
        val previous = recentClientDenials[lockInfo.licenseId]
        if (previous != null && now - previous < CLIENT_DENIAL_COOLDOWN_MS) return
        recentClientDenials[lockInfo.licenseId] = now
        recentClientDenials.entries.removeIf { (_, timestamp) -> now - timestamp > CLIENT_DENIAL_COOLDOWN_MS * 4 }
        val payload = SnackbarPayload(
            iconKind = "item",
            icon = lockInfo.iconItem,
            title = lockInfo.displayName.uppercase(Locale.ROOT),
            content = lockInfo.message,
            type = SnackbarType.ERROR.id,
            sound = SnackbarSounds.ERROR,
            durationMs = 2_400L,
        )
        runCatching {
            val client = Class.forName("dev.gisketch.chowkingdom.snackbar.SnackbarClient")
            client.getMethod("show", SnackbarPayload::class.java).invoke(client.getField("INSTANCE").get(null), payload)
        }
    }

    private fun giveBack(player: ServerPlayer, stack: ItemStack) {
        val copy = stack.copy()
        if (copy.isEmpty) return
        if (!player.inventory.add(copy)) player.drop(copy, false)
    }

    private fun isEquippable(stack: ItemStack): Boolean =
        stack.item is ArmorItem
}

data class TechLicenseDialogOption(
    val licenseId: String,
    val label: String,
    val iconItem: String,
    val cost: Long,
)

data class TechLicensePriorityCamper(
    val licenseId: String,
    val licenseName: String,
    val thresholdChowcoins: Long,
    val currentChowcoins: Long,
    val npcId: String,
    val npcName: String,
)

private data class TechGateDenial(
    val license: TechLicenseDefinition,
    val message: String,
)
