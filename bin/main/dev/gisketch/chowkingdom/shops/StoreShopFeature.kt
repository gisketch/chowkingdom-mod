package dev.gisketch.chowkingdom.shops

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.battlepass.BattlepassMissionEventBank
import dev.gisketch.chowkingdom.battlepass.BattlepassNetwork
import dev.gisketch.chowkingdom.config.TomlConfigIO
import dev.gisketch.chowkingdom.npc.NpcFeature
import dev.gisketch.chowkingdom.relicroulette.RelicRouletteFeature
import dev.gisketch.chowkingdom.snackbar.SnackbarIcons
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import dev.gisketch.chowkingdom.tech.TechLicenseFeature
import dev.gisketch.chowkingdom.wallets.ChowcoinNetwork
import dev.gisketch.chowkingdom.wallets.ChowcoinStore
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.random.Random

object StoreShopFeature {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var definitions: Map<String, StoreDefinition> = emptyMap()
    private var state = StoreStateFile()
    private var statePath: Path? = null
    private val warnedInvalidOfferItems = mutableSetOf<String>()
    private val warnedMissingOfferItems = mutableSetOf<String>()

    private val configDir: Path
        get() = FMLPaths.CONFIGDIR.get().resolve(ChowKingdomMod.MOD_ID).resolve("stores")

    fun register(modBus: IEventBus) {
        reloadDefinitions()
        ExplorerMapService.register()
        ExplorerCompassService.register()
        modBus.addListener(::registerPayloads)
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(::onServerStarted)
    }

    fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(shopRoot())
        dispatcher.register(Commands.literal("chowkingdom").then(shopRoot()))
        dispatcher.register(Commands.literal("ck").then(shopRoot()))
    }

    fun storeIds(): Collection<String> = definitions.keys.sorted()

    private fun shopRoot() = Commands.literal("shop")
        .then(
            Commands.argument("store", StringArgumentType.word())
                .suggests { _, builder -> SharedSuggestionProvider.suggest(storeIds(), builder) }
                .executes(::openStoreCommand)
                .then(
                    Commands.literal("reload")
                        .requires { source -> source.hasPermission(2) }
                        .then(Commands.literal("daily").executes { context -> reloadStoreCommand(context, ShopViewPool.DAILY) })
                        .then(Commands.literal("weekly").executes { context -> reloadStoreCommand(context, ShopViewPool.WEEKLY) })
                        .then(Commands.literal("all").executes { context -> reloadStoreCommand(context, ShopViewPool.ALL) }),
                ),
        )

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToClient(StoreShopOpenPayload.TYPE, StoreShopOpenPayload.STREAM_CODEC, ::handleOpenClient)
        registrar.playToServer(StoreShopCartBuyPayload.TYPE, StoreShopCartBuyPayload.STREAM_CODEC, ::handleCartBuy)
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        reloadDefinitions()
        loadState(event.server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("${ChowKingdomMod.MOD_ID}_stores.json"))
    }

    private fun openStoreCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val storeId = StringArgumentType.getString(context, "store").lowercase(Locale.ROOT)
        if (!openStore(player, storeId)) {
            context.source.sendFailure(Component.literal("Unknown store '$storeId'."))
            return 0
        }
        return 1
    }

    private fun reloadStoreCommand(context: CommandContext<CommandSourceStack>, pool: ShopViewPool): Int {
        val storeId = StringArgumentType.getString(context, "store").lowercase(Locale.ROOT)
        reloadDefinitions()
        val definition = definitions[storeId]
        if (definition == null) {
            context.source.sendFailure(Component.literal("Unknown store '$storeId'."))
            return 0
        }
        val commandStockKey = stockKey(storeId, definition.id)
        val player = runCatching { context.source.playerOrException }.getOrNull()
        val stockKeys = if (definition.hasPlayerScopedOffers()) {
            context.source.server.playerList.players.map { online -> scopedStockKey(online, definition, commandStockKey) }.ifEmpty {
                player?.let { listOf(scopedStockKey(it, definition, commandStockKey)) }.orEmpty()
            }
        } else listOf(commandStockKey)
        val refreshedPools = if (pool == ShopViewPool.ALL) ShopViewPool.entries.toSet() else setOf(pool)
        stockKeys.forEach { key ->
            refreshedPools.forEach { refreshed -> reroll(definition, key, refreshed, player?.dimensionId(), player) }
        }
        if (definition.hasPlayerScopedOffers()) {
            context.source.server.playerList.players.forEach { online ->
                prepareExplorerOffers(online, definition, scopedStockKey(online, definition, commandStockKey), refreshedPools)
            }
        }
        saveState()
        context.source.sendSuccess({ Component.literal("Reloaded ${pool.label.lowercase(Locale.ROOT)} stock for ${definition.displayName}.") }, true)
        player?.let { openStore(it, storeId) }
        return 1
    }

    private fun handleOpenClient(payload: StoreShopOpenPayload, context: IPayloadContext) {
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient) return
        context.enqueueWork {
            runCatching {
                val client = Class.forName("dev.gisketch.chowkingdom.shops.StoreShopClient")
                client.getMethod("open", StoreShopOpenPayload::class.java).invoke(client.getField("INSTANCE").get(null), payload)
            }
        }
    }

    private fun handleCartBuy(payload: StoreShopCartBuyPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        buy(player, payload.storeId.lowercase(Locale.ROOT), stockKey(payload.stockKey, payload.storeId), payload.lines)
    }

    fun openStore(player: ServerPlayer, storeId: String): Boolean {
        return openStore(player, storeId, storeId)
    }

    fun openStore(player: ServerPlayer, storeId: String, stockKey: String, subtitle: String = "Server shared stock"): Boolean {
        reloadDefinitions()
        val definition = definitions[storeId.lowercase(Locale.ROOT)] ?: return false
        val normalizedStockKey = runtimeStockKey(player, definition, stockKey(stockKey, definition.id))
        val lockedLicense = TechLicenseFeature.shopLock(player, definition.id, normalizedStockKey)
            ?: TechLicenseFeature.shopLock(player, definition.id)
        if (lockedLicense != null) {
            SnackbarNetwork.send(player, SnackbarNotification.item(lockedLicense.iconItem, "TECH SHOP LOCKED", "${lockedLicense.displayName} required.", SnackbarType.ERROR, SnackbarSounds.ERROR))
            return false
        }
        ensureActive(definition, normalizedStockKey, player.dimensionId(), player)
        PacketDistributor.sendToPlayer(player, StoreShopOpenPayload(view(player, definition, normalizedStockKey, subtitle)))
        return true
    }

    fun llmSummary(storeId: String, maxEntries: Int = 8): String {
        return llmSummary(storeId, storeId, maxEntries)
    }

    fun llmSummary(storeId: String, stockKey: String, maxEntries: Int = 8): String {
        reloadDefinitions()
        val definition = definitions[storeId.lowercase(Locale.ROOT)] ?: return "No configured store."
        val normalizedStockKey = stockKey(stockKey, definition.id)
        val activeOffers = activeOffers(definition, normalizedStockKey)
        if (statePath != null) saveState()
        val stockedOffers = activeOffers.filter { active -> stock(normalizedStockKey, active.pool, active.offer.id) > 0 }
        val visibleOffers = stockedOffers.ifEmpty { activeOffers }.take(maxEntries.coerceIn(1, 16))
        val items = visibleOffers.joinToString("; ") { active ->
            val count = stock(normalizedStockKey, active.pool, active.offer.id)
            val stockText = if (count > 0) count.toString() else "out"
            "${active.pool.label} ${active.category.id}: ${active.offer.item} price=${active.offer.priceAmount} stock=$stockText"
        }.ifBlank { "No configured offers right now." }
        val stockNote = if (stockedOffers.isEmpty() && activeOffers.isNotEmpty()) "All listed offers are currently out of stock. " else ""
        return "${definition.displayName} (${definition.id}); stock_key=$normalizedStockKey; resets at ${definition.resetHour.toString().padStart(2, '0')}:${definition.resetMinute.toString().padStart(2, '0')} ${definition.timeZone}; ${stockNote}items: $items"
    }

    private fun buy(player: ServerPlayer, storeId: String, stockKey: String, lines: List<ShopViewCartLine>) {
        val definition = definitions[storeId] ?: return
        val lockedLicense = TechLicenseFeature.shopLock(player, storeId, stockKey)
            ?: TechLicenseFeature.shopLock(player, storeId)
        if (lockedLicense != null) {
            SnackbarNetwork.send(player, SnackbarNotification.item(lockedLicense.iconItem, "TECH SHOP LOCKED", "${lockedLicense.displayName} required.", SnackbarType.ERROR, SnackbarSounds.ERROR))
            return
        }
        ensureActive(definition, stockKey, player.dimensionId(), player)
        val activeOffers = activeOffers(definition, stockKey, player.dimensionId(), player).associateBy { it.offer.id }
        val requested = lines.filter { it.quantity > 0 }.take(100)
        if (requested.isEmpty()) return
        val purchases = requested.mapNotNull { line ->
            val active = activeOffers[line.entryId] ?: return@mapNotNull null
            if (ExplorerMapService.isExplorerMapOffer(active.offer)) {
                val quantity = 1.coerceAtMost(stock(stockKey, active.pool, active.offer.id))
                if (quantity <= 0) return@mapNotNull null
                val stack = ExplorerMapService.purchaseStack(player, stockKey, active.pool, active.offer, periodKey(definition, active.pool))
                    ?: return@mapNotNull null
                val total = active.offer.priceAmount.coerceAtLeast(0L)
                PendingStoreBuy(active, quantity, total, stack)
            } else if (ExplorerCompassService.isExplorerCompassOffer(active.offer)) {
                val quantity = 1.coerceAtMost(stock(stockKey, active.pool, active.offer.id))
                if (quantity <= 0) return@mapNotNull null
                val stack = ExplorerCompassService.purchaseStack(player, storeId, stockKey, active.pool, active.offer) ?: return@mapNotNull null
                val total = active.offer.priceAmount.coerceAtLeast(0L)
                PendingStoreBuy(active, quantity, total, stack)
            } else {
                val quantity = line.quantity.coerceIn(1, stock(stockKey, active.pool, active.offer.id))
                if (quantity <= 0) return@mapNotNull null
                val stack = stackFor(active.offer, quantity)
                if (stack.isEmpty) return@mapNotNull null
                val total = active.offer.priceAmount.coerceAtLeast(0L).saturatingMultiply(quantity.toLong())
                PendingStoreBuy(active, quantity, total, stack)
            }
        }
        if (purchases.isEmpty()) {
            player.displayClientMessage(Component.literal("No stock available."), true)
            openStore(player, storeId, stockKey)
            return
        }
        val totalCost = purchases.fold(0L) { sum, buy -> sum.saturatingAdd(buy.total) }
        val balance = ChowcoinStore.get(player)
        if (balance < totalCost) {
            player.displayClientMessage(Component.literal("Not enough chowcoins."), true)
            ChowcoinNetwork.syncTo(player)
            return
        }
        val stacks = mutableListOf<ItemStack>()
        purchases.forEach { buy ->
            decrement(stockKey, buy.active.pool, buy.active.offer.id, buy.quantity)
            if (ExplorerMapService.isExplorerMapOffer(buy.active.offer)) {
                ExplorerMapService.recordPurchase(player, stockKey, buy.active.pool, buy.active.offer, periodKey(definition, buy.active.pool))
            } else if (ExplorerCompassService.isExplorerCompassOffer(buy.active.offer)) {
                ExplorerCompassService.recordPurchase(player, buy.active.offer)
            }
            stacks += buy.stack.copy()
        }
        ChowcoinStore.set(player, balance - totalCost)
        val boughtCount = stacks.sumOf { stack -> stack.count }
        stacks.forEach { stack -> if (!player.inventory.add(stack)) player.drop(stack, false) }
        ChowcoinNetwork.syncTo(player)
        saveState()
        recordBuyMission(player, totalCost)
        SnackbarNetwork.send(player, SnackbarNotification.texture(SnackbarIcons.CHOWCOIN_TEXTURE, "BUY SUCCESSFUL", "Bought $boughtCount items for $totalCost chowcoins", SnackbarType.GENERIC, SnackbarSounds.GENERIC))
        val itemName = if (purchases.size == 1) purchases.single().stack.hoverName.string else "items"
        NpcFeature.onStorePurchase(player, storeId, stockKey, boughtCount, itemName, totalCost)
    }

    private fun view(player: ServerPlayer, definition: StoreDefinition, stockKey: String, subtitle: String): ShopViewModel {
        val categories = definition.categories.map { ShopViewCategory(it.id, labelFromId(it.id)) }
        val entries = activeOffers(definition, stockKey, player.dimensionId(), player).mapNotNull { active ->
            if (ExplorerMapService.isExplorerMapOffer(active.offer)) {
                val preview = ExplorerMapService.preview(player, stockKey, active.pool, active.offer, periodKey(definition, active.pool))
                    ?: return@mapNotNull null
                ShopViewEntry(
                    active.offer.id,
                    active.category.id,
                    active.pool,
                    preview.stack,
                    stock(stockKey, active.pool, active.offer.id).coerceAtMost(preview.stockCount),
                    active.offer.priceAmount,
                    definition.displayName,
                )
            } else if (ExplorerCompassService.isExplorerCompassOffer(active.offer)) {
                val preview = ExplorerCompassService.preview(player, active.offer)
                    ?: return@mapNotNull null
                ShopViewEntry(
                    active.offer.id,
                    active.category.id,
                    active.pool,
                    preview.stack,
                    stock(stockKey, active.pool, active.offer.id).coerceAtMost(preview.stockCount),
                    active.offer.priceAmount,
                    definition.displayName,
                )
            } else {
                ShopViewEntry(
                    active.offer.id,
                    active.category.id,
                    active.pool,
                    stackFor(active.offer, 1),
                    stock(stockKey, active.pool, active.offer.id),
                    active.offer.priceAmount,
                    definition.displayName,
                )
            }
        }
        val pools = ShopViewPool.entries.map { pool -> ShopViewPoolInfo(pool, pool.label, resetText(definition, pool)) }
        return ShopViewModel(definition.id, stockKey, definition.displayName, subtitle, categories, pools, entries)
    }

    private fun activeOffers(definition: StoreDefinition, stockKey: String, dimensionId: String? = null, player: ServerPlayer? = null): List<ActiveStoreOffer> {
        ensureActive(definition, stockKey, dimensionId, player)
        return ShopViewPool.entries.flatMap { pool ->
            val poolState = stateFor(stockKey).pools.getOrPut(pool.id) { StorePoolState() }
            definition.categories.flatMap { category ->
                val allowed = when (pool) {
                    ShopViewPool.ALL -> category.allItems.map(StoreOffer::id).toSet()
                    else -> poolState.selected[category.id].orEmpty().toSet()
                }
                offersFor(category, pool, dimensionId, player).filter { it.id in allowed }.map { offer -> ActiveStoreOffer(category, pool, offer) }
            }
        }
    }

    private fun ensureActive(definition: StoreDefinition, stockKey: String, dimensionId: String? = null, player: ServerPlayer? = null): Set<ShopViewPool> {
        val refreshed = mutableSetOf<ShopViewPool>()
        ShopViewPool.entries.forEach { pool ->
            if (ensurePool(definition, stockKey, pool, dimensionId, player)) refreshed += pool
        }
        return refreshed
    }

    private fun ensurePool(definition: StoreDefinition, stockKey: String, pool: ShopViewPool, dimensionId: String? = null, player: ServerPlayer? = null): Boolean {
        val period = periodKey(definition, pool)
        val poolState = stateFor(stockKey).pools.getOrPut(pool.id) { StorePoolState() }
        if (poolState.period != period || missingSelectedCategories(definition, poolState, pool, dimensionId, player)) {
            reroll(definition, stockKey, pool, dimensionId, player)
            return true
        }
        return false
    }

    private fun prepareExplorerOffers(player: ServerPlayer, definition: StoreDefinition, stockKey: String, pools: Set<ShopViewPool>) {
        activeOffers(definition, stockKey, player.dimensionId(), player)
            .filter { active -> active.pool in pools && stock(stockKey, active.pool, active.offer.id) > 0 && ExplorerMapService.isExplorerMapOffer(active.offer) }
            .forEach { active -> ExplorerMapService.prepare(player, stockKey, active.pool, active.offer, periodKey(definition, active.pool)) }
    }

    private fun missingSelectedCategories(definition: StoreDefinition, poolState: StorePoolState, pool: ShopViewPool, dimensionId: String? = null, player: ServerPlayer? = null): Boolean {
        if (pool == ShopViewPool.ALL) return false
        return definition.categories.any { category ->
            val groups = rollGroupsFor(category, pool, dimensionId, player)
            val selected = poolState.selected[category.id]
            val validOfferIds = groups.flatMap(StoreOfferRollGroup::offers).map(StoreOffer::id).toSet()
            val expectedGroups = itemTypesToSell(category, pool).coerceAtMost(groups.size)
            groups.isNotEmpty() && (selected == null || selected.size != expectedGroups || selected.any { it !in validOfferIds })
        }
    }

    private fun itemTypesToSell(category: StoreCategoryDefinition, pool: ShopViewPool): Int {
        val override = when (pool) {
            ShopViewPool.ALL -> category.allItemTypesToSell
            ShopViewPool.DAILY -> category.dailyItemTypesToSell
            ShopViewPool.WEEKLY -> category.weeklyItemTypesToSell
        }
        return override?.coerceAtLeast(0) ?: category.itemTypesToSell.coerceAtLeast(1)
    }

    private fun reroll(definition: StoreDefinition, stockKey: String, pool: ShopViewPool, dimensionId: String? = null, player: ServerPlayer? = null) {
        val poolState = stateFor(stockKey).pools.getOrPut(pool.id) { StorePoolState() }
        poolState.period = periodKey(definition, pool)
        poolState.selected.clear()
        poolState.stock.clear()
        definition.categories.forEach { category ->
            val groups = rollGroupsFor(category, pool, dimensionId, player)
            val selected = (if (pool == ShopViewPool.ALL) groups else weightedSampleGroups(groups, itemTypesToSell(category, pool)))
                .flatMap(StoreOfferRollGroup::offers)
                .distinctBy(StoreOffer::id)
            poolState.selected[category.id] = selected.map(StoreOffer::id).toMutableList()
            selected.forEach { offer -> poolState.stock[offer.id] = offer.stockCount.coerceAtLeast(0) }
        }
    }

    private fun offersFor(category: StoreCategoryDefinition, pool: ShopViewPool, dimensionId: String? = null, player: ServerPlayer? = null): List<StoreOffer> = when (pool) {
        ShopViewPool.ALL -> category.allItems + category.allSets.flatMap(StoreOfferSet::items)
        ShopViewPool.DAILY -> category.dailyItems + category.dailySets.flatMap(StoreOfferSet::items)
        ShopViewPool.WEEKLY -> category.weeklyItems + category.weeklySets.flatMap(StoreOfferSet::items)
    }.filterValidOffers().flatMap { offer -> ExplorerCompassService.expandOffers(player, offer) }.filter { offer -> offer.isAvailableInDimension(dimensionId) }

    private fun rollGroupsFor(category: StoreCategoryDefinition, pool: ShopViewPool, dimensionId: String? = null, player: ServerPlayer? = null): List<StoreOfferRollGroup> {
        val singles = when (pool) {
            ShopViewPool.ALL -> category.allItems
            ShopViewPool.DAILY -> category.dailyItems
            ShopViewPool.WEEKLY -> category.weeklyItems
        }.filterValidOffers().flatMap { offer -> ExplorerCompassService.expandOffers(player, offer) }.filter { offer -> offer.isAvailableInDimension(dimensionId) }.map { offer -> StoreOfferRollGroup(offer.id, offer.weight, listOf(offer)) }
        val sets = when (pool) {
            ShopViewPool.ALL -> category.allSets
            ShopViewPool.DAILY -> category.dailySets
            ShopViewPool.WEEKLY -> category.weeklySets
        }.mapNotNull { set ->
            val offers = set.items.filterValidOffers().flatMap { offer -> ExplorerCompassService.expandOffers(player, offer) }.filter { offer -> offer.isAvailableInDimension(dimensionId) }.distinctBy(StoreOffer::id)
            if (set.id.isBlank() || offers.isEmpty()) null else StoreOfferRollGroup(set.id, set.weight, offers)
        }
        return singles + sets
    }

    private fun List<StoreOffer>.filterValidOffers(): List<StoreOffer> = filter(::isValidOffer)

    private fun isValidOffer(offer: StoreOffer): Boolean {
        if (ExplorerMapService.isExplorerMapOffer(offer)) return offer.id.isNotBlank() && offer.targets.isNotEmpty()
        if (ExplorerCompassService.isExplorerCompassOffer(offer)) {
            return offer.id.isNotBlank() &&
                (offer.targets.isNotEmpty() || offer.dimension.isNotBlank() || offer.dimensions.isNotEmpty()) &&
                offer.targetType.trim().lowercase(Locale.ROOT) in setOf("biome", "structure")
        }
        if (offer.id.isBlank() || offer.item.isBlank()) return false
        val id = runCatching { ResourceLocation.parse(offer.item) }
            .onFailure { if (warnedInvalidOfferItems.add(offer.item)) ChowKingdomMod.LOGGER.warn("Store offer {} has invalid item id {}; skipping offer.", offer.id, offer.item) }
            .getOrNull() ?: return false
        val item = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR)
        if (item == Items.AIR) {
            if (warnedMissingOfferItems.add(offer.item)) ChowKingdomMod.LOGGER.warn("Store offer item {} is not present in the current modlist; skipping matching offers.", offer.item)
            return false
        }
        return !RelicRouletteFeature.isTokenItem(item)
    }

    private fun stock(stockKey: String, pool: ShopViewPool, offerId: String): Int =
        stateFor(stockKey).pools[pool.id]?.stock?.get(offerId) ?: 0

    private fun decrement(stockKey: String, pool: ShopViewPool, offerId: String, quantity: Int) {
        val poolState = stateFor(stockKey).pools.getOrPut(pool.id) { StorePoolState() }
        poolState.stock[offerId] = (poolState.stock[offerId] ?: 0).minus(quantity).coerceAtLeast(0)
    }

    fun restoreStock(storeId: String, stockKey: String, poolId: String, offerId: String, quantity: Int) {
        if (quantity <= 0) return
        val poolState = stateFor(stockKey).pools.getOrPut(poolId) { StorePoolState() }
        poolState.stock[offerId] = (poolState.stock[offerId] ?: 0) + quantity
        saveState()
    }

    private fun stateFor(stockKey: String): StoreRuntimeState =
        state.stores.getOrPut(stockKey) { StoreRuntimeState() }

    private fun stockKey(value: String, fallback: String): String =
        value.trim().lowercase(Locale.ROOT).ifBlank { fallback.lowercase(Locale.ROOT) }.take(96)

    private fun scopedStockKey(player: ServerPlayer, definition: StoreDefinition, base: String): String {
        if (!definition.hasPlayerScopedOffers()) return base
        val suffix = player.uuid.toString().replace("-", "")
        if (base.endsWith("_$suffix")) return base
        return stockKey("${base.take(52)}_$suffix", definition.id)
    }

    private fun runtimeStockKey(player: ServerPlayer, definition: StoreDefinition, base: String): String {
        val dimensionScoped = if (definition.hasDimensionScopedOffers()) {
            stockKey("${base.take(44)}_${player.dimensionId().toStockKeyPart()}", definition.id)
        } else base
        return scopedStockKey(player, definition, dimensionScoped)
    }

    private fun weightedSample(offers: List<StoreOffer>, count: Int): List<StoreOffer> {
        val remaining = offers.toMutableList()
        val selected = mutableListOf<StoreOffer>()
        repeat(count.coerceAtMost(remaining.size)) {
            val totalWeight = remaining.sumOf { it.weight.coerceAtLeast(1) }
            var roll = Random.nextInt(totalWeight)
            val pickedIndex = remaining.indexOfFirst { offer ->
                roll -= offer.weight.coerceAtLeast(1)
                roll < 0
            }.coerceAtLeast(0)
            selected += remaining.removeAt(pickedIndex)
        }
        return selected
    }

    private fun weightedSampleGroups(groups: List<StoreOfferRollGroup>, count: Int): List<StoreOfferRollGroup> {
        val remaining = groups.toMutableList()
        val selected = mutableListOf<StoreOfferRollGroup>()
        repeat(count.coerceAtMost(remaining.size)) {
            val totalWeight = remaining.sumOf { group -> group.weight.coerceAtLeast(1) }
            var roll = Random.nextInt(totalWeight)
            val pickedIndex = remaining.indexOfFirst { group ->
                roll -= group.weight.coerceAtLeast(1)
                roll < 0
            }.coerceAtLeast(0)
            selected += remaining.removeAt(pickedIndex)
        }
        return selected
    }

    private fun stackFor(offer: StoreOffer, count: Int): ItemStack {
        val item = runCatching { ResourceLocation.parse(offer.item) }.getOrNull()
            ?.let { id -> BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR) }
            ?: Items.AIR
        if (item == Items.AIR) return ItemStack.EMPTY
        if (RelicRouletteFeature.isTokenItem(item)) return ItemStack.EMPTY
        return ItemStack(item, count.coerceAtLeast(1))
    }

    private fun reloadDefinitions() {
        configDir.createDirectories()
        val loaded = configDir.listDirectoryEntries("*.toml")
            .filter { it.isRegularFile() && it.extension.equals("toml", ignoreCase = true) }
            .mapNotNull(::readDefinition)
            .associateBy { it.id.lowercase(Locale.ROOT) }
        definitions = loaded
    }

    private fun readDefinition(path: Path): StoreDefinition? = try {
        TomlConfigIO.read(path, StoreDefinition::class.java, ::StoreDefinition)
            .normalized(path.fileName.toString().substringBeforeLast('.'))
    } catch (exception: Exception) {
        ChowKingdomMod.LOGGER.warn("Failed to load store config {}", path, exception)
        null
    }

    private fun loadState(path: Path) {
        statePath = path
        path.parent.createDirectories()
        state = if (path.exists()) {
            try {
                path.bufferedReader().use { reader -> gson.fromJson(reader, StoreStateFile::class.java) } ?: StoreStateFile()
            } catch (exception: Exception) {
                ChowKingdomMod.LOGGER.warn("Failed to load store state {}", path, exception)
                StoreStateFile()
            }
        } else StoreStateFile()
    }

    private fun saveState() {
        val path = statePath ?: return
        path.parent.createDirectories()
        val temp = Files.createTempFile(path.parent, "stores", ".json.tmp")
        temp.bufferedWriter().use { writer -> gson.toJson(state, writer) }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun periodKey(definition: StoreDefinition, pool: ShopViewPool): String {
        if (pool == ShopViewPool.ALL) return "all"
        val zone = runCatching { ZoneId.of(definition.timeZone.ifBlank { DEFAULT_TIME_ZONE }) }.getOrDefault(ZoneId.of(DEFAULT_TIME_ZONE))
        val now = LocalDateTime.now(zone).minusHours(definition.resetHour.coerceIn(0, 23).toLong()).minusMinutes(definition.resetMinute.coerceIn(0, 59).toLong())
        if (pool == ShopViewPool.DAILY) return now.toLocalDate().toString()
        val fields = WeekFields.of(DayOfWeek.MONDAY, 4)
        return "${now.get(fields.weekBasedYear())}-W${now.get(fields.weekOfWeekBasedYear()).toString().padStart(2, '0')}"
    }

    private fun resetText(definition: StoreDefinition, pool: ShopViewPool): String {
        if (pool == ShopViewPool.ALL) return ""
        val zone = runCatching { ZoneId.of(definition.timeZone.ifBlank { DEFAULT_TIME_ZONE }) }.getOrDefault(ZoneId.of(DEFAULT_TIME_ZONE))
        val now = LocalDateTime.now(zone)
        val resetToday = now.toLocalDate().atTime(definition.resetHour.coerceIn(0, 23), definition.resetMinute.coerceIn(0, 59))
        val next = when (pool) {
            ShopViewPool.DAILY -> if (now.isBefore(resetToday)) resetToday else resetToday.plusDays(1)
            ShopViewPool.WEEKLY -> {
                val mondayReset = resetToday.with(WeekFields.of(DayOfWeek.MONDAY, 4).dayOfWeek(), 1)
                if (now.isBefore(mondayReset)) mondayReset else mondayReset.plusWeeks(1)
            }
            ShopViewPool.ALL -> now
        }
        val minutes = ChronoUnit.MINUTES.between(now, next).coerceAtLeast(0)
        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val mins = minutes % 60
        return when {
            days > 0 -> "resets in ${days}d ${hours}h ${mins}m"
            hours > 0 -> "resets in ${hours}h ${mins}m"
            else -> "resets in ${mins}m"
        }
    }

    private fun labelFromId(id: String): String = id.replace('_', ' ').split(' ').filter(String::isNotBlank).joinToString(" ") { word ->
        word.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString() }
    }

    private fun recordBuyMission(player: ServerPlayer, total: Long) {
        val amount = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (amount > 0 && BattlepassMissionEventBank.record(player, SHOP_VALUE_BOUGHT_EVENT, amount)) BattlepassNetwork.syncAllPlayers()
    }

    private fun Long.saturatingMultiply(other: Long): Long = if (this <= 0L || other <= 0L) 0L else if (this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other
    private fun Long.saturatingAdd(other: Long): Long = if (other <= 0L) this else if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

    private const val DEFAULT_TIME_ZONE = "GMT+8"
    private const val SHOP_VALUE_BOUGHT_EVENT = "gisketchs_chowkingdom_mod:shop_value_bought"

    private data class ActiveStoreOffer(val category: StoreCategoryDefinition, val pool: ShopViewPool, val offer: StoreOffer)
    private data class PendingStoreBuy(val active: ActiveStoreOffer, val quantity: Int, val total: Long, val stack: ItemStack)
    private data class StoreOfferRollGroup(val id: String, val weight: Int, val offers: List<StoreOffer>)
}

class StoreDefinition(
    @SerializedName("id") var id: String = "",
    @SerializedName("display_name") var displayName: String = "Server Store",
    @SerializedName("time_zone") var timeZone: String = "GMT+8",
    @SerializedName("reset_hour") var resetHour: Int = 5,
    @SerializedName("reset_minute") var resetMinute: Int = 0,
    @SerializedName("categories") var categories: MutableList<StoreCategoryDefinition> = mutableListOf(),
) {
    fun normalized(fallbackId: String): StoreDefinition {
        id = id.ifBlank { fallbackId }.lowercase(Locale.ROOT)
        if (displayName.isBlank()) displayName = id
        categories = categories.filter { it.id.isNotBlank() }.toMutableList()
        return this
    }
}

private fun StoreDefinition.hasPlayerScopedOffers(): Boolean =
    categories.any { category ->
        (category.allItems + category.dailyItems + category.weeklyItems).any(ExplorerMapService::isExplorerMapOffer) ||
            (category.allSets + category.dailySets + category.weeklySets).flatMap(StoreOfferSet::items).any(ExplorerMapService::isExplorerMapOffer)
    }

private fun StoreDefinition.hasDimensionScopedOffers(): Boolean =
    categories.any { category ->
        (category.allItems + category.dailyItems + category.weeklyItems).any { offer -> ExplorerMapService.isExplorerMapOffer(offer) && offer.dimensions.isNotEmpty() } ||
            (category.allSets + category.dailySets + category.weeklySets).flatMap(StoreOfferSet::items)
                .any { offer -> ExplorerMapService.isExplorerMapOffer(offer) && offer.dimensions.isNotEmpty() }
    }

class StoreCategoryDefinition(
    @SerializedName("id") var id: String = "",
    @SerializedName("item_types_to_sell") var itemTypesToSell: Int = 1,
    @SerializedName("daily_item_types_to_sell") var dailyItemTypesToSell: Int? = null,
    @SerializedName("weekly_item_types_to_sell") var weeklyItemTypesToSell: Int? = null,
    @SerializedName("all_item_types_to_sell") var allItemTypesToSell: Int? = null,
    @SerializedName("daily_items") var dailyItems: MutableList<StoreOffer> = mutableListOf(),
    @SerializedName("weekly_items") var weeklyItems: MutableList<StoreOffer> = mutableListOf(),
    @SerializedName("all_items") var allItems: MutableList<StoreOffer> = mutableListOf(),
    @SerializedName("daily_sets") var dailySets: MutableList<StoreOfferSet> = mutableListOf(),
    @SerializedName("weekly_sets") var weeklySets: MutableList<StoreOfferSet> = mutableListOf(),
    @SerializedName("all_sets") var allSets: MutableList<StoreOfferSet> = mutableListOf(),
)

class StoreOfferSet(
    @SerializedName("id") var id: String = "",
    @SerializedName("weight") var weight: Int = 1,
    @SerializedName("items") var items: MutableList<StoreOffer> = mutableListOf(),
)

class StoreOffer(
    @SerializedName("id") var id: String = "",
    @SerializedName("item") var item: String = "minecraft:air",
    @SerializedName("offer_type") var offerType: String = "",
    @SerializedName("target_type") var targetType: String = "",
    @SerializedName("targets") var targets: MutableList<String> = mutableListOf(),
    @SerializedName("dimensions") var dimensions: MutableList<String> = mutableListOf(),
    @SerializedName("dimension") var dimension: String = "",
    @SerializedName("category") var category: String = "",
    @SerializedName("category_includes") var categoryIncludes: MutableList<String> = mutableListOf(),
    @SerializedName("category_excludes") var categoryExcludes: MutableList<String> = mutableListOf(),
    @SerializedName("distance_band") var distanceBand: String = "",
    @SerializedName("distance_bands") var distanceBands: MutableList<String> = mutableListOf(),
    @SerializedName("label") var label: String = "",
    @SerializedName("map_scale") var mapScale: Int = 2,
    @SerializedName("marker") var marker: String = "",
    @SerializedName("min_distance_blocks") var minDistanceBlocks: Int = 512,
    @SerializedName("max_distance_blocks") var maxDistanceBlocks: Int = 12000,
    @SerializedName("uses") var uses: Int = 1,
    @SerializedName("price_amount") var priceAmount: Long = 1L,
    @SerializedName("stock_count") var stockCount: Int = 1,
    @SerializedName("weight") var weight: Int = 1,
)

private fun StoreOffer.isAvailableInDimension(dimensionId: String?): Boolean {
    if (ExplorerCompassService.isExplorerCompassOffer(this)) return true
    if (dimensionId == null || dimensions.isEmpty()) return true
    return dimensions.map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank).contains(dimensionId.lowercase(Locale.ROOT))
}

private fun ServerPlayer.dimensionId(): String = level().dimension().location().toString()

private fun String.toStockKeyPart(): String = lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "dimension" }.take(40)

class StoreStateFile(
    @SerializedName("stores") var stores: MutableMap<String, StoreRuntimeState> = linkedMapOf(),
)

class StoreRuntimeState(
    @SerializedName("pools") var pools: MutableMap<String, StorePoolState> = linkedMapOf(),
)

class StorePoolState(
    @SerializedName("period") var period: String = "",
    @SerializedName("selected") var selected: MutableMap<String, MutableList<String>> = linkedMapOf(),
    @SerializedName("stock") var stock: MutableMap<String, Int> = linkedMapOf(),
)
