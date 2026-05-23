package dev.gisketch.chowkingdom.snackbar

import com.mojang.blaze3d.systems.RenderSystem
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.npc.NpcClient
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import java.util.Locale
import kotlin.math.roundToInt

object SnackbarClient {
    private val LAYER_ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "snackbar")
    private val active = mutableListOf<ClientSnackbar>()
    private val queued = mutableListOf<QueuedSnackbar>()
    private var nextId = 0L

    fun register(modBus: IEventBus) {
        modBus.addListener(::registerGuiLayers)
    }

    @JvmStatic
    fun show(payload: SnackbarPayload) {
        val now = System.currentTimeMillis()
        val next = QueuedSnackbar(
            SnackbarIconKind.fromId(payload.iconKind),
            payload.icon,
            payload.title,
            payload.content,
            SnackbarType.fromId(payload.type),
            payload.sound,
            payload.durationMs.coerceIn(1_000L, 60_000L),
            if (payload.progressTierSize > 0 && payload.progressToXp > payload.progressFromXp) SnackbarProgress(payload.progressFromXp, payload.progressToXp, payload.progressTierSize, payload.progressAnimationMs.coerceIn(1L, 60_000L)) else null,
        )
        if (!extendNewestDuplicate(next, now)) queued += next
        promoteQueued(now)
    }

    @JvmStatic
    fun clearActive() {
        active.clear()
        promoteQueued(System.currentTimeMillis())
    }

    private fun promoteQueued(now: Long) {
        while (active.size < MAX_ACTIVE && queued.isNotEmpty()) {
            val next = queued.removeAt(0)
            active += ClientSnackbar(
                nextId++,
                next.iconKind,
                next.icon,
                itemStack(next.icon),
                next.title,
                next.content,
                next.type,
                next.sound,
                now,
                next.durationMs,
                next.progress,
            )
            playSound(next.sound)
        }
    }

    private fun extendNewestDuplicate(next: QueuedSnackbar, now: Long): Boolean {
        val queuedIndex = queued.lastIndex
        if (queuedIndex >= 0 && queued[queuedIndex].sameDisplayAs(next)) {
            val current = queued[queuedIndex]
            queued[queuedIndex] = current.copy(durationMs = extendQueuedDuration(current.durationMs, next.durationMs))
            return true
        }

        val activeIndex = active.lastIndex
        if (activeIndex >= 0 && active[activeIndex].sameDisplayAs(next)) {
            val current = active[activeIndex]
            active[activeIndex] = current.copy(durationMs = extendActiveDuration(current, next.durationMs, now))
            return true
        }

        return false
    }

    private fun extendQueuedDuration(currentDurationMs: Long, addedDurationMs: Long): Long =
        (currentDurationMs + addedDurationMs).coerceAtMost(MAX_EXTENDED_DURATION_MS)

    private fun extendActiveDuration(current: ClientSnackbar, addedDurationMs: Long, now: Long): Long {
        val elapsed = (now - current.createdAtMs).coerceAtLeast(0L)
        val remaining = (current.durationMs - elapsed).coerceAtLeast(0L)
        val extendedRemaining = (remaining + addedDurationMs).coerceAtMost(MAX_EXTENDED_DURATION_MS)
        return elapsed + extendedRemaining
    }

    private fun registerGuiLayers(event: RegisterGuiLayersEvent) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, LAYER_ID) { guiGraphics, _ -> render(guiGraphics) }
    }

    private fun render(guiGraphics: GuiGraphics) {
        val minecraft = Minecraft.getInstance()
        if (minecraft.options.hideGui || minecraft.gui.debugOverlay.showDebugScreen()) return
        val now = System.currentTimeMillis()
        active.removeIf { now - it.createdAtMs > it.durationMs + EXIT_MS }
        promoteQueued(now)
        if (active.isEmpty()) return

        val font = minecraft.font
        val screenWidth = minecraft.window.guiScaledWidth
        val screenHeight = minecraft.window.guiScaledHeight
        val renderFromTop = NpcClient.isDialogOpen()
        if (renderFromTop) {
            var top = DIALOG_TOP_OFFSET_Y
            active.forEach { snackbar ->
                val layout = layout(font, snackbar, screenWidth)
                val x = (screenWidth - layout.width) / 2
                renderSnackbar(guiGraphics, font, snackbar, layout, x, top, now, true)
                top += layout.height + STACK_GAP
            }
            return
        }
        var bottom = screenHeight - HOTBAR_OFFSET_Y
        active.asReversed().forEach { snackbar ->
            val layout = layout(font, snackbar, screenWidth)
            val x = (screenWidth - layout.width) / 2
            val y = bottom - layout.height
            renderSnackbar(guiGraphics, font, snackbar, layout, x, y, now, false)
            bottom = y - STACK_GAP
        }
    }

    private fun renderSnackbar(guiGraphics: GuiGraphics, font: Font, snackbar: ClientSnackbar, layout: SnackbarLayout, x: Int, y: Int, now: Long, fromTop: Boolean) {
        val age = now - snackbar.createdAtMs
        val entrance = easeOutCubic((age.toFloat() / ENTER_MS).coerceIn(0.0f, 1.0f))
        val exit = if (age <= snackbar.durationMs) 1.0f else 1.0f - ((age - snackbar.durationMs).toFloat() / EXIT_MS).coerceIn(0.0f, 1.0f)
        val alpha = entrance * exit
        if (alpha <= 0.01f) return
        val slideY = (if (fromTop) -SLIDE_UP_OFFSET else SLIDE_UP_OFFSET) * (1.0f - entrance)
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(0.0, slideY.toDouble(), 240.0)
        renderNineSlice(guiGraphics, Rect(x, y, layout.width, layout.height), alpha)
        renderIcon(guiGraphics, snackbar, x + PAD_X, y + PAD_TOP + (layout.contentHeight - ICON_SIZE) / 2, alpha)
        val titleY = if (layout.titleOnly) y + PAD_TOP + (layout.contentHeight - TITLE_HEIGHT) / 2 else y + PAD_TOP
        drawTitle(guiGraphics, font, snackbar, layout, x + PAD_X + ICON_SLOT_WIDTH, titleY, alpha)
        if (!layout.titleOnly) {
            layout.contentLines.forEachIndexed { index, line ->
                guiGraphics.drawString(font, line, x + PAD_X + ICON_SLOT_WIDTH, y + PAD_TOP + TITLE_HEIGHT + CONTENT_GAP + index * CONTENT_LINE_HEIGHT, colorAlpha(CONTENT_COLOR, alpha), false)
            }
            snackbar.progress?.let { progress ->
                val barY = y + PAD_TOP + TITLE_HEIGHT + if (layout.contentLines.isEmpty()) PROGRESS_TOP_GAP else CONTENT_GAP + layout.contentLines.size * CONTENT_LINE_HEIGHT + PROGRESS_AFTER_CONTENT_GAP
                renderProgressBar(guiGraphics, progress, x + PAD_X + ICON_SLOT_WIDTH, barY, layout.textWidth, age, alpha)
            }
        }
        pose.popPose()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun drawTitle(guiGraphics: GuiGraphics, font: Font, snackbar: ClientSnackbar, layout: SnackbarLayout, x: Int, y: Int, alpha: Float) {
        val component = Component.literal(fitCkdmText(font, snackbar.title.uppercase(Locale.ROOT), layout.textWidth, CKDM_BOLD_SMALL_FONT)).withStyle { style -> style.withFont(CKDM_BOLD_SMALL_FONT) }
        guiGraphics.drawString(font, component, x + 1, y + 1, colorAlpha(TITLE_SHADOW, alpha), false)
        guiGraphics.drawString(font, component, x, y, colorAlpha(titleColor(snackbar.type), alpha), false)
    }

    private fun renderIcon(guiGraphics: GuiGraphics, snackbar: ClientSnackbar, x: Int, y: Int, alpha: Float) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha)
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(x.toFloat(), y.toFloat(), 10.0f)
        when (snackbar.iconKind) {
            SnackbarIconKind.ITEM -> {
                pose.scale(ICON_SCALE, ICON_SCALE, 1.0f)
                guiGraphics.renderItem(snackbar.itemIcon, 0, 0)
            }
            SnackbarIconKind.PLAYER -> renderPlayerIcon(guiGraphics, snackbar, alpha)
            SnackbarIconKind.TEXTURE -> renderTextureIcon(guiGraphics, snackbar.icon, alpha)
            SnackbarIconKind.NPC -> renderNpcIcon(guiGraphics, snackbar.icon, alpha)
        }
        pose.popPose()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun renderPlayerIcon(guiGraphics: GuiGraphics, snackbar: ClientSnackbar, alpha: Float) {
        val minecraft = Minecraft.getInstance()
        val playerId = snackbar.icon.substringBefore('|').let { raw -> runCatching { java.util.UUID.fromString(raw) }.getOrNull() }
        val skin = playerId?.let { id -> minecraft.connection?.getPlayerInfo(id)?.skin }
        if (skin != null) {
            guiGraphics.setColor(1.0f, 1.0f, 1.0f, alpha)
            PlayerFaceRenderer.draw(guiGraphics, skin, 0, 0, ICON_SIZE)
            guiGraphics.setColor(1.0f, 1.0f, 1.0f, 1.0f)
        } else {
            guiGraphics.fill(0, 0, ICON_SIZE, ICON_SIZE, colorAlpha(PLAYER_FALLBACK_FILL, alpha))
            val name = snackbar.icon.substringAfter('|', snackbar.title).trim()
            if (name.isNotBlank()) guiGraphics.drawString(minecraft.font, name.take(1).uppercase(Locale.ROOT), 8, 7, colorAlpha(GENERIC_TITLE, alpha), false)
        }
    }

    private fun renderTextureIcon(guiGraphics: GuiGraphics, icon: String, alpha: Float) {
        val texture = runCatching { ResourceLocation.parse(icon) }.getOrNull() ?: return
        guiGraphics.setColor(1.0f, 1.0f, 1.0f, alpha)
        guiGraphics.blit(texture, 0, 0, ICON_SIZE, ICON_SIZE, 0.0f, 0.0f, TEXTURE_ICON_SOURCE_SIZE, TEXTURE_ICON_SOURCE_SIZE, TEXTURE_ICON_SOURCE_SIZE, TEXTURE_ICON_SOURCE_SIZE)
        guiGraphics.setColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun renderNpcIcon(guiGraphics: GuiGraphics, npcId: String, alpha: Float) {
        val cleanId = npcId.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_./-]"), "")
        val texture = if (cleanId.isBlank()) STEVE_TEXTURE else ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/entity/npc/$cleanId.png")
        guiGraphics.setColor(1.0f, 1.0f, 1.0f, alpha)
        guiGraphics.blit(texture, 0, 0, ICON_SIZE, ICON_SIZE, 8.0f, 8.0f, 8, 8, PLAYER_SKIN_SOURCE_SIZE, PLAYER_SKIN_SOURCE_SIZE)
        guiGraphics.blit(texture, 0, 0, ICON_SIZE, ICON_SIZE, 40.0f, 8.0f, 8, 8, PLAYER_SKIN_SOURCE_SIZE, PLAYER_SKIN_SOURCE_SIZE)
        guiGraphics.setColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun renderProgressBar(guiGraphics: GuiGraphics, progress: SnackbarProgress, x: Int, y: Int, width: Int, age: Long, alpha: Float) {
        val tierSize = progress.tierSize.coerceAtLeast(1)
        val linearProgress = (age.toFloat() / progress.animationMs.coerceIn(1L, 60_000L).toFloat()).coerceIn(0.0f, 1.0f)
        val animation = if (progress.animationMs <= EASED_PROGRESS_MAX_MS) easeOutCubic(linearProgress) else linearProgress
        val currentXp = progress.fromXp + ((progress.toXp - progress.fromXp) * animation).roundToInt()
        val localXp = Math.floorMod(currentXp, tierSize)
        val innerWidth = (width - 2).coerceAtLeast(1)
        val fillWidth = (innerWidth * (localXp.toFloat() / tierSize.toFloat())).roundToInt().coerceIn(0, innerWidth)
        renderProgressSlice(guiGraphics, PROGRESS_EMPTY_TEXTURE, x, y, width, PROGRESS_BAR_HEIGHT, alpha)
        if (fillWidth > 0) renderProgressSlice(guiGraphics, PROGRESS_FILL_TEXTURE, x, y, (fillWidth + 2).coerceAtMost(width), PROGRESS_BAR_HEIGHT, alpha)
    }

    private fun renderProgressSlice(guiGraphics: GuiGraphics, texture: ResourceLocation, x: Int, y: Int, width: Int, height: Int, alpha: Float) {
        if (width <= 0 || height <= 0) return
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha)
        val corner = PROGRESS_SOURCE_CORNER.coerceAtMost(width / 2).coerceAtMost(height / 2).coerceAtLeast(1)
        val innerWidth = (width - corner * 2).coerceAtLeast(0)
        val innerHeight = (height - corner * 2).coerceAtLeast(0)
        blitTexture(guiGraphics, texture, x, y, corner, corner, 0, 0, PROGRESS_SOURCE_CORNER, PROGRESS_SOURCE_CORNER)
        blitTexture(guiGraphics, texture, x + corner, y, innerWidth, corner, PROGRESS_SOURCE_CORNER, 0, PROGRESS_TEXTURE_SIZE - PROGRESS_SOURCE_CORNER * 2, PROGRESS_SOURCE_CORNER)
        blitTexture(guiGraphics, texture, x + width - corner, y, corner, corner, PROGRESS_TEXTURE_SIZE - PROGRESS_SOURCE_CORNER, 0, PROGRESS_SOURCE_CORNER, PROGRESS_SOURCE_CORNER)
        blitTexture(guiGraphics, texture, x, y + corner, corner, innerHeight, 0, PROGRESS_SOURCE_CORNER, PROGRESS_SOURCE_CORNER, PROGRESS_TEXTURE_SIZE - PROGRESS_SOURCE_CORNER * 2)
        blitTexture(guiGraphics, texture, x + corner, y + corner, innerWidth, innerHeight, PROGRESS_SOURCE_CORNER, PROGRESS_SOURCE_CORNER, PROGRESS_TEXTURE_SIZE - PROGRESS_SOURCE_CORNER * 2, PROGRESS_TEXTURE_SIZE - PROGRESS_SOURCE_CORNER * 2)
        blitTexture(guiGraphics, texture, x + width - corner, y + corner, corner, innerHeight, PROGRESS_TEXTURE_SIZE - PROGRESS_SOURCE_CORNER, PROGRESS_SOURCE_CORNER, PROGRESS_SOURCE_CORNER, PROGRESS_TEXTURE_SIZE - PROGRESS_SOURCE_CORNER * 2)
        blitTexture(guiGraphics, texture, x, y + height - corner, corner, corner, 0, PROGRESS_TEXTURE_SIZE - PROGRESS_SOURCE_CORNER, PROGRESS_SOURCE_CORNER, PROGRESS_SOURCE_CORNER)
        blitTexture(guiGraphics, texture, x + corner, y + height - corner, innerWidth, corner, PROGRESS_SOURCE_CORNER, PROGRESS_TEXTURE_SIZE - PROGRESS_SOURCE_CORNER, PROGRESS_TEXTURE_SIZE - PROGRESS_SOURCE_CORNER * 2, PROGRESS_SOURCE_CORNER)
        blitTexture(guiGraphics, texture, x + width - corner, y + height - corner, corner, corner, PROGRESS_TEXTURE_SIZE - PROGRESS_SOURCE_CORNER, PROGRESS_TEXTURE_SIZE - PROGRESS_SOURCE_CORNER, PROGRESS_SOURCE_CORNER, PROGRESS_SOURCE_CORNER)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun renderNineSlice(guiGraphics: GuiGraphics, rect: Rect, alpha: Float) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha)
        val edgeX = FRAME_WIDTH - FRAME_SOURCE_CORNER
        val edgeY = FRAME_HEIGHT - FRAME_SOURCE_CORNER
        val middleWidth = FRAME_WIDTH - FRAME_SOURCE_CORNER * 2
        val middleHeight = FRAME_HEIGHT - FRAME_SOURCE_CORNER * 2
        val innerWidth = (rect.width - FRAME_DEST_CORNER * 2).coerceAtLeast(0)
        val innerHeight = (rect.height - FRAME_DEST_CORNER * 2).coerceAtLeast(0)
        blit(guiGraphics, Rect(rect.x, rect.y, FRAME_DEST_CORNER, FRAME_DEST_CORNER), 0, 0, FRAME_SOURCE_CORNER, FRAME_SOURCE_CORNER)
        blit(guiGraphics, Rect(rect.x + FRAME_DEST_CORNER, rect.y, innerWidth, FRAME_DEST_CORNER), FRAME_SOURCE_CORNER, 0, middleWidth, FRAME_SOURCE_CORNER)
        blit(guiGraphics, Rect(rect.right - FRAME_DEST_CORNER, rect.y, FRAME_DEST_CORNER, FRAME_DEST_CORNER), edgeX, 0, FRAME_SOURCE_CORNER, FRAME_SOURCE_CORNER)
        blit(guiGraphics, Rect(rect.x, rect.y + FRAME_DEST_CORNER, FRAME_DEST_CORNER, innerHeight), 0, FRAME_SOURCE_CORNER, FRAME_SOURCE_CORNER, middleHeight)
        blit(guiGraphics, Rect(rect.x + FRAME_DEST_CORNER, rect.y + FRAME_DEST_CORNER, innerWidth, innerHeight), FRAME_SOURCE_CORNER, FRAME_SOURCE_CORNER, middleWidth, middleHeight)
        blit(guiGraphics, Rect(rect.right - FRAME_DEST_CORNER, rect.y + FRAME_DEST_CORNER, FRAME_DEST_CORNER, innerHeight), edgeX, FRAME_SOURCE_CORNER, FRAME_SOURCE_CORNER, middleHeight)
        blit(guiGraphics, Rect(rect.x, rect.bottom - FRAME_DEST_CORNER, FRAME_DEST_CORNER, FRAME_DEST_CORNER), 0, edgeY, FRAME_SOURCE_CORNER, FRAME_SOURCE_CORNER)
        blit(guiGraphics, Rect(rect.x + FRAME_DEST_CORNER, rect.bottom - FRAME_DEST_CORNER, innerWidth, FRAME_DEST_CORNER), FRAME_SOURCE_CORNER, edgeY, middleWidth, FRAME_SOURCE_CORNER)
        blit(guiGraphics, Rect(rect.right - FRAME_DEST_CORNER, rect.bottom - FRAME_DEST_CORNER, FRAME_DEST_CORNER, FRAME_DEST_CORNER), edgeX, edgeY, FRAME_SOURCE_CORNER, FRAME_SOURCE_CORNER)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun blit(guiGraphics: GuiGraphics, rect: Rect, sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int) {
        if (rect.width <= 0 || rect.height <= 0) return
        guiGraphics.blit(FRAME_TEXTURE, rect.x, rect.y, rect.width, rect.height, sourceX.toFloat(), sourceY.toFloat(), sourceWidth, sourceHeight, FRAME_WIDTH, FRAME_HEIGHT)
    }

    private fun blitTexture(guiGraphics: GuiGraphics, texture: ResourceLocation, x: Int, y: Int, width: Int, height: Int, sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int) {
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return
        guiGraphics.blit(texture, x, y, width, height, sourceX.toFloat(), sourceY.toFloat(), sourceWidth, sourceHeight, PROGRESS_TEXTURE_SIZE, PROGRESS_TEXTURE_SIZE)
    }

    private fun layout(font: Font, snackbar: ClientSnackbar, screenWidth: Int): SnackbarLayout {
        val width = screenWidth.coerceAtMost(MAX_WIDTH + PAD_X * 2).coerceAtLeast(MIN_WIDTH)
        val textWidth = width - PAD_X * 2 - ICON_SLOT_WIDTH
        val lines = wrap(font, snackbar.content, textWidth).take(MAX_CONTENT_LINES)
        val titleOnly = lines.isEmpty() && snackbar.progress == null
        val progressHeight = if (snackbar.progress != null) progressBarTopGap(lines) + PROGRESS_BAR_HEIGHT else 0
        val textHeight = if (titleOnly) TITLE_HEIGHT else TITLE_HEIGHT + if (lines.isEmpty()) progressHeight else CONTENT_GAP + lines.size * CONTENT_LINE_HEIGHT + progressHeight
        val contentHeight = maxOf(ICON_SIZE, textHeight)
        return SnackbarLayout(width, contentHeight + PAD_TOP + PAD_BOTTOM, contentHeight, textWidth, titleOnly, lines)
    }

    private fun progressBarTopGap(lines: List<String>): Int = if (lines.isEmpty()) PROGRESS_TOP_GAP else PROGRESS_AFTER_CONTENT_GAP

    private fun wrap(font: Font, text: String, maxWidth: Int): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var line = ""
        words.forEach { word ->
            if (font.width(word) > maxWidth) {
                if (line.isNotBlank()) {
                    lines += line
                    line = ""
                }
                lines += splitLongWord(font, word, maxWidth)
                return@forEach
            }
            val candidate = if (line.isBlank()) word else "$line $word"
            if (font.width(candidate) <= maxWidth || line.isBlank()) line = candidate
            else {
                lines += line
                line = word
            }
        }
        if (line.isNotBlank()) lines += line
        return lines
    }

    private fun splitLongWord(font: Font, word: String, maxWidth: Int): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        word.forEach { character ->
            val candidate = line + character
            if (line.isNotEmpty() && font.width(candidate) > maxWidth) {
                lines += line
                line = character.toString()
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private fun fitCkdmText(font: Font, text: String, maxWidth: Int, fontId: ResourceLocation): String {
        if (font.width(ckdmText(text, fontId)) <= maxWidth) return text
        val suffix = "..."
        var trimmed = text
        while (trimmed.isNotEmpty() && font.width(ckdmText(trimmed + suffix, fontId)) > maxWidth) trimmed = trimmed.dropLast(1)
        return trimmed + suffix
    }

    private fun ckdmText(text: String, fontId: ResourceLocation): Component =
        Component.literal(text).withStyle { style -> style.withFont(fontId) }

    private fun itemStack(raw: String): ItemStack {
        val id = runCatching { ResourceLocation.parse(raw) }.getOrNull() ?: return ItemStack(Items.BARRIER)
        val item = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.BARRIER)
        return ItemStack(item)
    }

    private fun playSound(raw: String) {
        val sound = when (raw) {
            SnackbarSounds.SUCCESS, SnackbarSounds.SALE -> SoundEvents.EXPERIENCE_ORB_PICKUP
            SnackbarSounds.ERROR -> SoundEvents.NOTE_BLOCK_BASS.value()
            SnackbarSounds.REWARD -> SoundEvents.PLAYER_LEVELUP
            SnackbarSounds.TRADE -> SoundEvents.NOTE_BLOCK_CHIME.value()
            SnackbarSounds.GENERIC -> SoundEvents.NOTE_BLOCK_PLING.value()
            else -> null
        } ?: return
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(sound, 1.18f, 0.65f))
    }

    private fun titleColor(type: SnackbarType): Int = when (type) {
        SnackbarType.ERROR -> ERROR_TITLE
        SnackbarType.SUCCESS -> SUCCESS_TITLE
        SnackbarType.GENERIC -> GENERIC_TITLE
    }

    private fun easeOutCubic(progress: Float): Float {
        val inverse = 1.0f - progress.coerceIn(0.0f, 1.0f)
        return 1.0f - inverse * inverse * inverse
    }

    private fun colorAlpha(color: Int, alphaFactor: Float): Int = ((((color ushr 24) and 0xFF) * alphaFactor).toInt().coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    private data class QueuedSnackbar(val iconKind: SnackbarIconKind, val icon: String, val title: String, val content: String, val type: SnackbarType, val sound: String, val durationMs: Long, val progress: SnackbarProgress?) {
        fun sameDisplayAs(other: QueuedSnackbar): Boolean =
            iconKind == other.iconKind &&
                icon == other.icon &&
                title == other.title &&
                content == other.content &&
                type == other.type &&
                progress == other.progress
    }

    private data class ClientSnackbar(val id: Long, val iconKind: SnackbarIconKind, val icon: String, val itemIcon: ItemStack, val title: String, val content: String, val type: SnackbarType, val sound: String, val createdAtMs: Long, val durationMs: Long, val progress: SnackbarProgress?) {
        fun sameDisplayAs(other: QueuedSnackbar): Boolean =
            iconKind == other.iconKind &&
                icon == other.icon &&
                title == other.title &&
                content == other.content &&
                type == other.type &&
                progress == other.progress
    }
    private data class SnackbarLayout(val width: Int, val height: Int, val contentHeight: Int, val textWidth: Int, val titleOnly: Boolean, val contentLines: List<String>)
    private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right: Int get() = x + width
        val bottom: Int get() = y + height
    }

    private val FRAME_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_container_yellow.png")
    private val PROGRESS_EMPTY_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_progress_empty.png")
    private val PROGRESS_FILL_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_progress_fill.png")
    private val CKDM_BOLD_SMALL_FONT = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold_small")
    private const val FRAME_WIDTH = 1646
    private const val FRAME_HEIGHT = 256
    private const val FRAME_SOURCE_CORNER = 75
    private const val FRAME_DEST_CORNER = 14
    private const val MIN_WIDTH = 154
    private const val MAX_WIDTH = 218
    private const val PAD_X = 7
    private const val PAD_TOP = 9
    private const val PAD_BOTTOM = 6
    private const val ICON_SIZE = 18
    private const val ICON_SCALE = ICON_SIZE / 16.0f
    private const val TEXTURE_ICON_SOURCE_SIZE = 16
    private const val PLAYER_SKIN_SOURCE_SIZE = 64
    private const val ICON_SLOT_WIDTH = 25
    private const val TITLE_HEIGHT = 8
    private const val CONTENT_GAP = 2
    private const val CONTENT_LINE_HEIGHT = 9
    private const val MAX_CONTENT_LINES = 3
    private const val PROGRESS_TOP_GAP = 6
    private const val PROGRESS_AFTER_CONTENT_GAP = 4
    private const val PROGRESS_BAR_HEIGHT = 8
    private const val EASED_PROGRESS_MAX_MS = 1_500L
    private const val PROGRESS_TEXTURE_SIZE = 16
    private const val PROGRESS_SOURCE_CORNER = 4
    private const val HOTBAR_OFFSET_Y = 54
    private const val DIALOG_TOP_OFFSET_Y = 14
    private const val STACK_GAP = 6
    private const val MAX_ACTIVE = 3
    private const val MAX_EXTENDED_DURATION_MS = 60_000L
    private const val ENTER_MS = 260L
    private const val EXIT_MS = 180L
    private const val SLIDE_UP_OFFSET = 14.0f
    private const val GENERIC_TITLE = 0xFFFFFFFF.toInt()
    private const val ERROR_TITLE = 0xFFFF5A52.toInt()
    private const val SUCCESS_TITLE = 0xFFFFD56E.toInt()
    private const val TITLE_SHADOW = 0xCC050505.toInt()
    private const val CONTENT_COLOR = 0xFFEDE6DA.toInt()
    private const val PLAYER_FALLBACK_FILL = 0xCC2F3545.toInt()
    private val STEVE_TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png")
}
