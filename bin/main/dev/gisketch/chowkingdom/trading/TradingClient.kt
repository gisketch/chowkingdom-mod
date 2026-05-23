package dev.gisketch.chowkingdom.trading

import com.mojang.blaze3d.systems.RenderSystem
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.wallets.ChowcoinClientState
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Inventory
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent
import net.neoforged.neoforge.common.NeoForge
import java.util.Locale
import java.util.UUID

object TradingClient {
    private val glowTargets: MutableMap<UUID, Boolean> = linkedMapOf()
    private val previousGlow: MutableMap<UUID, Boolean> = linkedMapOf()

    fun register(modBus: IEventBus) {
        modBus.addListener(::registerScreens)
        NeoForge.EVENT_BUS.addListener(::onClientTick)
    }

    fun setGlow(entityId: UUID, enabled: Boolean) {
        val level = Minecraft.getInstance().level
        val entity = level?.getEntitiesOfClass(Entity::class.java, net.minecraft.world.phys.AABB.ofSize(net.minecraft.world.phys.Vec3.ZERO, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE))
            ?.firstOrNull { it.uuid == entityId }
        if (enabled) {
            glowTargets[entityId] = true
            if (entity != null) {
                previousGlow.putIfAbsent(entityId, entity.isCurrentlyGlowing)
                entity.setGlowingTag(true)
            }
        } else {
            glowTargets.remove(entityId)
            restoreGlow(entityId, entity)
        }
    }

    private fun registerScreens(event: RegisterMenuScreensEvent) {
        event.register(TradingFeature.TRADE_MENU.get(), ::TradingScreen)
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        val level = Minecraft.getInstance().level ?: return
        glowTargets.keys.toList().forEach { id ->
            val entity = level.getEntitiesOfClass(Entity::class.java, Minecraft.getInstance().player?.boundingBox?.inflate(128.0) ?: return@forEach)
                .firstOrNull { it.uuid == id }
            if (entity != null) {
                previousGlow.putIfAbsent(id, entity.isCurrentlyGlowing)
                entity.setGlowingTag(true)
            }
        }
    }

    private fun restoreGlow(entityId: UUID, entity: Entity?) {
        val previous = previousGlow.remove(entityId) ?: false
        entity?.setGlowingTag(previous)
    }
}

class TradingScreen(menu: TradingMenu, inventory: Inventory, title: Component) : AbstractContainerScreen<TradingMenu>(menu, inventory, title) {
    private var coinInput: EditBox? = null
    private var lastCoinInputValue: String? = null

    init {
        imageWidth = TradingMenu.IMAGE_WIDTH
        imageHeight = TradingMenu.IMAGE_HEIGHT
        titleLabelX = TradingMenu.LEFT_X + 8
        titleLabelY = 6
        inventoryLabelY = 72
    }

    override fun init() {
        super.init()
        val centerX = leftPos + imageWidth / 2
        coinInput = addRenderableWidget(EditBox(font, centerX - 38, topPos + COIN_INPUT_Y, 92, 20, Component.literal("Chowcoins")).also { input ->
            input.setFilter { value -> value.isEmpty() || value.all(Char::isDigit) }
            input.setMaxLength(15)
            input.setTextColor(CKDM_WHITE)
            input.setTextColorUneditable(DISABLED_TEXT_COLOR)
            input.setResponder(::onCoinInputChanged)
        })
        syncCoinInputFromState()
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        syncCoinInputFromState()
        super.render(guiGraphics, mouseX, mouseY, partialTick)
        dimOtherInventory(guiGraphics)
        renderTradeButtons(guiGraphics, mouseX, mouseY)
        renderScreenTitle(guiGraphics)
        renderTooltip(guiGraphics, mouseX, mouseY)
    }

    override fun renderBg(guiGraphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        renderChest(guiGraphics, leftPos + TradingMenu.LEFT_X, topPos)
        renderChest(guiGraphics, leftPos + TradingMenu.RIGHT_X, topPos)
        renderPaperDoll(guiGraphics, leftPos, topPos - 10, minecraft?.player, mouseX, mouseY)
        renderPaperDoll(guiGraphics, leftPos + TradingMenu.RIGHT_PAPER_DOLL_X, topPos - 10, otherPlayer(), mouseX, mouseY)
        renderProjectedBalances(guiGraphics)
        renderCoinInputIcon(guiGraphics)
    }

    override fun renderLabels(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val state = TradingClientState.get(menu.sessionId)
        renderReadyState(guiGraphics, TradingMenu.LEFT_X + 8, 6, state, self = true)
        renderReadyState(guiGraphics, TradingMenu.RIGHT_X + TradingMenu.PANEL_WIDTH - 86, 6, state, self = false)
        renderCoinRow(guiGraphics, TradingMenu.LEFT_X + 8, -19, state?.selfChowcoins ?: 0L)
        renderCoinRow(guiGraphics, TradingMenu.RIGHT_X + 8, -19, state?.otherChowcoins ?: 0L)
    }

    override fun removed() {
        TradingClientState.clear(menu.sessionId)
        super.removed()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) {
            val x = mouseX.toInt()
            val y = mouseY.toInt()
            when {
                readyButtonRect().contains(x, y) && readyButtonActive() -> {
                    readyOrConfirm()
                    playClickSound()
                    return true
                }
                cancelButtonRect().contains(x, y) -> {
                    TradingNetwork.sendAction(menu.sessionId, TradeAction.CANCEL)
                    playClickSound()
                    return true
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    private fun renderChest(guiGraphics: GuiGraphics, x: Int, y: Int) {
        val rows = 3
        guiGraphics.blit(SINGLE_CHEST_TEXTURE, x, y, 0, 0, TradingMenu.PANEL_WIDTH, rows * 18 + 17)
        guiGraphics.blit(SINGLE_CHEST_TEXTURE, x, y + rows * 18 + 17, 0, 126, TradingMenu.PANEL_WIDTH, 96)
    }

    private fun dimOtherInventory(guiGraphics: GuiGraphics) {
        val x = leftPos + TradingMenu.RIGHT_X + 7
        val y = topPos + TradingMenu.INVENTORY_Y - 1
        guiGraphics.fill(RenderType.guiOverlay(), x, y, x + 162, y + 76, 0x88000000.toInt())
    }

    private fun readyOrConfirm() {
        val state = TradingClientState.get(menu.sessionId)
        if (state?.selfReady == true && state.otherReady) TradingNetwork.sendAction(menu.sessionId, TradeAction.CONFIRM)
        else TradingNetwork.sendAction(menu.sessionId, TradeAction.READY)
    }

    private fun setCoins(amount: Long) {
        TradingNetwork.sendAction(menu.sessionId, TradeAction.SET_CHOWCOINS, amount.coerceAtLeast(0L))
    }

    private fun renderReadyState(guiGraphics: GuiGraphics, x: Int, y: Int, state: TradeStatePayload?, self: Boolean) {
        val text = statusText(state, self)
        val icon = if (isReadyOrConfirmed(state, self)) READY_TEXTURE else NOT_READY_TEXTURE
        renderIcon(guiGraphics, icon, x, y - 3, STATUS_ICON_SIZE, STATUS_ICON_TEXTURE_SIZE)
        guiGraphics.drawString(font, text, x + STATUS_ICON_SIZE + 4, y, 0x404040, false)
    }

    private fun statusText(state: TradeStatePayload?, self: Boolean): String {
        val ready = if (self) state?.selfReady else state?.otherReady
        val confirmed = if (self) state?.selfConfirmed else state?.otherConfirmed
        return when {
            confirmed == true -> "Confirmed"
            ready == true -> "Ready"
            else -> "Not Ready"
        }
    }

    private fun isReadyOrConfirmed(state: TradeStatePayload?, self: Boolean): Boolean =
        if (self) state?.selfReady == true || state?.selfConfirmed == true else state?.otherReady == true || state?.otherConfirmed == true

    private fun renderCoinRow(guiGraphics: GuiGraphics, x: Int, y: Int, amount: Long) {
        renderIcon(guiGraphics, CHOWCOIN_TEXTURE, x, y, COIN_ICON_SIZE, COIN_ICON_TEXTURE_SIZE)
        drawCkdmShadowed(guiGraphics, ckdmText(format(amount), CKDM_BOLD_FONT), x + COIN_ICON_SIZE + 6, y + 4, CKDM_WHITE, CKDM_DARK_SHADOW)
    }

    private fun renderScreenTitle(guiGraphics: GuiGraphics) {
        drawCenteredCkdm(guiGraphics, fitCkdmText("TRADING WITH ${menu.otherName}", width - 16, CKDM_BOLD_FONT), 0, SCREEN_TITLE_Y, width, CKDM_WHITE, CKDM_DARK_SHADOW, CKDM_BOLD_FONT)
    }

    private fun renderPaperDoll(guiGraphics: GuiGraphics, x: Int, y: Int, entity: LivingEntity?, mouseX: Int, mouseY: Int) {
        val tagWidth = TradingMenu.PAPER_DOLL_WIDTH
        if (entity != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                guiGraphics,
                x + 4,
                y,
                x + tagWidth - 4,
                y + 174,
                46,
                0.0f,
                mouseX.toFloat(),
                mouseY.toFloat(),
                entity,
            )
        } else {
            guiGraphics.drawCenteredString(font, "No preview", x + tagWidth / 2, y + 76, 0x777777)
        }
    }

    private fun renderProjectedBalances(guiGraphics: GuiGraphics) {
        val state = TradingClientState.get(menu.sessionId)
        val selfBalance = state?.selfBalance ?: ChowcoinClientState.balance()
        val otherBalance = state?.otherBalance ?: 0L
        renderProjectedBalance(guiGraphics, 0, 162, selfBalance, state?.selfChowcoins ?: 0L, state?.otherChowcoins ?: 0L)
        renderProjectedBalance(guiGraphics, TradingMenu.RIGHT_PAPER_DOLL_X, 162, otherBalance, state?.otherChowcoins ?: 0L, state?.selfChowcoins ?: 0L)
    }

    private fun renderProjectedBalance(guiGraphics: GuiGraphics, x: Int, y: Int, balance: Long, outgoing: Long, incoming: Long) {
        val projected = balance - outgoing + incoming
        val delta = incoming - outgoing
        val iconX = leftPos + x + 10
        val iconY = topPos + y
        renderIcon(guiGraphics, CHOWCOIN_TEXTURE, iconX, iconY, COIN_ICON_SIZE, COIN_ICON_TEXTURE_SIZE)
        val amountX = iconX + COIN_ICON_SIZE + 5
        val projectedText = ckdmText(format(projected), CKDM_BOLD_FONT)
        drawCkdmShadowed(guiGraphics, projectedText, amountX, iconY + 4, CKDM_WHITE, CKDM_DARK_SHADOW)
        if (delta != 0L) {
            val deltaText = if (delta > 0) "(+${format(delta)})" else "(-${format(-delta)})"
            val pose = guiGraphics.pose()
            pose.pushPose()
            pose.translate((amountX + font.width(projectedText) + 5).toFloat(), (iconY + 6).toFloat(), 0.0f)
            pose.scale(0.75f, 0.75f, 1.0f)
            guiGraphics.drawString(font, ckdmText(deltaText, CKDM_BOLD_SMALL_FONT), 0, 0, DELTA_TEXT_COLOR, false)
            pose.popPose()
        }
    }

    private fun renderCoinInputIcon(guiGraphics: GuiGraphics) {
        val input = coinInput ?: return
        renderIcon(guiGraphics, CHOWCOIN_TEXTURE, input.x - COIN_ICON_SIZE - 6, input.y + 2, COIN_ICON_SIZE, COIN_ICON_TEXTURE_SIZE)
    }

    private fun renderTradeButtons(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val label = readyButtonLabel()
        val kind = if (label == "Confirm") ButtonKind.GREEN else ButtonKind.GRAY
        renderButton(guiGraphics, readyButtonRect(), label, kind, mouseX, mouseY, readyButtonActive())
        renderButton(guiGraphics, cancelButtonRect(), "Cancel", ButtonKind.RED, mouseX, mouseY, true)
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
        drawCenteredCkdm(guiGraphics, label, rect.x, rect.y + (rect.height - font.lineHeight) / 2, rect.width, if (active) CKDM_WHITE else DISABLED_TEXT_COLOR, CKDM_DARK_SHADOW, CKDM_BOLD_SMALL_FONT)
    }

    private fun readyButtonLabel(): String {
        val state = TradingClientState.get(menu.sessionId)
        return when {
            state?.selfConfirmed == true -> "Confirmed"
            state?.selfReady == true && state.otherReady -> "Confirm"
            state?.selfReady == true -> "Waiting"
            else -> "Ready"
        }
    }

    private fun readyButtonActive(): Boolean {
        val state = TradingClientState.get(menu.sessionId)
        if (state?.selfConfirmed == true) return false
        return !(state?.selfReady == true && !state.otherReady)
    }

    private fun readyButtonRect(): Rect = Rect(leftPos + imageWidth / 2 - 54, topPos + READY_BUTTON_Y, 108, 20)

    private fun cancelButtonRect(): Rect = Rect(leftPos + imageWidth / 2 - 42, topPos + CANCEL_BUTTON_Y, 84, 20)

    private fun syncCoinInputFromState() {
        val input = coinInput ?: return
        if (input.isFocused) return
        val value = (TradingClientState.get(menu.sessionId)?.selfChowcoins ?: 0L).toString()
        if (input.value == value) return
        lastCoinInputValue = value
        input.setValue(value)
    }

    private fun onCoinInputChanged(value: String) {
        if (value == lastCoinInputValue) return
        lastCoinInputValue = value
        val amount = value.toLongOrNull()?.coerceAtMost(ChowcoinClientState.balance()) ?: 0L
        setCoins(amount)
    }

    private fun otherPlayer(): LivingEntity? {
        val state = TradingClientState.get(menu.sessionId) ?: return null
        if (state.debug) return null
        return minecraft?.level?.players()?.firstOrNull { player -> player.uuid == state.otherId }
    }

    private fun renderIcon(guiGraphics: GuiGraphics, texture: ResourceLocation, x: Int, y: Int, size: Int, textureSize: Int) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        guiGraphics.blit(texture, x, y, size, size, 0.0f, 0.0f, textureSize, textureSize, textureSize, textureSize)
    }

    private fun renderNineSlice(guiGraphics: GuiGraphics, texture: ResourceLocation, rect: Rect, textureWidth: Int, textureHeight: Int, sourceCorner: Int, destinationCorner: Int = sourceCorner) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
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
    }

    private fun blitRegion(guiGraphics: GuiGraphics, texture: ResourceLocation, rect: Rect, sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int, textureWidth: Int, textureHeight: Int) {
        if (rect.width <= 0 || rect.height <= 0) return
        guiGraphics.blit(texture, rect.x, rect.y, rect.width, rect.height, sourceX.toFloat(), sourceY.toFloat(), sourceWidth, sourceHeight, textureWidth, textureHeight)
    }

    private fun drawCenteredCkdm(guiGraphics: GuiGraphics, text: String, x: Int, y: Int, width: Int, color: Int, shadowColor: Int, fontId: ResourceLocation) {
        val component = ckdmText(text, fontId)
        drawCkdmShadowed(guiGraphics, component, x + (width - font.width(component)) / 2, y, color, shadowColor)
    }

    private fun drawCkdmShadowed(guiGraphics: GuiGraphics, component: Component, x: Int, y: Int, color: Int, shadowColor: Int) {
        guiGraphics.drawString(font, component, x + 1, y + 1, shadowColor, false)
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
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.45f))
    }

    private fun format(amount: Long): String = String.format(Locale.US, "%,d", amount)

    private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right: Int get() = x + width
        val bottom: Int get() = y + height
        fun contains(pointX: Int, pointY: Int): Boolean = pointX >= x && pointX < right && pointY >= y && pointY < bottom
        fun inflate(amount: Int): Rect = Rect(x - amount, y - amount, width + amount * 2, height + amount * 2)
    }

    private enum class ButtonKind(val texture: ResourceLocation, val hoverTexture: ResourceLocation) {
        GRAY(GRAY_BUTTON_TEXTURE, GRAY_BUTTON_HOVER_TEXTURE),
        GREEN(GREEN_BUTTON_TEXTURE, GREEN_BUTTON_HOVER_TEXTURE),
        RED(RED_BUTTON_TEXTURE, RED_BUTTON_HOVER_TEXTURE),
    }

    companion object {
        private val SINGLE_CHEST_TEXTURE: ResourceLocation = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png")
        private val CHOWCOIN_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/chowcoin.png")
        private val READY_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/friend_add.png")
        private val NOT_READY_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/friend_remove.png")
        private val GRAY_BUTTON_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_gray.png")
        private val GRAY_BUTTON_HOVER_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_gray_hover.png")
        private val GREEN_BUTTON_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_green.png")
        private val GREEN_BUTTON_HOVER_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_green_hover.png")
        private val RED_BUTTON_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_red.png")
        private val RED_BUTTON_HOVER_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_red_hover.png")
        private val CKDM_BOLD_FONT: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold")
        private val CKDM_BOLD_SMALL_FONT: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold_small")
        private const val STATUS_ICON_SIZE = 14
        private const val STATUS_ICON_TEXTURE_SIZE = 16
        private const val COIN_ICON_SIZE = 16
        private const val COIN_ICON_TEXTURE_SIZE = 16
        private const val READY_BUTTON_Y = 174
        private const val COIN_INPUT_Y = 200
        private const val CANCEL_BUTTON_Y = 226
        private const val SCREEN_TITLE_Y = 8
        private const val BUTTON_TEXTURE_SIZE = 8
        private const val BUTTON_SOURCE_CORNER = 2
        private const val BUTTON_DESTINATION_CORNER = 4
        private const val BUTTON_HOVER_TEXTURE_SIZE = 10
        private const val BUTTON_HOVER_SOURCE_CORNER = 3
        private const val BUTTON_HOVER_DESTINATION_CORNER = 5
        private const val CKDM_WHITE = 0xFFFFFFFF.toInt()
        private const val CKDM_DARK_SHADOW = 0xCC050505.toInt()
        private const val DISABLED_TEXT_COLOR = 0xFF8E8274.toInt()
        private const val DELTA_TEXT_COLOR = 0xFFDDDDDD.toInt()
    }
}
