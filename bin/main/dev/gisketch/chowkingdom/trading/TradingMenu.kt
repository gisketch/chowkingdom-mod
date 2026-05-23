package dev.gisketch.chowkingdom.trading

import dev.gisketch.chowkingdom.relicroulette.RelicRouletteFeature
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.UUID

class TradingMenu private constructor(
    containerId: Int,
    val playerInventory: Inventory,
    val sessionId: UUID,
    val selfId: UUID,
    val selfName: String,
    val otherName: String,
    val debug: Boolean,
    private val session: TradingSession?,
    private val ownOffer: SimpleContainer,
    private val otherOffer: SimpleContainer,
    private val otherInventory: Container,
) : AbstractContainerMenu(TradingFeature.TRADE_MENU.get(), containerId) {

    init {
        addOfferSlots(ownOffer, LEFT_X, OwnOfferSlot::class.java)
        addOfferSlots(otherOffer, RIGHT_X, ReadOnlySlot::class.java)
        addInventorySlots(playerInventory, LEFT_X, Slot::class.java)
        addInventorySlots(otherInventory, RIGHT_X, ReadOnlySlot::class.java)
        session?.let { activeSession ->
            activeSession.menus[selfId] = this
            ownOffer.addListener { TradingManager.onOfferChanged(activeSession, selfId) }
        }
    }

    override fun stillValid(player: Player): Boolean = session?.let { it.menus[player.uuid] === this } ?: true

    override fun slotsChanged(container: Container) {
        super.slotsChanged(container)
        val activeSession = session ?: return
        if (container === ownOffer) TradingManager.onOfferChanged(activeSession, selfId)
    }

    override fun removed(player: Player) {
        super.removed(player)
        val serverPlayer = player as? ServerPlayer ?: return
        TradingManager.onMenuClosed(serverPlayer, sessionId)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
        if (!slot.hasItem() || slot is ReadOnlySlot) return ItemStack.EMPTY
        val original = slot.item
        val copy = original.copy()
        when (index) {
            in OWN_OFFER_START until OTHER_OFFER_START -> {
                if (!moveItemStackTo(original, PLAYER_INV_START, OTHER_INV_START, true)) return ItemStack.EMPTY
            }
            in PLAYER_INV_START until OTHER_INV_START -> {
                if (!moveItemStackTo(original, OWN_OFFER_START, OTHER_OFFER_START, false)) return ItemStack.EMPTY
            }
            else -> return ItemStack.EMPTY
        }
        if (original.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return copy
    }

    override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
        if (slotId in OTHER_OFFER_START until PLAYER_INV_START || slotId >= OTHER_INV_START) return
        super.clicked(slotId, button, clickType, player)
    }

    fun isOtherInventorySlot(slot: Slot): Boolean = slots.indexOf(slot) >= OTHER_INV_START

    private fun addOfferSlots(container: SimpleContainer, startX: Int, kind: Class<out Slot>) {
        repeat(3) { row ->
            repeat(9) { col ->
                addSlot(createSlot(kind, container, col + row * 9, startX + 8 + col * 18, OFFER_Y + row * 18))
            }
        }
    }

    private fun addInventorySlots(container: Container, startX: Int, kind: Class<out Slot>) {
        repeat(3) { row ->
            repeat(9) { col ->
                addSlot(createSlot(kind, container, col + (row + 1) * 9, startX + 8 + col * 18, INVENTORY_Y + row * 18))
            }
        }
        repeat(9) { col ->
            addSlot(createSlot(kind, container, col, startX + 8 + col * 18, HOTBAR_Y))
        }
    }

    private fun createSlot(kind: Class<out Slot>, container: Container, index: Int, x: Int, y: Int): Slot =
        if (kind == ReadOnlySlot::class.java) ReadOnlySlot(container, index, x, y) else OwnOfferSlot(container, index, x, y)

    class OwnOfferSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = !RelicRouletteFeature.isTransferBlocked(stack)
    }

    class ReadOnlySlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = false
        override fun mayPickup(player: Player): Boolean = false
        override fun allowModification(player: Player): Boolean = false
    }

    companion object {
        const val OFFER_SIZE = 27
        const val PANEL_WIDTH = 176
        const val PANEL_GAP = 12
        const val PAPER_DOLL_WIDTH = 92
        const val SIDE_GAP = 16
        const val LEFT_X = PAPER_DOLL_WIDTH + SIDE_GAP
        const val RIGHT_X = LEFT_X + PANEL_WIDTH + PANEL_GAP
        const val RIGHT_PAPER_DOLL_X = RIGHT_X + PANEL_WIDTH + SIDE_GAP
        const val OFFER_Y = 18
        const val INVENTORY_Y = 84
        const val HOTBAR_Y = 142
        const val IMAGE_WIDTH = PANEL_WIDTH * 2 + PANEL_GAP + PAPER_DOLL_WIDTH * 2 + SIDE_GAP * 2
        const val IMAGE_HEIGHT = 242
        const val OWN_OFFER_START = 0
        const val OTHER_OFFER_START = 27
        const val PLAYER_INV_START = 54
        const val OTHER_INV_START = 90

        fun server(containerId: Int, inventory: Inventory, session: TradingSession, viewerId: UUID): TradingMenu {
            val otherId = session.otherId(viewerId)
            val otherInventory = if (session.debug) session.debugInventory else TradingManager.session(session.id)
                ?.let { net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer()?.playerList?.getPlayer(otherId)?.inventory }
                ?: SimpleContainer(Inventory.INVENTORY_SIZE)
            return TradingMenu(
                containerId,
                inventory,
                session.id,
                viewerId,
                session.name(viewerId),
                session.name(otherId),
                session.debug,
                session,
                session.offer(viewerId),
                session.otherOffer(viewerId),
                otherInventory,
            )
        }

        fun client(containerId: Int, inventory: Inventory, buffer: RegistryFriendlyByteBuf): TradingMenu {
            val sessionId = buffer.readUUID()
            val selfId = buffer.readUUID()
            val selfName = buffer.readUtf(32)
            val otherName = buffer.readUtf(32)
            val debug = buffer.readBoolean()
            return TradingMenu(
                containerId,
                inventory,
                sessionId,
                selfId,
                selfName,
                otherName,
                debug,
                null,
                SimpleContainer(OFFER_SIZE),
                SimpleContainer(OFFER_SIZE),
                SimpleContainer(Inventory.INVENTORY_SIZE),
            )
        }
    }
}
