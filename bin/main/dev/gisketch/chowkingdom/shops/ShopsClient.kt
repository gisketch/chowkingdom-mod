package dev.gisketch.chowkingdom.shops

import com.mojang.blaze3d.systems.RenderSystem
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.wallets.ChowcoinClientState
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.Util
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import java.util.Locale

object ShopsClient {
    fun register(modBus: IEventBus) {
        modBus.addListener(::onRegisterRenderers)
        modBus.addListener(::onRegisterScreens)
        VendorContractClient.register(modBus)
    }

    fun openBuyDialog(payload: ShopOpenBuyDialogPayload) {
        Minecraft.getInstance().setScreen(ShopBuyScreen(payload))
    }

    private fun onRegisterRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(ShopsFeature.SHOP_BLOCK_ENTITY.get(), ::ShopBlockEntityRenderer)
    }

    private fun onRegisterScreens(event: RegisterMenuScreensEvent) {
        event.register(ShopsFeature.SHOP_STOCK_MENU.get(), ::ShopStockScreen)
    }
}

private class ShopBuyScreen(private val payload: ShopOpenBuyDialogPayload) : Screen(Component.literal("Buy Shop Item")) {
    private var quantity = if (payload.stockCount > 0) 1 else 0
    private val openedAtMs = Util.getMillis()

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val panel = panelRect()
        withBounce(guiGraphics, panel) {
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
            renderNineSlice(guiGraphics, FRAME_TEXTURE, panel, FRAME_TEXTURE_WIDTH, FRAME_TEXTURE_HEIGHT, FRAME_SOURCE_CORNER, FRAME_DESTINATION_CORNER)
            drawBuyTitle(guiGraphics, panel)
            renderButton(guiGraphics, minusRect(), "-", ButtonKind.RED, mouseX, mouseY, quantity > 0)
            drawCenteredScaled(guiGraphics, format(quantity.toLong()), quantityRect(), QUANTITY_TEXT_SCALE, CKDM_WHITE, CKDM_DARK_SHADOW, CKDM_BOLD_FONT)
            renderButton(guiGraphics, plusRect(), "+", ButtonKind.GREEN, mouseX, mouseY, canIncrease())
            renderTotal(guiGraphics)
            renderButton(guiGraphics, yesRect(), "YES", ButtonKind.GREEN, mouseX, mouseY, canBuy())
            renderButton(guiGraphics, noRect(), "NO", ButtonKind.RED, mouseX, mouseY, true)
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)
        val x = mouseX.toInt()
        val y = mouseY.toInt()
        when {
            minusRect().contains(x, y) && quantity > 0 -> quantity--
            plusRect().contains(x, y) && canIncrease() -> quantity++
            yesRect().contains(x, y) && canBuy() -> {
                ShopStockNetwork.sendBuy(payload.pos, quantity)
                Minecraft.getInstance().setScreen(null)
            }
            noRect().contains(x, y) -> Minecraft.getInstance().setScreen(null)
            else -> return super.mouseClicked(mouseX, mouseY, button)
        }
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.45f))
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == 256) {
            Minecraft.getInstance().setScreen(null)
            return true
        }
        if ((keyCode == 257 || keyCode == 335) && canBuy()) {
            ShopStockNetwork.sendBuy(payload.pos, quantity)
            Minecraft.getInstance().setScreen(null)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun renderTotal(guiGraphics: GuiGraphics) {
        val text = ckdmText(format(totalCost()), CKDM_BOLD_FONT)
        val contentWidth = COIN_SIZE + 4 + font.width(text)
        val startX = panelRect().x + (panelRect().width - contentWidth) / 2
        val y = panelRect().y + 100
        renderChowcoin(guiGraphics, startX, y - 1, COIN_SIZE)
        drawShadowed(guiGraphics, text, startX + COIN_SIZE + 4, y, CKDM_WHITE, CKDM_DARK_SHADOW)
    }

    private fun drawBuyTitle(guiGraphics: GuiGraphics, panel: Rect) {
        val prefix = "ARE YOU SURE YOU WANT TO BUY"
        val maxWidth = panel.width - 28
        val prefixComponent = ckdmText(prefix, CKDM_BOLD_SMALL_FONT)
        val itemText = fitText(payload.itemName, (maxWidth - font.width(prefixComponent) - TITLE_ITEM_ICON_SIZE - TITLE_ITEM_GAP * 2).coerceAtLeast(20), CKDM_BOLD_SMALL_FONT)
        val itemComponent = ckdmText(itemText, CKDM_BOLD_SMALL_FONT)
        val totalWidth = font.width(prefixComponent) + TITLE_ITEM_GAP * 2 + TITLE_ITEM_ICON_SIZE + font.width(itemComponent)
        var x = panel.x + (panel.width - totalWidth) / 2
        val y = panel.y + 20
        drawShadowed(guiGraphics, prefixComponent, x, y, CKDM_WHITE, CKDM_DARK_SHADOW)
        x += font.width(prefixComponent)
        x += TITLE_ITEM_GAP
        renderTitleItem(guiGraphics, x, y - 2)
        x += TITLE_ITEM_ICON_SIZE + TITLE_ITEM_GAP
        drawShadowed(guiGraphics, itemComponent, x, y, CKDM_GOLD, CKDM_DARK_SHADOW)
    }

    private fun renderTitleItem(guiGraphics: GuiGraphics, x: Int, y: Int) {
        if (payload.stack.isEmpty) return
        val scale = TITLE_ITEM_ICON_SIZE / 16.0f
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(x.toFloat(), y.toFloat(), 120.0f)
        pose.scale(scale, scale, 1.0f)
        guiGraphics.renderItem(payload.stack, 0, 0)
        pose.popPose()
    }

    private fun renderButton(guiGraphics: GuiGraphics, rect: Rect, label: String, kind: ButtonKind, mouseX: Int, mouseY: Int, active: Boolean) {
        val hovered = active && rect.contains(mouseX, mouseY)
        val texture = when {
            !active -> GRAY_BUTTON_TEXTURE
            hovered -> kind.hoverTexture
            else -> kind.texture
        }
        val textureSize = if (active && hovered) BUTTON_HOVER_TEXTURE_SIZE else BUTTON_TEXTURE_SIZE
        val sourceCorner = if (active && hovered) BUTTON_HOVER_SOURCE_CORNER else BUTTON_SOURCE_CORNER
        val destinationCorner = if (active && hovered) BUTTON_HOVER_DESTINATION_CORNER else BUTTON_DESTINATION_CORNER
        renderNineSlice(guiGraphics, texture, if (hovered) rect.inflate(1) else rect, textureSize, textureSize, sourceCorner, destinationCorner)
        val fontId = if (label == "+" || label == "-") CKDM_BOLD_FONT else CKDM_BOLD_SMALL_FONT
        drawCentered(guiGraphics, label, rect.x, rect.y + (rect.height - font.lineHeight) / 2, rect.width, if (active) CKDM_WHITE else DISABLED_TEXT_COLOR, CKDM_DARK_SHADOW, fontId)
    }

    private fun canIncrease(): Boolean = quantity < payload.stockCount && totalCost(quantity + 1) <= ChowcoinClientState.balance()

    private fun canBuy(): Boolean = quantity > 0 && quantity <= payload.stockCount && totalCost() <= ChowcoinClientState.balance()

    private fun totalCost(amount: Int = quantity): Long {
        val price = payload.price.coerceAtLeast(0L)
        if (price == 0L || amount <= 0) return 0L
        if (price > Long.MAX_VALUE / amount) return Long.MAX_VALUE
        return price * amount
    }

    private fun panelRect(): Rect = Rect((width - PANEL_WIDTH) / 2, (height - PANEL_HEIGHT) / 2, PANEL_WIDTH, PANEL_HEIGHT)

    private fun quantityGroupX(): Int = panelRect().let { it.x + (it.width - QUANTITY_GROUP_WIDTH) / 2 }

    private fun minusRect(): Rect = panelRect().let { Rect(quantityGroupX(), it.y + 56, QUANTITY_BUTTON_SIZE, QUANTITY_ROW_HEIGHT) }

    private fun quantityRect(): Rect = panelRect().let { Rect(quantityGroupX() + QUANTITY_BUTTON_SIZE + QUANTITY_GAP, it.y + 56, QUANTITY_TEXT_WIDTH, QUANTITY_ROW_HEIGHT) }

    private fun plusRect(): Rect = panelRect().let { Rect(quantityGroupX() + QUANTITY_BUTTON_SIZE + QUANTITY_GAP + QUANTITY_TEXT_WIDTH + QUANTITY_GAP, it.y + 56, QUANTITY_BUTTON_SIZE, QUANTITY_ROW_HEIGHT) }

    private fun yesRect(): Rect = panelRect().let { Rect(it.x + 67, it.y + 126, 74, 24) }

    private fun noRect(): Rect = panelRect().let { Rect(it.right - 141, it.y + 126, 74, 24) }

    private fun renderNineSlice(guiGraphics: GuiGraphics, texture: ResourceLocation, rect: Rect, textureWidth: Int, textureHeight: Int, sourceCorner: Int, destinationCorner: Int = sourceCorner) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        val edgeX = textureWidth - sourceCorner
        val edgeY = textureHeight - sourceCorner
        val middleWidth = textureWidth - sourceCorner * 2
        val middleHeight = textureHeight - sourceCorner * 2
        val innerWidth = (rect.width - destinationCorner * 2).coerceAtLeast(0)
        val innerHeight = (rect.height - destinationCorner * 2).coerceAtLeast(0)
        blitRegion(guiGraphics, texture, Rect(rect.x, rect.y, destinationCorner, destinationCorner), 0, 0, sourceCorner, sourceCorner, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x + destinationCorner, rect.y, innerWidth, destinationCorner), sourceCorner, 0, middleWidth, sourceCorner, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x + rect.width - destinationCorner, rect.y, destinationCorner, destinationCorner), edgeX, 0, sourceCorner, sourceCorner, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x, rect.y + destinationCorner, destinationCorner, innerHeight), 0, sourceCorner, sourceCorner, middleHeight, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x + destinationCorner, rect.y + destinationCorner, innerWidth, innerHeight), sourceCorner, sourceCorner, middleWidth, middleHeight, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x + rect.width - destinationCorner, rect.y + destinationCorner, destinationCorner, innerHeight), edgeX, sourceCorner, sourceCorner, middleHeight, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x, rect.y + rect.height - destinationCorner, destinationCorner, destinationCorner), 0, edgeY, sourceCorner, sourceCorner, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x + destinationCorner, rect.y + rect.height - destinationCorner, innerWidth, destinationCorner), sourceCorner, edgeY, middleWidth, sourceCorner, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x + rect.width - destinationCorner, rect.y + rect.height - destinationCorner, destinationCorner, destinationCorner), edgeX, edgeY, sourceCorner, sourceCorner, textureWidth, textureHeight)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun blitRegion(guiGraphics: GuiGraphics, texture: ResourceLocation, rect: Rect, sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int, textureWidth: Int, textureHeight: Int) {
        if (rect.width <= 0 || rect.height <= 0) return
        guiGraphics.blit(texture, rect.x, rect.y, rect.width, rect.height, sourceX.toFloat(), sourceY.toFloat(), sourceWidth, sourceHeight, textureWidth, textureHeight)
    }

    private fun renderChowcoin(guiGraphics: GuiGraphics, x: Int, y: Int, size: Int) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        guiGraphics.blit(CHOWCOIN_TEXTURE, x, y, size, size, 0.0f, 0.0f, CHOWCOIN_TEXTURE_SIZE, CHOWCOIN_TEXTURE_SIZE, CHOWCOIN_TEXTURE_SIZE, CHOWCOIN_TEXTURE_SIZE)
    }

    private fun withBounce(guiGraphics: GuiGraphics, rect: Rect, render: () -> Unit) {
        val scale = bounceScale(Util.getMillis() - openedAtMs)
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(rect.x + rect.width / 2.0f, rect.y + rect.height / 2.0f, 0.0f)
        pose.scale(scale, scale, 1.0f)
        pose.translate(-(rect.x + rect.width / 2.0f), -(rect.y + rect.height / 2.0f), 0.0f)
        render()
        pose.popPose()
    }

    private fun bounceScale(elapsedMs: Long): Float {
        val progress = (elapsedMs / BOUNCE_DURATION_MS).coerceIn(0.0f, 1.0f)
        val shifted = progress - 1.0f
        val overshoot = 1.70158f
        return (1.0f + (overshoot + 1.0f) * shifted * shifted * shifted + overshoot * shifted * shifted)
            .coerceAtLeast(BOUNCE_SCALE_FROM)
    }

    private fun drawCentered(guiGraphics: GuiGraphics, text: String, x: Int, y: Int, width: Int, color: Int, shadowColor: Int, fontId: ResourceLocation) {
        val component = ckdmText(text, fontId)
        drawShadowed(guiGraphics, component, x + (width - font.width(component)) / 2, y, color, shadowColor)
    }

    private fun drawCenteredScaled(guiGraphics: GuiGraphics, text: String, rect: Rect, scale: Float, color: Int, shadowColor: Int, fontId: ResourceLocation) {
        val component = ckdmText(text, fontId)
        val scaledWidth = (font.width(component) * scale).toInt()
        val scaledHeight = (font.lineHeight * scale).toInt()
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate((rect.x + (rect.width - scaledWidth) / 2).toFloat(), (rect.y + (rect.height - scaledHeight) / 2).toFloat(), 0.0f)
        pose.scale(scale, scale, 1.0f)
        guiGraphics.drawString(font, component, 1, 1, shadowColor, false)
        guiGraphics.drawString(font, component, 0, 0, color, false)
        pose.popPose()
    }

    private fun drawShadowed(guiGraphics: GuiGraphics, component: Component, x: Int, y: Int, color: Int, shadowColor: Int) {
        guiGraphics.drawString(font, component, x + 1, y + 1, shadowColor, false)
        guiGraphics.drawString(font, component, x, y, color, false)
    }

    private fun ckdmText(text: String, fontId: ResourceLocation): Component =
        Component.literal(text.uppercase(Locale.ROOT)).withStyle { style -> style.withFont(fontId) }

    private fun fitText(text: String, maxWidth: Int, fontId: ResourceLocation): String {
        if (font.width(ckdmText(text, fontId)) <= maxWidth) return text
        val suffix = "..."
        var trimmed = text
        while (trimmed.isNotEmpty() && font.width(ckdmText(trimmed + suffix, fontId)) > maxWidth) trimmed = trimmed.dropLast(1)
        return trimmed + suffix
    }

    private fun format(amount: Long): String = String.format(Locale.US, "%,d", amount)

    private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right: Int get() = x + width
        val bottom: Int get() = y + height
        fun contains(pointX: Int, pointY: Int): Boolean = pointX >= x && pointX < right && pointY >= y && pointY < bottom
        fun inflate(amount: Int): Rect = Rect(x - amount, y - amount, width + amount * 2, height + amount * 2)
    }

    private enum class ButtonKind(val texture: ResourceLocation, val hoverTexture: ResourceLocation) {
        GREEN(GREEN_BUTTON_TEXTURE, GREEN_BUTTON_HOVER_TEXTURE),
        RED(RED_BUTTON_TEXTURE, RED_BUTTON_HOVER_TEXTURE),
    }

    companion object {
        private val CHOWCOIN_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/chowcoin.png")
        private val FRAME_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_container_yellow.png")
        private val GREEN_BUTTON_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_green.png")
        private val GREEN_BUTTON_HOVER_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_green_hover.png")
        private val RED_BUTTON_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_red.png")
        private val RED_BUTTON_HOVER_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_red_hover.png")
        private val GRAY_BUTTON_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_gray.png")
        private val CKDM_BOLD_FONT: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold")
        private val CKDM_BOLD_SMALL_FONT: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold_small")
        private const val PANEL_WIDTH = 300
        private const val PANEL_HEIGHT = 170
        private const val QUANTITY_BUTTON_SIZE = 40
        private const val QUANTITY_TEXT_WIDTH = 96
        private const val QUANTITY_ROW_HEIGHT = 30
        private const val QUANTITY_GAP = 6
        private const val QUANTITY_GROUP_WIDTH = QUANTITY_BUTTON_SIZE * 2 + QUANTITY_TEXT_WIDTH + QUANTITY_GAP * 2
        private const val QUANTITY_TEXT_SCALE = 2.35f
        private const val FRAME_TEXTURE_WIDTH = 1646
        private const val FRAME_TEXTURE_HEIGHT = 256
        private const val FRAME_SOURCE_CORNER = 75
        private const val FRAME_DESTINATION_CORNER = 14
        private const val BOUNCE_DURATION_MS = 320.0f
        private const val BOUNCE_SCALE_FROM = 0.86f
        private const val BUTTON_TEXTURE_SIZE = 8
        private const val BUTTON_SOURCE_CORNER = 2
        private const val BUTTON_DESTINATION_CORNER = 4
        private const val BUTTON_HOVER_TEXTURE_SIZE = 10
        private const val BUTTON_HOVER_SOURCE_CORNER = 3
        private const val BUTTON_HOVER_DESTINATION_CORNER = 5
        private const val CHOWCOIN_TEXTURE_SIZE = 16
        private const val COIN_SIZE = 11
        private const val TITLE_ITEM_ICON_SIZE = 12
        private const val TITLE_ITEM_GAP = 4
        private const val CKDM_WHITE = 0xFFFFFFFF.toInt()
        private const val CKDM_GOLD = 0xFFFFD56E.toInt()
        private const val CKDM_DARK_SHADOW = 0xCC050505.toInt()
        private const val DISABLED_TEXT_COLOR = 0xFF8E8274.toInt()
    }
}

class ShopStockScreen(menu: ShopStockMenu, inventory: Inventory, title: Component) : AbstractContainerScreen<ShopStockMenu>(menu, inventory, title) {
    private var priceDialogInput: EditBox? = null
    private var priceDialogOpen = false
    private var savedPrice = menu.price
    private var currentPrice = menu.price
    private var currentClaimableRevenue = menu.claimableRevenue
    private var previousPriceHover = false
    private var previousSaveHover = false
    private var previousCollectHover = false
    private var previousRemoveHover = false
    private var openedAtMs = Util.getMillis()

    init {
        imageWidth = SCREEN_WIDTH
        imageHeight = SCREEN_HEIGHT
        titleLabelX = 0
        inventoryLabelY = -1000
    }

    override fun init() {
        super.init()
        priceDialogInput = null
        priceDialogOpen = false
        openedAtMs = Util.getMillis()
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTick)
        withBounce(guiGraphics, Rect(leftPos, topPos, imageWidth, EDITOR_FRAME_HEIGHT), 0) {
            renderSoldOutStock(guiGraphics)
            renderStockQuantityOverlay(guiGraphics)
            renderButtons(guiGraphics, mouseX, mouseY)
        }
        if (priceDialogOpen) {
            renderPriceDialog(guiGraphics, mouseX, mouseY)
            renderTooltip(guiGraphics, mouseX, mouseY)
            return
        }
        renderTooltip(guiGraphics, mouseX, mouseY)
    }

    override fun renderBg(guiGraphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        withBounce(guiGraphics, Rect(leftPos, topPos, imageWidth, EDITOR_FRAME_HEIGHT), 0) {
            renderNineSlice(guiGraphics, FRAME_TEXTURE, Rect(leftPos, topPos, imageWidth, EDITOR_FRAME_HEIGHT), FRAME_TEXTURE_WIDTH, FRAME_TEXTURE_HEIGHT, FRAME_SOURCE_CORNER, FRAME_DESTINATION_CORNER)
            renderPriceButton(guiGraphics, mouseX, mouseY)
            renderStats(guiGraphics)
            renderStock(guiGraphics)
            renderStaticLabels(guiGraphics)
            renderPriceText(guiGraphics)
            renderPriceCurrencyLabel(guiGraphics)
        }
        withBounce(guiGraphics, Rect(leftPos + VANILLA_INVENTORY_X, topPos + VANILLA_INVENTORY_Y, VANILLA_INVENTORY_WIDTH, VANILLA_INVENTORY_HEIGHT), 90) {
            renderVanillaInventory(guiGraphics)
        }
    }

    override fun renderLabels(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) = Unit

    override fun renderSlot(guiGraphics: GuiGraphics, slot: Slot) {
        if (slot == menu.getSlot(ShopStockMenu.STOCK_SLOT_INDEX)) {
            withBounce(guiGraphics, Rect(leftPos, topPos, imageWidth, EDITOR_FRAME_HEIGHT), 0) {
                super.renderSlot(guiGraphics, slot)
            }
            return
        }
        super.renderSlot(guiGraphics, slot)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (priceDialogOpen) return clickPriceDialog(mouseX.toInt(), mouseY.toInt(), button)
        if (button == 0) {
            val pointX = mouseX.toInt()
            val pointY = mouseY.toInt()
            when {
                priceButtonRect().contains(pointX, pointY) -> return openPriceDialog()
                saveButtonRect().contains(pointX, pointY) -> return clickSave()
                collectButtonRect().contains(pointX, pointY) -> return clickCollect()
                removeButtonRect().contains(pointX, pointY) -> return clickRemove()
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (priceDialogOpen) {
            if (keyCode == 257 || keyCode == 335) return confirmPriceDialog()
            if (keyCode == 256) return closePriceDialog()
            return priceDialogInput?.keyPressed(keyCode, scanCode, modifiers) ?: true
        }
        if ((keyCode == 257 || keyCode == 335) && canSave()) return clickSave()
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (priceDialogOpen) return priceDialogInput?.charTyped(codePoint, modifiers) ?: true
        return super.charTyped(codePoint, modifiers)
    }

    private fun renderStock(guiGraphics: GuiGraphics) {
        renderNineSlice(guiGraphics, SLOT_TEXTURE, stockSlotRect(), SLOT_TEXTURE_SIZE, SLOT_TEXTURE_SIZE, SLOT_SOURCE_CORNER, SLOT_DESTINATION_CORNER)
    }

    private fun renderStaticLabels(guiGraphics: GuiGraphics) {
        drawCenteredCkdm(guiGraphics, "SHOP", leftPos, topPos + 13, imageWidth, CKDM_WHITE, CKDM_DARK_SHADOW, CKDM_BOLD_FONT)
        drawCenteredCkdmScaled(guiGraphics, "STOCK", leftPos + STOCK_COLUMN_X, topPos + SECTION_HEADER_Y, STOCK_COLUMN_WIDTH, 0.86f, CKDM_WHITE, CKDM_DARK_SHADOW, CKDM_BOLD_SMALL_FONT)
        drawCenteredCkdmScaled(guiGraphics, "PRICE", leftPos + PRICE_COLUMN_X, topPos + SECTION_HEADER_Y, PRICE_COLUMN_WIDTH, 0.86f, CKDM_WHITE, CKDM_DARK_SHADOW, CKDM_BOLD_SMALL_FONT)
        if (!menu.canEdit) drawCenteredCkdmScaled(guiGraphics, "OWNER ONLY", leftPos + PRICE_COLUMN_X, topPos + PRICE_OWNER_LOCK_Y, PRICE_COLUMN_WIDTH, 0.78f, ERROR_TEXT_COLOR, CKDM_DARK_SHADOW, CKDM_BOLD_SMALL_FONT)
    }

    private fun renderPriceButton(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val rect = priceButtonRect()
        val hovered = menu.canEdit && !priceDialogOpen && rect.contains(mouseX, mouseY)
        if (hovered && !previousPriceHover) playHoverSound()
        previousPriceHover = hovered
        val renderRect = if (hovered) rect.inflate(1) else rect
        renderNineSlice(guiGraphics, FRAME_TEXTURE, renderRect, FRAME_TEXTURE_WIDTH, FRAME_TEXTURE_HEIGHT, FRAME_SOURCE_CORNER, FRAME_DESTINATION_CORNER)
    }

    private fun renderPriceText(guiGraphics: GuiGraphics) {
        drawCenteredCkdm(guiGraphics, currentPrice.toString(), leftPos + PRICE_BOX_X, topPos + PRICE_BOX_Y + 8, PRICE_BOX_WIDTH, if (menu.canEdit) CKDM_WHITE else DISABLED_TEXT_COLOR, CKDM_DARK_SHADOW, CKDM_BOLD_FONT)
    }

    private fun renderPriceCurrencyLabel(guiGraphics: GuiGraphics) {
        val label = ckdmText("chowcoin", CKDM_BOLD_FONT)
        val contentWidth = PRICE_COIN_SIZE + PRICE_COIN_GAP + font.width(label)
        val startX = leftPos + PRICE_BOX_X + (PRICE_BOX_WIDTH - contentWidth) / 2
        val y = topPos + PRICE_COIN_Y
        renderChowcoin(guiGraphics, startX, y - 1, PRICE_COIN_SIZE)
        drawCkdmShadowed(guiGraphics, label, startX + PRICE_COIN_SIZE + PRICE_COIN_GAP, y, CHOWCOIN_LABEL_COLOR, CKDM_DARK_SHADOW, 1)
    }

    private fun renderStats(guiGraphics: GuiGraphics) {
        val stats = listOf(
            StatWidget("SOLD", format(menu.soldCount), false),
            StatWidget("TOTAL REVENUE", format(menu.totalRevenue), true),
            StatWidget("TO CLAIM", format(currentClaimableRevenue), true),
        )
        stats.forEachIndexed { index, stat ->
            val rect = Rect(leftPos + STATS_X + index * (STAT_WIDTH + STAT_GAP), topPos + STATS_Y, STAT_WIDTH, STAT_HEIGHT)
            drawCenteredCkdmScaled(guiGraphics, fitCkdmText(stat.header, STAT_WIDTH - 8, CKDM_BOLD_SMALL_FONT), rect.x, rect.y + 1, rect.width, 0.78f, CKDM_WHITE, CKDM_DARK_SHADOW, CKDM_BOLD_SMALL_FONT)
            if (stat.withCoin) {
                val component = ckdmText(stat.value, CKDM_BOLD_FONT)
                val contentWidth = STAT_COIN_SIZE + STAT_COIN_GAP + font.width(component)
                val iconX = rect.x + (rect.width - contentWidth) / 2
                val valueY = rect.y + 15
                renderChowcoin(guiGraphics, iconX, valueY - 1, STAT_COIN_SIZE)
                drawCkdmShadowed(guiGraphics, component, iconX + STAT_COIN_SIZE + STAT_COIN_GAP, valueY, CKDM_WHITE, CKDM_DARK_SHADOW, 1)
            } else {
                drawCenteredCkdm(guiGraphics, stat.value, rect.x, rect.y + 15, rect.width, CKDM_WHITE, CKDM_DARK_SHADOW, CKDM_BOLD_FONT)
            }
        }
    }

    private fun renderVanillaInventory(guiGraphics: GuiGraphics) {
        guiGraphics.blit(VANILLA_INVENTORY_TEXTURE, leftPos + VANILLA_INVENTORY_X, topPos + VANILLA_INVENTORY_Y, 0, VANILLA_INVENTORY_SOURCE_Y, VANILLA_INVENTORY_WIDTH, VANILLA_INVENTORY_HEIGHT)
    }

    private fun renderButtons(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val saveHovered = canSave() && saveButtonRect().contains(mouseX, mouseY)
        val collectHovered = canCollect() && collectButtonRect().contains(mouseX, mouseY)
        val removeHovered = canRemove() && removeButtonRect().contains(mouseX, mouseY)
        if ((saveHovered && !previousSaveHover) || (collectHovered && !previousCollectHover) || (removeHovered && !previousRemoveHover)) playHoverSound()
        previousSaveHover = saveHovered
        previousCollectHover = collectHovered
        previousRemoveHover = removeHovered
        renderButton(guiGraphics, saveButtonRect(), "SAVE SHOP", ButtonKind.GREEN, saveHovered, canSave(), false)
        renderButton(guiGraphics, collectButtonRect(), "COLLECT", ButtonKind.YELLOW, collectHovered, canCollect(), canCollect())
        renderButton(guiGraphics, removeButtonRect(), "REMOVE ITEM", ButtonKind.RED, removeHovered, canRemove(), false)
    }

    private fun renderButton(guiGraphics: GuiGraphics, rect: Rect, labelText: String, kind: ButtonKind, hovered: Boolean, active: Boolean, coin: Boolean) {
        val texture = when {
            !active -> GRAY_BUTTON_TEXTURE
            hovered -> kind.hoverTexture
            else -> kind.texture
        }
        val textureSize = if (active && hovered) BUTTON_HOVER_TEXTURE_SIZE else BUTTON_TEXTURE_SIZE
        val sourceCorner = if (active && hovered) BUTTON_HOVER_SOURCE_CORNER else BUTTON_SOURCE_CORNER
        val destinationCorner = if (active && hovered) BUTTON_HOVER_DESTINATION_CORNER else BUTTON_DESTINATION_CORNER
        val renderRect = if (active && hovered) rect.inflate(1) else rect
        renderNineSlice(guiGraphics, texture, renderRect, textureSize, textureSize, sourceCorner, destinationCorner)
        val label = ckdmText(labelText, CKDM_BOLD_SMALL_FONT)
        val iconWidth = if (coin) BUTTON_COIN_SIZE + BUTTON_COIN_GAP else 0
        val contentWidth = iconWidth + font.width(label)
        val labelX = rect.x + (rect.width - contentWidth) / 2 + iconWidth
        val labelY = rect.y + (rect.height - font.lineHeight) / 2 + 1
        if (coin) renderChowcoin(guiGraphics, labelX - iconWidth, labelY - 1, BUTTON_COIN_SIZE)
        val alpha = if (active) 1.0f else 0.58f
        drawCkdmShadowed(guiGraphics, label, labelX, labelY, colorWithAlpha(CKDM_WHITE, alpha), colorWithAlpha(BUTTON_TEXT_SHADOW, alpha), 1)
    }

    private fun clickSave(): Boolean {
        if (!canSave()) return true
        playClickSound()
        savedPrice = currentPrice
        ShopStockNetwork.sendPrice(menu.pos, currentPrice)
        return true
    }

    private fun clickRemove(): Boolean {
        if (!canRemove()) return true
        playClickSound()
        ShopStockNetwork.sendRemoveStock(menu.pos)
        return true
    }

    private fun clickCollect(): Boolean {
        if (!canCollect()) return true
        playClickSound()
        ShopStockNetwork.sendCollectRevenue(menu.pos)
        currentClaimableRevenue = 0L
        return true
    }

    private fun openPriceDialog(): Boolean {
        if (!menu.canEdit) return true
        playClickSound()
        priceDialogOpen = true
        priceDialogInput = EditBox(font, priceDialogInputRect().x, priceDialogInputRect().y, priceDialogInputRect().width, priceDialogInputRect().height, Component.literal("Input Price")).also { input ->
            input.setFilter { value -> value.isEmpty() || value.all(Char::isDigit) }
            input.setMaxLength(10)
            input.setValue(currentPrice.toString())
            input.setFocused(true)
        }
        setFocused(priceDialogInput)
        return true
    }

    private fun clickPriceDialog(mouseX: Int, mouseY: Int, button: Int): Boolean {
        if (button != 0) return true
        return when {
            priceDialogDoneRect().contains(mouseX, mouseY) -> confirmPriceDialog()
            priceDialogCancelRect().contains(mouseX, mouseY) -> closePriceDialog()
            else -> {
                priceDialogInput?.mouseClicked(mouseX.toDouble(), mouseY.toDouble(), button)
                true
            }
        }
    }

    private fun confirmPriceDialog(): Boolean {
        val value = priceDialogInput?.value.orEmpty().toLongOrNull()?.coerceIn(0L, ShopBlockEntity.MAX_PRICE) ?: 0L
        currentPrice = value
        return closePriceDialog()
    }

    private fun closePriceDialog(): Boolean {
        priceDialogOpen = false
        priceDialogInput = null
        setFocused(null)
        return true
    }

    private fun renderPriceDialog(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        guiGraphics.fill(0, 0, width, height, DIALOG_SCRIM_COLOR)
        val panel = priceDialogRect()
        withBounce(guiGraphics, panel, 0) {
            renderNineSlice(guiGraphics, FRAME_TEXTURE, panel, FRAME_TEXTURE_WIDTH, FRAME_TEXTURE_HEIGHT, FRAME_SOURCE_CORNER, FRAME_DESTINATION_CORNER)
            guiGraphics.drawCenteredString(font, Component.literal("Input Price"), panel.x + panel.width / 2, panel.y + 12, CKDM_WHITE)
            renderNineSlice(guiGraphics, FRAME_TEXTURE, priceDialogInputRect().inflate(4), FRAME_TEXTURE_WIDTH, FRAME_TEXTURE_HEIGHT, FRAME_SOURCE_CORNER, 8)
            priceDialogInput?.render(guiGraphics, mouseX, mouseY, 0.0f)
            renderDialogButton(guiGraphics, priceDialogDoneRect(), "DONE", priceDialogDoneRect().contains(mouseX, mouseY), true)
            renderDialogButton(guiGraphics, priceDialogCancelRect(), "CANCEL", priceDialogCancelRect().contains(mouseX, mouseY), false)
        }
    }

    private fun renderDialogButton(guiGraphics: GuiGraphics, rect: Rect, labelText: String, hovered: Boolean, confirm: Boolean) {
        val texture = when {
            hovered && confirm -> GREEN_BUTTON_HOVER_TEXTURE
            confirm -> GREEN_BUTTON_TEXTURE
            hovered -> GRAY_BUTTON_TEXTURE
            else -> GRAY_BUTTON_TEXTURE
        }
        val textureSize = if (hovered && confirm) BUTTON_HOVER_TEXTURE_SIZE else BUTTON_TEXTURE_SIZE
        val sourceCorner = if (hovered && confirm) BUTTON_HOVER_SOURCE_CORNER else BUTTON_SOURCE_CORNER
        val destinationCorner = if (hovered && confirm) BUTTON_HOVER_DESTINATION_CORNER else BUTTON_DESTINATION_CORNER
        renderNineSlice(guiGraphics, texture, if (hovered && confirm) rect.inflate(1) else rect, textureSize, textureSize, sourceCorner, destinationCorner)
        drawCenteredCkdmScaled(guiGraphics, labelText, rect.x, rect.y + 6, rect.width, 0.78f, CKDM_WHITE, CKDM_DARK_SHADOW, CKDM_BOLD_SMALL_FONT)
    }

    private fun canSave(): Boolean = menu.canEdit && currentPrice != savedPrice

    private fun canCollect(): Boolean = menu.canEdit && currentClaimableRevenue > 0L

    private fun canRemove(): Boolean = menu.canEdit && !displayStock().isEmpty

    private fun saveButtonRect(): Rect = Rect(leftPos + BUTTON_ROW_X, topPos + BUTTON_ROW_Y, BUTTON_WIDTH, BUTTON_HEIGHT)

    private fun priceButtonRect(): Rect = Rect(leftPos + PRICE_BOX_X, topPos + PRICE_BOX_Y, PRICE_BOX_WIDTH, PRICE_BOX_HEIGHT)

    private fun collectButtonRect(): Rect = Rect(leftPos + BUTTON_ROW_X + BUTTON_WIDTH + BUTTON_GAP, topPos + BUTTON_ROW_Y, BUTTON_WIDTH, BUTTON_HEIGHT)

    private fun removeButtonRect(): Rect = Rect(leftPos + BUTTON_ROW_X + (BUTTON_WIDTH + BUTTON_GAP) * 2, topPos + BUTTON_ROW_Y, BUTTON_WIDTH, BUTTON_HEIGHT)

    private fun stockSlotRect(): Rect = Rect(leftPos + STOCK_SLOT_X, topPos + STOCK_SLOT_Y, STOCK_SLOT_SIZE, STOCK_SLOT_SIZE)

    private fun renderStockQuantityOverlay(guiGraphics: GuiGraphics) {
        if (displayStock().isEmpty) return
        val slot = stockSlotRect()
        val label = ckdmText(fitCkdmText(format(displayStockCount()), STOCK_COUNT_MAX_WIDTH, CKDM_BOLD_SMALL_FONT), CKDM_BOLD_SMALL_FONT)
        val scaledWidth = (font.width(label) * STOCK_COUNT_SCALE).toInt()
        val scaledHeight = (font.lineHeight * STOCK_COUNT_SCALE).toInt()
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate((slot.right - scaledWidth - 4).toFloat(), (slot.bottom - scaledHeight - 5).toFloat(), 220.0f)
        pose.scale(STOCK_COUNT_SCALE, STOCK_COUNT_SCALE, 1.0f)
        guiGraphics.drawString(font, label, 1, 1, CKDM_DARK_SHADOW, false)
        guiGraphics.drawString(font, label, 0, 0, CKDM_WHITE, false)
        pose.popPose()
    }

    private fun renderSoldOutStock(guiGraphics: GuiGraphics) {
        val stack = displayStock()
        if (stack.isEmpty || displayStockCount() > 0) return
        val slot = stockSlotRect()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 0.5f)
        guiGraphics.renderItem(stack, slot.x + 12, slot.y + 12)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun priceDialogRect(): Rect = Rect((width - PRICE_DIALOG_WIDTH) / 2, (height - PRICE_DIALOG_HEIGHT) / 2, PRICE_DIALOG_WIDTH, PRICE_DIALOG_HEIGHT)

    private fun priceDialogInputRect(): Rect = priceDialogRect().let { Rect(it.x + 18, it.y + 35, it.width - 36, 20) }

    private fun priceDialogDoneRect(): Rect = priceDialogRect().let { Rect(it.x + 18, it.y + 66, 66, 20) }

    private fun priceDialogCancelRect(): Rect = priceDialogRect().let { Rect(it.right - 84, it.y + 66, 66, 20) }

    private fun renderNineSlice(guiGraphics: GuiGraphics, texture: ResourceLocation, rect: Rect, textureWidth: Int, textureHeight: Int, sourceCorner: Int, destinationCorner: Int = sourceCorner) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        val edgeX = textureWidth - sourceCorner
        val edgeY = textureHeight - sourceCorner
        val middleWidth = textureWidth - sourceCorner * 2
        val middleHeight = textureHeight - sourceCorner * 2
        val innerWidth = (rect.width - destinationCorner * 2).coerceAtLeast(0)
        val innerHeight = (rect.height - destinationCorner * 2).coerceAtLeast(0)
        blitRegion(guiGraphics, texture, Rect(rect.x, rect.y, destinationCorner, destinationCorner), 0, 0, sourceCorner, sourceCorner, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x + destinationCorner, rect.y, innerWidth, destinationCorner), sourceCorner, 0, middleWidth, sourceCorner, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x + rect.width - destinationCorner, rect.y, destinationCorner, destinationCorner), edgeX, 0, sourceCorner, sourceCorner, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x, rect.y + destinationCorner, destinationCorner, innerHeight), 0, sourceCorner, sourceCorner, middleHeight, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x + destinationCorner, rect.y + destinationCorner, innerWidth, innerHeight), sourceCorner, sourceCorner, middleWidth, middleHeight, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x + rect.width - destinationCorner, rect.y + destinationCorner, destinationCorner, innerHeight), edgeX, sourceCorner, sourceCorner, middleHeight, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x, rect.y + rect.height - destinationCorner, destinationCorner, destinationCorner), 0, edgeY, sourceCorner, sourceCorner, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x + destinationCorner, rect.y + rect.height - destinationCorner, innerWidth, destinationCorner), sourceCorner, edgeY, middleWidth, sourceCorner, textureWidth, textureHeight)
        blitRegion(guiGraphics, texture, Rect(rect.x + rect.width - destinationCorner, rect.y + rect.height - destinationCorner, destinationCorner, destinationCorner), edgeX, edgeY, sourceCorner, sourceCorner, textureWidth, textureHeight)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun blitRegion(guiGraphics: GuiGraphics, texture: ResourceLocation, rect: Rect, sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int, textureWidth: Int, textureHeight: Int) {
        if (rect.width <= 0 || rect.height <= 0) return
        guiGraphics.blit(texture, rect.x, rect.y, rect.width, rect.height, sourceX.toFloat(), sourceY.toFloat(), sourceWidth, sourceHeight, textureWidth, textureHeight)
    }

    private fun drawCenteredCkdm(guiGraphics: GuiGraphics, text: String, x: Int, y: Int, width: Int, color: Int, shadowColor: Int, fontId: ResourceLocation) {
        val component = ckdmText(text, fontId)
        drawCkdmShadowed(guiGraphics, component, x + (width - font.width(component)) / 2, y, color, shadowColor, 1)
    }

    private fun drawCenteredCkdmScaled(guiGraphics: GuiGraphics, text: String, x: Int, y: Int, width: Int, scale: Float, color: Int, shadowColor: Int, fontId: ResourceLocation) {
        val component = ckdmText(text, fontId)
        val scaledWidth = (font.width(component) * scale).toInt()
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate((x + (width - scaledWidth) / 2).toFloat(), y.toFloat(), 0.0f)
        pose.scale(scale, scale, 1.0f)
        guiGraphics.drawString(font, component, 1, 1, shadowColor, false)
        guiGraphics.drawString(font, component, 0, 0, color, false)
        pose.popPose()
    }

    private fun drawCkdmShadowed(guiGraphics: GuiGraphics, component: Component, x: Int, y: Int, color: Int, shadowColor: Int, shadowOffset: Int) {
        guiGraphics.drawString(font, component, x + shadowOffset, y + shadowOffset, shadowColor, false)
        guiGraphics.drawString(font, component, x, y, color, false)
    }

    private fun ckdmText(text: String, fontId: ResourceLocation): Component =
        Component.literal(text.uppercase(Locale.ROOT)).withStyle { style -> style.withFont(fontId) }

    private fun fitCkdmText(text: String, maxWidth: Int, fontId: ResourceLocation): String {
        if (font.width(ckdmText(text, fontId)) <= maxWidth) return text
        val suffix = "..."
        var trimmed = text
        while (trimmed.isNotEmpty() && font.width(ckdmText(trimmed + suffix, fontId)) > maxWidth) trimmed = trimmed.dropLast(1)
        return trimmed + suffix
    }

    private fun playClickSound() {
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), BUTTON_CLICK_SOUND_PITCH, BUTTON_CLICK_SOUND_VOLUME))
    }

    private fun playHoverSound() {
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), BUTTON_HOVER_SOUND_PITCH, BUTTON_HOVER_SOUND_VOLUME))
    }

    private fun renderChowcoin(guiGraphics: GuiGraphics, x: Int, y: Int, size: Int) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        guiGraphics.blit(CHOWCOIN_TEXTURE, x, y, size, size, 0.0f, 0.0f, CHOWCOIN_TEXTURE_SIZE, CHOWCOIN_TEXTURE_SIZE, CHOWCOIN_TEXTURE_SIZE, CHOWCOIN_TEXTURE_SIZE)
    }

    private fun withBounce(guiGraphics: GuiGraphics, rect: Rect, delayMs: Int, render: () -> Unit) {
        val scale = bounceScale(Util.getMillis() - openedAtMs - delayMs)
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(rect.x + rect.width / 2.0f, rect.y + rect.height / 2.0f, 0.0f)
        pose.scale(scale, scale, 1.0f)
        pose.translate(-(rect.x + rect.width / 2.0f), -(rect.y + rect.height / 2.0f), 0.0f)
        render()
        pose.popPose()
    }

    private fun bounceScale(elapsedMs: Long): Float {
        val progress = (elapsedMs / BOUNCE_DURATION_MS).coerceIn(0.0f, 1.0f)
        val shifted = progress - 1.0f
        val overshoot = 1.70158f
        return (1.0f + (overshoot + 1.0f) * shifted * shifted * shifted + overshoot * shifted * shifted)
            .coerceAtLeast(BOUNCE_SCALE_FROM)
    }

    private fun displayStock(): net.minecraft.world.item.ItemStack =
        (Minecraft.getInstance().level?.getBlockEntity(menu.pos) as? ShopBlockEntity)?.displayItem
            ?: menu.getSlot(ShopStockMenu.STOCK_SLOT_INDEX).item
            ?: menu.stock

    private fun displayStockCount(): Int =
        (Minecraft.getInstance().level?.getBlockEntity(menu.pos) as? ShopBlockEntity)?.stockCount ?: menu.stockCount

    private fun format(amount: Int): String = String.format(Locale.US, "%,d", amount)

    private fun format(amount: Long): String = String.format(Locale.US, "%,d", amount)

    private fun colorWithAlpha(color: Int, alphaFactor: Float): Int {
        val alpha = (((color ushr 24) and 0xFF) * alphaFactor).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right: Int get() = x + width
        val bottom: Int get() = y + height
        fun contains(pointX: Int, pointY: Int): Boolean = pointX >= x && pointX < right && pointY >= y && pointY < bottom
        fun inflate(amount: Int): Rect = Rect(x - amount, y - amount, width + amount * 2, height + amount * 2)
    }

    private data class StatWidget(val header: String, val value: String, val withCoin: Boolean)

    private enum class ButtonKind(val texture: ResourceLocation, val hoverTexture: ResourceLocation) {
        GREEN(GREEN_BUTTON_TEXTURE, GREEN_BUTTON_HOVER_TEXTURE),
        YELLOW(YELLOW_BUTTON_TEXTURE, YELLOW_BUTTON_HOVER_TEXTURE),
        RED(RED_BUTTON_TEXTURE, RED_BUTTON_HOVER_TEXTURE),
    }

    companion object {
        private val CHOWCOIN_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/chowcoin.png")
        private val FRAME_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_container_yellow.png")
        private val SLOT_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_slot.png")
        private val VANILLA_INVENTORY_TEXTURE: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png")
        private val GREEN_BUTTON_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_green.png")
        private val GREEN_BUTTON_HOVER_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_green_hover.png")
        private val YELLOW_BUTTON_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_yellow.png")
        private val YELLOW_BUTTON_HOVER_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_yellow_hover.png")
        private val RED_BUTTON_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_red.png")
        private val RED_BUTTON_HOVER_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_red_hover.png")
        private val GRAY_BUTTON_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_gray.png")
        private val CKDM_BOLD_FONT: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold")
        private val CKDM_BOLD_SMALL_FONT: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold_small")
        private const val SCREEN_WIDTH = 320
        private const val SCREEN_HEIGHT = 286
        private const val EDITOR_FRAME_HEIGHT = 180
        private const val FRAME_TEXTURE_WIDTH = 1646
        private const val FRAME_TEXTURE_HEIGHT = 256
        private const val FRAME_SOURCE_CORNER = 75
        private const val FRAME_DESTINATION_CORNER = 14
        private const val SLOT_TEXTURE_SIZE = 370
        private const val SLOT_SOURCE_CORNER = 75
        private const val SLOT_DESTINATION_CORNER = 10
        private const val STOCK_COLUMN_X = 28
        private const val STOCK_COLUMN_WIDTH = 72
        private const val PRICE_COLUMN_X = 112
        private const val PRICE_COLUMN_WIDTH = 180
        private const val SECTION_HEADER_Y = 34
        private const val PRICE_OWNER_LOCK_Y = 96
        private const val STOCK_SLOT_X = 44
        private const val STOCK_SLOT_Y = 51
        private const val STOCK_SLOT_SIZE = 40
        private const val PRICE_BOX_X = 118
        private const val PRICE_BOX_Y = 55
        private const val PRICE_BOX_WIDTH = 168
        private const val PRICE_BOX_HEIGHT = 24
        private const val PRICE_COIN_Y = 86
        private const val PRICE_COIN_SIZE = 10
        private const val PRICE_COIN_GAP = 4
        private const val STATS_X = 23
        private const val STATS_Y = 104
        private const val STAT_WIDTH = 86
        private const val STAT_HEIGHT = 26
        private const val STAT_GAP = 8
        private const val STAT_COIN_SIZE = 10
        private const val STAT_COIN_GAP = 3
        private const val BUTTON_ROW_X = 22
        private const val BUTTON_ROW_Y = 136
        private const val BUTTON_WIDTH = 88
        private const val BUTTON_HEIGHT = 21
        private const val BUTTON_GAP = 6
        private const val BUTTON_TEXTURE_SIZE = 8
        private const val BUTTON_SOURCE_CORNER = 2
        private const val BUTTON_DESTINATION_CORNER = 4
        private const val BUTTON_HOVER_TEXTURE_SIZE = 10
        private const val BUTTON_HOVER_SOURCE_CORNER = 3
        private const val BUTTON_HOVER_DESTINATION_CORNER = 5
        private const val BUTTON_COIN_SIZE = 10
        private const val BUTTON_COIN_GAP = 3
        private const val CHOWCOIN_TEXTURE_SIZE = 16
        private const val VANILLA_INVENTORY_X = 72
        private const val VANILLA_INVENTORY_Y = 190
        private const val VANILLA_INVENTORY_WIDTH = 176
        private const val VANILLA_INVENTORY_HEIGHT = 96
        private const val VANILLA_INVENTORY_SOURCE_Y = 126
        private const val STOCK_COUNT_MAX_WIDTH = 30
        private const val STOCK_COUNT_SCALE = 0.62f
        private const val PRICE_DIALOG_WIDTH = 180
        private const val PRICE_DIALOG_HEIGHT = 98
        private const val DIALOG_SCRIM_COLOR = 0x99000000.toInt()
        private const val BOUNCE_DURATION_MS = 320.0f
        private const val BOUNCE_SCALE_FROM = 0.86f
        private const val CKDM_WHITE = 0xFFFFFFFF.toInt()
        private const val CKDM_DARK_SHADOW = 0xCC050505.toInt()
        private const val BUTTON_TEXT_SHADOW = 0xAA101010.toInt()
        private const val CHOWCOIN_LABEL_COLOR = 0xBFFFFFFF.toInt()
        private const val DISABLED_TEXT_COLOR = 0xFF8E8274.toInt()
        private const val ERROR_TEXT_COLOR = 0xFFFF7878.toInt()
        private const val BUTTON_CLICK_SOUND_PITCH = 1.0f
        private const val BUTTON_CLICK_SOUND_VOLUME = 0.45f
        private const val BUTTON_HOVER_SOUND_PITCH = 1.55f
        private const val BUTTON_HOVER_SOUND_VOLUME = 0.24f
    }
}
