package dev.gisketch.chowkingdom.shops

import dev.gisketch.chowkingdom.ChowKingdomMod
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

enum class ShopViewPool(val id: String, val label: String) {
    ALL("all", "All"),
    DAILY("daily", "Daily"),
    WEEKLY("weekly", "Weekly");

    companion object {
        fun byId(id: String): ShopViewPool = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ALL
    }
}

data class ShopViewCategory(val id: String, val label: String)

data class ShopViewPoolInfo(val pool: ShopViewPool, val label: String, val resetText: String)

data class ShopViewEntry(
    val id: String,
    val categoryId: String,
    val pool: ShopViewPool,
    val stack: ItemStack,
    val stockCount: Int,
    val price: Long,
    val sellerName: String,
)

data class ShopViewModel(
    val storeId: String,
    val stockKey: String,
    val title: String,
    val subtitle: String,
    val categories: List<ShopViewCategory>,
    val pools: List<ShopViewPoolInfo>,
    val entries: List<ShopViewEntry>,
)

data class ShopViewCartLine(val entryId: String, val quantity: Int)

data class StoreShopOpenPayload(val view: ShopViewModel) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<StoreShopOpenPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<StoreShopOpenPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "shops/store_open"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StoreShopOpenPayload> = object : StreamCodec<RegistryFriendlyByteBuf, StoreShopOpenPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): StoreShopOpenPayload {
                val storeId = buffer.readUtf(64)
                val stockKey = buffer.readUtf(96)
                val title = buffer.readUtf(96)
                val subtitle = buffer.readUtf(160)
                val categories = List(buffer.readVarInt().coerceIn(0, MAX_CATEGORIES)) {
                    ShopViewCategory(buffer.readUtf(64), buffer.readUtf(96))
                }
                val pools = List(buffer.readVarInt().coerceIn(0, MAX_POOLS)) {
                    ShopViewPoolInfo(ShopViewPool.byId(buffer.readUtf(16)), buffer.readUtf(64), buffer.readUtf(64))
                }
                val entries = List(buffer.readVarInt().coerceIn(0, MAX_ENTRIES)) {
                    ShopViewEntry(
                        buffer.readUtf(96),
                        buffer.readUtf(64),
                        ShopViewPool.byId(buffer.readUtf(16)),
                        ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
                        buffer.readVarInt(),
                        buffer.readVarLong(),
                        buffer.readUtf(96),
                    )
                }
                return StoreShopOpenPayload(ShopViewModel(storeId, stockKey, title, subtitle, categories, pools, entries))
            }

            override fun encode(buffer: RegistryFriendlyByteBuf, value: StoreShopOpenPayload) {
                buffer.writeBoundedUtf(value.view.storeId, 64)
                buffer.writeBoundedUtf(value.view.stockKey, 96)
                buffer.writeBoundedUtf(value.view.title, 96)
                buffer.writeBoundedUtf(value.view.subtitle, 160)
                buffer.writeVarInt(value.view.categories.size.coerceAtMost(MAX_CATEGORIES))
                value.view.categories.take(MAX_CATEGORIES).forEach { category ->
                    buffer.writeBoundedUtf(category.id, 64)
                    buffer.writeBoundedUtf(category.label, 96)
                }
                buffer.writeVarInt(value.view.pools.size.coerceAtMost(MAX_POOLS))
                value.view.pools.take(MAX_POOLS).forEach { pool ->
                    buffer.writeBoundedUtf(pool.pool.id, 16)
                    buffer.writeBoundedUtf(pool.label, 64)
                    buffer.writeBoundedUtf(pool.resetText, 64)
                }
                buffer.writeVarInt(value.view.entries.size.coerceAtMost(MAX_ENTRIES))
                value.view.entries.take(MAX_ENTRIES).forEach { entry ->
                    buffer.writeBoundedUtf(entry.id, 96)
                    buffer.writeBoundedUtf(entry.categoryId, 64)
                    buffer.writeBoundedUtf(entry.pool.id, 16)
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, entry.stack.copyWithCount(1))
                    buffer.writeVarInt(entry.stockCount)
                    buffer.writeVarLong(entry.price)
                    buffer.writeBoundedUtf(entry.sellerName, 96)
                }
            }
        }

        private const val MAX_CATEGORIES = 64
        private const val MAX_POOLS = 3
        private const val MAX_ENTRIES = 512
    }
}

data class StoreShopCartBuyPayload(val storeId: String, val stockKey: String, val lines: List<ShopViewCartLine>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<StoreShopCartBuyPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<StoreShopCartBuyPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "shops/store_cart_buy"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, StoreShopCartBuyPayload> = object : StreamCodec<RegistryFriendlyByteBuf, StoreShopCartBuyPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): StoreShopCartBuyPayload =
                StoreShopCartBuyPayload(
                    buffer.readUtf(64),
                    buffer.readUtf(96),
                    List(buffer.readVarInt().coerceIn(0, MAX_LINES)) { ShopViewCartLine(buffer.readUtf(96), buffer.readVarInt()) },
                )

            override fun encode(buffer: RegistryFriendlyByteBuf, value: StoreShopCartBuyPayload) {
                buffer.writeBoundedUtf(value.storeId, 64)
                buffer.writeBoundedUtf(value.stockKey, 96)
                buffer.writeVarInt(value.lines.size.coerceAtMost(MAX_LINES))
                value.lines.take(MAX_LINES).forEach { line ->
                    buffer.writeBoundedUtf(line.entryId, 96)
                    buffer.writeVarInt(line.quantity)
                }
            }
        }

        private const val MAX_LINES = 100
    }
}

private fun RegistryFriendlyByteBuf.writeBoundedUtf(value: String, maxBytes: Int) {
    writeUtf(value.truncateUtf8(maxBytes), maxBytes)
}

private fun String.truncateUtf8(maxBytes: Int): String {
    if (toByteArray(Charsets.UTF_8).size <= maxBytes) return this
    val builder = StringBuilder()
    var used = 0
    for (char in this) {
        val bytes = char.toString().toByteArray(Charsets.UTF_8).size
        if (used + bytes > maxBytes) break
        builder.append(char)
        used += bytes
    }
    return builder.toString()
}
