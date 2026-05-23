package dev.gisketch.chowkingdom.shops

import dev.gisketch.chowkingdom.ChowKingdomMod
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import snownee.jade.api.BlockAccessor
import snownee.jade.api.EntityAccessor
import snownee.jade.api.IBlockComponentProvider
import snownee.jade.api.IEntityComponentProvider
import snownee.jade.api.IServerDataProvider
import snownee.jade.api.ITooltip
import snownee.jade.api.IWailaClientRegistration
import snownee.jade.api.IWailaCommonRegistration
import snownee.jade.api.IWailaPlugin
import snownee.jade.api.JadeIds
import snownee.jade.api.WailaPlugin
import snownee.jade.api.config.IPluginConfig
import snownee.jade.api.ui.IElement
import snownee.jade.api.ui.IElementHelper
import java.util.Locale

@WailaPlugin
class ShopJadePlugin : IWailaPlugin {
    override fun register(registration: IWailaCommonRegistration) {
        registration.registerEntityDataProvider(VendorJadeProvider, Entity::class.java)
    }

    override fun registerClient(registration: IWailaClientRegistration) {
        registration.registerBlockComponent(ShopJadeProvider, StockShopBlock::class.java)
        registration.registerEntityComponent(VendorJadeProvider, Entity::class.java)
    }
}

object ShopJadeProvider : IBlockComponentProvider {
    private val UID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "shop_stock")

    override fun appendTooltip(tooltip: ITooltip, accessor: BlockAccessor, config: IPluginConfig) {
        val shop = accessor.blockEntity as? ShopBlockEntity ?: return
        if (!shop.hasDisplayItem) return
        replaceObjectNameWithPrice(tooltip, shop.price)
        tooltip.add(Component.translatable("jade.${ChowKingdomMod.MOD_ID}.seller", shop.ownerName.ifBlank { "Unknown" }))
        tooltip.add(Component.translatable("jade.${ChowKingdomMod.MOD_ID}.quantity", format(shop.stockCount.toLong())))
        if (shop.stockCount <= 0) tooltip.add(Component.literal("Out of stock").withStyle(ChatFormatting.RED))
    }

    override fun getUid(): ResourceLocation = UID

    private fun replaceObjectNameWithPrice(tooltip: ITooltip, price: Long) {
        val replaced = tooltip.replace(JadeIds.CORE_OBJECT_NAME) { listOf(priceElements(price)) }
        if (!replaced) tooltip.add(0, priceElements(price))
    }

    private fun priceElements(price: Long): List<IElement> = listOf(
        IElementHelper.get().sprite(CHOWCOIN_SPRITE, PRICE_ICON_SIZE, PRICE_ICON_SIZE),
        IElementHelper.get().text(Component.literal(" ${format(price)}").withStyle(ChatFormatting.WHITE)),
    )

    private fun format(amount: Long): String = String.format(Locale.US, "%,d", amount)

    private val CHOWCOIN_SPRITE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "chowcoin")
    private const val PRICE_ICON_SIZE = 10
}

object VendorJadeProvider : IEntityComponentProvider, IServerDataProvider<EntityAccessor> {
    val UID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "vendor")

    override fun appendServerData(data: CompoundTag, accessor: EntityAccessor) {
        val summary = VendorContractFeature.jadeSummary(accessor.level.server ?: return, accessor.entity) ?: return
        data.putString(SHOP_NAME_TAG, summary.shopName)
        data.putInt(ITEM_TYPES_TAG, summary.itemTypes)
        data.putInt(SELLERS_TAG, summary.sellerCount)
    }

    override fun appendTooltip(tooltip: ITooltip, accessor: EntityAccessor, config: IPluginConfig) {
        val data = accessor.serverData
        if (!data.contains(SHOP_NAME_TAG)) return
        val shopName = data.getString(SHOP_NAME_TAG).ifBlank { "Vendor Shop" }
        val itemTypes = data.getInt(ITEM_TYPES_TAG).coerceAtLeast(0)
        val sellers = data.getInt(SELLERS_TAG).coerceAtLeast(0)
        if (!tooltip.replace(JadeIds.CORE_OBJECT_NAME, Component.literal(shopName))) {
            tooltip.add(0, Component.literal(shopName), JadeIds.CORE_OBJECT_NAME)
        }
        tooltip.add(Component.literal("Items: $itemTypes ${plural(itemTypes, "type", "types")}"), UID)
        tooltip.add(Component.literal("Sellers: $sellers"), UID)
    }

    override fun shouldRequestData(accessor: EntityAccessor): Boolean = true

    override fun getUid(): ResourceLocation = UID

    override fun getDefaultPriority(): Int = 10_000

    private fun plural(amount: Int, singular: String, plural: String): String = if (amount == 1) singular else plural

    private const val SHOP_NAME_TAG = "ChowkingdomVendorShopName"
    private const val ITEM_TYPES_TAG = "ChowkingdomVendorItemTypes"
    private const val SELLERS_TAG = "ChowkingdomVendorSellers"
}
