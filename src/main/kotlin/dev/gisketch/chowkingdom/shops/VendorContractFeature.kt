package dev.gisketch.chowkingdom.shops

import com.google.gson.annotations.SerializedName
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.commerce.CommerceAuditLog
import dev.gisketch.chowkingdom.config.TomlConfigIO
import dev.gisketch.chowkingdom.relicroulette.RelicRouletteFeature
import dev.gisketch.chowkingdom.snackbar.SnackbarIcons
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import dev.gisketch.chowkingdom.wallets.ChowcoinNetwork
import dev.gisketch.chowkingdom.wallets.ChowcoinStore
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.function.Consumer
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class VendorContractItem(properties: Properties) : Item(properties) {
    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltipComponents: MutableList<Component>, tooltipFlag: TooltipFlag) {
        val links = VendorContractData.links(stack)
        tooltipComponents += Component.literal("${links.size}/${VendorContractConfig.maxLinks()} shops linked")
        if (links.isEmpty()) tooltipComponents += Component.literal("Right-click priced shops with an item to link.")
        else tooltipComponents += Component.literal("Right-click a mob to sign.")
    }
}

object VendorContractFeature {
    private var tickCounter = 0
    private var nextSellerDiscoveryTick = 0
    private val knownSellerIdsByLevel: MutableMap<ResourceKey<net.minecraft.world.level.Level>, MutableSet<UUID>> = linkedMapOf()

    fun register(modBus: IEventBus) {
        VendorContractConfig.load()
        modBus.addListener(::registerPayloads)
        CobblemonVendorSupport.registerEvents()
        NeoForge.EVENT_BUS.addListener(::onServerStarted)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onRightClickBlock)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onEntityInteractSpecific)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onEntityInteract)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onAttackEntity)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onIncomingDamage)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onLivingDamagePre)
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ::onProjectileImpact)
        NeoForge.EVENT_BUS.addListener(::onLivingDeath)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToClient(VendorOpenPayload.TYPE, VendorOpenPayload.STREAM_CODEC, ::handleOpenClient)
        registrar.playToClient(VendorContractSelectionPayload.TYPE, VendorContractSelectionPayload.STREAM_CODEC, ::handleSelectionClient)
        registrar.playToClient(VendorSellerIdsPayload.TYPE, VendorSellerIdsPayload.STREAM_CODEC, ::handleSellerIdsClient)
        registrar.playToServer(VendorBuyPayload.TYPE, VendorBuyPayload.STREAM_CODEC, ::handleBuy)
        registrar.playToServer(VendorCartBuyPayload.TYPE, VendorCartBuyPayload.STREAM_CODEC, ::handleCartBuy)
        registrar.playToServer(VendorVoidPayload.TYPE, VendorVoidPayload.STREAM_CODEC, ::handleVoid)
        registrar.playToServer(VendorRenamePayload.TYPE, VendorRenamePayload.STREAM_CODEC, ::handleRename)
        registrar.playToServer(VendorCollectPayload.TYPE, VendorCollectPayload.STREAM_CODEC, ::handleCollect)
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        VendorContractConfig.load()
    }

    private fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        val player = event.entity
        if (event.hand != InteractionHand.MAIN_HAND) return
        val stack = player.getItemInHand(event.hand)
        if (!isContract(stack)) return
        val shop = player.level().getBlockEntity(event.pos) as? ShopBlockEntity ?: return
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
        if (player.level().isClientSide) return
        player as? ServerPlayer ?: return
        if (player.isShiftKeyDown) unlinkShop(player, stack, shop)
        else linkShop(player, stack, shop)
        syncContractSelection(player)
    }

    private fun onEntityInteractSpecific(event: PlayerInteractEvent.EntityInteractSpecific) {
        handleEntityInteract(event.entity, event.target, event.hand) {
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
        }
    }

    private fun onEntityInteract(event: PlayerInteractEvent.EntityInteract) {
        handleEntityInteract(event.entity, event.target, event.hand) {
            event.isCanceled = true
            event.cancellationResult = InteractionResult.SUCCESS
        }
    }

    private fun handleEntityInteract(player: Player, target: Entity, hand: InteractionHand, cancel: () -> Unit) {
        if (hand != InteractionHand.MAIN_HAND) return
        val seller = target as? Mob ?: return
        if (SellerData.isSeller(seller)) {
            cancel()
            if (!player.level().isClientSide && player is ServerPlayer) openVendor(player, seller)
            return
        }
        val stack = player.getItemInHand(hand)
        if (!isContract(stack) || VendorContractData.links(stack).isEmpty()) return
        cancel()
        if (!player.level().isClientSide && player is ServerPlayer) signSeller(player, seller, stack)
    }

    private fun linkShop(player: ServerPlayer, stack: ItemStack, shop: ShopBlockEntity) {
        val key = ShopKey.from(player.level(), shop.blockPos)
        if (!isValidLinkedShop(shop)) {
            player.displayClientMessage(Component.literal("Shop needs an item, owner, and price."), true)
            return
        }
        val links = VendorContractData.links(stack).toMutableList()
        if (links.any { it == key }) {
            player.displayClientMessage(Component.literal("Shop already linked (${links.size}/${VendorContractConfig.maxLinks()})."), true)
            return
        }
        if (links.size >= VendorContractConfig.maxLinks()) {
            player.displayClientMessage(Component.literal("Contract is full (${VendorContractConfig.maxLinks()} shops)."), true)
            return
        }
        links += key
        VendorContractData.saveLinks(stack, links)
        player.displayClientMessage(Component.literal("Linked ${shop.displayItem.hoverName.string} (${links.size}/${VendorContractConfig.maxLinks()})."), true)
    }

    private fun unlinkShop(player: ServerPlayer, stack: ItemStack, shop: ShopBlockEntity) {
        val key = ShopKey.from(player.level(), shop.blockPos)
        val links = VendorContractData.links(stack)
        val updated = links.filterNot { it == key }
        if (updated.size == links.size) {
            player.displayClientMessage(Component.literal("Shop is not linked."), true)
            return
        }
        VendorContractData.saveLinks(stack, updated)
        player.displayClientMessage(Component.literal("Unlinked shop (${updated.size}/${VendorContractConfig.maxLinks()})."), true)
    }

    private fun signSeller(player: ServerPlayer, seller: Mob, stack: ItemStack) {
        if (!CobblemonVendorSupport.canBecomeVendor(seller)) {
            player.displayClientMessage(Component.literal("Owned party Pokemon cannot sign contracts. Use wild or pastured Pokemon."), true)
            return
        }
        val links = VendorContractData.links(stack).filter { resolveShop(player.server, it)?.let(::isValidLinkedShop) == true }.take(VendorContractConfig.maxLinks())
        if (links.isEmpty()) {
            player.displayClientMessage(Component.literal("No loaded valid shops on this contract."), true)
            return
        }
        SellerData.save(seller, player, links)
        CobblemonVendorSupport.applyVendorState(seller)
        seller.setNoAi(true)
        seller.setPersistenceRequired()
        seller.setDeltaMovement(Vec3.ZERO)
        rememberSeller(seller)
        stack.shrink(1)
        player.displayClientMessage(Component.literal("Contract Signed"), true)
        openVendor(player, seller)
    }

    private fun openVendor(player: ServerPlayer, seller: Mob) {
        val state = SellerData.read(seller) ?: return
        val entries = state.links.mapNotNull { key ->
            val shop = resolveShop(player.server, key) ?: return@mapNotNull null
            if (!isValidLinkedShop(shop)) return@mapNotNull null
            VendorEntry(key.dimension, key.pos, shop.displayItem.copy(), shop.stockCount, shop.price, shop.ownerUuid ?: return@mapNotNull null, shop.ownerName)
        }
        val canManage = player.isCreative || state.ownerId == player.uuid || entries.any { it.ownerId == player.uuid }
        PacketDistributor.sendToPlayer(
            player,
            VendorOpenPayload(
                seller.uuid,
                state.shopName,
                state.ownerId == player.uuid || player.isCreative,
                canManage,
                vendorClaimableRevenue(player, state),
                entries,
            ),
        )
    }

    private fun handleBuy(payload: VendorBuyPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        val seller = findSeller(player.server, payload.sellerId) as? Mob ?: return
        val state = SellerData.read(seller) ?: return
        if (player.level() !== seller.level() || player.distanceToSqr(seller) > 64.0) return
        val key = ShopKey(payload.dimension, payload.pos)
        if (state.links.none { it == key }) return
        val shop = resolveShop(player.server, key) ?: return
        if (!isValidLinkedShop(shop)) return
        if (ShopStockNetwork.buyFromShop(player, shop, payload.quantity, requireOtherOwner = true)) openVendor(player, seller)
    }

    private fun handleCartBuy(payload: VendorCartBuyPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        val seller = findSeller(player.server, payload.sellerId) as? Mob ?: return
        val state = SellerData.read(seller) ?: return
        if (player.level() !== seller.level() || player.distanceToSqr(seller) > 64.0) return
        val lines = payload.lines.filter { it.quantity > 0 }.take(100)
        if (lines.isEmpty()) return

        val resolved = lines.mapNotNull { line ->
            val key = ShopKey(line.dimension, line.pos)
            if (state.links.none { it == key }) return@mapNotNull null
            val shop = resolveShop(player.server, key) ?: return@mapNotNull null
            if (!isValidLinkedShop(shop) || shop.stockCount <= 0) return@mapNotNull null
            if (shop.ownerUuid == player.uuid) return@mapNotNull null
            val quantity = line.quantity.coerceIn(1, shop.stockCount)
            val total = shop.price.saturatingMultiply(quantity.toLong())
            PendingBuy(key, shop, quantity, total, shop.ownerUuid ?: return@mapNotNull null, shop.ownerName, shop.displayItem.hoverName.string)
        }
        if (resolved.isEmpty()) {
            player.displayClientMessage(Component.literal("No stock available."), true)
            return
        }
        val totalCost = resolved.fold(0L) { sum, buy -> sum.saturatingAdd(buy.total) }
        val balance = ChowcoinStore.get(player)
        if (balance < totalCost) {
            player.displayClientMessage(Component.literal("Not enough chowcoins."), true)
            ChowcoinNetwork.syncTo(player)
            return
        }
        val bought = mutableListOf<ItemStack>()
        resolved.forEach { buy ->
            val removed = buy.shop.removeStockStacks(buy.quantity)
            if (removed.isNotEmpty()) {
                val boughtCount = removed.sumOf { it.count }
                bought += removed
                buy.shop.recordSale(boughtCount, buy.total, claimable = true)
                SnackbarNetwork.sendOrQueue(player.server, buy.ownerId, SnackbarNotification.texture(SnackbarIcons.CHOWCOIN_TEXTURE, "ITEM SOLD", "${player.gameProfile.name} bought $boughtCount ${buy.itemName} for ${buy.total} chowcoins", SnackbarType.SUCCESS, SnackbarSounds.SALE))
                CommerceAuditLog.recordVendorBuy(player, buy.ownerId, buy.ownerName, seller, buy.key.dimension, buy.key.pos, buy.itemName, boughtCount, buy.total)
            }
        }
        if (bought.isEmpty()) {
            player.displayClientMessage(Component.literal("Stock changed. Try again."), true)
            return
        }
        val boughtCount = bought.sumOf { it.count }
        ChowcoinStore.set(player, balance - totalCost)
        bought.forEach { stack -> if (!player.inventory.add(stack)) player.drop(stack, false) }
        ChowcoinNetwork.syncTo(player)
        SnackbarNetwork.send(player, SnackbarNotification.texture(SnackbarIcons.CHOWCOIN_TEXTURE, "BUY SUCCESSFUL", "Bought $boughtCount items for $totalCost chowcoins", SnackbarType.GENERIC, SnackbarSounds.GENERIC))
    }

    private fun handleVoid(payload: VendorVoidPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        val seller = findSeller(player.server, payload.sellerId) as? Mob ?: return
        val state = SellerData.read(seller) ?: return
        if (player.level() !== seller.level() || player.distanceToSqr(seller) > 64.0) return
        if (state.ownerId != player.uuid && !player.isCreative) return
        SellerData.clear(seller)
        forgetSeller(seller)
        CobblemonVendorSupport.restoreVendorState(seller, state.previousCobblemonHideLabel, state.previousCobblemonUnbattleable, state.previousCobblemonShouldRenderName)
        seller.setNoAi(state.previousNoAi)
        val contract = contractStack(state.links)
        if (!player.inventory.add(contract)) player.drop(contract, false)
        player.displayClientMessage(Component.literal("Contract Voided"), true)
    }

    private fun handleRename(payload: VendorRenamePayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        val seller = findSeller(player.server, payload.sellerId) as? Mob ?: return
        if (!canManageSeller(player, seller)) return
        SellerData.setShopName(seller, payload.name)
        openVendor(player, seller)
    }

    private fun handleCollect(payload: VendorCollectPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        val seller = findSeller(player.server, payload.sellerId) as? Mob ?: return
        val state = SellerData.read(seller) ?: return
        if (!canManageSeller(player, seller)) return
        val amount = collectVendorRevenue(player, state)
        if (amount <= 0L) return
        ChowcoinStore.add(player, amount)
        ChowcoinNetwork.syncTo(player)
        SnackbarNetwork.send(player, SnackbarNotification.texture(SnackbarIcons.CHOWCOIN_TEXTURE, "VENDOR REVENUE COLLECTED", "$amount chowcoins", SnackbarType.SUCCESS, SnackbarSounds.SALE))
    }

    private fun itemIcon(stack: ItemStack): String = BuiltInRegistries.ITEM.getKey(stack.item).toString()

    private fun handleOpenClient(payload: VendorOpenPayload, context: IPayloadContext) {
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient) return
        context.enqueueWork {
            runCatching {
                val client = Class.forName("dev.gisketch.chowkingdom.shops.VendorContractClient")
                client.getMethod("openVendor", VendorOpenPayload::class.java).invoke(client.getField("INSTANCE").get(null), payload)
            }
        }
    }

    private fun handleSelectionClient(payload: VendorContractSelectionPayload, context: IPayloadContext) {
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient) return
        context.enqueueWork {
            runCatching {
                val client = Class.forName("dev.gisketch.chowkingdom.shops.VendorContractClient")
                client.getMethod("syncSelection", VendorContractSelectionPayload::class.java).invoke(client.getField("INSTANCE").get(null), payload)
            }
        }
    }

    private fun handleSellerIdsClient(payload: VendorSellerIdsPayload, context: IPayloadContext) {
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient) return
        context.enqueueWork {
            runCatching {
                val client = Class.forName("dev.gisketch.chowkingdom.shops.VendorContractClient")
                client.getMethod("syncSellerIds", VendorSellerIdsPayload::class.java).invoke(client.getField("INSTANCE").get(null), payload)
            }
        }
    }

    private fun onAttackEntity(event: AttackEntityEvent) {
        if (SellerData.isSeller(event.target)) event.isCanceled = true
    }

    private fun onIncomingDamage(event: LivingIncomingDamageEvent) {
        if (!SellerData.isSeller(event.entity)) return
        event.isCanceled = true
        event.amount = 0.0f
    }

    private fun onLivingDamagePre(event: LivingDamageEvent.Pre) {
        if (SellerData.isSeller(event.entity)) event.newDamage = 0.0f
    }

    private fun onProjectileImpact(event: ProjectileImpactEvent) {
        val hit = event.rayTraceResult as? EntityHitResult ?: return
        if (!SellerData.isSeller(hit.entity)) return
        event.isCanceled = true
        event.projectile.discard()
    }

    private fun onLivingDeath(event: LivingDeathEvent) {
        val seller = event.entity as? Mob ?: return
        val state = SellerData.read(seller) ?: return
        SellerData.clear(seller)
        forgetSeller(seller)
        CobblemonVendorSupport.restoreVendorState(seller, state.previousCobblemonHideLabel, state.previousCobblemonUnbattleable, state.previousCobblemonShouldRenderName)
        seller.spawnAtLocation(contractStack(state.links))
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        if (event.server.tickCount >= nextSellerDiscoveryTick) {
            discoverLoadedSellers(event.server)
            nextSellerDiscoveryTick = event.server.tickCount + VENDOR_DISCOVERY_INTERVAL_TICKS
        }
        tickCounter = (tickCounter + 1) % 10
        if (tickCounter != 0) return
        loadedKnownSellers(event.server).forEach(::freezeSeller)
        event.server.playerList.players.forEach { player ->
            syncContractSelection(player)
            syncVendorSellers(player)
        }
    }

    private fun freezeSeller(mob: Mob) {
        val state = SellerData.read(mob) ?: return
        rememberSeller(mob)
        mob.setNoAi(true)
        CobblemonVendorSupport.applyVendorState(mob)
        mob.setDeltaMovement(Vec3.ZERO)
        val dx = mob.x - state.anchorX
        val dy = mob.y - state.anchorY
        val dz = mob.z - state.anchorZ
        if (dx * dx + dy * dy + dz * dz > 0.01) mob.teleportTo(state.anchorX, state.anchorY, state.anchorZ)
        val nearest = (mob.level() as? ServerLevel)?.players()?.filter { it.distanceToSqr(mob) <= 100.0 }?.minByOrNull { it.distanceToSqr(mob) }
        if (nearest != null) mob.lookAt(nearest, 30.0f, 30.0f)
    }

    private fun syncContractSelection(player: ServerPlayer) {
        val stack = player.mainHandItem.takeIf(::isContract) ?: player.offhandItem.takeIf(::isContract)
        val dimension = player.level().dimension().location().toString()
        val positions = stack?.let { VendorContractData.links(it).filter { key -> key.dimension == dimension }.map { key -> key.pos } } ?: emptyList()
        PacketDistributor.sendToPlayer(player, VendorContractSelectionPayload(positions))
    }

    private fun syncVendorSellers(player: ServerPlayer) {
        val sellers = loadedKnownSellers(player.server)
            .asSequence()
            .filter { seller -> seller.level() == player.level() && seller.distanceToSqr(player) <= VENDOR_SYNC_RADIUS_SQR }
            .mapNotNull { seller -> SellerData.read(seller)?.let { VendorSellerName(seller.uuid, it.shopName) } }
            .take(256)
            .toList()
        PacketDistributor.sendToPlayer(player, VendorSellerIdsPayload(sellers))
    }

    private fun contractStack(links: List<ShopKey>): ItemStack =
        ItemStack(ShopsFeature.VENDOR_CONTRACT_ITEM.get()).also { VendorContractData.saveLinks(it, links.take(VendorContractConfig.maxLinks())) }

    private fun resolveShop(server: MinecraftServer, key: ShopKey): ShopBlockEntity? {
        val level = server.getLevel(dimensionKey(key.dimension)) ?: return null
        if (!level.isLoaded(key.pos)) return null
        return level.getBlockEntity(key.pos) as? ShopBlockEntity
    }

    private fun findSeller(server: MinecraftServer, id: UUID): Entity? {
        loadedKnownSellers(server).firstOrNull { seller -> seller.uuid == id }?.let { return it }
        return server.allLevels.asSequence()
            .flatMap { level -> level.allEntities.asSequence() }
            .firstOrNull { it.uuid == id && SellerData.isSeller(it) }
            ?.also(::rememberSeller)
    }

    private fun loadedKnownSellers(server: MinecraftServer): List<Mob> {
        val sellers = mutableListOf<Mob>()
        val stale: MutableList<Pair<ResourceKey<net.minecraft.world.level.Level>, UUID>> = mutableListOf()
        knownSellerIdsByLevel.forEach { (dimension, ids) ->
            val level = server.getLevel(dimension)
            if (level == null) {
                ids.forEach { id -> stale += dimension to id }
                return@forEach
            }
            ids.forEach { id ->
                val seller = level.getEntity(id) as? Mob
                if (seller != null && seller.isAlive && SellerData.isSeller(seller)) sellers += seller else stale += dimension to id
            }
        }
        stale.forEach { (dimension, id) -> knownSellerIdsByLevel[dimension]?.remove(id) }
        knownSellerIdsByLevel.entries.removeIf { (_, ids) -> ids.isEmpty() }
        return sellers
    }

    private fun discoverLoadedSellers(server: MinecraftServer) {
        val found: MutableMap<ResourceKey<net.minecraft.world.level.Level>, MutableSet<UUID>> = linkedMapOf()
        server.allLevels.forEach { level ->
            level.allEntities.forEach { entity ->
                val mob = entity as? Mob ?: return@forEach
                if (SellerData.isSeller(mob)) found.getOrPut(level.dimension()) { linkedSetOf() }.add(mob.uuid)
            }
        }
        knownSellerIdsByLevel.clear()
        knownSellerIdsByLevel.putAll(found)
    }

    private fun rememberSeller(entity: Entity) {
        knownSellerIdsByLevel.getOrPut(entity.level().dimension()) { linkedSetOf() }.add(entity.uuid)
    }

    private fun forgetSeller(entity: Entity) {
        knownSellerIdsByLevel[entity.level().dimension()]?.remove(entity.uuid)
        knownSellerIdsByLevel.entries.removeIf { (_, ids) -> ids.isEmpty() }
    }

    private fun dimensionKey(raw: String): ResourceKey<net.minecraft.world.level.Level> =
        ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(raw))

    private fun isValidLinkedShop(shop: ShopBlockEntity): Boolean =
        shop.ownerUuid != null && shop.hasDisplayItem && shop.price > 0L && !RelicRouletteFeature.isTransferBlocked(shop.displayItem)

    private fun isContract(stack: ItemStack): Boolean = !stack.isEmpty && stack.item === ShopsFeature.VENDOR_CONTRACT_ITEM.get()

    private fun canManageSeller(player: ServerPlayer, seller: Mob): Boolean {
        val state = SellerData.read(seller) ?: return false
        if (state.ownerId == player.uuid || player.isCreative) return true
        return state.links.any { key -> resolveShop(player.server, key)?.ownerUuid == player.uuid }
    }

    private fun vendorClaimableRevenue(player: ServerPlayer, state: SellerState): Long =
        state.links.distinct().asSequence()
            .mapNotNull { key -> resolveShop(player.server, key) }
            .filter { shop -> player.isCreative || shop.ownerUuid == player.uuid }
            .fold(0L) { sum, shop -> sum.saturatingAdd(shop.claimableRevenue) }

    private fun collectVendorRevenue(player: ServerPlayer, state: SellerState): Long =
        state.links.distinct().asSequence()
            .mapNotNull { key -> resolveShop(player.server, key) }
            .filter { shop -> player.isCreative || shop.ownerUuid == player.uuid }
            .fold(0L) { sum, shop -> sum.saturatingAdd(shop.collectRevenue(player)) }

    fun jadeSummary(server: MinecraftServer, seller: Entity): VendorJadeSummary? {
        val state = SellerData.read(seller) ?: return null
        val shops = state.links.mapNotNull { key -> resolveShop(server, key) }
            .filter(::isValidLinkedShop)
        val itemTypes = shops.map { shop -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(shop.displayItem.item) }
            .distinct()
            .size
        val sellerCount = shops.mapNotNull(ShopBlockEntity::ownerUuid)
            .distinct()
            .size
        return VendorJadeSummary(state.shopName, itemTypes, sellerCount)
    }

    private fun Long.saturatingMultiply(other: Long): Long {
        if (this <= 0L || other <= 0L) return 0L
        if (this > Long.MAX_VALUE / other) return Long.MAX_VALUE
        return this * other
    }

    private fun Long.saturatingAdd(other: Long): Long {
        if (other <= 0L) return this
        if (this > Long.MAX_VALUE - other) return Long.MAX_VALUE
        return this + other
    }

    private const val VENDOR_DISCOVERY_INTERVAL_TICKS = 20 * 10
    private const val VENDOR_SYNC_RADIUS_SQR = 128.0 * 128.0

    private data class PendingBuy(val key: ShopKey, val shop: ShopBlockEntity, val quantity: Int, val total: Long, val ownerId: UUID, val ownerName: String, val itemName: String)
}

data class VendorJadeSummary(val shopName: String, val itemTypes: Int, val sellerCount: Int)

object VendorContractConfig {
    private var config = VendorConfig()

    private val file: Path
        get() = FMLPaths.CONFIGDIR.get().resolve(ChowKingdomMod.MOD_ID).resolve("shops").resolve("vendor_contract.toml")

    fun load() {
        file.parent.createDirectories()
        if (!file.exists()) writeDefault()
        config = try {
            TomlConfigIO.read(file, VendorConfig::class.java, ::VendorConfig)
        } catch (exception: Exception) {
            ChowKingdomMod.LOGGER.warn("Failed to load vendor contract config {}", file, exception)
            VendorConfig()
        }
    }

    fun maxLinks(): Int = config.maxLinkedShops.coerceIn(1, 1_000)

    private fun writeDefault() {
        TomlConfigIO.write(file, VendorConfig())
    }
}

class VendorConfig(
    @SerializedName("max_linked_shops") var maxLinkedShops: Int = 100,
)

data class ShopKey(val dimension: String, val pos: BlockPos) {
    companion object {
        fun from(level: net.minecraft.world.level.Level, pos: BlockPos): ShopKey = ShopKey(level.dimension().location().toString(), pos)
    }
}

object VendorContractData {
    fun links(stack: ItemStack): List<ShopKey> {
        val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
        return readLinks(tag)
    }

    fun saveLinks(stack: ItemStack, links: List<ShopKey>) {
        val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
        writeLinks(tag, links)
        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag)
    }

    fun readLinks(tag: CompoundTag): List<ShopKey> {
        val list = tag.getList(SHOPS_TAG, CompoundTag.TAG_COMPOUND.toInt())
        return (0 until list.size).mapNotNull { index ->
            val entry = list.getCompound(index)
            val dimension = entry.getString(DIMENSION_TAG).takeIf(String::isNotBlank) ?: return@mapNotNull null
            ShopKey(dimension, BlockPos(entry.getInt(X_TAG), entry.getInt(Y_TAG), entry.getInt(Z_TAG)))
        }
    }

    fun writeLinks(tag: CompoundTag, links: List<ShopKey>) {
        val list = ListTag()
        links.distinct().forEach { key ->
            list.add(CompoundTag().also { entry ->
                entry.putString(DIMENSION_TAG, key.dimension)
                entry.putInt(X_TAG, key.pos.x)
                entry.putInt(Y_TAG, key.pos.y)
                entry.putInt(Z_TAG, key.pos.z)
            })
        }
        tag.put(SHOPS_TAG, list)
    }

    const val SHOPS_TAG = "VendorShops"
    private const val DIMENSION_TAG = "Dimension"
    private const val X_TAG = "X"
    private const val Y_TAG = "Y"
    private const val Z_TAG = "Z"
}

data class SellerState(
    val ownerId: UUID,
    val ownerName: String,
    val shopName: String,
    val previousNoAi: Boolean,
    val previousCobblemonHideLabel: Boolean?,
    val previousCobblemonUnbattleable: Boolean?,
    val previousCobblemonShouldRenderName: Boolean?,
    val anchorX: Double,
    val anchorY: Double,
    val anchorZ: Double,
    val links: List<ShopKey>,
    val revenue: Map<UUID, Long>,
)

object SellerData {
    fun isSeller(entity: Entity): Boolean = entity.persistentData.contains(SELLER_TAG, CompoundTag.TAG_COMPOUND.toInt())

    fun save(entity: Mob, owner: ServerPlayer, links: List<ShopKey>) {
        entity.persistentData.put(SELLER_TAG, CompoundTag().also { tag ->
            tag.putUUID(OWNER_ID_TAG, owner.uuid)
            tag.putString(OWNER_NAME_TAG, owner.gameProfile.name)
            tag.putString(SHOP_NAME_TAG, "${owner.gameProfile.name}'s Vendor")
            tag.putBoolean(PREVIOUS_NO_AI_TAG, entity.isNoAi)
            CobblemonVendorSupport.readVendorState(entity)?.let { state ->
                tag.putBoolean(PREVIOUS_COBBLEMON_HIDE_LABEL_TAG, state.hideLabel)
                tag.putBoolean(PREVIOUS_COBBLEMON_UNBATTLEABLE_TAG, state.unbattleable)
                tag.putBoolean(PREVIOUS_COBBLEMON_SHOULD_RENDER_NAME_TAG, state.shouldRenderName)
            }
            tag.putDouble(ANCHOR_X_TAG, entity.x)
            tag.putDouble(ANCHOR_Y_TAG, entity.y)
            tag.putDouble(ANCHOR_Z_TAG, entity.z)
            VendorContractData.writeLinks(tag, links)
        })
    }

    fun read(entity: Entity): SellerState? {
        val tag = entity.persistentData.getCompound(SELLER_TAG)
        if (!tag.hasUUID(OWNER_ID_TAG)) return null
        return SellerState(
            tag.getUUID(OWNER_ID_TAG),
            tag.getString(OWNER_NAME_TAG),
            tag.getString(SHOP_NAME_TAG).ifBlank { "Vendor Shop" },
            tag.getBoolean(PREVIOUS_NO_AI_TAG),
            tag.takeIf { it.contains(PREVIOUS_COBBLEMON_HIDE_LABEL_TAG) }?.getBoolean(PREVIOUS_COBBLEMON_HIDE_LABEL_TAG),
            tag.takeIf { it.contains(PREVIOUS_COBBLEMON_UNBATTLEABLE_TAG) }?.getBoolean(PREVIOUS_COBBLEMON_UNBATTLEABLE_TAG),
            tag.takeIf { it.contains(PREVIOUS_COBBLEMON_SHOULD_RENDER_NAME_TAG) }?.getBoolean(PREVIOUS_COBBLEMON_SHOULD_RENDER_NAME_TAG),
            tag.getDouble(ANCHOR_X_TAG),
            tag.getDouble(ANCHOR_Y_TAG),
            tag.getDouble(ANCHOR_Z_TAG),
            VendorContractData.readLinks(tag),
            readRevenue(tag),
        )
    }

    fun clear(entity: Entity) {
        entity.persistentData.remove(SELLER_TAG)
    }

    fun setShopName(entity: Entity, name: String) {
        val tag = entity.persistentData.getCompound(SELLER_TAG)
        if (tag.isEmpty) return
        tag.putString(SHOP_NAME_TAG, name.trim().take(48).ifBlank { "Vendor Shop" })
        entity.persistentData.put(SELLER_TAG, tag)
    }

    fun addRevenue(entity: Entity, ownerId: UUID, amount: Long) {
        if (amount <= 0L) return
        val tag = entity.persistentData.getCompound(SELLER_TAG)
        if (tag.isEmpty) return
        val revenue = tag.getCompound(REVENUE_TAG)
        val current = revenue.getLong(ownerId.toString())
        val next = if (current > Long.MAX_VALUE - amount) Long.MAX_VALUE else current + amount
        revenue.putLong(ownerId.toString(), next)
        tag.put(REVENUE_TAG, revenue)
        entity.persistentData.put(SELLER_TAG, tag)
    }

    fun collectRevenue(entity: Entity, ownerId: UUID): Long {
        val tag = entity.persistentData.getCompound(SELLER_TAG)
        if (tag.isEmpty) return 0L
        val revenue = tag.getCompound(REVENUE_TAG)
        val key = ownerId.toString()
        val amount = revenue.getLong(key).coerceAtLeast(0L)
        if (amount > 0L) revenue.remove(key)
        tag.put(REVENUE_TAG, revenue)
        entity.persistentData.put(SELLER_TAG, tag)
        return amount
    }

    private fun readRevenue(tag: CompoundTag): Map<UUID, Long> {
        val revenue = tag.getCompound(REVENUE_TAG)
        return revenue.allKeys.mapNotNull { key ->
            val id = runCatching { UUID.fromString(key) }.getOrNull() ?: return@mapNotNull null
            id to revenue.getLong(key).coerceAtLeast(0L)
        }.filter { (_, amount) -> amount > 0L }.toMap()
    }

    private const val SELLER_TAG = "ChowkingdomVendorSeller"
    private const val OWNER_ID_TAG = "OwnerId"
    private const val OWNER_NAME_TAG = "OwnerName"
    private const val SHOP_NAME_TAG = "ShopName"
    private const val PREVIOUS_NO_AI_TAG = "PreviousNoAi"
    private const val PREVIOUS_COBBLEMON_HIDE_LABEL_TAG = "PreviousCobblemonHideLabel"
    private const val PREVIOUS_COBBLEMON_UNBATTLEABLE_TAG = "PreviousCobblemonUnbattleable"
    private const val PREVIOUS_COBBLEMON_SHOULD_RENDER_NAME_TAG = "PreviousCobblemonShouldRenderName"
    private const val ANCHOR_X_TAG = "AnchorX"
    private const val ANCHOR_Y_TAG = "AnchorY"
    private const val ANCHOR_Z_TAG = "AnchorZ"
    private const val REVENUE_TAG = "Revenue"
}

object CobblemonVendorSupport {
    private const val POKEMON_ENTITY_CLASS = "com.cobblemon.mod.common.entity.pokemon.PokemonEntity"
    private var eventsRegistered = false
    private val pokemonEntityClass: Class<*>? by lazy { runCatching { Class.forName(POKEMON_ENTITY_CLASS) }.getOrNull() }
    private val hideLabelAccessor: Any? by lazy { pokemonEntityClass?.staticAccessor("HIDE_LABEL") }
    private val unbattleableAccessor: Any? by lazy { pokemonEntityClass?.staticAccessor("UNBATTLEABLE") }
    private val shouldRenderNameAccessor: Any? by lazy { pokemonEntityClass?.staticAccessor("SHOULD_RENDER_NAME") }

    fun registerEvents() {
        if (eventsRegistered) return
        eventsRegistered = true
        runCatching {
            val eventsClass = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents")
            subscribeRaw(eventsClass, "BATTLE_STARTED_PRE", ::handleBattleStartedPre)
        }.onFailure { exception ->
            ChowKingdomMod.LOGGER.debug("Cobblemon vendor battle guard unavailable", exception)
        }
    }

    fun canBecomeVendor(entity: Entity): Boolean {
        if (!isPokemonEntity(entity)) return true
        val ownerId = pokemonOwnerId(entity)
        val pastured = isPastured(entity)
        return ownerId == null || pastured
    }

    fun readVendorState(entity: Entity): CobblemonVendorState? {
        if (!isPokemonEntity(entity)) return null
        return CobblemonVendorState(
            readEntityDataBoolean(entity, hideLabelAccessor) ?: false,
            readEntityDataBoolean(entity, unbattleableAccessor) ?: false,
            readEntityDataBoolean(entity, shouldRenderNameAccessor) ?: entity.shouldShowName(),
        )
    }

    fun applyVendorState(entity: Entity) {
        if (!isPokemonEntity(entity)) return
        writeEntityDataBoolean(entity, hideLabelAccessor, true)
        writeEntityDataBoolean(entity, unbattleableAccessor, true)
        writeEntityDataBoolean(entity, shouldRenderNameAccessor, false)
        runCatching { entity.javaClass.getMethod("hideNameRendering").invoke(entity) }
        entity.isCustomNameVisible = false
    }

    fun restoreVendorState(entity: Entity, hideLabel: Boolean?, unbattleable: Boolean?, shouldRenderName: Boolean?) {
        if (!isPokemonEntity(entity)) return
        hideLabel?.let { writeEntityDataBoolean(entity, hideLabelAccessor, it) }
        unbattleable?.let { writeEntityDataBoolean(entity, unbattleableAccessor, it) }
        shouldRenderName?.let { writeEntityDataBoolean(entity, shouldRenderNameAccessor, it) }
    }

    private fun handleBattleStartedPre(event: Any) {
        if (!battleContainsVendor(event)) return
        runCatching { event.javaClass.getMethod("cancel").invoke(event) }
        runCatching { event.javaClass.getMethod("setReason", net.minecraft.network.chat.MutableComponent::class.java).invoke(event, Component.literal("Vendor Pokemon cannot battle.")) }
    }

    private fun battleContainsVendor(event: Any): Boolean {
        val battle = runCatching { event.javaClass.getMethod("getBattle").invoke(event) }.getOrNull() ?: return false
        val activePokemon = runCatching { battle.javaClass.getMethod("getActivePokemon").invoke(battle) as? Iterable<*> }.getOrNull() ?: emptyList<Any?>()
        if (activePokemon.any(::activeBattlePokemonIsVendor)) return true
        val actors = runCatching { battle.javaClass.getMethod("getActors").invoke(battle) as? Iterable<*> }.getOrNull() ?: emptyList<Any?>()
        return actors.any { actor ->
            val pokemonList = runCatching { actor?.javaClass?.getMethod("getPokemonList")?.invoke(actor) as? Iterable<*> }.getOrNull() ?: emptyList<Any?>()
            pokemonList.any(::battlePokemonIsVendor)
        }
    }

    private fun activeBattlePokemonIsVendor(active: Any?): Boolean {
        val battlePokemon = runCatching { active?.javaClass?.getMethod("getBattlePokemon")?.invoke(active) }.getOrNull()
        return battlePokemonIsVendor(battlePokemon)
    }

    private fun battlePokemonIsVendor(battlePokemon: Any?): Boolean {
        val entity = runCatching { battlePokemon?.javaClass?.getMethod("getEntity")?.invoke(battlePokemon) as? Entity }.getOrNull()
        if (entity?.let(SellerData::isSeller) == true) return true
        val originalPokemon = runCatching { battlePokemon?.javaClass?.getMethod("getOriginalPokemon")?.invoke(battlePokemon) }.getOrNull()
        val originalEntity = runCatching { originalPokemon?.javaClass?.getMethod("getEntity")?.invoke(originalPokemon) as? Entity }.getOrNull()
        return originalEntity?.let(SellerData::isSeller) == true
    }

    private fun isPokemonEntity(entity: Entity): Boolean =
        pokemonEntityClass?.isInstance(entity) == true

    private fun isPastured(entity: Entity): Boolean =
        runCatching { entity.javaClass.getField("tethering").get(entity) != null }
            .recoverCatching {
                val method = entity.javaClass.getMethod("getTethering")
                method.invoke(entity) != null
            }
            .getOrDefault(false)

    private fun pokemonOwnerId(entity: Entity): UUID? {
        val direct = runCatching { entity.javaClass.getMethod("getOwnerUUID").invoke(entity) as? UUID }.getOrNull()
        if (direct != null) return direct
        val pokemon = runCatching { entity.javaClass.getMethod("getPokemon").invoke(entity) }.getOrNull() ?: return null
        return runCatching { pokemon.javaClass.getMethod("getOwnerUUID").invoke(pokemon) as? UUID }.getOrNull()
    }

    private fun subscribeRaw(eventsClass: Class<*>, fieldName: String, handler: (Any) -> Unit) {
        val observable = eventsClass.getField(fieldName).get(null)
        val subscribe = observable.javaClass.methods.first { method ->
            method.name == "subscribe" && method.parameterTypes.size == 1 && method.parameterTypes[0] == Consumer::class.java
        }
        subscribe.invoke(observable, Consumer<Any> { event -> handler(event) })
    }

    private fun Class<*>.staticAccessor(name: String): Any? =
        runCatching { getMethod("get$name").invoke(null) }.getOrNull()
            ?: runCatching { getMethod("access\$get${name}\$cp").invoke(null) }.getOrNull()
            ?: runCatching { getDeclaredField(name).also { it.isAccessible = true }.get(null) }.getOrNull()

    private fun readEntityDataBoolean(entity: Entity, accessor: Any?): Boolean? {
        accessor ?: return null
        return runCatching {
            val getMethod = entity.entityData.javaClass.methods.firstOrNull { method -> method.name == "get" && method.parameterCount == 1 }
            getMethod?.invoke(entity.entityData, accessor) as? Boolean
        }.getOrNull()
    }

    private fun writeEntityDataBoolean(entity: Entity, accessor: Any?, value: Boolean) {
        accessor ?: return
        runCatching {
            val setMethod = entity.entityData.javaClass.methods.firstOrNull { method ->
                method.name == "set" && method.parameterCount == 2 && method.parameterTypes[1] == Any::class.java
            } ?: entity.entityData.javaClass.methods.firstOrNull { method -> method.name == "set" && method.parameterCount == 2 }
            setMethod?.invoke(entity.entityData, accessor, value)
        }
    }

    data class CobblemonVendorState(val hideLabel: Boolean, val unbattleable: Boolean, val shouldRenderName: Boolean)
}

data class VendorEntry(
    val dimension: String,
    val pos: BlockPos,
    val stack: ItemStack,
    val stockCount: Int,
    val price: Long,
    val ownerId: UUID,
    val ownerName: String,
)

data class VendorOpenPayload(
    val sellerId: UUID,
    val shopName: String,
    val canVoid: Boolean,
    val canManage: Boolean,
    val claimableRevenue: Long,
    val entries: List<VendorEntry>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<VendorOpenPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<VendorOpenPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "shops/vendor_open"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, VendorOpenPayload> = object : StreamCodec<RegistryFriendlyByteBuf, VendorOpenPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): VendorOpenPayload {
                val sellerId = buffer.readUUID()
                val shopName = buffer.readUtf(64)
                val canVoid = buffer.readBoolean()
                val canManage = buffer.readBoolean()
                val claimableRevenue = buffer.readVarLong()
                val entries = List(buffer.readVarInt().coerceIn(0, VendorContractConfig.maxLinks())) {
                    VendorEntry(
                        buffer.readUtf(128),
                        buffer.readBlockPos(),
                        ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
                        buffer.readVarInt(),
                        buffer.readVarLong(),
                        buffer.readUUID(),
                        buffer.readUtf(64),
                    )
                }
                return VendorOpenPayload(sellerId, shopName, canVoid, canManage, claimableRevenue, entries)
            }

            override fun encode(buffer: RegistryFriendlyByteBuf, value: VendorOpenPayload) {
                buffer.writeUUID(value.sellerId)
                buffer.writeUtf(value.shopName, 64)
                buffer.writeBoolean(value.canVoid)
                buffer.writeBoolean(value.canManage)
                buffer.writeVarLong(value.claimableRevenue)
                buffer.writeVarInt(value.entries.size)
                value.entries.forEach { entry ->
                    buffer.writeUtf(entry.dimension, 128)
                    buffer.writeBlockPos(entry.pos)
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, entry.stack.copyWithCount(1))
                    buffer.writeVarInt(entry.stockCount)
                    buffer.writeVarLong(entry.price)
                    buffer.writeUUID(entry.ownerId)
                    buffer.writeUtf(entry.ownerName, 64)
                }
            }
        }
    }
}

data class VendorCartLine(val dimension: String, val pos: BlockPos, val quantity: Int)

data class VendorCartBuyPayload(val sellerId: UUID, val lines: List<VendorCartLine>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<VendorCartBuyPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<VendorCartBuyPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "shops/vendor_cart_buy"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, VendorCartBuyPayload> = object : StreamCodec<RegistryFriendlyByteBuf, VendorCartBuyPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): VendorCartBuyPayload =
                VendorCartBuyPayload(
                    buffer.readUUID(),
                    List(buffer.readVarInt().coerceIn(0, 100)) {
                        VendorCartLine(buffer.readUtf(128), buffer.readBlockPos(), buffer.readVarInt())
                    },
                )

            override fun encode(buffer: RegistryFriendlyByteBuf, value: VendorCartBuyPayload) {
                buffer.writeUUID(value.sellerId)
                buffer.writeVarInt(value.lines.size)
                value.lines.forEach { line ->
                    buffer.writeUtf(line.dimension, 128)
                    buffer.writeBlockPos(line.pos)
                    buffer.writeVarInt(line.quantity)
                }
            }
        }
    }
}

data class VendorBuyPayload(val sellerId: UUID, val dimension: String, val pos: BlockPos, val quantity: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<VendorBuyPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<VendorBuyPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "shops/vendor_buy"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, VendorBuyPayload> = object : StreamCodec<RegistryFriendlyByteBuf, VendorBuyPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): VendorBuyPayload =
                VendorBuyPayload(buffer.readUUID(), buffer.readUtf(128), buffer.readBlockPos(), buffer.readVarInt())

            override fun encode(buffer: RegistryFriendlyByteBuf, value: VendorBuyPayload) {
                buffer.writeUUID(value.sellerId)
                buffer.writeUtf(value.dimension, 128)
                buffer.writeBlockPos(value.pos)
                buffer.writeVarInt(value.quantity)
            }
        }
    }
}

data class VendorRenamePayload(val sellerId: UUID, val name: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<VendorRenamePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<VendorRenamePayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "shops/vendor_rename"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, VendorRenamePayload> = object : StreamCodec<RegistryFriendlyByteBuf, VendorRenamePayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): VendorRenamePayload = VendorRenamePayload(buffer.readUUID(), buffer.readUtf(64))
            override fun encode(buffer: RegistryFriendlyByteBuf, value: VendorRenamePayload) {
                buffer.writeUUID(value.sellerId)
                buffer.writeUtf(value.name, 64)
            }
        }
    }
}

data class VendorCollectPayload(val sellerId: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<VendorCollectPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<VendorCollectPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "shops/vendor_collect"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, VendorCollectPayload> = object : StreamCodec<RegistryFriendlyByteBuf, VendorCollectPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): VendorCollectPayload = VendorCollectPayload(buffer.readUUID())
            override fun encode(buffer: RegistryFriendlyByteBuf, value: VendorCollectPayload) {
                buffer.writeUUID(value.sellerId)
            }
        }
    }
}

data class VendorVoidPayload(val sellerId: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<VendorVoidPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<VendorVoidPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "shops/vendor_void"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, VendorVoidPayload> = object : StreamCodec<RegistryFriendlyByteBuf, VendorVoidPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): VendorVoidPayload = VendorVoidPayload(buffer.readUUID())
            override fun encode(buffer: RegistryFriendlyByteBuf, value: VendorVoidPayload) {
                buffer.writeUUID(value.sellerId)
            }
        }
    }
}

data class VendorContractSelectionPayload(val positions: List<BlockPos>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<VendorContractSelectionPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<VendorContractSelectionPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "shops/vendor_contract_selection"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, VendorContractSelectionPayload> = object : StreamCodec<RegistryFriendlyByteBuf, VendorContractSelectionPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): VendorContractSelectionPayload =
                VendorContractSelectionPayload(List(buffer.readVarInt().coerceIn(0, VendorContractConfig.maxLinks())) { buffer.readBlockPos() })

            override fun encode(buffer: RegistryFriendlyByteBuf, value: VendorContractSelectionPayload) {
                buffer.writeVarInt(value.positions.size)
                value.positions.forEach(buffer::writeBlockPos)
            }
        }
    }
}

data class VendorSellerName(val sellerId: UUID, val shopName: String)

data class VendorSellerIdsPayload(val sellers: List<VendorSellerName>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<VendorSellerIdsPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<VendorSellerIdsPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "shops/vendor_seller_ids"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, VendorSellerIdsPayload> = object : StreamCodec<RegistryFriendlyByteBuf, VendorSellerIdsPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): VendorSellerIdsPayload =
                VendorSellerIdsPayload(
                    List(buffer.readVarInt().coerceIn(0, 256)) {
                        VendorSellerName(buffer.readUUID(), buffer.readUtf(64))
                    },
                )

            override fun encode(buffer: RegistryFriendlyByteBuf, value: VendorSellerIdsPayload) {
                buffer.writeVarInt(value.sellers.size.coerceAtMost(256))
                value.sellers.take(256).forEach { seller ->
                    buffer.writeUUID(seller.sellerId)
                    buffer.writeUtf(seller.shopName, 64)
                }
            }
        }
    }
}
