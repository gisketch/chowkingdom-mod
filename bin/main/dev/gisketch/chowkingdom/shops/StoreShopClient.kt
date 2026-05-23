package dev.gisketch.chowkingdom.shops

import com.mojang.blaze3d.systems.RenderSystem
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.wallets.ChowcoinClientState
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.PacketDistributor
import java.util.Locale

object StoreShopClient {
    @JvmStatic
    fun open(payload: StoreShopOpenPayload) {
        Minecraft.getInstance().setScreen(StoreShopScreen(payload.view))
    }
}

private data class StoreEntranceStyle(
    val delayMs: Int,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val scaleFrom: Float = 1.0f,
    val durationMs: Int = 260,
)

private class StoreShopScreen(private val view: ShopViewModel) : Screen(Component.literal(view.title)) {
    private val cart: MutableMap<String, Int> = linkedMapOf()
    private var selectedCategory: String? = null
    private var pool = ShopViewPool.ALL
    private var itemScroll = 0
    private var cartScroll = 0
    private var categoryScroll = 0
    private var renderAlpha = 1.0f
    private val openedAtMs = Util.getMillis()

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick)
        renderColumn(guiGraphics, 0, col1()) { renderLeft(guiGraphics, mouseX, mouseY) }
        renderColumn(guiGraphics, 1, col2()) { renderStockList(guiGraphics, mouseX, mouseY) }
        renderColumn(guiGraphics, 2, col3()) { renderCart(guiGraphics, mouseX, mouseY) }
    }

    private fun renderColumn(guiGraphics: GuiGraphics, index: Int, rect: Rect, renderContent: () -> Unit) {
        withEntrance(guiGraphics, StoreEntranceStyle(index * COLUMN_STAGGER_MS, offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            renderNineSlice(guiGraphics, FRAME2_TEXTURE, rect, FRAME2_WIDTH, FRAME2_HEIGHT, FRAME2_CORNER, FRAME2_DEST_CORNER, 1.0f)
            renderContent()
        }
    }

    private fun renderLeft(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        withEntrance(guiGraphics, StoreEntranceStyle(columnContentDelay(0, 0), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            val hero = heroRect()
            renderNineSlice(guiGraphics, ITEM_FRAME_TEXTURE, hero, ITEM_FRAME_SIZE, ITEM_FRAME_SIZE, ITEM_FRAME_CORNER, 12, 0.78f)
            drawCenteredCkdm(guiGraphics, fitText(view.title, hero.width - 24, CKDM_BOLD), hero.x, hero.y + 22, hero.width, WHITE)
            drawCenteredCkdm(guiGraphics, fitText(view.subtitle, hero.width - 24, CKDM_SMALL), hero.x, hero.y + 44, hero.width, colorAlpha(WHITE, 0.58f), CKDM_SMALL)
        }
        withEntrance(guiGraphics, StoreEntranceStyle(columnContentDelay(0, 1), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) { renderBalance(guiGraphics) }
        renderCategoryFilters(guiGraphics, mouseX, mouseY)
    }

    private fun renderBalance(guiGraphics: GuiGraphics) {
        val rect = balanceRect()
        val top = rect.y + WIDGET_TOP_PAD
        renderNineSlice(guiGraphics, ITEM_FRAME_TEXTURE, rect, ITEM_FRAME_SIZE, ITEM_FRAME_SIZE, ITEM_FRAME_CORNER, 12, 0.78f)
        drawCkdm(guiGraphics, "YOU HAVE", rect.x + 12, top + 8, colorAlpha(WHITE, 0.5f), CKDM_SMALL)
        renderChowcoin(guiGraphics, rect.x + 12, top + 23, 12)
        drawCkdm(guiGraphics, format(ChowcoinClientState.displayBalance()), rect.x + 29, top + 25, WHITE, CKDM_BOLD)
    }

    private fun renderCategoryFilters(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        withEntrance(guiGraphics, StoreEntranceStyle(columnContentDelay(0, 2), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            drawCkdm(guiGraphics, "CATEGORIES", col1().x + PAD, categoryHeaderY(), WHITE, CKDM_BOLD)
        }
        visibleCategories().forEachIndexed { index, category ->
            withEntrance(guiGraphics, StoreEntranceStyle(columnContentDelay(0, 3 + index), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
                val rect = categoryButtonRect(index)
                val selected = (category.id == nullCategoryId() && selectedCategory == null) || category.id == selectedCategory
                renderButton(guiGraphics, rect, category.label, if (selected) GREEN_BUTTON_TEXTURE else GRAY_BUTTON_TEXTURE, if (selected) GREEN_BUTTON_HOVER_TEXTURE else GRAY_BUTTON_HOVER_TEXTURE, mouseX, mouseY, true)
            }
        }
    }

    private fun renderStockList(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        withEntrance(guiGraphics, StoreEntranceStyle(columnContentDelay(1, 0), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            poolButtons().forEach { (candidate, rect) ->
                renderButton(guiGraphics, rect, candidate.label, if (candidate == pool) GREEN_BUTTON_TEXTURE else GRAY_BUTTON_TEXTURE, if (candidate == pool) GREEN_BUTTON_HOVER_TEXTURE else GRAY_BUTTON_HOVER_TEXTURE, mouseX, mouseY, true)
            }
        }
        val rows = stockRows()
        val area = itemListRect()
        guiGraphics.enableScissor(area.x, area.y, area.right, area.bottom)
        rows.drop(itemScroll).take(visibleItemRows()).forEachIndexed { index, row ->
            val entrance = StoreEntranceStyle(columnContentDelay(1, 1) + STOCK_LIST_DELAY_MS + index * STOCK_ROW_STAGGER_MS, offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)
            withEntrance(guiGraphics, entrance) {
                when (row) {
                    is StoreStockRow.Header -> renderPoolHeader(guiGraphics, itemRowRect(index), row)
                    is StoreStockRow.Item -> renderItemRow(guiGraphics, itemRowRect(index), row.entry, mouseX, mouseY, itemEntranceScale(entrance))
                }
            }
        }
        guiGraphics.disableScissor()
    }

    private fun renderItemRow(guiGraphics: GuiGraphics, rect: Rect, entry: ShopViewEntry, mouseX: Int, mouseY: Int, iconScale: Float) {
        renderNineSlice(guiGraphics, ITEM_FRAME_TEXTURE, rect, ITEM_FRAME_SIZE, ITEM_FRAME_SIZE, ITEM_FRAME_CORNER, ITEM_FRAME_CORNER, 0.82f)
        renderScaledItem(guiGraphics, entry.stack, rect.x + 10, rect.y + 10, ITEM_ICON_SIZE, if (entry.stockCount <= 0) 0.5f else 1.0f, iconScale)
        drawCkdm(guiGraphics, fitText(entry.stack.hoverName.string, rect.width / 2, CKDM_SMALL), rect.x + 54, rect.y + 8, WHITE, CKDM_SMALL)
        drawCkdm(guiGraphics, categoryLabel(entry.categoryId).uppercase(Locale.ROOT), rect.x + 54, rect.y + 20, colorAlpha(WHITE, 0.5f), CKDM_SMALL)
        drawCkdm(guiGraphics, stockLabel(entry.stockCount), rect.x + 54, rect.y + 32, if (entry.stockCount <= 0) DISABLED else WHITE, CKDM_SMALL)
        val right = rect.right - 12
        renderChowcoin(guiGraphics, right - 142, rect.y + 21, 12)
        drawCkdm(guiGraphics, format(entry.price), right - 126, rect.y + 23, WHITE, CKDM_BOLD)
        renderButton(guiGraphics, rowMinusRect(rect), "-", GRAY_BUTTON_TEXTURE, GRAY_BUTTON_HOVER_TEXTURE, mouseX, mouseY, cartQty(entry) > 0)
        drawCenteredCkdm(guiGraphics, cartQty(entry).toString(), rowQtyRect(rect).x, rowQtyRect(rect).y + 6, rowQtyRect(rect).width, WHITE)
        renderButton(guiGraphics, rowPlusRect(rect), "+", GRAY_BUTTON_TEXTURE, GRAY_BUTTON_HOVER_TEXTURE, mouseX, mouseY, canAddToCart(entry))
    }

    private fun renderPoolHeader(guiGraphics: GuiGraphics, rect: Rect, row: StoreStockRow.Header) {
        val headerRect = Rect(rect.x, rect.y + 13, rect.width, 30)
        renderNineSlice(guiGraphics, ITEM_FRAME_TEXTURE, headerRect, ITEM_FRAME_SIZE, ITEM_FRAME_SIZE, ITEM_FRAME_CORNER, 8, 0.72f)
        drawCkdm(guiGraphics, row.label, headerRect.x + 12, headerRect.y + 10, WHITE, CKDM_BOLD)
        if (row.resetText.isNotBlank()) drawCkdm(guiGraphics, row.resetText, headerRect.x + 100, headerRect.y + 10, colorAlpha(WHITE, 0.56f), CKDM_SMALL)
    }

    private fun renderCart(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        withEntrance(guiGraphics, StoreEntranceStyle(columnContentDelay(2, 0), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            drawCenteredCkdm(guiGraphics, "CART", col3().x, col3().y + PAD, col3().width, WHITE)
        }
        val total = cartTotal()
        val canBuyNow = canBuyNow(total)
        withEntrance(guiGraphics, StoreEntranceStyle(columnContentDelay(2, 1), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            val rect = totalRect()
            val top = rect.y + WIDGET_TOP_PAD
            renderNineSlice(guiGraphics, ITEM_FRAME_TEXTURE, rect, ITEM_FRAME_SIZE, ITEM_FRAME_SIZE, ITEM_FRAME_CORNER, 12, 0.78f)
            drawCkdm(guiGraphics, "OVERALL COST", rect.x + 12, top + 8, colorAlpha(WHITE, 0.5f), CKDM_SMALL)
            renderChowcoin(guiGraphics, rect.x + 12, top + 25, 12)
            drawCkdm(guiGraphics, format(total), rect.x + 29, top + 27, WHITE, CKDM_BOLD)
        }
        withEntrance(guiGraphics, StoreEntranceStyle(columnContentDelay(2, 2), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            renderButton(guiGraphics, buyNowRect(), "BUY NOW", GREEN_BUTTON_TEXTURE, GREEN_BUTTON_HOVER_TEXTURE, mouseX, mouseY, canBuyNow)
        }
        val area = cartListRect()
        guiGraphics.enableScissor(area.x, area.y, area.right, area.bottom)
        cartEntries().drop(cartScroll).take(visibleCartRows()).forEachIndexed { index, entry ->
            withEntrance(guiGraphics, StoreEntranceStyle(columnContentDelay(2, 3 + index), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
                renderCartRow(guiGraphics, cartRowRect(index), entry, mouseX, mouseY)
            }
        }
        guiGraphics.disableScissor()
    }

    private fun renderCartRow(guiGraphics: GuiGraphics, rect: Rect, entry: ShopViewEntry, mouseX: Int, mouseY: Int) {
        renderNineSlice(guiGraphics, ITEM_FRAME_TEXTURE, rect, ITEM_FRAME_SIZE, ITEM_FRAME_SIZE, ITEM_FRAME_CORNER, 8, 0.7f)
        renderScaledItem(guiGraphics, entry.stack, rect.x + 8, rect.y + 7, 22, 1.0f)
        drawCkdm(guiGraphics, fitText(entry.stack.hoverName.string, rect.width - 86, CKDM_SMALL), rect.x + 36, rect.y + 8, WHITE, CKDM_SMALL)
        drawCkdm(guiGraphics, cartQty(entry).toString(), rect.x + 36, rect.y + 22, GOLD, CKDM_SMALL)
        renderButton(guiGraphics, removeCartRect(rect), "X", RED_BUTTON_TEXTURE, RED_BUTTON_HOVER_TEXTURE, mouseX, mouseY, true)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)
        val x = mouseX.toInt()
        val y = mouseY.toInt()
        visibleCategories().forEachIndexed { index, category ->
            if (categoryButtonRect(index).contains(x, y)) {
                selectedCategory = category.id.takeIf { it != nullCategoryId() }
                itemScroll = 0
                click()
                return true
            }
        }
        poolButtons().forEach { (candidate, rect) ->
            if (rect.contains(x, y)) {
                pool = candidate
                itemScroll = 0
                click()
                return true
            }
        }
        stockRows().drop(itemScroll).take(visibleItemRows()).forEachIndexed { index, row ->
            val entry = (row as? StoreStockRow.Item)?.entry ?: return@forEachIndexed
            val row = itemRowRect(index)
            when {
                rowMinusRect(row).contains(x, y) && cartQty(entry) > 0 -> {
                    setCartQty(entry, cartQty(entry) - 1)
                    click()
                    return true
                }
                rowPlusRect(row).contains(x, y) && canAddToCart(entry) -> {
                    setCartQty(entry, cartQty(entry) + 1)
                    click()
                    return true
                }
            }
        }
        cartEntries().drop(cartScroll).take(visibleCartRows()).forEachIndexed { index, entry ->
            if (removeCartRect(cartRowRect(index)).contains(x, y)) {
                setCartQty(entry, 0)
                click()
                return true
            }
        }
        if (buyNowRect().contains(x, y) && canBuyNow(cartTotal())) {
            click()
            PacketDistributor.sendToServer(StoreShopCartBuyPayload(view.storeId, view.stockKey, cartEntries().map { ShopViewCartLine(it.id, cartQty(it)) }))
            cart.clear()
            minecraft?.setScreen(null)
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val x = mouseX.toInt()
        val y = mouseY.toInt()
        when {
            itemListRect().contains(x, y) -> itemScroll = (itemScroll - scrollY.toInt()).coerceIn(0, (stockRows().size - visibleItemRows()).coerceAtLeast(0))
            cartListRect().contains(x, y) -> cartScroll = (cartScroll - scrollY.toInt()).coerceIn(0, (cartEntries().size - visibleCartRows()).coerceAtLeast(0))
            categoryListRect().contains(x, y) -> categoryScroll = (categoryScroll - scrollY.toInt()).coerceIn(0, (categories().size - visibleCategoryRows()).coerceAtLeast(0))
            else -> return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        }
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == 256) {
            minecraft?.setScreen(null)
            return true
        }
        if ((keyCode == 257 || keyCode == 335) && canBuyNow(cartTotal())) {
            PacketDistributor.sendToServer(StoreShopCartBuyPayload(view.storeId, view.stockKey, cartEntries().map { ShopViewCartLine(it.id, cartQty(it)) }))
            minecraft?.setScreen(null)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun filteredEntries(): List<ShopViewEntry> = view.entries.asSequence()
        .filter { selectedCategory == null || it.categoryId == selectedCategory }
        .filter { pool == ShopViewPool.ALL || it.pool == pool }
        .sortedWith(compareByDescending<ShopViewEntry> { it.stockCount }.thenBy { it.stack.hoverName.string.lowercase(Locale.ROOT) })
        .toList()

    private fun stockRows(): List<StoreStockRow> {
        val entries = filteredEntries()
        val visiblePools = if (pool == ShopViewPool.ALL) listOf(ShopViewPool.DAILY, ShopViewPool.WEEKLY, ShopViewPool.ALL) else listOf(pool)
        return visiblePools.flatMap { visiblePool ->
            val poolEntries = entries.filter { it.pool == visiblePool }
            if (poolEntries.isEmpty()) emptyList() else listOf(poolHeader(visiblePool)) + poolEntries.map(StoreStockRow::Item)
        }
    }

    private fun poolHeader(pool: ShopViewPool): StoreStockRow.Header {
        val info = view.pools.firstOrNull { it.pool == pool }
        val resetText = info?.resetText.orEmpty().takeIf { pool != ShopViewPool.ALL }.orEmpty()
        return StoreStockRow.Header(pool.label, resetText)
    }

    private fun categories(): List<ShopViewCategory> = listOf(ShopViewCategory(nullCategoryId(), "ALL")) + view.categories
    private fun visibleCategories(): List<ShopViewCategory> = categories().drop(categoryScroll).take(visibleCategoryRows())
    private fun cartEntries(): List<ShopViewEntry> = view.entries.filter { cartQty(it) > 0 }
    private fun cartQty(entry: ShopViewEntry): Int = cart[entry.id] ?: 0
    private fun setCartQty(entry: ShopViewEntry, qty: Int) {
        val value = qty.coerceIn(0, entry.stockCount.coerceAtLeast(0))
        if (value <= 0) cart.remove(entry.id) else cart[entry.id] = value
    }
    private fun cartTotal(): Long = cartEntries().fold(0L) { sum, entry -> sum.saturatingAdd(entry.price.saturatingMultiply(cartQty(entry).toLong())) }
    private fun canBuyNow(total: Long): Boolean = total > 0L && ChowcoinClientState.balance() >= total
    private fun canAddToCart(entry: ShopViewEntry): Boolean = entry.stockCount > 0 && cartQty(entry) < entry.stockCount && ChowcoinClientState.balance() >= cartTotal().saturatingAdd(entry.price.coerceAtLeast(0L))
    private fun nullCategoryId(): String = ""
    private fun categoryLabel(id: String): String = view.categories.firstOrNull { it.id == id }?.label ?: "All"
    private fun stockLabel(stockCount: Int): String = if (stockCount <= 0) "OUT OF STOCK" else format(stockCount)

    private fun renderButton(guiGraphics: GuiGraphics, rect: Rect, label: String, texture: ResourceLocation, hoverTexture: ResourceLocation, mouseX: Int, mouseY: Int, active: Boolean) {
        val hovered = active && rect.contains(mouseX, mouseY)
        val tex = if (hovered) hoverTexture else texture
        val texSize = if (hovered) 10 else 8
        val sourceCorner = if (hovered) 3 else 2
        renderNineSlice(guiGraphics, if (active) tex else GRAY_BUTTON_TEXTURE, rect, texSize, texSize, sourceCorner, 4, if (active) 1.0f else 0.55f)
        drawCenteredCkdm(guiGraphics, label, rect.x, rect.y + (rect.height - font.lineHeight) / 2 + 1, rect.width, if (active) WHITE else DISABLED)
    }

    private fun renderNineSlice(guiGraphics: GuiGraphics, texture: ResourceLocation, rect: Rect, textureWidth: Int, textureHeight: Int, sourceCorner: Int, destinationCorner: Int, alpha: Float) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha * renderAlpha)
        val edgeX = textureWidth - sourceCorner
        val edgeY = textureHeight - sourceCorner
        val middleWidth = textureWidth - sourceCorner * 2
        val middleHeight = textureHeight - sourceCorner * 2
        val innerWidth = (rect.width - destinationCorner * 2).coerceAtLeast(0)
        val innerHeight = (rect.height - destinationCorner * 2).coerceAtLeast(0)
        blit(guiGraphics, texture, Rect(rect.x, rect.y, destinationCorner, destinationCorner), 0, 0, sourceCorner, sourceCorner, textureWidth, textureHeight)
        blit(guiGraphics, texture, Rect(rect.x + destinationCorner, rect.y, innerWidth, destinationCorner), sourceCorner, 0, middleWidth, sourceCorner, textureWidth, textureHeight)
        blit(guiGraphics, texture, Rect(rect.right - destinationCorner, rect.y, destinationCorner, destinationCorner), edgeX, 0, sourceCorner, sourceCorner, textureWidth, textureHeight)
        blit(guiGraphics, texture, Rect(rect.x, rect.y + destinationCorner, destinationCorner, innerHeight), 0, sourceCorner, sourceCorner, middleHeight, textureWidth, textureHeight)
        blit(guiGraphics, texture, Rect(rect.x + destinationCorner, rect.y + destinationCorner, innerWidth, innerHeight), sourceCorner, sourceCorner, middleWidth, middleHeight, textureWidth, textureHeight)
        blit(guiGraphics, texture, Rect(rect.right - destinationCorner, rect.y + destinationCorner, destinationCorner, innerHeight), edgeX, sourceCorner, sourceCorner, middleHeight, textureWidth, textureHeight)
        blit(guiGraphics, texture, Rect(rect.x, rect.bottom - destinationCorner, destinationCorner, destinationCorner), 0, edgeY, sourceCorner, sourceCorner, textureWidth, textureHeight)
        blit(guiGraphics, texture, Rect(rect.x + destinationCorner, rect.bottom - destinationCorner, innerWidth, destinationCorner), sourceCorner, edgeY, middleWidth, sourceCorner, textureWidth, textureHeight)
        blit(guiGraphics, texture, Rect(rect.right - destinationCorner, rect.bottom - destinationCorner, destinationCorner, destinationCorner), edgeX, edgeY, sourceCorner, sourceCorner, textureWidth, textureHeight)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun blit(guiGraphics: GuiGraphics, texture: ResourceLocation, rect: Rect, sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int, textureWidth: Int, textureHeight: Int) {
        if (rect.width <= 0 || rect.height <= 0) return
        guiGraphics.blit(texture, rect.x, rect.y, rect.width, rect.height, sourceX.toFloat(), sourceY.toFloat(), sourceWidth, sourceHeight, textureWidth, textureHeight)
    }

    private fun renderChowcoin(guiGraphics: GuiGraphics, x: Int, y: Int, size: Int) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, renderAlpha)
        guiGraphics.blit(CHOWCOIN_TEXTURE, x, y, size, size, 0.0f, 0.0f, 16, 16, 16, 16)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun renderScaledItem(guiGraphics: GuiGraphics, stack: ItemStack, x: Int, y: Int, size: Int, alpha: Float, scaleMultiplier: Float = 1.0f) {
        if (scaleMultiplier <= 0.0f) return
        val scale = size / 16.0f * scaleMultiplier
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha * renderAlpha)
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(x + size / 2.0f, y + size / 2.0f, 0.0f)
        pose.scale(scale, scale, 1.0f)
        pose.translate(-8.0f, -8.0f, 0.0f)
        guiGraphics.renderItem(stack, 0, 0)
        pose.popPose()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun drawCkdm(guiGraphics: GuiGraphics, text: String, x: Int, y: Int, color: Int, fontId: ResourceLocation) {
        if (renderAlpha <= MIN_TEXT_RENDER_ALPHA) return
        guiGraphics.drawString(font, ckdmText(text, fontId), x, y, colorWithRenderAlpha(color), false)
    }

    private fun drawCenteredCkdm(guiGraphics: GuiGraphics, text: String, x: Int, y: Int, width: Int, color: Int, fontId: ResourceLocation = CKDM_BOLD) {
        if (renderAlpha <= MIN_TEXT_RENDER_ALPHA) return
        val component = ckdmText(text, fontId)
        guiGraphics.drawString(font, component, x + (width - font.width(component)) / 2, y, colorWithRenderAlpha(color), false)
    }

    private fun ckdmText(text: String, fontId: ResourceLocation): Component =
        Component.literal(text.uppercase(Locale.ROOT)).withStyle { style -> style.withFont(fontId) }

    private fun fitText(text: String, maxWidth: Int, fontId: ResourceLocation): String {
        if (font.width(ckdmText(text, fontId)) <= maxWidth) return text
        var value = text
        while (value.isNotEmpty() && font.width(ckdmText("$value...", fontId)) > maxWidth) value = value.dropLast(1)
        return "$value..."
    }

    private fun click() {
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.45f))
    }

    private fun withEntrance(guiGraphics: GuiGraphics, style: StoreEntranceStyle, render: () -> Unit) {
        val eased = entranceProgress(style)
        val previousAlpha = renderAlpha
        renderAlpha *= eased
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, renderAlpha)
        guiGraphics.pose().pushPose()
        guiGraphics.pose().translate(style.offsetX * (1.0f - eased), style.offsetY * (1.0f - eased), 0.0f)
        if (style.scaleFrom != 1.0f) {
            val scale = style.scaleFrom + (1.0f - style.scaleFrom) * eased
            guiGraphics.pose().scale(scale, scale, 1.0f)
        }
        render()
        guiGraphics.pose().popPose()
        renderAlpha = previousAlpha
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, renderAlpha)
    }

    private fun entranceProgress(style: StoreEntranceStyle): Float {
        val elapsed = (Util.getMillis() - openedAtMs - style.delayMs).toFloat()
        val linear = (elapsed / style.durationMs.coerceAtLeast(1)).coerceIn(0.0f, 1.0f)
        val inverse = 1.0f - linear
        return 1.0f - inverse * inverse * inverse
    }

    private fun itemEntranceScale(style: StoreEntranceStyle): Float {
        val elapsed = (Util.getMillis() - openedAtMs - style.delayMs).toFloat()
        val progress = (elapsed / ITEM_ICON_BOUNCE_DURATION_MS).coerceIn(0.0f, 1.0f)
        if (progress <= 0.0f) return 0.0f
        val shifted = progress - 1.0f
        val overshoot = 1.70158f
        return (1.0f + (overshoot + 1.0f) * shifted * shifted * shifted + overshoot * shifted * shifted).coerceAtMost(ITEM_ICON_MAX_SCALE)
    }

    private fun columnContentDelay(column: Int, row: Int): Int = column * COLUMN_STAGGER_MS + COLUMN_CONTENT_DELAY_MS + row * CONTENT_STAGGER_MS

    private fun layout(): Layout {
        val margin = 12
        val gap = 8
        val total = width - margin * 2 - gap * 2
        val unit = total / 4
        val c1 = Rect(margin, margin, unit, height - margin * 2)
        val c2 = Rect(c1.right + gap, margin, unit * 2, c1.height)
        val c3 = Rect(c2.right + gap, margin, width - margin - (c2.right + gap), c1.height)
        return Layout(c1, c2, c3)
    }

    private fun col1(): Rect = layout().c1
    private fun col2(): Rect = layout().c2
    private fun col3(): Rect = layout().c3
    private fun heroRect(): Rect = col1().let { Rect(it.x + PAD, it.y + PAD, it.width - PAD * 2, 86) }
    private fun balanceRect(): Rect = heroRect().let { Rect(col1().x + PAD, it.bottom + 8, col1().width - PAD * 2, 52) }
    private fun categoryHeaderY(): Int = balanceRect().bottom + 12
    private fun categoryListRect(): Rect = Rect(col1().x + PAD, categoryHeaderY() + 15, col1().width - PAD * 2, col1().bottom - categoryHeaderY() - PAD - 15)
    private fun categoryButtonRect(index: Int): Rect = categoryListRect().let { Rect(it.x, it.y + index * 24, it.width, 20) }
    private fun visibleCategoryRows(): Int = (categoryListRect().height / 24).coerceAtLeast(1)
    private fun poolButtons(): List<Pair<ShopViewPool, Rect>> = ShopViewPool.entries.mapIndexed { index, candidate ->
        val area = poolTabsRect()
        val gap = 6
        val tabWidth = (area.width - gap * 2) / 3
        candidate to Rect(area.x + index * (tabWidth + gap), area.y, tabWidth, area.height)
    }
    private fun poolTabsRect(): Rect = col2().let { Rect(it.x + PAD, it.y + PAD + 6, it.width - PAD * 2, 20) }
    private fun itemListRect(): Rect = col2().let { Rect(it.x + PAD, it.y + 54, it.width - PAD * 2, it.height - 66) }
    private fun itemRowRect(index: Int): Rect = itemListRect().let { Rect(it.x, it.y + index * 62, it.width, 56) }
    private fun visibleItemRows(): Int = (itemListRect().height / 62).coerceAtLeast(1)
    private fun rowMinusRect(row: Rect): Rect = Rect(row.right - 74, row.y + 19, 18, 16)
    private fun rowQtyRect(row: Rect): Rect = Rect(row.right - 55, row.y + 18, 28, 18)
    private fun rowPlusRect(row: Rect): Rect = Rect(row.right - 26, row.y + 19, 18, 16)
    private fun totalRect(): Rect = col3().let { Rect(it.x + PAD, it.y + 36, it.width - PAD * 2, 58) }
    private fun buyNowRect(): Rect = totalRect().let { Rect(it.x, it.bottom + 8, it.width, 28) }
    private fun cartListRect(): Rect = col3().let { Rect(it.x + PAD, buyNowRect().bottom + 12, it.width - PAD * 2, it.bottom - buyNowRect().bottom - 24) }
    private fun cartRowRect(index: Int): Rect = cartListRect().let { Rect(it.x, it.y + index * 44, it.width, 38) }
    private fun visibleCartRows(): Int = (cartListRect().height / 44).coerceAtLeast(1)
    private fun removeCartRect(row: Rect): Rect = Rect(row.right - 28, row.y + 8, 20, 20)

    private fun format(amount: Long): String = String.format(Locale.US, "%,d", amount)
    private fun format(amount: Int): String = String.format(Locale.US, "%,d", amount)
    private fun colorAlpha(color: Int, alphaFactor: Float): Int = ((((color ushr 24) and 0xFF) * alphaFactor).toInt().coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
    private fun colorWithRenderAlpha(color: Int): Int = colorAlpha(color, renderAlpha)
    private fun Long.saturatingMultiply(other: Long): Long = if (this <= 0L || other <= 0L) 0L else if (this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other
    private fun Long.saturatingAdd(other: Long): Long = if (other <= 0L) this else if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

    private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right: Int get() = x + width
        val bottom: Int get() = y + height
        fun contains(pointX: Int, pointY: Int): Boolean = pointX >= x && pointX < right && pointY >= y && pointY < bottom
    }

    private data class Layout(val c1: Rect, val c2: Rect, val c3: Rect)

    private sealed interface StoreStockRow {
        data class Header(val label: String, val resetText: String) : StoreStockRow
        data class Item(val entry: ShopViewEntry) : StoreStockRow
    }

    companion object {
        private const val PAD = 14
        private const val ITEM_ICON_SIZE = 33
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val DISABLED = 0xFF928A7C.toInt()
        private const val GOLD = 0xFFFFD56E.toInt()
        private val FRAME2_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_container_yellow.png")
        private val ITEM_FRAME_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_item.png")
        private val CHOWCOIN_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/chowcoin.png")
        private val GREEN_BUTTON_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_green.png")
        private val GREEN_BUTTON_HOVER_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_green_hover.png")
        private val GRAY_BUTTON_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_gray.png")
        private val GRAY_BUTTON_HOVER_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_gray_hover.png")
        private val RED_BUTTON_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_red.png")
        private val RED_BUTTON_HOVER_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_red_hover.png")
        private val CKDM_BOLD = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold")
        private val CKDM_SMALL = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold_small")
        private const val FRAME2_WIDTH = 1646
        private const val FRAME2_HEIGHT = 256
        private const val FRAME2_CORNER = 75
        private const val FRAME2_DEST_CORNER = 14
        private const val ITEM_FRAME_SIZE = 32
        private const val ITEM_FRAME_CORNER = 10
        private const val COLUMN_STAGGER_MS = 90
        private const val COLUMN_CONTENT_DELAY_MS = 80
        private const val CONTENT_STAGGER_MS = 36
        private const val STOCK_LIST_DELAY_MS = 80
        private const val STOCK_ROW_STAGGER_MS = 34
        private const val ENTRANCE_SLIDE_DOWN_OFFSET = -10
        private const val WIDGET_TOP_PAD = 3
        private const val MIN_TEXT_RENDER_ALPHA = 0.004f
        private const val ITEM_ICON_BOUNCE_DURATION_MS = 220.0f
        private const val ITEM_ICON_MAX_SCALE = 1.12f
    }
}
