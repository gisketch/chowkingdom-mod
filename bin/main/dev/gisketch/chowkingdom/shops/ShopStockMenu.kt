package dev.gisketch.chowkingdom.shops

import dev.gisketch.chowkingdom.relicroulette.RelicRouletteFeature
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

class ShopStockMenu private constructor(
    containerId: Int,
    val playerInventory: Inventory,
    val pos: BlockPos,
    val stock: ItemStack,
    val stockCount: Int,
    val price: Long,
    val ownerName: String,
    val canEdit: Boolean,
    val soldCount: Long,
    val totalRevenue: Long,
    val claimableRevenue: Long,
    private val stockContainer: Container,
    private val shop: ShopBlockEntity?,
) : AbstractContainerMenu(ShopsFeature.SHOP_STOCK_MENU.get(), containerId) {
    init {
        addSlot(StockSlot(stockContainer, shop, ShopBlockEntity.DISPLAY_SLOT, STOCK_SLOT_X, STOCK_SLOT_Y))
        addPlayerInventorySlots()
    }

    override fun stillValid(player: Player): Boolean =
        shop?.let { player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) <= 64.0 && !it.isRemoved } ?: true

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem()) return ItemStack.EMPTY
        if (shop?.isClaimedByOther(player) == true) return ItemStack.EMPTY
        val original = slot.item
        val copy = original.copy()
        when (index) {
            STOCK_SLOT_INDEX -> {
                val activeShop = shop ?: return ItemStack.EMPTY
                val moved = activeShop.removeStockStacks(minOf(activeShop.stockCount, activeShop.displayItem.maxStackSize))
                if (moved.isEmpty()) return ItemStack.EMPTY
                moved.forEach { stack -> if (!player.inventory.add(stack)) player.drop(stack, false) }
            }
            in PLAYER_INV_START until PLAYER_SLOT_END -> {
                val activeShop = shop ?: return ItemStack.EMPTY
                if (player is net.minecraft.server.level.ServerPlayer && RelicRouletteFeature.rejectTransfer(player, original, "shops")) return ItemStack.EMPTY
                val added = activeShop.addStock(player, original)
                if (added <= 0) return ItemStack.EMPTY
            }
            else -> return ItemStack.EMPTY
        }
        if (original.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        shop.claimOwner(player)
        return copy
    }

    override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
        if (shop?.isClaimedByOther(player) == true && (slotId == STOCK_SLOT_INDEX || clickType == ClickType.QUICK_MOVE)) return
        if (shop != null && slotId == STOCK_SLOT_INDEX && clickType == ClickType.PICKUP) {
            clickStockSlot(button, player)
            broadcastChanges()
            return
        }
        super.clicked(slotId, button, clickType, player)
        shop?.claimOwner(player)
    }

    private fun clickStockSlot(button: Int, player: Player) {
        val activeShop = shop ?: return
        val carried = carried
        if (!carried.isEmpty) {
            if (player is net.minecraft.server.level.ServerPlayer && RelicRouletteFeature.rejectTransfer(player, carried, "shops")) return
            val requested = if (button == 1) 1 else carried.count
            val stack = carried.copyWithCount(requested.coerceAtMost(carried.count))
            val added = activeShop.addStock(player, stack)
            if (added > 0 && !player.abilities.instabuild) {
                carried.shrink(added)
                if (carried.isEmpty) setCarried(ItemStack.EMPTY) else setCarried(carried)
            }
            return
        }
        if (activeShop.stock.isEmpty) return
        val visibleCount = minOf(activeShop.stockCount, activeShop.stock.maxStackSize)
        val requested = if (button == 1) (visibleCount + 1) / 2 else visibleCount
        setCarried(activeShop.removeItem(ShopBlockEntity.DISPLAY_SLOT, requested))
    }

    private fun addPlayerInventorySlots() {
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + (row + 1) * 9, PLAYER_INVENTORY_X + col * 18, PLAYER_INVENTORY_Y + row * 18))
            }
        }
        for (col in 0 until 9) addSlot(Slot(playerInventory, col, PLAYER_INVENTORY_X + col * 18, HOTBAR_Y))
    }

    private class StockSlot(container: Container, private val shop: ShopBlockEntity?, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun getItem(): ItemStack {
            val stack = super.getItem()
            return if (stack.isEmpty) stack else stack.copyWithCount(1)
        }

        override fun mayPlace(stack: ItemStack): Boolean {
            if (stack.isEmpty) return false
            if (RelicRouletteFeature.isTransferBlocked(stack)) return false
            val current = item
            return current.isEmpty || ItemStack.isSameItemSameComponents(current, stack)
        }

        override fun mayPickup(player: Player): Boolean = shop?.isOwner(player) ?: true

        override fun allowModification(player: Player): Boolean = shop?.isOwner(player) ?: true

        override fun getMaxStackSize(): Int = ShopBlockEntity.MAX_STOCK

        override fun getMaxStackSize(stack: ItemStack): Int = ShopBlockEntity.MAX_STOCK
    }

    companion object {
        const val STOCK_SLOT_INDEX = 0
        const val STOCK_SLOT_X = 56
        const val STOCK_SLOT_Y = 63
        const val PLAYER_INVENTORY_X = 80
        const val PLAYER_INVENTORY_Y = 208
        const val HOTBAR_Y = 266
        const val PLAYER_INV_START = 1
        const val PLAYER_SLOT_END = 37

        fun server(containerId: Int, inventory: Inventory, shop: ShopBlockEntity): ShopStockMenu =
            ShopStockMenu(
                containerId,
                inventory,
                shop.blockPos,
                shop.displayItem.copy(),
                shop.stockCount,
                shop.price,
                shop.ownerName,
                !shop.isClaimedByOther(inventory.player),
                shop.soldCount,
                shop.totalRevenue,
                shop.claimableRevenue,
                shop,
                shop,
            )

        fun client(containerId: Int, inventory: Inventory, buffer: RegistryFriendlyByteBuf): ShopStockMenu {
            val pos = buffer.readBlockPos()
            val stockCount = buffer.readVarInt()
            return ShopStockMenu(
                containerId,
                inventory,
                pos,
                ItemStack.EMPTY,
                stockCount,
                buffer.readVarLong(),
                buffer.readUtf(64),
                buffer.readBoolean(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                buffer.readVarLong(),
                SimpleContainer(1),
                null,
            )
        }
    }
}
