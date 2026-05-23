package dev.gisketch.chowkingdom.trading

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import java.util.UUID

data class TradeRequest(
    val fromId: UUID,
    val fromName: String,
    val toId: UUID,
    val toName: String,
    val expiresAtTick: Int,
)

class TradingSession(
    val id: UUID,
    val firstId: UUID,
    val firstName: String,
    val secondId: UUID,
    val secondName: String,
    val debug: Boolean = false,
) {
    val firstOffer: SimpleContainer = SimpleContainer(TradingMenu.OFFER_SIZE)
    val secondOffer: SimpleContainer = SimpleContainer(TradingMenu.OFFER_SIZE)
    val debugInventory: SimpleContainer = SimpleContainer(Inventory.INVENTORY_SIZE)
    val menus: MutableMap<UUID, TradingMenu> = linkedMapOf()
    val chowcoins: MutableMap<UUID, Long> = linkedMapOf(firstId to 0L, secondId to 0L)
    val ready: MutableSet<UUID> = linkedSetOf()
    val confirmed: MutableSet<UUID> = linkedSetOf()
    var suppressOfferChange: Boolean = false
    var completed: Boolean = false

    init {
        if (debug) {
            ready += secondId
            confirmed += secondId
            chowcoins[secondId] = DEBUG_PARTNER_CHOWCOINS
        }
    }

    fun otherId(playerId: UUID): UUID = if (playerId == firstId) secondId else firstId

    fun name(playerId: UUID): String = if (playerId == firstId) firstName else secondName

    fun offer(playerId: UUID): SimpleContainer = if (playerId == firstId) firstOffer else secondOffer

    fun otherOffer(playerId: UUID): SimpleContainer = offer(otherId(playerId))

    fun offeredItems(playerId: UUID): List<ItemStack> = offer(playerId).itemsSnapshot()

    fun resetConsent(changedBy: UUID? = null) {
        ready.clear()
        confirmed.clear()
        if (debug) {
            ready += secondId
            confirmed += secondId
        }
        if (changedBy != null) confirmed -= changedBy
    }

    companion object {
        val DEBUG_PARTNER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
        const val DEBUG_PARTNER_NAME: String = "Debug Trader"
        const val DEBUG_PARTNER_CHOWCOINS: Long = 1_000L
    }
}

fun SimpleContainer.itemsSnapshot(): List<ItemStack> =
    (0 until containerSize).mapNotNull { index -> getItem(index).takeUnless(ItemStack::isEmpty)?.copy() }
