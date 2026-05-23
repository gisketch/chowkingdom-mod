package dev.gisketch.chowkingdom.shops

import com.mojang.blaze3d.systems.RenderSystem
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.wallets.ChowcoinClientState
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.Util
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.RenderNameTagEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.TriState
import net.neoforged.neoforge.network.PacketDistributor
import java.util.Locale
import java.util.UUID

private data class EntranceStyle(
    val delayMs: Int,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val scaleFrom: Float = 1.0f,
    val durationMs: Int = 260,
)

object VendorContractClient {
    private var highlighted: Set<BlockPos> = emptySet()
    private var vendorSellers: Map<UUID, String> = emptyMap()

    fun register(modBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(::onRenderLevel)
        NeoForge.EVENT_BUS.addListener(::onRenderNameTag)
    }

    @JvmStatic
    fun syncSelection(payload: VendorContractSelectionPayload) {
        highlighted = payload.positions.toSet()
    }

    @JvmStatic
    fun syncSellerIds(payload: VendorSellerIdsPayload) {
        vendorSellers = payload.sellers.associate { it.sellerId to it.shopName }
    }

    @JvmStatic
    fun openVendor(payload: VendorOpenPayload) {
        vendorSellers = vendorSellers + (payload.sellerId to payload.shopName)
        Minecraft.getInstance().setScreen(VendorSellerScreen(payload))
    }

    private fun onRenderLevel(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || highlighted.isEmpty()) return
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val camera = event.camera.position
        val bufferSource = minecraft.renderBuffers().bufferSource()
        val buffer = bufferSource.getBuffer(RenderType.lines())
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        highlighted.forEach { pos ->
            val shop = level.getBlockEntity(pos) as? ShopBlockEntity ?: return@forEach
            val shape = shop.blockState.getShape(level, pos)
            val box = if (shape.isEmpty) AABB(pos) else shape.bounds().move(pos)
            LevelRenderer.renderLineBox(event.poseStack, buffer, box.inflate(0.035).move(-camera.x, -camera.y, -camera.z), 1.0f, 0.86f, 0.05f, 1.0f)
        }
        bufferSource.endBatch(RenderType.lines())
    }

    private fun onRenderNameTag(event: RenderNameTagEvent) {
        val shopName = vendorSellers[event.entity.uuid] ?: SellerData.read(event.entity)?.shopName ?: return
        event.setContent(Component.literal(shopName))
        event.setCanRender(TriState.TRUE)
    }
}

private class VendorSellerScreen(private var payload: VendorOpenPayload) : Screen(Component.literal("Vendor")) {
    private val cart: MutableMap<String, Int> = linkedMapOf()
    private var selectedSeller: UUID? = null
    private var itemScroll = 0
    private var cartScroll = 0
    private var sellerScroll = 0
    private var searchBox: EditBox? = null
    private var renameBox: EditBox? = null
    private var renameOpen = false
    private var voidConfirmOpen = false
    private var voidConfirmOpenedAtMs = 0L
    private var renderAlpha = 1.0f
    private val openedAtMs = Util.getMillis()

    override fun init() {
        searchBox = addRenderableWidget(EditBox(font, col2().x + PAD, col2().y + PAD + 6, col2().width - PAD * 2, 20, Component.literal("Search")).also { input ->
            input.setMaxLength(64)
            input.setHint(Component.literal("Search"))
            input.setTextColor(WHITE)
            input.setTextColorUneditable(DISABLED)
            input.setResponder { itemScroll = 0 }
        })
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick)
        renderColumn(guiGraphics, 0, col1()) { renderLeft(guiGraphics, mouseX, mouseY) }
        renderColumn(guiGraphics, 1, col2()) { renderStockList(guiGraphics, mouseX, mouseY) }
        renderColumn(guiGraphics, 2, col3()) { renderCart(guiGraphics, mouseX, mouseY) }
        if (renameOpen) renderRenameDialog(guiGraphics, mouseX, mouseY)
        if (voidConfirmOpen) renderVoidConfirmDialog(guiGraphics, mouseX, mouseY)
    }

    private fun renderColumn(guiGraphics: GuiGraphics, index: Int, rect: Rect, renderContent: () -> Unit) {
        withEntrance(guiGraphics, EntranceStyle(index * COLUMN_STAGGER_MS, offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            renderNineSlice(guiGraphics, FRAME2_TEXTURE, rect, FRAME2_WIDTH, FRAME2_HEIGHT, FRAME2_CORNER, FRAME2_DEST_CORNER, 1.0f)
            renderContent()
        }
    }

    private fun renderLeft(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val column = col1()
        withEntrance(guiGraphics, EntranceStyle(columnContentDelay(0, 0), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            val doll = dollRect()
            guiGraphics.fill(doll.x, doll.y, doll.right, doll.bottom, 0x33000000)
            sellerEntity()?.let { entity ->
                InventoryScreen.renderEntityInInventoryFollowsMouse(guiGraphics, doll.x + 8, doll.y + 8, doll.right - 8, doll.bottom - 8, dollScale(entity), 0.08f, mouseX.toFloat(), mouseY.toFloat(), entity)
            }
        }

        withEntrance(guiGraphics, EntranceStyle(columnContentDelay(0, 1), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            val titleColor = if (payload.canManage && titleRect().contains(mouseX, mouseY)) GOLD else WHITE
            drawCenteredCkdm(guiGraphics, fitText(payload.shopName, column.width - PAD * 2, CKDM_BOLD), titleRect().x, titleRect().y, titleRect().width, titleColor)
        }

        withEntrance(guiGraphics, EntranceStyle(columnContentDelay(0, 2), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) { renderBalance(guiGraphics) }
        renderSellerFilters(guiGraphics, mouseX, mouseY)
        if (payload.canManage) withEntrance(guiGraphics, EntranceStyle(columnContentDelay(0, 5 + visibleSellers().size), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) { renderRevenue(guiGraphics, mouseX, mouseY) }
    }

    private fun renderBalance(guiGraphics: GuiGraphics) {
        val rect = balanceRect()
        val top = rect.y + WIDGET_TOP_PAD
        renderNineSlice(guiGraphics, ITEM_FRAME_TEXTURE, rect, ITEM_FRAME_SIZE, ITEM_FRAME_SIZE, ITEM_FRAME_CORNER, 12, 0.78f)
        drawCkdm(guiGraphics, "YOU HAVE", rect.x + 12, top + 8, colorAlpha(WHITE, 0.5f), CKDM_SMALL)
        renderChowcoin(guiGraphics, rect.x + 12, top + 23, 12)
        drawCkdm(guiGraphics, format(ChowcoinClientState.displayBalance()), rect.x + 29, top + 25, WHITE, CKDM_BOLD)
    }

    private fun renderSellerFilters(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        withEntrance(guiGraphics, EntranceStyle(columnContentDelay(0, 3), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            drawCkdm(guiGraphics, "SELLERS", col1().x + PAD, sellerHeaderY(), WHITE, CKDM_BOLD)
        }
        visibleSellers().forEachIndexed { index, seller ->
            withEntrance(guiGraphics, EntranceStyle(columnContentDelay(0, 4 + index), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
                val rect = sellerButtonRect(index)
                val selected = seller.id == selectedSeller
                renderButton(guiGraphics, rect, seller.name, if (selected) GREEN_BUTTON_TEXTURE else GRAY_BUTTON_TEXTURE, if (selected) GREEN_BUTTON_HOVER_TEXTURE else GRAY_BUTTON_HOVER_TEXTURE, mouseX, mouseY, true)
            }
        }
    }

    private fun renderRevenue(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val rect = revenueRect()
        val top = rect.y + WIDGET_TOP_PAD
        renderNineSlice(guiGraphics, ITEM_FRAME_TEXTURE, rect, ITEM_FRAME_SIZE, ITEM_FRAME_SIZE, ITEM_FRAME_CORNER, 12, 0.78f)
        drawCkdm(guiGraphics, "CLAIMABLE", rect.x + 12, top + 8, colorAlpha(WHITE, 0.5f), CKDM_SMALL)
        renderChowcoin(guiGraphics, rect.x + 12, top + 23, 12)
        drawCkdm(guiGraphics, format(payload.claimableRevenue), rect.x + 29, top + 25, WHITE, CKDM_BOLD)
        renderButton(guiGraphics, collectRect(), "COLLECT", YELLOW_BUTTON_TEXTURE, YELLOW_BUTTON_HOVER_TEXTURE, mouseX, mouseY, payload.claimableRevenue > 0)
    }

    private fun renderStockList(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        withEntrance(guiGraphics, EntranceStyle(columnContentDelay(1, 0), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            searchBox?.render(guiGraphics, mouseX, mouseY, 0.0f)
        }
        val list = filteredEntries()
        val area = itemListRect()
        guiGraphics.enableScissor(area.x, area.y, area.right, area.bottom)
        list.drop(itemScroll).take(visibleItemRows()).forEachIndexed { index, entry ->
            val entrance = EntranceStyle(columnContentDelay(1, 1) + STOCK_LIST_DELAY_MS + index * STOCK_ROW_STAGGER_MS, offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)
            withEntrance(guiGraphics, entrance) {
                renderItemRow(guiGraphics, itemRowRect(index), entry, mouseX, mouseY, itemEntranceScale(entrance))
            }
        }
        guiGraphics.disableScissor()
    }

    private fun renderItemRow(guiGraphics: GuiGraphics, rect: Rect, entry: VendorEntry, mouseX: Int, mouseY: Int, iconScale: Float) {
        renderNineSlice(guiGraphics, ITEM_FRAME_TEXTURE, rect, ITEM_FRAME_SIZE, ITEM_FRAME_SIZE, ITEM_FRAME_CORNER, ITEM_FRAME_CORNER, 0.82f)
        renderScaledItem(guiGraphics, entry.stack, rect.x + 10, rect.y + 10, ITEM_ICON_SIZE, if (entry.stockCount <= 0) 0.5f else 1.0f, iconScale)
        drawCkdm(guiGraphics, fitText(entry.stack.hoverName.string, rect.width / 2, CKDM_SMALL), rect.x + 54, rect.y + 8, WHITE, CKDM_SMALL)
        drawCkdm(guiGraphics, entry.ownerName.uppercase(Locale.ROOT), rect.x + 54, rect.y + 20, colorAlpha(WHITE, 0.5f), CKDM_SMALL)
        drawCkdm(guiGraphics, stockLabel(entry.stockCount), rect.x + 54, rect.y + 32, if (entry.stockCount <= 0) DISABLED else WHITE, CKDM_SMALL)
        val right = rect.right - 12
        renderChowcoin(guiGraphics, right - 142, rect.y + 21, 12)
        drawCkdm(guiGraphics, format(entry.price), right - 126, rect.y + 23, WHITE, CKDM_BOLD)
        renderButton(guiGraphics, rowMinusRect(rect), "-", GRAY_BUTTON_TEXTURE, GRAY_BUTTON_HOVER_TEXTURE, mouseX, mouseY, cartQty(entry) > 0)
        drawCenteredCkdm(guiGraphics, cartQty(entry).toString(), rowQtyRect(rect).x, rowQtyRect(rect).y + 6, rowQtyRect(rect).width, WHITE)
        renderButton(guiGraphics, rowPlusRect(rect), "+", GRAY_BUTTON_TEXTURE, GRAY_BUTTON_HOVER_TEXTURE, mouseX, mouseY, canAddToCart(entry))
    }

    private fun renderCart(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        withEntrance(guiGraphics, EntranceStyle(columnContentDelay(2, 0), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            drawCenteredCkdm(guiGraphics, "CART", col3().x, col3().y + PAD, col3().width, WHITE)
        }
        val total = cartTotal()
        val canBuyNow = canBuyNow(total)
        withEntrance(guiGraphics, EntranceStyle(columnContentDelay(2, 1), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            val rect = totalRect()
            val top = rect.y + WIDGET_TOP_PAD
            renderNineSlice(guiGraphics, ITEM_FRAME_TEXTURE, rect, ITEM_FRAME_SIZE, ITEM_FRAME_SIZE, ITEM_FRAME_CORNER, 12, 0.78f)
            drawCkdm(guiGraphics, "OVERALL COST", rect.x + 12, top + 8, colorAlpha(WHITE, 0.5f), CKDM_SMALL)
            renderChowcoin(guiGraphics, rect.x + 12, top + 25, 12)
            drawCkdm(guiGraphics, format(total), rect.x + 29, top + 27, WHITE, CKDM_BOLD)
        }
        withEntrance(guiGraphics, EntranceStyle(columnContentDelay(2, 2), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            renderButton(guiGraphics, buyNowRect(), "BUY NOW", GREEN_BUTTON_TEXTURE, GREEN_BUTTON_HOVER_TEXTURE, mouseX, mouseY, canBuyNow)
        }

        val area = cartListRect()
        guiGraphics.enableScissor(area.x, area.y, area.right, area.bottom)
        cartEntries().drop(cartScroll).take(visibleCartRows()).forEachIndexed { index, entry ->
            withEntrance(guiGraphics, EntranceStyle(columnContentDelay(2, 3 + index), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
                renderCartRow(guiGraphics, cartRowRect(index), entry, mouseX, mouseY)
            }
        }
        guiGraphics.disableScissor()
        if (payload.canVoid) withEntrance(guiGraphics, EntranceStyle(columnContentDelay(2, 4 + cartEntries().size), offsetY = ENTRANCE_SLIDE_DOWN_OFFSET)) {
            renderButton(guiGraphics, voidContractRect(), "VOID CONTRACT", RED_BUTTON_TEXTURE, RED_BUTTON_HOVER_TEXTURE, mouseX, mouseY, true)
        }
    }

    private fun renderCartRow(guiGraphics: GuiGraphics, rect: Rect, entry: VendorEntry, mouseX: Int, mouseY: Int) {
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
        if (renameOpen) return clickRenameDialog(x, y)
        if (voidConfirmOpen) return clickVoidConfirmDialog(x, y)
        if (payload.canManage && titleRect().contains(x, y)) return openRenameDialog()
        if (payload.canManage && collectRect().contains(x, y) && payload.claimableRevenue > 0) {
            click()
            PacketDistributor.sendToServer(VendorCollectPayload(payload.sellerId))
            minecraft?.setScreen(null)
            return true
        }
        visibleSellers().forEachIndexed { index, seller ->
            if (sellerButtonRect(index).contains(x, y)) {
                selectedSeller = seller.id
                itemScroll = 0
                click()
                return true
            }
        }
        filteredEntries().drop(itemScroll).take(visibleItemRows()).forEachIndexed { index, entry ->
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
            PacketDistributor.sendToServer(VendorCartBuyPayload(payload.sellerId, purchasableCartEntries().map { VendorCartLine(it.dimension, it.pos, cartQty(it)) }))
            cart.clear()
            minecraft?.setScreen(null)
            return true
        }
        if (payload.canVoid && voidContractRect().contains(x, y)) {
            click()
            voidConfirmOpen = true
            voidConfirmOpenedAtMs = Util.getMillis()
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val x = mouseX.toInt()
        val y = mouseY.toInt()
        when {
            itemListRect().contains(x, y) -> itemScroll = (itemScroll - scrollY.toInt()).coerceIn(0, (filteredEntries().size - visibleItemRows()).coerceAtLeast(0))
            cartListRect().contains(x, y) -> cartScroll = (cartScroll - scrollY.toInt()).coerceIn(0, (cartEntries().size - visibleCartRows()).coerceAtLeast(0))
            sellerListRect().contains(x, y) -> sellerScroll = (sellerScroll - scrollY.toInt()).coerceIn(0, (sellers().size - visibleSellerRows()).coerceAtLeast(0))
            else -> return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        }
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (renameOpen) {
            if (keyCode == 257 || keyCode == 335) return confirmRename()
            if (keyCode == 256) return closeRename()
            return renameBox?.keyPressed(keyCode, scanCode, modifiers) ?: true
        }
        if (voidConfirmOpen) {
            if (keyCode == 257 || keyCode == 335) return confirmVoid()
            if (keyCode == 256) return closeVoidConfirm()
            return true
        }
        if (keyCode == 256) {
            minecraft?.setScreen(null)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (renameOpen) return renameBox?.charTyped(codePoint, modifiers) ?: true
        return super.charTyped(codePoint, modifiers)
    }

    private fun openRenameDialog(): Boolean {
        renameOpen = true
        renameBox = EditBox(font, renameInputRect().x, renameInputRect().y, renameInputRect().width, renameInputRect().height, Component.literal("Shop Name")).also {
            it.setMaxLength(48)
            it.setValue(payload.shopName)
            it.setFocused(true)
        }
        setFocused(renameBox)
        click()
        return true
    }

    private fun clickRenameDialog(x: Int, y: Int): Boolean =
        when {
            renameDoneRect().contains(x, y) -> confirmRename()
            renameCancelRect().contains(x, y) -> closeRename()
            else -> {
                renameBox?.mouseClicked(x.toDouble(), y.toDouble(), 0)
                true
            }
        }

    private fun confirmRename(): Boolean {
        PacketDistributor.sendToServer(VendorRenamePayload(payload.sellerId, renameBox?.value.orEmpty()))
        return closeRename()
    }

    private fun closeRename(): Boolean {
        renameOpen = false
        renameBox = null
        setFocused(searchBox)
        return true
    }

    private fun renderRenameDialog(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        guiGraphics.fill(0, 0, width, height, 0xAA000000.toInt())
        renderNineSlice(guiGraphics, ITEM_FRAME_TEXTURE, renameDialogRect(), ITEM_FRAME_SIZE, ITEM_FRAME_SIZE, ITEM_FRAME_CORNER, 14, 0.95f)
        drawCenteredCkdm(guiGraphics, "SHOP NAME", renameDialogRect().x, renameDialogRect().y + 14, renameDialogRect().width, WHITE)
        renameBox?.render(guiGraphics, mouseX, mouseY, 0.0f)
        renderButton(guiGraphics, renameDoneRect(), "DONE", GREEN_BUTTON_TEXTURE, GREEN_BUTTON_HOVER_TEXTURE, mouseX, mouseY, true)
        renderButton(guiGraphics, renameCancelRect(), "CANCEL", GRAY_BUTTON_TEXTURE, GRAY_BUTTON_HOVER_TEXTURE, mouseX, mouseY, true)
    }

    private fun clickVoidConfirmDialog(x: Int, y: Int): Boolean =
        when {
            voidConfirmYesRect().contains(x, y) -> confirmVoid()
            voidConfirmNoRect().contains(x, y) -> closeVoidConfirm()
            else -> true
        }

    private fun confirmVoid(): Boolean {
        click()
        PacketDistributor.sendToServer(VendorVoidPayload(payload.sellerId))
        minecraft?.setScreen(null)
        return true
    }

    private fun closeVoidConfirm(): Boolean {
        voidConfirmOpen = false
        click()
        return true
    }

    private fun renderVoidConfirmDialog(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val progress = dialogEntranceProgress(voidConfirmOpenedAtMs)
        val previousAlpha = renderAlpha
        renderAlpha *= progress
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(0.0f, VOID_DIALOG_SLIDE_OFFSET * (1.0f - progress), DIALOG_Z)
        guiGraphics.fill(0, 0, width, height, colorWithRenderAlpha(0xAA000000.toInt()))
        renderNineSlice(guiGraphics, ITEM_FRAME_TEXTURE, voidConfirmDialogRect(), ITEM_FRAME_SIZE, ITEM_FRAME_SIZE, ITEM_FRAME_CORNER, 14, 0.95f)
        drawCenteredCkdm(guiGraphics, "VOID CONTRACT?", voidConfirmDialogRect().x, voidConfirmDialogRect().y + 14, voidConfirmDialogRect().width, WHITE)
        drawCenteredCkdm(guiGraphics, "SELLER AI RETURNS", voidConfirmDialogRect().x, voidConfirmDialogRect().y + 42, voidConfirmDialogRect().width, colorAlpha(WHITE, 0.64f), CKDM_SMALL)
        drawCenteredCkdm(guiGraphics, "CONTRACT GOES BACK TO YOU", voidConfirmDialogRect().x, voidConfirmDialogRect().y + 56, voidConfirmDialogRect().width, colorAlpha(WHITE, 0.64f), CKDM_SMALL)
        renderButton(guiGraphics, voidConfirmYesRect(), "VOID", RED_BUTTON_TEXTURE, RED_BUTTON_HOVER_TEXTURE, mouseX, mouseY, true)
        renderButton(guiGraphics, voidConfirmNoRect(), "KEEP", GRAY_BUTTON_TEXTURE, GRAY_BUTTON_HOVER_TEXTURE, mouseX, mouseY, true)
        pose.popPose()
        renderAlpha = previousAlpha
    }

    private fun filteredEntries(): List<VendorEntry> {
        val query = searchBox?.value.orEmpty().trim().lowercase(Locale.ROOT)
        return payload.entries.asSequence()
            .filter { selectedSeller == null || it.ownerId == selectedSeller }
            .filter { query.isBlank() || it.stack.hoverName.string.lowercase(Locale.ROOT).contains(query) || it.ownerName.lowercase(Locale.ROOT).contains(query) }
            .sortedWith(compareByDescending<VendorEntry> { it.stockCount }.thenBy { it.stack.hoverName.string.lowercase(Locale.ROOT) }.thenBy { it.ownerName.lowercase(Locale.ROOT) })
            .toList()
    }

    private fun sellers(): List<SellerFilter> =
        listOf(SellerFilter(null, "ALL")) + payload.entries.distinctBy { it.ownerId }.map { SellerFilter(it.ownerId, it.ownerName.ifBlank { "Seller" }) }

    private fun visibleSellers(): List<SellerFilter> = sellers().drop(sellerScroll).take(visibleSellerRows())
    private fun cartEntries(): List<VendorEntry> = payload.entries.filter { cartQty(it) > 0 }
    private fun purchasableCartEntries(): List<VendorEntry> = cartEntries().filterNot(::isOwnEntry)
    private fun cartQty(entry: VendorEntry): Int = cart[key(entry)] ?: 0
    private fun setCartQty(entry: VendorEntry, qty: Int) {
        if (qty > 0 && isOwnEntry(entry)) return
        val value = qty.coerceIn(0, entry.stockCount.coerceAtLeast(0))
        if (value <= 0) cart.remove(key(entry)) else cart[key(entry)] = value
    }
    private fun cartTotal(): Long = purchasableCartEntries().fold(0L) { sum, entry -> sum.saturatingAdd(entry.price.saturatingMultiply(cartQty(entry).toLong())) }
    private fun canBuyNow(total: Long): Boolean = total > 0L && ChowcoinClientState.balance() >= total
    private fun canAddToCart(entry: VendorEntry): Boolean = !isOwnEntry(entry) && entry.stockCount > 0 && cartQty(entry) < entry.stockCount
    private fun isOwnEntry(entry: VendorEntry): Boolean = minecraft?.player?.uuid == entry.ownerId
    private fun key(entry: VendorEntry): String = "${entry.dimension}:${entry.pos.asLong()}"

    private fun sellerEntity(): LivingEntity? {
        val level = minecraft?.level ?: return null
        val player = minecraft?.player ?: return null
        return level.getEntitiesOfClass(Entity::class.java, player.boundingBox.inflate(128.0))
            .firstOrNull { it.uuid == payload.sellerId } as? LivingEntity
    }

    private fun dollScale(entity: LivingEntity): Int = (117.0f / entity.bbHeight.coerceAtLeast(1.0f)).toInt().coerceIn(27, 88)

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

    private fun drawCkdmRight(guiGraphics: GuiGraphics, text: String, right: Int, y: Int, color: Int, fontId: ResourceLocation) {
        if (renderAlpha <= MIN_TEXT_RENDER_ALPHA) return
        val component = ckdmText(text, fontId)
        guiGraphics.drawString(font, component, right - font.width(component), y, colorWithRenderAlpha(color), false)
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

    private fun withEntrance(guiGraphics: GuiGraphics, style: EntranceStyle, anchorX: Int = 0, anchorY: Int = 0, render: () -> Unit) {
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
            guiGraphics.pose().translate(anchorX.toFloat(), anchorY.toFloat(), 0.0f)
            guiGraphics.pose().scale(scale, scale, 1.0f)
            guiGraphics.pose().translate(-anchorX.toFloat(), -anchorY.toFloat(), 0.0f)
        }
        render()
        guiGraphics.pose().popPose()
        renderAlpha = previousAlpha
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, renderAlpha)
    }

    private fun entranceProgress(style: EntranceStyle): Float {
        val elapsed = (Util.getMillis() - openedAtMs - style.delayMs).toFloat()
        val linear = (elapsed / style.durationMs.coerceAtLeast(1)).coerceIn(0.0f, 1.0f)
        val inverse = 1.0f - linear
        return 1.0f - inverse * inverse * inverse
    }

    private fun itemEntranceScale(style: EntranceStyle): Float {
        val elapsed = (Util.getMillis() - openedAtMs - style.delayMs).toFloat()
        val progress = (elapsed / ITEM_ICON_BOUNCE_DURATION_MS).coerceIn(0.0f, 1.0f)
        if (progress <= 0.0f) return 0.0f
        val shifted = progress - 1.0f
        val overshoot = 1.70158f
        return (1.0f + (overshoot + 1.0f) * shifted * shifted * shifted + overshoot * shifted * shifted).coerceAtMost(ITEM_ICON_MAX_SCALE)
    }

    private fun dialogEntranceProgress(openedAt: Long): Float {
        val elapsed = (Util.getMillis() - openedAt).toFloat()
        val linear = (elapsed / DIALOG_ENTRANCE_DURATION_MS).coerceIn(0.0f, 1.0f)
        val inverse = 1.0f - linear
        return 1.0f - inverse * inverse * inverse
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
    private fun dollRect(): Rect = col1().let { Rect(it.x + PAD, it.y + PAD, it.width - PAD * 2, (it.height * 0.34f).toInt().coerceAtLeast(96)) }
    private fun titleRect(): Rect = dollRect().let { Rect(col1().x + PAD, it.bottom + 10, col1().width - PAD * 2, 18) }
    private fun balanceRect(): Rect = titleRect().let { Rect(col1().x + PAD, it.bottom + 8, col1().width - PAD * 2, 52) }
    private fun sellerHeaderY(): Int = balanceRect().bottom + 12
    private fun sellerListRect(): Rect = Rect(col1().x + PAD, sellerHeaderY() + 15, col1().width - PAD * 2, if (payload.canManage) revenueRect().y - sellerHeaderY() - 24 else col1().bottom - sellerHeaderY() - PAD - 15)
    private fun sellerButtonRect(index: Int): Rect = sellerListRect().let { Rect(it.x, it.y + index * 24, it.width, 20) }
    private fun visibleSellerRows(): Int = (sellerListRect().height / 24).coerceAtLeast(1)
    private fun revenueRect(): Rect = col1().let { Rect(it.x + PAD, it.bottom - 98, it.width - PAD * 2, 54) }
    private fun collectRect(): Rect = revenueRect().let { Rect(it.x, it.bottom + 8, it.width, 24) }
    private fun itemListRect(): Rect = col2().let { Rect(it.x + PAD, it.y + 54, it.width - PAD * 2, it.height - 66) }
    private fun itemRowRect(index: Int): Rect = itemListRect().let { Rect(it.x, it.y + index * 62, it.width, 56) }
    private fun visibleItemRows(): Int = (itemListRect().height / 62).coerceAtLeast(1)
    private fun rowMinusRect(row: Rect): Rect = Rect(row.right - 74, row.y + 19, 18, 16)
    private fun rowQtyRect(row: Rect): Rect = Rect(row.right - 55, row.y + 18, 28, 18)
    private fun rowPlusRect(row: Rect): Rect = Rect(row.right - 26, row.y + 19, 18, 16)
    private fun totalRect(): Rect = col3().let { Rect(it.x + PAD, it.y + 36, it.width - PAD * 2, 58) }
    private fun buyNowRect(): Rect = totalRect().let { Rect(it.x, it.bottom + 8, it.width, 28) }
    private fun cartListRect(): Rect = col3().let {
        val bottomPad = if (payload.canVoid) 58 else 24
        Rect(it.x + PAD, buyNowRect().bottom + 12, it.width - PAD * 2, it.bottom - buyNowRect().bottom - bottomPad)
    }
    private fun cartRowRect(index: Int): Rect = cartListRect().let { Rect(it.x, it.y + index * 44, it.width, 38) }
    private fun visibleCartRows(): Int = (cartListRect().height / 44).coerceAtLeast(1)
    private fun removeCartRect(row: Rect): Rect = Rect(row.right - 28, row.y + 8, 20, 20)
    private fun voidContractRect(): Rect = col3().let { Rect(it.x + PAD, it.bottom - 40, it.width - PAD * 2, 26) }
    private fun renameDialogRect(): Rect = Rect((width - 260) / 2, (height - 126) / 2, 260, 126)
    private fun renameInputRect(): Rect = renameDialogRect().let { Rect(it.x + 22, it.y + 44, it.width - 44, 20) }
    private fun renameDoneRect(): Rect = renameDialogRect().let { Rect(it.x + 22, it.bottom - 36, 88, 22) }
    private fun renameCancelRect(): Rect = renameDialogRect().let { Rect(it.right - 110, it.bottom - 36, 88, 22) }
    private fun voidConfirmDialogRect(): Rect = Rect((width - 280) / 2, (height - 142) / 2, 280, 142)
    private fun voidConfirmYesRect(): Rect = voidConfirmDialogRect().let { Rect(it.x + 24, it.bottom - 38, 92, 24) }
    private fun voidConfirmNoRect(): Rect = voidConfirmDialogRect().let { Rect(it.right - 116, it.bottom - 38, 92, 24) }

    private fun format(amount: Long): String = String.format(Locale.US, "%,d", amount)
    private fun format(amount: Int): String = String.format(Locale.US, "%,d", amount)
    private fun stockLabel(stockCount: Int): String = if (stockCount <= 0) "OUT OF STOCK" else format(stockCount)
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
    private data class SellerFilter(val id: UUID?, val name: String)

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
        private val YELLOW_BUTTON_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_yellow.png")
        private val YELLOW_BUTTON_HOVER_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_yellow_hover.png")
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
        private const val DIALOG_ENTRANCE_DURATION_MS = 220.0f
        private const val VOID_DIALOG_SLIDE_OFFSET = -10.0f
        private const val DIALOG_Z = 500.0f
    }
}
