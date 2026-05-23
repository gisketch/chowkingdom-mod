package dev.gisketch.chowkingdom.shops

import dev.gisketch.chowkingdom.relicroulette.RelicRouletteFeature
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.Containers
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.nio.charset.StandardCharsets
import java.util.UUID

class ShopBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ShopsFeature.SHOP_BLOCK_ENTITY.get(), pos, state), Container {
    private val items: NonNullList<ItemStack> = NonNullList.withSize(CONTAINER_SIZE, ItemStack.EMPTY)
    private var storedStockCount: Int = 0
    var ownerId: UUID? = null
        private set
    var ownerName: String = ""
        private set
    var price: Long = 0L
        private set
    var soldCount: Long = 0L
        private set
    var totalRevenue: Long = 0L
        private set
    var claimableRevenue: Long = 0L
        private set

    val displayItem: ItemStack
        get() {
            val template = items[DISPLAY_SLOT]
            return if (template.isEmpty) ItemStack.EMPTY else template.copyWithCount(1)
        }

    val hasDisplayItem: Boolean
        get() = !items[DISPLAY_SLOT].isEmpty

    val stock: ItemStack
        get() {
            val template = items[DISPLAY_SLOT]
            return if (template.isEmpty || storedStockCount <= 0) ItemStack.EMPTY else template.copyWithCount(storedStockCount)
        }

    val stockCount: Int
        get() = storedStockCount

    val ownerUuid: UUID?
        get() = ownerId

    val renderStyle: ShopRenderStyle
        get() = (blockState.block as? StockShopBlock)?.renderStyle ?: ShopRenderStyle.ANGLED

    override fun getContainerSize(): Int = CONTAINER_SIZE

    override fun isEmpty(): Boolean = stock.isEmpty

    override fun getItem(slot: Int): ItemStack {
        if (slot != DISPLAY_SLOT || storedStockCount <= 0) return ItemStack.EMPTY
        val template = items[DISPLAY_SLOT]
        return if (template.isEmpty) ItemStack.EMPTY else template.copyWithCount(minOf(storedStockCount, template.maxStackSize))
    }

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = slot == DISPLAY_SLOT && !RelicRouletteFeature.isTransferBlocked(stack)

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        if (slot != DISPLAY_SLOT || amount <= 0 || storedStockCount <= 0) return ItemStack.EMPTY
        val template = items[DISPLAY_SLOT]
        if (template.isEmpty) return ItemStack.EMPTY
        val removed = minOf(amount, storedStockCount, template.maxStackSize)
        storedStockCount -= removed
        if (storedStockCount <= 0) storedStockCount = 0
        setChanged()
        return template.copyWithCount(removed)
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        if (slot != DISPLAY_SLOT || storedStockCount <= 0) return ItemStack.EMPTY
        val template = items[DISPLAY_SLOT]
        if (template.isEmpty) return ItemStack.EMPTY
        val removed = minOf(storedStockCount, template.maxStackSize)
        storedStockCount -= removed
        if (storedStockCount <= 0) storedStockCount = 0
        return template.copyWithCount(removed)
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        if (slot != DISPLAY_SLOT) return
        if (RelicRouletteFeature.isTransferBlocked(stack)) return
        storedStockCount = stack.count.coerceIn(0, MAX_STOCK)
        items[slot] = if (!stack.isEmpty) stack.copyWithCount(1) else ItemStack.EMPTY
        if (items[slot].isEmpty) clearClaim()
        setChanged()
    }

    override fun getMaxStackSize(): Int = MAX_STOCK

    fun isOwner(player: Player): Boolean = ownerId == null || ownerId == player.uuid || player.isCreative

    fun isClaimedByOther(player: Player): Boolean = ownerId != null && ownerId != player.uuid && !player.isCreative

    fun claimOwner(player: Player) {
        if (ownerId != null || !hasDisplayItem) return
        ownerId = player.uuid
        ownerName = player.name.string
        setChanged()
    }

    fun addStock(player: Player, stack: ItemStack): Int {
        if (stack.isEmpty || isClaimedByOther(player)) return 0
        if (RelicRouletteFeature.isTransferBlocked(stack)) return 0
        val current = displayItem
        if (!current.isEmpty && !ItemStack.isSameItemSameComponents(current, stack)) return 0
        val remaining = MAX_STOCK - storedStockCount
        if (remaining <= 0) return 0
        val added = minOf(stack.count, remaining)
        if (current.isEmpty) {
            ownerId = player.uuid
            ownerName = player.name.string
            items[DISPLAY_SLOT] = stack.copyWithCount(1)
            storedStockCount = added
        } else {
            storedStockCount += added
        }
        if (!player.abilities.instabuild) stack.shrink(added)
        setChanged()
        return added
    }

    fun setPrice(player: Player, amount: Long): Boolean {
        if (isClaimedByOther(player)) return false
        price = amount.coerceIn(0L, MAX_PRICE)
        setChanged()
        return true
    }

    fun removeStock(player: Player): Boolean {
        if (level == null) return false
        if (!hasDisplayItem || !isOwner(player)) return false
        val template = items[DISPLAY_SLOT]
        var remaining = storedStockCount
        while (!template.isEmpty && remaining > 0) {
            val dropCount = minOf(remaining, template.maxStackSize)
            val stack = template.copyWithCount(dropCount)
            if (!player.inventory.add(stack)) player.drop(stack, false)
            remaining -= dropCount
        }
        clearContent()
        return true
    }

    fun removeStockStacks(amount: Int): List<ItemStack> {
        if (amount <= 0 || stock.isEmpty) return emptyList()
        val template = items[DISPLAY_SLOT]
        val removed = mutableListOf<ItemStack>()
        var remaining = minOf(amount, storedStockCount)
        while (!template.isEmpty && remaining > 0) {
            val stackCount = minOf(remaining, template.maxStackSize)
            removed += template.copyWithCount(stackCount)
            storedStockCount -= stackCount
            remaining -= stackCount
        }
        if (storedStockCount <= 0) storedStockCount = 0
        setChanged()
        return removed
    }

    fun recordSale(quantity: Int, total: Long, claimable: Boolean) {
        if (quantity <= 0 || total <= 0L) return
        soldCount = soldCount.saturatingAdd(quantity.toLong())
        totalRevenue = totalRevenue.saturatingAdd(total)
        if (claimable) claimableRevenue = claimableRevenue.saturatingAdd(total)
        setChanged()
    }

    fun collectRevenue(player: Player): Long {
        if (!isOwner(player) || claimableRevenue <= 0L) return 0L
        val amount = claimableRevenue
        claimableRevenue = 0L
        setChanged()
        return amount
    }

    fun debugClaimByOther(player: Player) {
        ownerId = UUID.nameUUIDFromBytes("debug-shop-owner-${player.uuid}".toByteArray(StandardCharsets.UTF_8))
        ownerName = "Debug Seller"
        setChanged()
    }

    override fun stillValid(player: Player): Boolean =
        level?.getBlockEntity(blockPos) === this && player.distanceToSqr(
            blockPos.x + 0.5,
            blockPos.y + 0.5,
            blockPos.z + 0.5,
        ) <= 64.0

    override fun clearContent() {
        items.clear()
        storedStockCount = 0
        clearClaim()
        setChanged()
    }

    private fun clearClaim() {
        ownerId = null
        ownerName = ""
        price = 0L
        soldCount = 0L
        totalRevenue = 0L
        claimableRevenue = 0L
    }

    fun dropStock(level: Level) {
        val template = items[DISPLAY_SLOT]
        var remaining = storedStockCount
        while (!template.isEmpty && remaining > 0) {
            val dropCount = minOf(remaining, template.maxStackSize)
            Containers.dropItemStack(level, blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5, template.copyWithCount(dropCount))
            remaining -= dropCount
        }
    }

    override fun setChanged() {
        super.setChanged()
        val currentLevel = level
        if (currentLevel != null && !currentLevel.isClientSide) {
            currentLevel.sendBlockUpdated(blockPos, blockState, blockState, 3)
        }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        items.clear()
        ContainerHelper.loadAllItems(tag, items, registries)
        storedStockCount = tag.getInt(STOCK_COUNT_TAG).coerceIn(0, MAX_STOCK)
        if (!tag.contains(STOCK_COUNT_TAG) && storedStockCount == 0) storedStockCount = items[DISPLAY_SLOT].count.coerceIn(0, MAX_STOCK)
        items[DISPLAY_SLOT] = if (!items[DISPLAY_SLOT].isEmpty) items[DISPLAY_SLOT].copyWithCount(1) else ItemStack.EMPTY
        ownerId = if (tag.hasUUID(OWNER_ID_TAG)) tag.getUUID(OWNER_ID_TAG) else null
        ownerName = tag.getString(OWNER_NAME_TAG)
        price = tag.getLong(PRICE_TAG).coerceIn(0L, MAX_PRICE)
        soldCount = tag.getLong(SOLD_COUNT_TAG).coerceAtLeast(0L)
        totalRevenue = tag.getLong(TOTAL_REVENUE_TAG).coerceAtLeast(0L)
        claimableRevenue = tag.getLong(CLAIMABLE_REVENUE_TAG).coerceAtLeast(0L)
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        ContainerHelper.saveAllItems(tag, items, registries)
        tag.putInt(STOCK_COUNT_TAG, storedStockCount.coerceIn(0, MAX_STOCK))
        ownerId?.let { tag.putUUID(OWNER_ID_TAG, it) }
        if (ownerName.isNotBlank()) tag.putString(OWNER_NAME_TAG, ownerName)
        tag.putLong(PRICE_TAG, price)
        tag.putLong(SOLD_COUNT_TAG, soldCount.coerceAtLeast(0L))
        tag.putLong(TOTAL_REVENUE_TAG, totalRevenue.coerceAtLeast(0L))
        tag.putLong(CLAIMABLE_REVENUE_TAG, claimableRevenue.coerceAtLeast(0L))
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveWithoutMetadata(registries)

    override fun handleUpdateTag(tag: CompoundTag, registries: HolderLookup.Provider) {
        loadAdditional(tag, registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun onDataPacket(net: Connection, pkt: ClientboundBlockEntityDataPacket, registries: HolderLookup.Provider) {
        loadWithComponents(pkt.tag, registries)
    }

    companion object {
        const val MAX_STOCK = 4096
        const val MAX_PRICE = 9_999_999_999L
        private const val CONTAINER_SIZE = 1
        const val DISPLAY_SLOT = 0
        private const val OWNER_ID_TAG = "OwnerId"
        private const val OWNER_NAME_TAG = "OwnerName"
        private const val PRICE_TAG = "Price"
        private const val STOCK_COUNT_TAG = "StockCount"
        private const val SOLD_COUNT_TAG = "SoldCount"
        private const val TOTAL_REVENUE_TAG = "TotalRevenue"
        private const val CLAIMABLE_REVENUE_TAG = "ClaimableRevenue"
    }
}

private fun Long.saturatingAdd(other: Long): Long =
    if (other <= 0L) this else if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
