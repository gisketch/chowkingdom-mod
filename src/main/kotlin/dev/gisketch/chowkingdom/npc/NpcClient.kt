package dev.gisketch.chowkingdom.npc

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.compat.PehkuiScaleBridge
import dev.gisketch.chowkingdom.discord.DiscordQuickSkinSupport
import dev.gisketch.chowkingdom.mixin.GuiGraphicsAccessor
import dev.gisketch.chowkingdom.roles.RoleNametagIcons
import dev.gisketch.chowkingdom.roles.RolesClientState
import dev.gisketch.chowkingdom.roles.roleIconStack
import dev.gisketch.chowkingdom.roles.roleIconTexture
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.CameraType
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ComponentRenderUtils
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.MutableComponent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.ChatVisiblity
import net.minecraft.world.inventory.tooltip.TooltipComponent
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent
import net.neoforged.neoforge.client.event.RenderTooltipEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.NeoForge
import com.mojang.datafixers.util.Either
import dev.kosmx.playerAnim.api.TransformType
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode
import dev.kosmx.playerAnim.api.layered.IAnimation
import dev.kosmx.playerAnim.api.layered.ModifierLayer
import dev.kosmx.playerAnim.core.util.Vec3f
import dev.kosmx.playerAnim.core.data.KeyframeAnimation
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry
import me.Thelnfamous1.mobplayeranimator.api.MobAnimationAccess
import net.bettercombat.client.animation.CustomAnimationPlayer
import java.util.Locale
import java.util.UUID
import java.io.ByteArrayInputStream
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.joml.Quaternionf
import org.joml.Vector3f
import software.bernie.geckolib.cache.`object`.GeoBone
import software.bernie.geckolib.model.GeoModel
import software.bernie.geckolib.renderer.GeoEntityRenderer
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer

object NpcClient {
    private val WORLD_CHAT_HEADS_LAYER_ID = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "npc_world_chat_heads")
    private val activeBalloons = mutableMapOf<Int, NpcBalloonLine>()
    private val activeFriendshipDeltas = mutableMapOf<Int, NpcFriendshipDeltaLine>()
    private val balloonVisibility = mutableMapOf<Int, NpcBalloonVisibility>()
    private val worldChatEntries = mutableListOf<NpcWorldChatEntry>()
    private val quickSkinChatTextures = mutableMapOf<UUID, ResourceLocation?>()
    private val skippedTalkResponses = mutableSetOf<Long>()
    private val playerlikeAnimationLayers = mutableMapOf<Int, NpcPlayerlikeAnimationLayer>()

    @JvmStatic
    fun openDialog(payload: NpcDialogPayload) {
        Minecraft.getInstance().setScreen(NpcDialogScreen(payload))
    }

    @JvmStatic
    fun isBossDialogOpen(): Boolean = (Minecraft.getInstance().screen as? NpcDialogScreen)?.isBossMode() == true

    @JvmStatic
    fun showBalloon(payload: NpcBalloonPayload) {
        val now = System.currentTimeMillis()
        val expiresAtMs = now + payload.durationTicks.coerceIn(20, 240) * 50L
        friendshipDeltaLine(payload.message, now, expiresAtMs)?.let { delta ->
            activeFriendshipDeltas[payload.npcEntityId] = delta
            return
        }
        activeBalloons[payload.npcEntityId] = NpcBalloonLine(payload.message, now, expiresAtMs)
    }

    @JvmStatic
    fun receiveTalkResponse(payload: NpcTalkResponsePayload) {
        (Minecraft.getInstance().screen as? NpcDialogScreen)?.receiveTalkResponse(payload)
    }

    @JvmStatic
    fun receiveWorldChat(payload: NpcWorldChatPayload) {
        val minecraft = Minecraft.getInstance()
        val line = worldChatLine(payload)
        minecraft.gui.chat.addMessage(line)
        val lineCount = worldChatLineCount(minecraft, line)
        val targetHeadX = if (payload.targetKind == "thinking") 0 else minecraft.font.width(worldChatTargetPrefix(payload.npcName))
        worldChatEntries.add(0, NpcWorldChatEntry(payload.npcId, payload.targetName, payload.targetId, payload.targetKind, minecraft.gui.guiTicks, lineCount, targetHeadX))
        while (worldChatEntries.size > MAX_WORLD_CHAT_HEAD_ENTRIES) worldChatEntries.removeLast()
    }

    @JvmStatic
    fun reloadAnimationResources() {
        Minecraft.getInstance().reloadResourcePacks()
    }

    fun skipTalkResponse(responseToken: Long) {
        if (responseToken != 0L) skippedTalkResponses += responseToken
    }

    fun shouldIgnoreTalkResponse(responseToken: Long): Boolean = responseToken != 0L && skippedTalkResponses.remove(responseToken)

    @JvmStatic
    fun renderBalloon(entity: LivingEntity, poseStack: PoseStack, bufferSource: MultiBufferSource, font: Font, packedLight: Int) {
        if (entity !is ChowNpcEntity || entity.isInvisible || !entity.isAlive) return
        val now = System.currentTimeMillis()
        val balloon = activeBalloons[entity.id]
        if (balloon != null && balloon.expiresAtMs <= now) {
            activeBalloons.remove(entity.id)
        }
        val activeBalloon = activeBalloons[entity.id]
        val delta = activeFriendshipDeltas[entity.id]
        if (delta != null && delta.expiresAtMs <= now) activeFriendshipDeltas.remove(entity.id)
        val activeDelta = activeFriendshipDeltas[entity.id]
        if (activeBalloon == null && activeDelta == null) {
            balloonVisibility.remove(entity.id)
            return
        }

        val minecraft = Minecraft.getInstance()
        val targetVisibility = 1.0f
        val visibilityAlpha = balloonVisibilityAlpha(entity.id, targetVisibility, now)
        if (visibilityAlpha <= 0.0f) return
        val guiGraphics = GuiGraphicsAccessor.`chowkingdom$create`(minecraft, poseStack, minecraft.renderBuffers().bufferSource())
        val rotation = Axis.YP.rotationDegrees(toEulerXyzDegrees(minecraft.entityRenderDispatcher.cameraOrientation()).y + 180.0f)
        if (activeBalloon == null) {
            renderFriendshipDeltaPopup(entity, poseStack, guiGraphics, rotation, font, activeDelta, now, visibilityAlpha)
            return
        }
        val styledBalloon = balloonStyle(activeBalloon.message)
        val balloonIcon = balloonIcon(styledBalloon.message)
        val hasBalloonIcon = balloonIcon != null
        val balloonMessage = normalizeBalloonDisplaySpacing(balloonIcon?.let { styledBalloon.message.removePrefix(it.marker).trimStart() } ?: styledBalloon.message)
        val priorityBalloon = styledBalloon.gold || styledBalloon.green
        val text = if (priorityBalloon) {
            Component.literal(balloonMessage.uppercase(Locale.ROOT)).withStyle { style -> style.withFont(BALLOON_CKDM_FONT) }
        } else {
            FormattedText.of(balloonMessage)
        }
        val lines = font.split(text, BALLOON_MAX_TEXT_WIDTH)
        if (lines.isEmpty()) {
            renderFriendshipDeltaPopup(entity, poseStack, guiGraphics, rotation, font, activeDelta, now, visibilityAlpha)
            return
        }
        val alpha = animationAlpha(activeBalloon.startedAtMs, activeBalloon.expiresAtMs, now, BALLOON_FADE_MS) * visibilityAlpha
        val iconSpace = if (hasBalloonIcon) BALLOON_ICON_SIZE + 2 else 0
        val balloonPaddingX = if (priorityBalloon) BALLOON_PRIORITY_PADDING_X else BALLOON_PADDING
        val balloonPaddingY = if (priorityBalloon) BALLOON_PRIORITY_PADDING_Y else BALLOON_PADDING
        val balloonLineHeight = if (priorityBalloon) BALLOON_PRIORITY_LINE_HEIGHT else BALLOON_LINE_HEIGHT
        val greatestTextWidth = lines.mapIndexed { index, line -> font.width(line) + if (index == 0) iconSpace else 0 }.maxOrNull() ?: 0
        val balloonWidth = (greatestTextWidth + balloonPaddingX * 2).coerceAtLeast(BALLOON_MIN_WIDTH)
        val balloonHeight = lines.size * balloonLineHeight + balloonPaddingY * 2
        val balloonX = -balloonWidth / 2
        val balloonY = -balloonHeight

        poseStack.pushPose()
        applyInversePehkuiBillboardScale(entity, poseStack)
        poseStack.translate(0.0, entity.bbHeight + BALLOON_ENTITY_Y_OFFSET, 0.0)
        poseStack.mulPose(rotation)
        val animatedScale = BALLOON_SCALE * (0.88f + 0.12f * alpha)
        poseStack.scale(-animatedScale, -animatedScale, animatedScale)

        RenderSystem.enableBlend()
        RenderSystem.enableDepthTest()
        RenderSystem.enablePolygonOffset()
        RenderSystem.polygonOffset(3.0f, 3.0f)
        if (styledBalloon.green) {
            RenderSystem.setShaderColor(0.23f, 0.72f, 0.32f, BALLOON_BG_ALPHA * alpha)
        } else if (styledBalloon.gold) {
            RenderSystem.setShaderColor(1.0f, 0.78f, 0.23f, BALLOON_BG_ALPHA * alpha)
        } else {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, BALLOON_BG_ALPHA * alpha)
        }
        renderBalloonNineSlice(guiGraphics, balloonX, balloonY, balloonWidth, balloonHeight)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        RenderSystem.polygonOffset(0.0f, 0.0f)
        RenderSystem.disablePolygonOffset()
        RenderSystem.disableBlend()

        RenderSystem.disableDepthTest()
        var textY = balloonY + balloonPaddingY
        lines.forEachIndexed { index, line ->
            val lineIconSpace = if (hasBalloonIcon && index == 0) iconSpace else 0
            val lineWidth = font.width(line) + lineIconSpace
            val iconX = -lineWidth / 2
            val textX = iconX + lineIconSpace
            if (balloonIcon != null && index == 0) {
                RenderSystem.enableBlend()
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha)
                guiGraphics.blit(balloonIcon.texture, iconX, textY, BALLOON_ICON_SIZE, BALLOON_ICON_SIZE, 0.0f, 0.0f, BALLOON_ICON_TEXTURE_SIZE, BALLOON_ICON_TEXTURE_SIZE, BALLOON_ICON_TEXTURE_SIZE, BALLOON_ICON_TEXTURE_SIZE)
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
            }
            if (priorityBalloon) {
                guiGraphics.drawString(font, line, textX, textY, withAlpha(BALLOON_PRIORITY_TEXT_COLOR, alpha), false)
            } else {
                guiGraphics.drawString(font, line, textX, textY, withAlpha(BALLOON_TEXT_COLOR, alpha), false)
            }
            textY += balloonLineHeight
        }
        guiGraphics.flush()
        RenderSystem.enableDepthTest()
        poseStack.popPose()
        renderFriendshipDeltaPopup(entity, poseStack, guiGraphics, rotation, font, activeDelta, now, visibilityAlpha)
    }

    fun register(modBus: IEventBus) {
        modBus.addListener(::registerRenderers)
        modBus.addListener(::registerGuiLayers)
        modBus.addListener(::registerTooltipFactories)
        NeoForge.EVENT_BUS.addListener(::hideHotbarDuringDialog)
        NeoForge.EVENT_BUS.addListener(::gatherRentContractTooltip)
    }

    @JvmStatic
    fun isDialogOpen(): Boolean = Minecraft.getInstance().screen is NpcDialogScreen

    private fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerEntityRenderer(NpcFeature.NPC_ENTITY.get()) { context -> ChowNpcDelegatingRenderer(context) }
        event.registerBlockEntityRenderer(NpcFeature.CAMPING_BLOCK_ENTITY.get()) { CampingBlockRenderer() }
    }

    fun ensurePlayerlikeAnimationLayer(entity: ChowNpcEntity) {
        if (!entity.level().isClientSide) return
        val existing = playerlikeAnimationLayers[entity.id]
        if (existing?.entity === entity) return
        playerlikeAnimationLayers.entries.removeIf { (_, layer) -> !layer.entity.isAlive || layer.entity.level() !== entity.level() || layer.entity.id == entity.id }
        val layer = NpcPlayerlikeAnimationLayer(entity)
        runCatching {
            MobAnimationAccess.getMobAnimLayer(entity).addAnimLayer(2000, layer)
            playerlikeAnimationLayers[entity.id] = layer
            ChowKingdomMod.LOGGER.info("Attached NPC playerlike animation layer to {} ({})", entity.npcId, entity.id)
        }.onFailure { error ->
            ChowKingdomMod.LOGGER.warn("Failed to attach NPC playerlike animation layer to {} ({}).", entity.npcId, entity.id, error)
        }
    }

    private fun registerGuiLayers(event: RegisterGuiLayersEvent) {
        event.registerAbove(VanillaGuiLayers.CHAT, WORLD_CHAT_HEADS_LAYER_ID) { guiGraphics, _ -> renderWorldChatHeads(guiGraphics) }
    }

    private fun registerTooltipFactories(event: RegisterClientTooltipComponentFactoriesEvent) {
        event.register(NpcRentContractTooltip::class.java, ::NpcRentContractClientTooltip)
        event.register(NpcJobApplicationTooltip::class.java, ::NpcJobApplicationClientTooltip)
    }

    private fun gatherRentContractTooltip(event: RenderTooltipEvent.GatherComponents) {
        val npcId = NpcRentContractData.readNpcId(event.itemStack)
        if (npcId.isNotBlank()) {
            val definition = NpcConfig.get(npcId)
            val name = definition?.name ?: npcId
            event.tooltipElements.add(1.coerceAtMost(event.tooltipElements.size), Either.right(NpcRentContractTooltip(npcId, name)))
            return
        }
        val jobNpcId = NpcJobApplicationData.readNpcId(event.itemStack)
        if (jobNpcId.isBlank()) return
        val requirements = NpcConfig.get(jobNpcId)?.workBlocks.orEmpty()
        if (requirements.isEmpty()) return
        val entries = requirements.map { requirement ->
            NpcJobApplicationTooltipEntry(workBlockIconStack(requirement), requirement.count, requirement.label())
        }
        event.tooltipElements.add(event.tooltipElements.size, Either.right(NpcJobApplicationTooltip(entries)))
    }

    private fun workBlockIconStack(requirement: NpcWorkBlockRequirementDefinition): ItemStack {
        val iconId = when (requirement.id) {
            "#minecraft:beds" -> "minecraft:red_bed"
            else -> requirement.id.removePrefix("#")
        }
        val item = runCatching { BuiltInRegistries.ITEM.get(ResourceLocation.parse(iconId)) }.getOrDefault(Items.PAPER)
            .takeUnless { resolved -> resolved == Items.AIR }
            ?: Items.PAPER
        return ItemStack(item, requirement.count.coerceIn(1, 64))
    }

    private fun hideHotbarDuringDialog(event: RenderGuiLayerEvent.Pre) {
        if (Minecraft.getInstance().screen !is NpcDialogScreen) return
        if (event.name in HIDDEN_DIALOG_HUD_LAYERS) event.isCanceled = true
    }

    private fun renderWorldChatHeads(guiGraphics: GuiGraphics) {
        val minecraft = Minecraft.getInstance()
        if (minecraft.options.hideGui || minecraft.options.chatVisibility().get() == ChatVisiblity.HIDDEN) return
        if (worldChatEntries.isEmpty()) return

        val focused = minecraft.screen is ChatScreen
        val now = minecraft.gui.guiTicks
        worldChatEntries.removeIf { entry -> !focused && now - entry.addedTick >= CHAT_FADE_TICKS }
        val scale = minecraft.gui.chat.scale
        if (scale <= 0.0) return

        val lineHeight = (9.0 * (minecraft.options.chatLineSpacing().get() + 1.0)).toInt().coerceAtLeast(1)
        val lineOffset = Math.round(-8.0 * (minecraft.options.chatLineSpacing().get() + 1.0) + 4.0 * minecraft.options.chatLineSpacing().get()).toInt()
        val chatBottom = floor((guiGraphics.guiHeight() - CHAT_BOTTOM_MARGIN) / scale).toInt()
        val headSize = (CHAT_HEAD_SIZE * scale).roundToInt().coerceAtLeast(1)
        var visualLineOffset = 0
        worldChatEntries.forEach { entry ->
            val firstLineOffset = visualLineOffset + entry.lineCount - 1
            visualLineOffset += entry.lineCount
            val age = now - entry.addedTick
            val alpha = if (focused) 1.0f else chatFade(age) * (minecraft.options.chatOpacity().get().toFloat() * 0.9f + 0.1f)
            if (alpha <= 0.02f) return@forEach
            val y = ((chatBottom - firstLineOffset * lineHeight + lineOffset) * scale).roundToInt()
            val npcX = (CHAT_LEFT_MARGIN * scale).roundToInt()
            renderNpcChatHead(guiGraphics, entry.npcId, npcX, y, headSize, alpha)
            if (entry.targetKind != "thinking") {
                val targetX = ((CHAT_LEFT_MARGIN + entry.targetHeadX) * scale).roundToInt()
                renderTargetChatHead(guiGraphics, entry.targetKind, entry.targetId, entry.targetName, targetX, y, headSize, alpha)
                renderTargetRoleChatIcons(guiGraphics, entry, y, alpha, scale)
            }
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun renderNpcChatHead(guiGraphics: GuiGraphics, npcId: String, x: Int, y: Int, size: Int, alpha: Float) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha)
        val texture = npcTexture(npcId)
        guiGraphics.blit(texture, x, y, size, size, 8.0f, 8.0f, 8, 8, 64, 64)
        guiGraphics.blit(texture, x, y, size, size, 40.0f, 8.0f, 8, 8, 64, 64)
    }

    private fun renderTargetChatHead(guiGraphics: GuiGraphics, targetKind: String, playerId: UUID?, name: String, x: Int, y: Int, size: Int, alpha: Float) {
        if (targetKind == "discord") return renderDiscordChatHead(guiGraphics, x, y, size, alpha)
        val minecraft = Minecraft.getInstance()
        val quickSkinTexture = playerId?.let(::quickSkinChatTexture)
        val skin = playerId?.let { id -> minecraft.connection?.getPlayerInfo(id)?.skin }
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha)
        when {
            quickSkinTexture != null -> guiGraphics.blit(quickSkinTexture, x, y, size, size, 0.0f, 0.0f, QUICKSKIN_HEAD_TEXTURE_SIZE, QUICKSKIN_HEAD_TEXTURE_SIZE, QUICKSKIN_HEAD_TEXTURE_SIZE, QUICKSKIN_HEAD_TEXTURE_SIZE)
            skin != null -> PlayerFaceRenderer.draw(guiGraphics, skin, x, y, size)
            else -> renderFallbackChatHead(guiGraphics, name, x, y, size, alpha)
        }
    }

    private fun renderTargetRoleChatIcons(guiGraphics: GuiGraphics, entry: NpcWorldChatEntry, y: Int, alpha: Float, chatScale: Double) {
        val roles = entry.targetId?.let(RolesClientState::iconsFor) ?: return
        if (roles.jobIcons.isEmpty() && roles.classIcons.isEmpty()) return
        val font = Minecraft.getInstance().font
        val targetTextStart = CHAT_LEFT_MARGIN + entry.targetHeadX + font.width(CHAT_HEAD_SPACES)
        val iconSize = (CHAT_ROLE_ICON_SIZE * chatScale).roundToInt().coerceAtLeast(1)
        val iconStep = ((CHAT_ROLE_ICON_SIZE + CHAT_ROLE_ICON_GAP) * chatScale).roundToInt().coerceAtLeast(iconSize)
        var jobX = (targetTextStart * chatScale).roundToInt()
        roles.jobIcons.forEach { icon ->
            renderRoleChatIcon(guiGraphics, icon, jobX, y, iconSize, alpha)
            jobX += iconStep
        }
        var classX = ((targetTextStart + font.width(roleChatSpaces(roles.jobIcons.size)) + font.width(entry.targetName)) * chatScale).roundToInt()
        roles.classIcons.forEach { icon ->
            renderRoleChatIcon(guiGraphics, icon, classX, y, iconSize, alpha)
            classX += iconStep
        }
    }

    private fun renderRoleChatIcon(guiGraphics: GuiGraphics, rawIcon: String, x: Int, y: Int, size: Int, alpha: Float) {
        val stack = roleIconStack(rawIcon)
        if (!stack.isEmpty) {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha)
            guiGraphics.pose().pushPose()
            val scale = size / 16.0f
            guiGraphics.pose().translate(x.toFloat(), y.toFloat(), 0.0f)
            guiGraphics.pose().scale(scale, scale, 1.0f)
            guiGraphics.renderItem(stack, 0, 0)
            guiGraphics.pose().popPose()
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
            return
        }
        val texture = roleIconTexture(rawIcon) ?: return
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha)
        guiGraphics.blit(texture, x, y, size, size, 0.0f, 0.0f, CHAT_ROLE_ICON_TEXTURE_SIZE, CHAT_ROLE_ICON_TEXTURE_SIZE, CHAT_ROLE_ICON_TEXTURE_SIZE, CHAT_ROLE_ICON_TEXTURE_SIZE)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun renderDiscordChatHead(guiGraphics: GuiGraphics, x: Int, y: Int, size: Int, alpha: Float) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha)
        guiGraphics.blit(DISCORD_CHAT_ICON, x, y, size, size, 0.0f, 0.0f, DISCORD_CHAT_ICON_TEXTURE_SIZE, DISCORD_CHAT_ICON_TEXTURE_SIZE, DISCORD_CHAT_ICON_TEXTURE_SIZE, DISCORD_CHAT_ICON_TEXTURE_SIZE)
    }

    private fun renderFallbackChatHead(guiGraphics: GuiGraphics, name: String, x: Int, y: Int, size: Int, alpha: Float) {
        val color = withAlpha(CHAT_HEAD_FALLBACK_FILL, alpha)
        guiGraphics.fill(x, y, x + size, y + size, color)
        val letter = name.trim().take(1).uppercase(Locale.ROOT).ifBlank { "?" }
        val textX = x + ((size - Minecraft.getInstance().font.width(letter)) / 2).coerceAtLeast(0)
        val textY = y + ((size - 8) / 2).coerceAtLeast(0)
        guiGraphics.drawString(Minecraft.getInstance().font, letter, textX, textY, withAlpha(0x00FFFFFF, alpha), false)
    }

    private fun quickSkinChatTexture(playerId: UUID): ResourceLocation? {
        if (quickSkinChatTextures.containsKey(playerId)) return quickSkinChatTextures[playerId]
        val texture = runCatching {
            val bytes = DiscordQuickSkinSupport.quickSkinHeadPng(playerId) ?: return@runCatching null
            val image = NativeImage.read(ByteArrayInputStream(bytes))
            val id = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "quickskin/chat_head/${playerId.toString().replace("-", "_")}")
            Minecraft.getInstance().textureManager.register(id, DynamicTexture(image))
            id
        }.getOrNull()
        quickSkinChatTextures[playerId] = texture
        return texture
    }

    private fun worldChatLine(payload: NpcWorldChatPayload): Component = Component.empty()
        .append(Component.literal(CHAT_HEAD_SPACES))
        .append(Component.literal(payload.npcName).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
        .also { component ->
            if (payload.targetKind == "thinking") {
                component.append(Component.literal(" ${payload.message}").withStyle(ChatFormatting.GRAY))
            } else {
                val roles = payload.targetId?.let(RolesClientState::iconsFor) ?: RoleNametagIcons()
                component.append(Component.literal(" > ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(CHAT_HEAD_SPACES))
                    .append(Component.literal(roleChatSpaces(roles.jobIcons.size)))
                    .append(Component.literal(payload.targetName).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                    .append(Component.literal(roleChatSpaces(roles.classIcons.size)))
                    .append(Component.literal(": ${payload.message}").withStyle(ChatFormatting.GRAY))
            }
        }

    private fun roleChatSpaces(count: Int): String = if (count <= 0) "" else CHAT_ROLE_ICON_SPACES.repeat(count)

    private fun worldChatTargetPrefix(npcName: String): Component = Component.empty()
        .append(Component.literal(CHAT_HEAD_SPACES))
        .append(Component.literal(npcName).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
        .append(Component.literal(" > ").withStyle(ChatFormatting.GRAY))

    private fun worldChatLineCount(minecraft: Minecraft, line: Component): Int {
        val maxWidth = floor(minecraft.gui.chat.width / minecraft.gui.chat.scale).toInt().coerceAtLeast(1)
        return ComponentRenderUtils.wrapComponents(line, maxWidth, minecraft.font).size.coerceAtLeast(1)
    }

    private fun chatFade(ageTicks: Int): Float {
        val progress = (1.0 - (ageTicks.coerceAtLeast(0) / CHAT_FADE_TICKS.toDouble())).coerceIn(0.0, 1.0) * 10.0
        val clamped = progress.coerceIn(0.0, 1.0)
        return (clamped * clamped).toFloat()
    }

    private fun withAlpha(rgb: Int, alpha: Float): Int = ((alpha.coerceIn(0.0f, 1.0f) * 255).roundToInt() shl 24) or (rgb and 0x00FFFFFF)

    private fun friendshipDeltaLine(message: String, startedAtMs: Long, expiresAtMs: Long): NpcFriendshipDeltaLine? = when {
        message.startsWith(HEART_BALLOON_MARKER) -> NpcFriendshipDeltaLine(message.removePrefix(HEART_BALLOON_MARKER).trimStart(), HEART_BALLOON_ICON, FRIENDSHIP_DELTA_WORLD_POSITIVE, startedAtMs, expiresAtMs)
        message.startsWith(ANGRY_BALLOON_MARKER) -> NpcFriendshipDeltaLine(message.removePrefix(ANGRY_BALLOON_MARKER).trimStart(), ANGRY_BALLOON_ICON, FRIENDSHIP_DELTA_WORLD_NEGATIVE, startedAtMs, expiresAtMs)
        else -> null
    }

    private fun animationAlpha(startedAtMs: Long, expiresAtMs: Long, now: Long, fadeMs: Long): Float {
        val fadeIn = ((now - startedAtMs).toFloat() / fadeMs).coerceIn(0.0f, 1.0f)
        val fadeOut = ((expiresAtMs - now).toFloat() / fadeMs).coerceIn(0.0f, 1.0f)
        return minOf(fadeIn, fadeOut)
    }

    private fun balloonVisibilityAlpha(entityId: Int, targetAlpha: Float, now: Long): Float {
        val visibility = balloonVisibility.getOrPut(entityId) { NpcBalloonVisibility(0.0f, now) }
        val step = ((now - visibility.updatedAtMs).coerceAtLeast(0L).toFloat() / BALLOON_CULL_FADE_MS).coerceIn(0.0f, 1.0f)
        visibility.alpha = if (visibility.alpha < targetAlpha) {
            (visibility.alpha + step).coerceAtMost(targetAlpha)
        } else {
            (visibility.alpha - step).coerceAtLeast(targetAlpha)
        }
        visibility.updatedAtMs = now
        return visibility.alpha
    }

    private fun renderFriendshipDeltaPopup(entity: LivingEntity, poseStack: PoseStack, guiGraphics: GuiGraphics, rotation: Quaternionf, font: Font, delta: NpcFriendshipDeltaLine?, now: Long, visibilityAlpha: Float) {
        if (delta == null) return
        val duration = (delta.expiresAtMs - delta.startedAtMs).coerceAtLeast(1L)
        val progress = ((now - delta.startedAtMs).toFloat() / duration).coerceIn(0.0f, 1.0f)
        val alpha = animationAlpha(delta.startedAtMs, delta.expiresAtMs, now, FRIENDSHIP_DELTA_WORLD_FADE_MS) * visibilityAlpha
        if (alpha <= 0.0f) return
        val text = delta.text.ifBlank { return }
        val popupX = FRIENDSHIP_DELTA_WORLD_X
        val popupY = FRIENDSHIP_DELTA_WORLD_Y - (progress * FRIENDSHIP_DELTA_WORLD_SLIDE).roundToInt()

        poseStack.pushPose()
        applyInversePehkuiBillboardScale(entity, poseStack)
        poseStack.translate(0.0, entity.bbHeight + FRIENDSHIP_DELTA_WORLD_ENTITY_Y_OFFSET, 0.0)
        poseStack.mulPose(rotation)
        val scale = FRIENDSHIP_DELTA_WORLD_SCALE * (0.88f + 0.12f * alpha)
        poseStack.scale(-scale, -scale, scale)

        RenderSystem.enableBlend()
        RenderSystem.enableDepthTest()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha)
        guiGraphics.blit(delta.icon, popupX, popupY, FRIENDSHIP_DELTA_WORLD_ICON_SIZE, FRIENDSHIP_DELTA_WORLD_ICON_SIZE, 0.0f, 0.0f, BALLOON_ICON_TEXTURE_SIZE, BALLOON_ICON_TEXTURE_SIZE, BALLOON_ICON_TEXTURE_SIZE, BALLOON_ICON_TEXTURE_SIZE)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        guiGraphics.drawString(font, text, popupX + FRIENDSHIP_DELTA_WORLD_ICON_SIZE + 2, popupY + 1, withAlpha(delta.color, alpha), false)
        guiGraphics.flush()
        RenderSystem.disableBlend()
        poseStack.popPose()
    }

    private data class NpcBalloonLine(val message: String, val startedAtMs: Long, val expiresAtMs: Long)

    private data class NpcFriendshipDeltaLine(val text: String, val icon: ResourceLocation, val color: Int, val startedAtMs: Long, val expiresAtMs: Long)

    private data class NpcBalloonVisibility(var alpha: Float, var updatedAtMs: Long)

    private data class NpcWorldChatEntry(
        val npcId: String,
        val targetName: String,
        val targetId: UUID?,
        val targetKind: String,
        val addedTick: Int,
        val lineCount: Int,
        val targetHeadX: Int,
    )

    private val HIDDEN_DIALOG_HUD_LAYERS = setOf(
        VanillaGuiLayers.HOTBAR,
        VanillaGuiLayers.EXPERIENCE_BAR,
        VanillaGuiLayers.PLAYER_HEALTH,
        VanillaGuiLayers.ARMOR_LEVEL,
        VanillaGuiLayers.FOOD_LEVEL,
        VanillaGuiLayers.AIR_LEVEL,
        VanillaGuiLayers.VEHICLE_HEALTH,
    )

    private fun renderBalloonNineSlice(guiGraphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        val middleWidth = (width - BALLOON_CORNER * 2).coerceAtLeast(0)
        val middleHeight = (height - BALLOON_CORNER * 2).coerceAtLeast(0)
        guiGraphics.blit(BALLOON_TEXTURE, x, y, BALLOON_CORNER, BALLOON_CORNER, 0.0f, 0.0f, BALLOON_CORNER, BALLOON_CORNER, BALLOON_TEXTURE_SIZE, BALLOON_TEXTURE_SIZE)
        guiGraphics.blit(BALLOON_TEXTURE, x + BALLOON_CORNER, y, middleWidth, BALLOON_CORNER, BALLOON_CORNER.toFloat(), 0.0f, BALLOON_TEXTURE_SIZE - BALLOON_CORNER * 2, BALLOON_CORNER, BALLOON_TEXTURE_SIZE, BALLOON_TEXTURE_SIZE)
        guiGraphics.blit(BALLOON_TEXTURE, x + width - BALLOON_CORNER, y, BALLOON_CORNER, BALLOON_CORNER, (BALLOON_TEXTURE_SIZE - BALLOON_CORNER).toFloat(), 0.0f, BALLOON_CORNER, BALLOON_CORNER, BALLOON_TEXTURE_SIZE, BALLOON_TEXTURE_SIZE)
        guiGraphics.blit(BALLOON_TEXTURE, x, y + BALLOON_CORNER, BALLOON_CORNER, middleHeight, 0.0f, BALLOON_CORNER.toFloat(), BALLOON_CORNER, BALLOON_TEXTURE_SIZE - BALLOON_CORNER * 2, BALLOON_TEXTURE_SIZE, BALLOON_TEXTURE_SIZE)
        guiGraphics.blit(BALLOON_TEXTURE, x + BALLOON_CORNER, y + BALLOON_CORNER, middleWidth, middleHeight, BALLOON_CORNER.toFloat(), BALLOON_CORNER.toFloat(), BALLOON_TEXTURE_SIZE - BALLOON_CORNER * 2, BALLOON_TEXTURE_SIZE - BALLOON_CORNER * 2, BALLOON_TEXTURE_SIZE, BALLOON_TEXTURE_SIZE)
        guiGraphics.blit(BALLOON_TEXTURE, x + width - BALLOON_CORNER, y + BALLOON_CORNER, BALLOON_CORNER, middleHeight, (BALLOON_TEXTURE_SIZE - BALLOON_CORNER).toFloat(), BALLOON_CORNER.toFloat(), BALLOON_CORNER, BALLOON_TEXTURE_SIZE - BALLOON_CORNER * 2, BALLOON_TEXTURE_SIZE, BALLOON_TEXTURE_SIZE)
        guiGraphics.blit(BALLOON_TEXTURE, x, y + height - BALLOON_CORNER, BALLOON_CORNER, BALLOON_CORNER, 0.0f, (BALLOON_TEXTURE_SIZE - BALLOON_CORNER).toFloat(), BALLOON_CORNER, BALLOON_CORNER, BALLOON_TEXTURE_SIZE, BALLOON_TEXTURE_SIZE)
        guiGraphics.blit(BALLOON_TEXTURE, x + BALLOON_CORNER, y + height - BALLOON_CORNER, middleWidth, BALLOON_CORNER, BALLOON_CORNER.toFloat(), (BALLOON_TEXTURE_SIZE - BALLOON_CORNER).toFloat(), BALLOON_TEXTURE_SIZE - BALLOON_CORNER * 2, BALLOON_CORNER, BALLOON_TEXTURE_SIZE, BALLOON_TEXTURE_SIZE)
        guiGraphics.blit(BALLOON_TEXTURE, x + width - BALLOON_CORNER, y + height - BALLOON_CORNER, BALLOON_CORNER, BALLOON_CORNER, (BALLOON_TEXTURE_SIZE - BALLOON_CORNER).toFloat(), (BALLOON_TEXTURE_SIZE - BALLOON_CORNER).toFloat(), BALLOON_CORNER, BALLOON_CORNER, BALLOON_TEXTURE_SIZE, BALLOON_TEXTURE_SIZE)
    }

    private fun applyInversePehkuiBillboardScale(entity: LivingEntity, poseStack: PoseStack) {
        val width = PehkuiScaleBridge.widthScale(entity).takeIf { it > 0.0f } ?: 1.0f
        val height = PehkuiScaleBridge.heightScale(entity).takeIf { it > 0.0f } ?: 1.0f
        poseStack.scale(1.0f / width, 1.0f / height, 1.0f / width)
    }

    private fun toEulerXyz(quaternion: Quaternionf): Vector3f {
        val w = quaternion.w() * quaternion.w()
        val x = quaternion.x() * quaternion.x()
        val y = quaternion.y() * quaternion.y()
        val z = quaternion.z() * quaternion.z()
        val sum = w + x + y + z
        val pitchSin = 2.0f * quaternion.w() * quaternion.x() - 2.0f * quaternion.y() * quaternion.z()
        val pitch = asin((pitchSin / sum).toDouble()).toFloat()
        if (abs(pitchSin) > 0.999f * sum) return Vector3f(pitch, 2.0f * atan2(quaternion.y().toDouble(), quaternion.w().toDouble()).toFloat(), 0.0f)
        return Vector3f(
            pitch,
            atan2((2.0f * quaternion.x() * quaternion.z() + 2.0f * quaternion.y() * quaternion.w()).toDouble(), (w - x - y + z).toDouble()).toFloat(),
            atan2((2.0f * quaternion.x() * quaternion.y() + 2.0f * quaternion.w() * quaternion.z()).toDouble(), (w - x + y - z).toDouble()).toFloat(),
        )
    }

    private fun toEulerXyzDegrees(quaternion: Quaternionf): Vector3f {
        val radians = toEulerXyz(quaternion)
        return Vector3f(Math.toDegrees(radians.x().toDouble()).toFloat(), Math.toDegrees(radians.y().toDouble()).toFloat(), Math.toDegrees(radians.z().toDouble()).toFloat())
    }

    private fun balloonIcon(message: String): NpcBalloonIcon? = when {
        message.startsWith(GIFT_BALLOON_MARKER) -> NpcBalloonIcon(GIFT_BALLOON_MARKER, GIFT_BALLOON_ICON)
        message.startsWith(QUEST_BALLOON_MARKER) -> NpcBalloonIcon(QUEST_BALLOON_MARKER, QUEST_BALLOON_ICON)
        message.startsWith(POKE_BALL_BALLOON_MARKER) -> NpcBalloonIcon(POKE_BALL_BALLOON_MARKER, POKE_BALL_BALLOON_ICON)
        message.startsWith(POKEBALL_BALLOON_MARKER) -> NpcBalloonIcon(POKEBALL_BALLOON_MARKER, POKE_BALL_BALLOON_ICON)
        message.startsWith(HEART_BALLOON_MARKER) -> NpcBalloonIcon(HEART_BALLOON_MARKER, HEART_BALLOON_ICON)
        message.startsWith(ANGRY_BALLOON_MARKER) -> NpcBalloonIcon(ANGRY_BALLOON_MARKER, ANGRY_BALLOON_ICON)
        else -> null
    }

    private fun balloonStyle(message: String): NpcBalloonStyle {
        val clean = message.trimStart()
        return when {
            clean.startsWith(GREEN_BALLOON_MARKER) -> NpcBalloonStyle(clean.removePrefix(GREEN_BALLOON_MARKER).trimStart(), gold = false, green = true)
            clean.startsWith(GOLD_BALLOON_MARKER) -> NpcBalloonStyle(clean.removePrefix(GOLD_BALLOON_MARKER).trimStart(), gold = true, green = false)
            else -> NpcBalloonStyle(message, gold = false, green = false)
        }
    }

    private fun normalizeBalloonDisplaySpacing(text: String): String = text
        .replace(Regex("(?<=[A-Za-z])(?=\\d)"), " ")
        .replace(Regex("(?<=\\d)(?=[A-Za-z]{2})"), " ")


private data class NpcBalloonIcon(val marker: String, val texture: ResourceLocation)
    private data class NpcBalloonStyle(val message: String, val gold: Boolean, val green: Boolean)
    private val BALLOON_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/chat_bubble.png")
    private val GIFT_BALLOON_ICON = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/gift.png")
    private val QUEST_BALLOON_ICON = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/quest_log.png")
    private val POKE_BALL_BALLOON_ICON = ResourceLocation.fromNamespaceAndPath("cobblemon", "textures/item/poke_balls/poke_ball.png")
    private val HEART_BALLOON_ICON = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/heart.png")
    private val ANGRY_BALLOON_ICON = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/angry.png")
    private val BALLOON_CKDM_FONT = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold_claim")
    private const val GIFT_BALLOON_MARKER = "@gift.png"
    private const val QUEST_BALLOON_MARKER = "@quest_log.png"
    private const val POKE_BALL_BALLOON_MARKER = "@poke_ball.png"
    private const val POKEBALL_BALLOON_MARKER = "@pokeball.png"
    private const val HEART_BALLOON_MARKER = "@heart.png"
    private const val ANGRY_BALLOON_MARKER = "@angry.png"
    private const val GOLD_BALLOON_MARKER = "@gold"
    private const val GREEN_BALLOON_MARKER = "@green"
    private const val BALLOON_ICON_SIZE = 8
    private const val BALLOON_ICON_TEXTURE_SIZE = 16
    private const val BALLOON_SCALE = 0.020f
    private const val BALLOON_BG_ALPHA = 0.90f
    private const val BALLOON_FADE_MS = 180L
    private const val BALLOON_CULL_FADE_MS = 180L
    private const val BALLOON_ENTITY_Y_OFFSET = 0.9
    private const val BALLOON_MAX_TEXT_WIDTH = 118
    private const val BALLOON_MIN_WIDTH = 45
    private const val BALLOON_PADDING = 6
    private const val BALLOON_PRIORITY_PADDING_X = 8
    private const val BALLOON_PRIORITY_PADDING_Y = 9
    private const val BALLOON_CORNER = 4
    private const val BALLOON_TEXTURE_SIZE = 16
    private const val BALLOON_LINE_HEIGHT = 9
    private const val BALLOON_PRIORITY_LINE_HEIGHT = 10
    private const val BALLOON_TEXT_COLOR = 0xFF24201C.toInt()
    private const val BALLOON_PRIORITY_TEXT_COLOR = 0xFFFFFFFF.toInt()
    private const val FRIENDSHIP_DELTA_WORLD_SCALE = 0.021f
    private const val FRIENDSHIP_DELTA_WORLD_ENTITY_Y_OFFSET = 1.04
    private const val FRIENDSHIP_DELTA_WORLD_X = 14
    private const val FRIENDSHIP_DELTA_WORLD_Y = -22
    private const val FRIENDSHIP_DELTA_WORLD_SLIDE = 16
    private const val FRIENDSHIP_DELTA_WORLD_ICON_SIZE = 9
    private const val FRIENDSHIP_DELTA_WORLD_FADE_MS = 260L
    private const val FRIENDSHIP_DELTA_WORLD_POSITIVE = 0xFF83F28F.toInt()
    private const val FRIENDSHIP_DELTA_WORLD_NEGATIVE = 0xFFFF6F6F.toInt()
    private const val CHAT_HEAD_SPACES = "   "
    private const val CHAT_HEAD_SIZE = 8
    private const val CHAT_ROLE_ICON_SIZE = 8
    private const val CHAT_ROLE_ICON_GAP = 1
    private const val CHAT_ROLE_ICON_TEXTURE_SIZE = 16
    private const val CHAT_ROLE_ICON_SPACES = "   "
    private const val CHAT_LEFT_MARGIN = 4
    private const val CHAT_BOTTOM_MARGIN = 40
    private const val CHAT_FADE_TICKS = 200
    private const val MAX_WORLD_CHAT_HEAD_ENTRIES = 50
    private const val QUICKSKIN_HEAD_TEXTURE_SIZE = 128
    private const val DISCORD_CHAT_ICON_TEXTURE_SIZE = 8
    private const val CHAT_HEAD_FALLBACK_FILL = 0x003D4352
    private val DISCORD_CHAT_ICON = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/fonts/discord.png")
}

private fun npcTexture(npcId: String): ResourceLocation = NpcTextures.texture(npcId)

object NpcTextures {
    @JvmStatic
    fun texture(npcId: String): ResourceLocation {
        val cleanId = npcId.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_./-]"), "")
        if (cleanId.isBlank() || cleanId == ChowNpcEntity.ANIMATION_DEBUG_NPC_ID) return STEVE_TEXTURE
        val configuredSkin = NpcConfig.get(cleanId)?.skin?.takeIf(String::isNotBlank)
        val debugSkin = bossFightDebugClassId(cleanId)
            ?.let { classId -> NpcConfig.all().firstOrNull { definition -> NpcBossMovesets.normalizeId(definition.classId) == classId && definition.skin.isNotBlank() } }
            ?.skin
        return npcSkinTexture(configuredSkin ?: debugSkin ?: cleanId)
    }
}

private val STEVE_TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png")

private fun bossFightDebugClassId(npcId: String): String? {
    val body = npcId.removePrefix(BOSS_DEBUG_NPC_PREFIX).takeIf { it != npcId } ?: return null
    return body.substringBeforeLast('_', body).takeIf(String::isNotBlank)
}

private fun npcSkinTexture(rawSkin: String): ResourceLocation {
    val normalized = rawSkin.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_:./-]"), "")
    val location = runCatching {
        if (':' in normalized) ResourceLocation.parse(normalized) else ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, normalized)
    }.getOrNull() ?: return STEVE_TEXTURE
    val path = location.path.trim('/')
    val texturePath = when {
        path.startsWith("textures/") -> path
        path.startsWith("entity/") -> "textures/$path"
        path.startsWith("npc/") -> "textures/entity/$path"
        else -> "textures/entity/npc/$path"
    }.let { value -> if (value.endsWith(".png")) value else "$value.png" }
    return ResourceLocation.fromNamespaceAndPath(location.namespace, texturePath)
}

private const val BOSS_DEBUG_NPC_PREFIX = "boss_debug_"

private class ChowNpcDelegatingRenderer(context: EntityRendererProvider.Context) : EntityRenderer<ChowNpcEntity>(context) {
    private val betterCombatPlayerlikeRenderer = ChowNpcBetterCombatPlayerlikeRenderer(context)
    private val playerlikeRenderer = ChowNpcPlayerlikeRenderer(context)
    private val vanillaRenderer = ChowNpcRenderer(context)
    private val geckoRenderer = ChowNpcGeoRenderer(context)

    override fun render(entity: ChowNpcEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack, buffer: MultiBufferSource, packedLight: Int) {
        rendererFor(entity).render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight)
    }

    override fun getTextureLocation(entity: ChowNpcEntity): ResourceLocation = rendererFor(entity).getTextureLocation(entity)

    private fun rendererFor(entity: ChowNpcEntity): EntityRenderer<ChowNpcEntity> {
        val definition = NpcConfig.get(entity.npcId)
        return when {
            entity.playerlikeAnimation || definition?.playerlikeAnimation == true -> betterCombatPlayerlikeRenderer
            entity.customAnimation || definition?.customAnimation == true -> geckoRenderer
            NpcConfig.settings().rendering.betterCombatPlayerlikeRenderer -> betterCombatPlayerlikeRenderer
            NpcConfig.settings().rendering.playerlikeRenderer -> playerlikeRenderer
            else -> vanillaRenderer
        }
    }
}

private class ChowNpcBetterCombatPlayerlikeRenderer(context: EntityRendererProvider.Context) : HumanoidMobRenderer<ChowNpcEntity, PlayerModel<ChowNpcEntity>>(context, PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f) {
    private val normalModel = PlayerModel<ChowNpcEntity>(context.bakeLayer(ModelLayers.PLAYER), false)
    private val slimModel = PlayerModel<ChowNpcEntity>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true)

    init {
        addLayer(
            HumanoidArmorLayer(
                this,
                HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.modelManager,
            )
        )
        addLayer(ItemInHandLayer(this, context.itemInHandRenderer))
        addLayer(NpcFemaleGenderLayer(this))
    }

    override fun render(entity: ChowNpcEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack, buffer: MultiBufferSource, packedLight: Int) {
        NpcClient.ensurePlayerlikeAnimationLayer(entity)
        model = if (entity.bodyType == NpcBodyTypes.SLIM) slimModel else normalModel
        model.rightArmPose = if (entity.mainHandItem.isEmpty) HumanoidModel.ArmPose.EMPTY else HumanoidModel.ArmPose.ITEM
        model.leftArmPose = if (entity.offhandItem.isEmpty) HumanoidModel.ArmPose.EMPTY else HumanoidModel.ArmPose.ITEM
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight)
    }

    override fun getTextureLocation(entity: ChowNpcEntity): ResourceLocation = npcTexture(entity.npcId)
}

private class ChowNpcPlayerlikeRenderer(context: EntityRendererProvider.Context) : HumanoidMobRenderer<ChowNpcEntity, PlayerModel<ChowNpcEntity>>(context, PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f) {
    private val normalModel = PlayerModel<ChowNpcEntity>(context.bakeLayer(ModelLayers.PLAYER), false)
    private val slimModel = PlayerModel<ChowNpcEntity>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true)

    init {
        addLayer(
            HumanoidArmorLayer(
                this,
                HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.modelManager,
            )
        )
        addLayer(ItemInHandLayer(this, context.itemInHandRenderer))
        addLayer(NpcFemaleGenderLayer(this))
    }

    override fun render(entity: ChowNpcEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack, buffer: MultiBufferSource, packedLight: Int) {
        NpcClient.ensurePlayerlikeAnimationLayer(entity)
        model = if (entity.bodyType == NpcBodyTypes.SLIM) slimModel else normalModel
        model.rightArmPose = if (entity.mainHandItem.isEmpty) HumanoidModel.ArmPose.EMPTY else HumanoidModel.ArmPose.ITEM
        model.leftArmPose = if (entity.offhandItem.isEmpty) HumanoidModel.ArmPose.EMPTY else HumanoidModel.ArmPose.ITEM
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight)
    }

    override fun getTextureLocation(entity: ChowNpcEntity): ResourceLocation = npcTexture(entity.npcId)
}

private class ChowNpcRenderer(context: EntityRendererProvider.Context) : MobRenderer<ChowNpcEntity, PlayerModel<ChowNpcEntity>>(context, ChowNpcModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f) {
    private val normalModel = ChowNpcModel(context.bakeLayer(ModelLayers.PLAYER), false)
    private val slimModel = ChowNpcModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true)

    init {
        addLayer(
            HumanoidArmorLayer(
                this,
                HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.modelManager,
            )
        )
        addLayer(ItemInHandLayer(this, context.itemInHandRenderer))
        addLayer(NpcFemaleGenderLayer(this))
    }

    override fun render(entity: ChowNpcEntity, entityYaw: Float, partialTicks: Float, poseStack: PoseStack, buffer: MultiBufferSource, packedLight: Int) {
        model = if (entity.bodyType == NpcBodyTypes.SLIM) slimModel else normalModel
        model.rightArmPose = if (entity.mainHandItem.isEmpty) HumanoidModel.ArmPose.EMPTY else HumanoidModel.ArmPose.ITEM
        model.leftArmPose = if (entity.offhandItem.isEmpty) HumanoidModel.ArmPose.EMPTY else HumanoidModel.ArmPose.ITEM
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight)
    }

    override fun getTextureLocation(entity: ChowNpcEntity): ResourceLocation = npcTexture(entity.npcId)
}

private class ChowNpcGeoRenderer(context: EntityRendererProvider.Context) : GeoEntityRenderer<ChowNpcEntity>(context, ChowNpcGeoModel()) {
    init {
        shadowRadius = 0.5f
        addRenderLayer(NpcGeoHeldItemLayer(this))
    }

    override fun render(entity: ChowNpcEntity, entityYaw: Float, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
        NpcClient.renderBalloon(entity, poseStack, bufferSource, Minecraft.getInstance().font, packedLight)
    }

    override fun getTextureLocation(entity: ChowNpcEntity): ResourceLocation = npcTexture(entity.npcId)
}

private class NpcGeoHeldItemLayer(renderer: GeoEntityRenderer<ChowNpcEntity>) : BlockAndItemGeoLayer<ChowNpcEntity>(renderer) {
    override fun getStackForBone(bone: GeoBone, animatable: ChowNpcEntity): ItemStack = when (bone.name) {
        "right_hand_item" -> animatable.mainHandItem
        "left_hand_item" -> animatable.offhandItem
        else -> ItemStack.EMPTY
    }

    override fun getTransformTypeForStack(bone: GeoBone, stack: ItemStack, animatable: ChowNpcEntity): ItemDisplayContext = ItemDisplayContext.NONE

    override fun renderStackForBone(poseStack: PoseStack, bone: GeoBone, stack: ItemStack, animatable: ChowNpcEntity, bufferSource: MultiBufferSource, partialTick: Float, packedLight: Int, packedOverlay: Int) {
        if (bone.name != "right_hand_item" && bone.name != "left_hand_item") {
            super.renderStackForBone(poseStack, bone, stack, animatable, bufferSource, partialTick, packedLight, packedOverlay)
            return
        }
        val leftHand = bone.name == "left_hand_item"
        val transformType = getTransformTypeForStack(bone, stack, animatable)
        poseStack.pushPose()
        val flatItem = isFlatItem(stack, animatable)
        applyHeldWeaponSocketTransform(poseStack, animatable, leftHand, flatItem)
        applyHeldWeaponModelSpaceTransform(poseStack, leftHand, flatItem)
        if (animatable.heldItemDebugRotSpace != "socket") applyHeldWeaponDebugRotation(poseStack, animatable)
        Minecraft.getInstance().itemRenderer.renderStatic(
            animatable,
            stack,
            transformType,
            leftHand,
            poseStack,
            bufferSource,
            animatable.level(),
            packedLight,
            OverlayTexture.NO_OVERLAY,
            animatable.id + transformType.ordinal,
        )
        poseStack.popPose()
    }

    private fun isFlatItem(stack: ItemStack, animatable: ChowNpcEntity): Boolean {
        val model = Minecraft.getInstance().itemRenderer.getModel(stack, animatable.level(), animatable, animatable.id)
        return !model.isGui3d
    }

    private fun applyHeldWeaponSocketTransform(poseStack: PoseStack, animatable: ChowNpcEntity, leftHand: Boolean, flatItem: Boolean) {
        if (flatItem) {
            val side = if (leftHand) -1.0f else 1.0f
            poseStack.mulPose(Axis.ZP.rotationDegrees(HELD_WEAPON_FLAT_SOCKET_AXIS_Z_DEGREES * side))
        }
        val side = if (leftHand) -1.0f else 1.0f
        poseStack.translate((HELD_WEAPON_SOCKET_OFFSET_X * side).toDouble(), HELD_WEAPON_SOCKET_OFFSET_Y.toDouble(), HELD_WEAPON_SOCKET_OFFSET_Z.toDouble())
        if (animatable.heldItemDebugRotSpace == "socket") applyHeldWeaponDebugRotation(poseStack, animatable)
        applyHeldWeaponDebugPositionAndScale(poseStack, animatable)
    }

    private fun applyHeldWeaponModelSpaceTransform(poseStack: PoseStack, leftHand: Boolean, flatItem: Boolean) {
        if (flatItem) {
            applyFlatWeaponTransform(poseStack, leftHand)
        } else {
            applyModeledWeaponTransform(poseStack, leftHand)
        }
        if (leftHand) poseStack.mulPose(Axis.ZP.rotationDegrees(HELD_WEAPON_LEFT_HAND_ITEM_AXIS_Z_DEGREES))
    }

    private fun applyFlatWeaponTransform(poseStack: PoseStack, leftHand: Boolean) {
        val side = if (leftHand) -1.0f else 1.0f
        poseStack.mulPose(Axis.YP.rotationDegrees(HELD_WEAPON_FLAT_AXIS_Y_DEGREES * side))
        poseStack.mulPose(Axis.XP.rotationDegrees(HELD_WEAPON_FLAT_AXIS_X_DEGREES))
        poseStack.mulPose(Axis.ZP.rotationDegrees(HELD_WEAPON_FLAT_AXIS_Z_DEGREES * side))
        poseStack.scale(HELD_WEAPON_SCALE, HELD_WEAPON_SCALE, HELD_WEAPON_SCALE)
    }

    private fun applyModeledWeaponTransform(poseStack: PoseStack, leftHand: Boolean) {
        val side = if (leftHand) -1.0f else 1.0f
        poseStack.mulPose(Axis.YP.rotationDegrees(HELD_WEAPON_MODELED_AXIS_Y_DEGREES * side))
        poseStack.mulPose(Axis.XP.rotationDegrees(HELD_WEAPON_MODELED_AXIS_X_DEGREES))
        poseStack.mulPose(Axis.ZP.rotationDegrees(HELD_WEAPON_MODELED_AXIS_Z_DEGREES * side))
        poseStack.scale(HELD_WEAPON_SCALE, HELD_WEAPON_SCALE, HELD_WEAPON_SCALE)
    }

    private fun applyHeldWeaponDebugRotation(poseStack: PoseStack, animatable: ChowNpcEntity) {
        animatable.heldItemDebugRotOrder.forEach { axis ->
            when (axis) {
                'x' -> if (animatable.heldItemDebugRotX != 0.0f) poseStack.mulPose(Axis.XP.rotationDegrees(animatable.heldItemDebugRotX))
                'y' -> if (animatable.heldItemDebugRotY != 0.0f) poseStack.mulPose(Axis.YP.rotationDegrees(animatable.heldItemDebugRotY))
                'z' -> if (animatable.heldItemDebugRotZ != 0.0f) poseStack.mulPose(Axis.ZP.rotationDegrees(animatable.heldItemDebugRotZ))
            }
        }
    }

    private fun applyHeldWeaponDebugPositionAndScale(poseStack: PoseStack, animatable: ChowNpcEntity) {
        if (animatable.heldItemDebugPosX != 0.0f || animatable.heldItemDebugPosY != 0.0f || animatable.heldItemDebugPosZ != 0.0f) {
            poseStack.translate(animatable.heldItemDebugPosX.toDouble(), animatable.heldItemDebugPosY.toDouble(), animatable.heldItemDebugPosZ.toDouble())
        }
        if (animatable.heldItemDebugScale != 1.0f) {
            poseStack.scale(animatable.heldItemDebugScale, animatable.heldItemDebugScale, animatable.heldItemDebugScale)
        }
    }

    companion object {
        private const val HELD_WEAPON_SCALE = 0.9f
        private const val HELD_WEAPON_SOCKET_OFFSET_X = 0.0f
        private const val HELD_WEAPON_SOCKET_OFFSET_Y = 0.0f
        private const val HELD_WEAPON_SOCKET_OFFSET_Z = -0.4f
        private const val HELD_WEAPON_FLAT_SOCKET_AXIS_Z_DEGREES = 90.0f
        private const val HELD_WEAPON_FLAT_AXIS_Y_DEGREES = -90.0f
        private const val HELD_WEAPON_FLAT_AXIS_X_DEGREES = 90.0f
        private const val HELD_WEAPON_FLAT_AXIS_Z_DEGREES = 135.0f
        private const val HELD_WEAPON_MODELED_AXIS_Y_DEGREES = -90.0f
        private const val HELD_WEAPON_MODELED_AXIS_X_DEGREES = 45.0f
        private const val HELD_WEAPON_MODELED_AXIS_Z_DEGREES = 45.0f
        private const val HELD_WEAPON_LEFT_HAND_ITEM_AXIS_Z_DEGREES = 90.0f
    }
}

private class NpcPlayerlikeAnimationLayer(val entity: ChowNpcEntity) : ModifierLayer<IAnimation>() {
    private var observedPlayId = Int.MIN_VALUE
    private var observedAnimationKey = ""
    private var observedLoop = false
    private var warnedMissingAnimationKey = ""

    override fun isActive(): Boolean {
        val pendingAnimation = entity.playerlikeAnimation &&
            entity.playerlikeAnimationKey.isNotBlank() &&
            (entity.playerlikeAnimationPlayId != observedPlayId || entity.playerlikeAnimationKey != observedAnimationKey || entity.playerlikeAnimationLoop != observedLoop)
        val pendingClear = observedAnimationKey.isNotBlank() && (!entity.playerlikeAnimation || entity.playerlikeAnimationKey.isBlank())
        return entity.isAlive && (pendingAnimation || pendingClear || super.isActive())
    }

    override fun tick() {
        val playId = entity.playerlikeAnimationPlayId
        val animationKey = entity.playerlikeAnimationKey
        val loop = entity.playerlikeAnimationLoop
        if (!entity.playerlikeAnimation || animationKey.isBlank()) {
            if (observedAnimationKey.isNotBlank()) setAnimation(null)
            observedAnimationKey = ""
            observedPlayId = playId
            observedLoop = false
            super.tick()
            return
        }
        if (playId != observedPlayId || animationKey != observedAnimationKey || loop != observedLoop) {
            val emotecraftAnimation = NpcEmotecraftBridge.resolve(animationKey)
            val createAnimation = {
                if (emotecraftAnimation != null) {
                    NpcSmoothCustomAnimationPlayer(emotecraftAnimation, 0)
                } else {
                    val animationId = runCatching { ResourceLocation.parse(animationKey) }.getOrNull()
                    val playable = animationId?.let(PlayerAnimationRegistry::getAnimation)
                    when (playable) {
                        is KeyframeAnimation -> NpcSmoothCustomAnimationPlayer(playable, 0)
                        else -> (playable?.playAnimation() as? IAnimation)?.let(::NpcSmoothPlayerlikeAnimation)
                    }
                }
            }
            val animation = createAnimation()
            val finalAnimation = if (animation != null && loop) {
                NpcLoopingPlayerlikeAnimation(createAnimation)
            } else {
                animation
            }
            setAnimation(finalAnimation)
            if (animation == null && warnedMissingAnimationKey != animationKey) {
                warnedMissingAnimationKey = animationKey
                ChowKingdomMod.LOGGER.warn(
                    "No client PlayerAnimator/Emotecraft animation found for NPC playerlike key {}. Loaded namespaces/ids sample: {}. {}",
                    animationKey,
                    PlayerAnimationRegistry.getAnimations().keys.take(20).joinToString(", "),
                    NpcEmotecraftBridge.debugStatus(),
                )
            } else if (animation != null) {
                warnedMissingAnimationKey = ""
                val source = if (emotecraftAnimation != null) "Emotecraft" else "PlayerAnimator"
                ChowKingdomMod.LOGGER.info("Playing NPC {} animation {} on {} loop={}", source, animationKey, entity.npcId, loop)
            }
            observedAnimationKey = animationKey
            observedPlayId = playId
            observedLoop = loop
        }
        super.tick()
    }
}

private class NpcLoopingPlayerlikeAnimation(private val create: () -> IAnimation?) : IAnimation {
    private var delegate: IAnimation? = create()

    override fun tick() {
        val current = delegate
        if (current == null || !current.isActive()) delegate = create()
        delegate?.tick()
    }

    override fun isActive(): Boolean = true

    override fun setupAnim(tickDelta: Float) = delegate?.setupAnim(tickDelta) ?: Unit

    override fun get3DTransform(modelName: String, type: TransformType, tickDelta: Float, value0: Vec3f): Vec3f =
        delegate?.get3DTransform(modelName, type, tickDelta, value0) ?: value0

    override fun getFirstPersonMode(tickDelta: Float): FirstPersonMode =
        delegate?.getFirstPersonMode(tickDelta) ?: FirstPersonMode.NONE

    override fun getFirstPersonConfiguration(tickDelta: Float): FirstPersonConfiguration =
        delegate?.getFirstPersonConfiguration(tickDelta) ?: FirstPersonConfiguration()
}

private class NpcSmoothCustomAnimationPlayer(animation: KeyframeAnimation, startTick: Int) : CustomAnimationPlayer(animation, startTick) {
    override fun setupAnim(tickDelta: Float) = super.setupAnim(npcPlayerlikePartialTick(tickDelta))

    override fun get3DTransform(modelName: String, type: TransformType, tickDelta: Float, value0: Vec3f): Vec3f {
        val partialTick = npcPlayerlikePartialTick(tickDelta)
        super.setupAnim(partialTick)
        return super.get3DTransform(modelName, type, partialTick, value0)
    }

    override fun getFirstPersonMode(tickDelta: Float): FirstPersonMode {
        val partialTick = npcPlayerlikePartialTick(tickDelta)
        super.setupAnim(partialTick)
        return super.getFirstPersonMode(partialTick)
    }

    override fun getFirstPersonConfiguration(tickDelta: Float): FirstPersonConfiguration {
        val partialTick = npcPlayerlikePartialTick(tickDelta)
        super.setupAnim(partialTick)
        return super.getFirstPersonConfiguration(partialTick)
    }
}

private class NpcSmoothPlayerlikeAnimation(private val delegate: IAnimation) : IAnimation {
    override fun tick() = delegate.tick()

    override fun isActive(): Boolean = delegate.isActive()

    override fun setupAnim(tickDelta: Float) = delegate.setupAnim(npcPlayerlikePartialTick(tickDelta))

    override fun get3DTransform(modelName: String, type: TransformType, tickDelta: Float, value0: Vec3f): Vec3f {
        val partialTick = npcPlayerlikePartialTick(tickDelta)
        delegate.setupAnim(partialTick)
        return delegate.get3DTransform(modelName, type, partialTick, value0)
    }

    override fun getFirstPersonMode(tickDelta: Float): FirstPersonMode {
        val partialTick = npcPlayerlikePartialTick(tickDelta)
        delegate.setupAnim(partialTick)
        return delegate.getFirstPersonMode(partialTick)
    }

    override fun getFirstPersonConfiguration(tickDelta: Float): FirstPersonConfiguration {
        val partialTick = npcPlayerlikePartialTick(tickDelta)
        delegate.setupAnim(partialTick)
        return delegate.getFirstPersonConfiguration(partialTick)
    }
}

private fun npcPlayerlikePartialTick(value: Float): Float {
    if (!java.lang.Float.isFinite(value)) return 0.0f
    return (value - floor(value)).coerceIn(0.0f, 0.999f)
}

@Suppress("OVERRIDE_DEPRECATION")
private class ChowNpcGeoModel : GeoModel<ChowNpcEntity>() {
    override fun getModelResource(animatable: ChowNpcEntity): ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "geo/entity/npc/playerlike.geo.json")

    override fun getTextureResource(animatable: ChowNpcEntity): ResourceLocation = npcTexture(animatable.npcId)

    override fun getAnimationResource(animatable: ChowNpcEntity): ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "animations/npc/playerlike.animation.json")
}

class NpcRentContractTooltip(val npcId: String, val npcName: String) : TooltipComponent

class NpcRentContractClientTooltip(private val tooltip: NpcRentContractTooltip) : ClientTooltipComponent {
    override fun getHeight(): Int = 26

    override fun getWidth(font: Font): Int = 28 + font.width(tooltip.npcName)

    override fun renderImage(font: Font, x: Int, y: Int, guiGraphics: GuiGraphics) {
        val texture = npcTexture(tooltip.npcId)
        guiGraphics.blit(texture, x, y, 24, 24, 8.0f, 8.0f, 8, 8, 64, 64)
        guiGraphics.blit(texture, x, y, 24, 24, 40.0f, 8.0f, 8, 8, 64, 64)
        guiGraphics.drawString(font, tooltip.npcName, x + 28, y + 8, 0xFFFFFFFF.toInt(), false)
    }
}

class NpcJobApplicationTooltip(val entries: List<NpcJobApplicationTooltipEntry>) : TooltipComponent

class NpcJobApplicationTooltipEntry(val stack: ItemStack, val count: Int, val label: String)

class NpcJobApplicationClientTooltip(private val tooltip: NpcJobApplicationTooltip) : ClientTooltipComponent {
    override fun getHeight(): Int = tooltip.entries.size * 18

    override fun getWidth(font: Font): Int = tooltip.entries.maxOfOrNull { entry -> 24 + font.width("${entry.count} x ${entry.label}") } ?: 0

    override fun renderImage(font: Font, x: Int, y: Int, guiGraphics: GuiGraphics) {
        tooltip.entries.forEachIndexed { index, entry ->
            val rowY = y + index * 18
            guiGraphics.renderItem(entry.stack, x, rowY)
            guiGraphics.renderItemDecorations(font, entry.stack, x, rowY)
            guiGraphics.drawString(font, "${entry.count} x ${entry.label}", x + 22, rowY + 4, 0xFFAAAAAA.toInt(), false)
        }
    }
}

private class ChowNpcModel(root: ModelPart, slim: Boolean) : PlayerModel<ChowNpcEntity>(root, slim) {
    override fun setupAnim(entity: ChowNpcEntity, limbSwing: Float, limbSwingAmount: Float, ageInTicks: Float, netHeadYaw: Float, headPitch: Float) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch)
        if (entity.scriptedAttackTicks <= 0) return
        val partialTick = (ageInTicks - entity.tickCount.toFloat()).coerceIn(0.0f, 1.0f)
        val remainingTicks = (entity.scriptedAttackTicks.toFloat() - partialTick).coerceAtLeast(0.0f)
        val progress = 1.0f - (remainingTicks / SCRIPTED_ATTACK_TICKS).coerceIn(0.0f, 1.0f)
        val windup = easeInOut((progress / 0.4f).coerceIn(0.0f, 1.0f))
        val slash = easeInOut(((progress - 0.4f) / 0.6f).coerceIn(0.0f, 1.0f))
        val followThrough = sin(progress * PI).toFloat()
        rightArm.xRot = lerp(lerp(-0.72f, -2.25f, windup), -0.5f, slash)
        rightArm.yRot = lerp(-0.08f, 0.36f, slash)
        rightArm.zRot = lerp(0.16f, -0.28f, followThrough)
        body.yRot = followThrough * 0.1f
        rightSleeve.copyFrom(rightArm)
        jacket.copyFrom(body)
    }

    private fun easeInOut(progress: Float): Float = progress * progress * (3.0f - 2.0f * progress)

    private fun lerp(start: Float, end: Float, progress: Float): Float = start + (end - start) * progress

    companion object {
        private const val SCRIPTED_ATTACK_TICKS = 9.0f
    }
}

private class NpcDialogScreen(private val payload: NpcDialogPayload) : Screen(Component.literal(payload.name)) {
    private val openedAtMs: Long = System.currentTimeMillis()
    private var messageStartedAtMs: Long = openedAtMs
    private var displayMessage: String = payload.message
    private var lastAnimaleseIndex: Int = 0
    private var streamingResponseActive: Boolean = false
    private var highestVisibleCharacters: Int = 0
    private var talkMode: Boolean = payload.startTalkMode
    private var waitingForTalk: Boolean = payload.message == "..."
    private var activeResponseToken: Long = payload.responseToken
    private var talkModeChangedAtMs: Long = openedAtMs
    private var animatedPanelHeight: Float = BASE_PANEL_HEIGHT.toFloat()
    private var panelHeightAnimationFrom: Float = BASE_PANEL_HEIGHT.toFloat()
    private var panelHeightAnimationTarget: Int = BASE_PANEL_HEIGHT
    private var panelHeightAnimationStartedAtMs: Long = openedAtMs
    private var talkInput: EditBox? = null
    private var keepaliveTicks = 0
    private var closingSent = false
    private var previousCameraType: CameraType? = null
    private var classChangePage = 0

    override fun isPauseScreen(): Boolean = false

    fun isBossMode(): Boolean = payload.dialogMode == "boss"

    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) = Unit

    override fun renderTransparentBackground(guiGraphics: GuiGraphics) = Unit

    override fun renderBlurredBackground(partialTick: Float) = Unit

    override fun init() {
        val options = Minecraft.getInstance().options
        previousCameraType = options.cameraType
        options.cameraType = CameraType.FIRST_PERSON
        val layout = layout()
        talkInput = EditBox(font, layout.inputX, layout.inputY, layout.inputWidth, INPUT_HEIGHT, Component.literal("Message ${payload.name}...")).apply {
            setMaxLength(2000)
            setHint(Component.literal("Message ${payload.name}..."))
            visible = talkMode
        }
        talkInput?.let(::addRenderableWidget)
        if (talkMode) {
            talkInput?.isFocused = true
            setFocused(talkInput)
        }
    }

    override fun tick() {
        super.tick()
        keepaliveTicks--
        if (keepaliveTicks <= 0) {
            NpcNetwork.sendAction(payload.npcId, "dialog_keepalive")
            keepaliveTicks = DIALOG_KEEPALIVE_CLIENT_TICKS
        }
    }

    override fun removed() {
        previousCameraType?.let { cameraType -> Minecraft.getInstance().options.cameraType = cameraType }
        if (!closingSent) {
            closingSent = true
            NpcNetwork.sendAction(payload.npcId, "dialog_close")
        }
        super.removed()
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        frameNpcCamera()
        val layout = layout()
        updateTalkInput(layout)
        val panelWidth = layout.panelWidth
        val panelHeight = layout.panelHeight
        val x = layout.x
        val y = layout.y
        val progress = entranceProgress()
        val scale = 0.92f + progress * 0.08f
        val slideY = (18.0f * (1.0f - progress))
        val anchorX = x + panelWidth / 2.0f
        val anchorY = y + panelHeight.toFloat()
        val localMouse = localMouse(mouseX, mouseY, anchorX, anchorY, slideY, scale)
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(anchorX, anchorY + slideY, 0.0f)
        pose.scale(scale, scale, 1.0f)
        pose.translate(-anchorX, -anchorY, 0.0f)
        renderNineSlice(guiGraphics, PANEL_TEXTURE, x, y, panelWidth, panelHeight, PANEL_TEXTURE_WIDTH, PANEL_TEXTURE_HEIGHT, PANEL_SOURCE_CORNER, PANEL_DEST_CORNER)
        renderAvatar(guiGraphics, payload.npcId, x + PAD, y + PAD)

        val nameX = x + PAD + AVATAR_SIZE + TEXT_GAP
        val buttonX = x + panelWidth + BUTTON_OUTSIDE_GAP
        val dialogX = x + PAD
        val dialogY = y + PAD + AVATAR_SIZE + 10
        val dialogWidth = panelWidth - PAD * 2
        val dialogWrapWidth = (dialogWidth / DIALOG_TEXT_SCALE).toInt().coerceAtLeast(1)
        val buttonTop = buttonTop(panelHeight, actions().size)
        val name = ckdmText(payload.name)
        drawScaledString(guiGraphics, name, nameX, y + NAME_Y, NAME_SCALE, NAME_COLOR, NAME_SHADOW)
        renderFriendship(guiGraphics, nameX, y + FRIENDSHIP_Y, payload.friendshipLevel)
        renderFriendshipDelta(guiGraphics, nameX + FRIENDSHIP_ICON_COUNT * FRIENDSHIP_ICON_STEP + 5, y + FRIENDSHIP_Y + 1, payload.friendshipDelta)

        talkInput?.visible = talkMode
        val visibleCharacters = visibleDialogCharacters().coerceAtLeast(highestVisibleCharacters).coerceAtMost(currentRenderMessage().length)
        highestVisibleCharacters = visibleCharacters
        playAnimalese(visibleCharacters)
        val visibleMessage = currentRenderMessage().take(visibleCharacters)
        val shadowLines = font.split(dialogTextComponent(visibleMessage, shadow = true), dialogWrapWidth)
        val lines = font.split(dialogTextComponent(visibleMessage), dialogWrapWidth)
        var lineY = dialogY
        lines.take(layout.dialogLineLimit).forEachIndexed { index, line ->
            shadowLines.getOrNull(index)?.let { shadowLine -> drawScaledString(guiGraphics, shadowLine, dialogX + 1, lineY + 1, DIALOG_TEXT_SCALE, DIALOG_SHADOW) }
            drawScaledString(guiGraphics, line, dialogX, lineY, DIALOG_TEXT_SCALE, DIALOG_COLOR)
            lineY += LINE_HEIGHT
        }
        if (talkMode) talkInput?.render(guiGraphics, localMouse.first, localMouse.second, partialTick)
        val hoveredAction = actionAt(localMouse.first, localMouse.second)
        val hoveredClassChange = if (classChangeMode()) classChangeOptionAt(localMouse.first, localMouse.second) else null
        val hoveredQuizChoice = if (quizMode()) quizChoiceAt(localMouse.first, localMouse.second) else null
        if (classChangeMode()) renderClassChangeOptions(guiGraphics, localMouse.first, localMouse.second, layout)
        if (quizMode()) renderQuizChoices(guiGraphics, localMouse.first, localMouse.second, layout)
        renderActionButtons(guiGraphics, localMouse.first, localMouse.second, buttonX, y + buttonTop)
        pose.popPose()
        renderActionTooltip(guiGraphics, mouseX, mouseY, hoveredAction)
        renderClassChangeTooltip(guiGraphics, mouseX, mouseY, hoveredClassChange)
        renderQuizTooltip(guiGraphics, mouseX, mouseY, hoveredQuizChoice)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return true
        val layout = layout()
        updateTalkInput(layout)
        val panelWidth = layout.panelWidth
        val panelHeight = layout.panelHeight
        val x = layout.x
        val y = layout.y
        val progress = entranceProgress()
        val scale = 0.92f + progress * 0.08f
        val slideY = 18.0f * (1.0f - progress)
        val localMouse = localMouse(mouseX.toInt(), mouseY.toInt(), x + panelWidth / 2.0f, y + panelHeight.toFloat(), slideY, scale)
        if (talkMode && talkInput?.mouseClicked(localMouse.first.toDouble(), localMouse.second.toDouble(), button) == true) return true
        if (classChangeMode()) {
            val pager = classChangePagerAt(localMouse.first, localMouse.second)
            if (pager != 0) {
                classChangePage = (classChangePage + pager).coerceIn(0, classChangePageCount() - 1)
                return true
            }
            val option = classChangeOptionAt(localMouse.first, localMouse.second)
            if (option != null) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "class_change:${option.classId}")
                return true
            }
        }
        if (quizMode()) {
            val choice = quizChoiceAt(localMouse.first, localMouse.second)
            if (choice != null) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "quiz_answer:${choice.index}")
                return true
            }
        }
        val action = actionAt(localMouse.first, localMouse.second) ?: return true
        when (action) {
            DialogAction.Buy -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "buy")
            }
            DialogAction.Gift -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "gift")
            }
            DialogAction.Work -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "work")
            }
            DialogAction.Contracts -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "boss_contracts")
            }
            DialogAction.League -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "league_ticket")
            }
            DialogAction.Compass -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "league_compass")
            }
            DialogAction.Kanto -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "league_choice:0")
            }
            DialogAction.Johto -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "league_choice:1")
            }
            DialogAction.Hoenn -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "league_choice:2")
            }
            DialogAction.Sinnoh -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "league_choice:3")
            }
            DialogAction.Unova -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "league_choice:4")
            }
            DialogAction.Kalos -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "league_choice:5")
            }
            DialogAction.Alola -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "league_choice:6")
            }
            DialogAction.Galar -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "league_choice:7")
            }
            DialogAction.Paldea -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "league_choice:8")
            }
            DialogAction.Retire -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "league_retire")
            }
            DialogAction.Confirm -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "league_retire_confirm")
            }
            DialogAction.Challenge -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "gym_challenge")
            }
            DialogAction.FriendlyBattle -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, if (gymTrainerMode() || gymFriendlyMode()) "gym_friendly_battle" else "npc_friendly_battle")
            }
            DialogAction.RetryBattle -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "quest_retry_battle")
            }
            DialogAction.Badge -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "gym_badge")
            }
            DialogAction.Record -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "gym_badge")
            }
            DialogAction.Training -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "training")
            }
            DialogAction.TechLicense -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "tech_license:${payload.techLicenseId}")
            }
            DialogAction.Change -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "class_change_offer")
            }
            DialogAction.Move -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "work_move")
            }
            DialogAction.Fire -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "work_fire")
            }
            DialogAction.Bye -> if (questMode()) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "quest_decline")
            } else if (leagueRetireMode()) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "league_retire_cancel")
            } else onClose()
            DialogAction.Claim -> if (isActionEnabled(action)) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "boss_claim")
            }
            DialogAction.Talk -> if (questMode()) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "quest_accept")
            } else if (bossClaimMode()) {
                skipPendingTalkResponse()
                NpcNetwork.sendAction(payload.npcId, "boss_claim")
            } else if (bossContractMode() && !waitingForTalk && payload.talkEnabled) {
                if (talkMode) sendTalkMessage() else {
                    NpcNetwork.sendAction(payload.npcId, "boss_contract_talk")
                    enterTalkMode()
                }
            } else if (!waitingForTalk && payload.talkEnabled) {
                if (talkMode) sendTalkMessage() else {
                    NpcNetwork.sendAction(payload.npcId, "join_talk")
                    enterTalkMode()
                }
            }
        }
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if ((classChangeMode() || quizMode()) && classChangePageCount() > 1) {
            classChangePage = (classChangePage - scrollY.toInt()).coerceIn(0, classChangePageCount() - 1)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun onClose() {
        val wasWaitingForTalk = waitingForTalk
        val wasInTalkMode = talkMode
        val wasLlmDialog = activeResponseToken != 0L || payload.responseToken != 0L
        skipPendingTalkResponse()
        if (wasWaitingForTalk || wasInTalkMode || wasLlmDialog) NpcNetwork.sendAction(payload.npcId, "leave_llm_dialog")
        super.onClose()
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (talkMode && keyCode == 257 && !waitingForTalk) {
            sendTalkMessage()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    fun receiveTalkResponse(response: NpcTalkResponsePayload) {
        if (response.npcId != payload.npcId) return
        if (NpcClient.shouldIgnoreTalkResponse(response.responseToken)) return
        if (response.responseToken != 0L && response.responseToken != activeResponseToken) return
        if (response.partial) {
            waitingForTalk = false
            updateStreamingDisplayMessage(response.message)
            return
        }
        waitingForTalk = false
        activeResponseToken = 0L
        if (streamingResponseActive) {
            updateStreamingDisplayMessage(response.message)
            streamingResponseActive = false
        } else {
            updateDisplayMessage(response.message)
        }
    }

    private fun localMouse(mouseX: Int, mouseY: Int, anchorX: Float, anchorY: Float, slideY: Float, scale: Float): Pair<Int, Int> = Pair(
        (anchorX + (mouseX - anchorX) / scale).toInt(),
        (anchorY + (mouseY - anchorY - slideY) / scale).toInt(),
    )

    private fun renderActionButtons(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, x: Int, y: Int) {
        val giftStack = giftStack()
        actions().forEachIndexed { index, action ->
            val buttonY = y + index * BUTTON_STEP
            val hovered = mouseX in x until x + BUTTON_WIDTH && mouseY in buttonY until buttonY + BUTTON_HEIGHT
            val enabled = isActionEnabled(action, giftStack)
            val activeHover = hovered && enabled
            val highlighted = isActionHighlighted(action)
            val texture = when {
                activeHover && (action == DialogAction.Bye || action == DialogAction.Fire || action == DialogAction.Retire || action == DialogAction.Confirm) -> RED_BUTTON_HOVER_TEXTURE
                activeHover -> BUTTON_HOVER_TEXTURE
                highlighted && enabled -> GREEN_BUTTON_TEXTURE
                else -> BUTTON_TEXTURE
            }
            val sourceSize = if (activeHover) BUTTON_HOVER_TEXTURE_SIZE else BUTTON_TEXTURE_SIZE
            val sourceCorner = if (activeHover) BUTTON_HOVER_SOURCE_CORNER else BUTTON_SOURCE_CORNER
            val textColor = if (enabled) NAME_COLOR else DISABLED_COLOR
            renderNineSlice(guiGraphics, texture, x, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT, sourceSize, sourceSize, sourceCorner, BUTTON_DEST_CORNER)
            guiGraphics.blit(action.icon, x + 6, buttonY + 3, ICON_SIZE, ICON_SIZE, 0.0f, 0.0f, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE)
            guiGraphics.drawString(font, ckdmSmallText(fitActionLabel(actionLabel(action))), x + 25, buttonY + 6, textColor, false)
            if (action == DialogAction.Gift && hovered && !giftStack.isEmpty) guiGraphics.renderItem(giftStack.copyWithCount(1), x + BUTTON_WIDTH - 19, buttonY + 2)
        }
    }

    private fun renderClassChangeOptions(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, layout: DialogLayout) {
        val options = classChangePageOptions()
        if (options.isEmpty()) return
        val area = classChangeArea(layout)
        options.forEachIndexed { index, option ->
            val optionY = area.y + index * CLASS_CHANGE_OPTION_STEP
            val hovered = mouseX in area.x until area.x + area.width && mouseY in optionY until optionY + CLASS_CHANGE_OPTION_HEIGHT
            val texture = if (hovered) BUTTON_HOVER_TEXTURE else BUTTON_TEXTURE
            val sourceSize = if (hovered) BUTTON_HOVER_TEXTURE_SIZE else BUTTON_TEXTURE_SIZE
            val sourceCorner = if (hovered) BUTTON_HOVER_SOURCE_CORNER else BUTTON_SOURCE_CORNER
            renderNineSlice(guiGraphics, texture, area.x, optionY, area.width, CLASS_CHANGE_OPTION_HEIGHT, sourceSize, sourceSize, sourceCorner, BUTTON_DEST_CORNER)
            guiGraphics.blit(classIconTexture(option.classId), area.x + 5, optionY + 2, ICON_SIZE, ICON_SIZE, 0.0f, 0.0f, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE)
            guiGraphics.drawString(font, ckdmSmallText(option.displayName.uppercase(Locale.ROOT)), area.x + 24, optionY + 5, NAME_COLOR, false)
            if (option.warning.isNotBlank()) guiGraphics.drawString(font, ckdmSmallText("!"), area.x + area.width - 12, optionY + 5, DIALOG_GOLD, false)
        }
        if (classChangePageCount() <= 1) return
        val pagerY = area.y + CLASS_CHANGE_VISIBLE_OPTIONS * CLASS_CHANGE_OPTION_STEP + 2
        renderPagerButton(guiGraphics, area.x, pagerY, "PREV", mouseX, mouseY, enabled = classChangePage > 0)
        renderPagerButton(guiGraphics, area.x + area.width - CLASS_CHANGE_PAGER_WIDTH, pagerY, "NEXT", mouseX, mouseY, enabled = classChangePage < classChangePageCount() - 1)
        val page = "${classChangePage + 1}/${classChangePageCount()}"
        guiGraphics.drawString(font, ckdmSmallText(page), area.x + (area.width - font.width(page)) / 2, pagerY + 5, DISABLED_COLOR, false)
    }

    private fun renderQuizChoices(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, layout: DialogLayout) {
        val choices = quizPageChoices()
        if (choices.isEmpty()) return
        val area = classChangeArea(layout)
        choices.forEachIndexed { index, choice ->
            val optionY = area.y + index * CLASS_CHANGE_OPTION_STEP
            val hovered = mouseX in area.x until area.x + area.width && mouseY in optionY until optionY + CLASS_CHANGE_OPTION_HEIGHT
            val texture = if (hovered) BUTTON_HOVER_TEXTURE else BUTTON_TEXTURE
            val sourceSize = if (hovered) BUTTON_HOVER_TEXTURE_SIZE else BUTTON_TEXTURE_SIZE
            val sourceCorner = if (hovered) BUTTON_HOVER_SOURCE_CORNER else BUTTON_SOURCE_CORNER
            renderNineSlice(guiGraphics, texture, area.x, optionY, area.width, CLASS_CHANGE_OPTION_HEIGHT, sourceSize, sourceSize, sourceCorner, BUTTON_DEST_CORNER)
            val label = "${('A'.code + choice.index).toChar()}. ${choice.text}"
            guiGraphics.drawString(font, ckdmSmallText(fitQuizChoice(label, area.width - 10)), area.x + 6, optionY + 5, NAME_COLOR, false)
        }
        if (classChangePageCount() <= 1) return
        val pagerY = area.y + CLASS_CHANGE_VISIBLE_OPTIONS * CLASS_CHANGE_OPTION_STEP + 2
        renderPagerButton(guiGraphics, area.x, pagerY, "PREV", mouseX, mouseY, enabled = classChangePage > 0)
        renderPagerButton(guiGraphics, area.x + area.width - CLASS_CHANGE_PAGER_WIDTH, pagerY, "NEXT", mouseX, mouseY, enabled = classChangePage < classChangePageCount() - 1)
    }

    private fun renderPagerButton(guiGraphics: GuiGraphics, x: Int, y: Int, label: String, mouseX: Int, mouseY: Int, enabled: Boolean) {
        val hovered = enabled && mouseX in x until x + CLASS_CHANGE_PAGER_WIDTH && mouseY in y until y + CLASS_CHANGE_OPTION_HEIGHT
        val texture = if (hovered) BUTTON_HOVER_TEXTURE else BUTTON_TEXTURE
        val sourceSize = if (hovered) BUTTON_HOVER_TEXTURE_SIZE else BUTTON_TEXTURE_SIZE
        val sourceCorner = if (hovered) BUTTON_HOVER_SOURCE_CORNER else BUTTON_SOURCE_CORNER
        renderNineSlice(guiGraphics, texture, x, y, CLASS_CHANGE_PAGER_WIDTH, CLASS_CHANGE_OPTION_HEIGHT, sourceSize, sourceSize, sourceCorner, BUTTON_DEST_CORNER)
        guiGraphics.drawString(font, ckdmSmallText(label), x + 8, y + 5, if (enabled) NAME_COLOR else DISABLED_COLOR, false)
    }

    private fun renderFriendship(guiGraphics: GuiGraphics, x: Int, y: Int, level: Int) {
        val clamped = level.coerceIn(-10, 10)
        repeat(FRIENDSHIP_ICON_COUNT) { index ->
            val texture = when {
                clamped > 0 && index < clamped -> HEART_TEXTURE
                clamped < 0 && index < -clamped -> ANGRY_TEXTURE
                else -> HEART_EMPTY_TEXTURE
            }
            guiGraphics.blit(texture, x + index * FRIENDSHIP_ICON_STEP, y, FRIENDSHIP_ICON_SIZE, FRIENDSHIP_ICON_SIZE, 0.0f, 0.0f, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE, ICON_SOURCE_SIZE)
        }
    }

    private fun renderFriendshipDelta(guiGraphics: GuiGraphics, x: Int, y: Int, delta: Int) {
        if (delta == 0) return
        val elapsed = System.currentTimeMillis() - messageStartedAtMs
        val alpha = when {
            elapsed < FRIENDSHIP_DELTA_FADE_MS -> elapsed.toFloat() / FRIENDSHIP_DELTA_FADE_MS
            elapsed > FRIENDSHIP_DELTA_DURATION_MS - FRIENDSHIP_DELTA_FADE_MS -> ((FRIENDSHIP_DELTA_DURATION_MS - elapsed).toFloat() / FRIENDSHIP_DELTA_FADE_MS).coerceAtLeast(0.0f)
            else -> 1.0f
        }
        if (alpha <= 0.0f) return
        val sign = if (delta > 0) "+" else ""
        val color = dialogWithAlpha(if (delta > 0) FRIENDSHIP_DELTA_POSITIVE else FRIENDSHIP_DELTA_NEGATIVE, alpha)
        val shadow = dialogWithAlpha(NAME_SHADOW, alpha)
        val text = ckdmSmallText("$sign$delta")
        guiGraphics.drawString(font, text, x + 1, y + 1, shadow, false)
        guiGraphics.drawString(font, text, x, y, color, false)
    }

    private fun dialogWithAlpha(rgb: Int, alpha: Float): Int = ((alpha.coerceIn(0.0f, 1.0f) * 255).roundToInt() shl 24) or (rgb and 0x00FFFFFF)

    private fun drawScaledString(guiGraphics: GuiGraphics, text: Component, x: Int, y: Int, scale: Float, color: Int, shadowColor: Int) {
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(x.toFloat(), y.toFloat(), 0.0f)
        pose.scale(scale, scale, 1.0f)
        guiGraphics.drawString(font, text, 1, 1, shadowColor, false)
        guiGraphics.drawString(font, text, 0, 0, color, false)
        pose.popPose()
    }

    private fun drawScaledString(guiGraphics: GuiGraphics, text: FormattedCharSequence, x: Int, y: Int, scale: Float, color: Int) {
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(x.toFloat(), y.toFloat(), 0.0f)
        pose.scale(scale, scale, 1.0f)
        guiGraphics.drawString(font, text, 0, 0, color, false)
        pose.popPose()
    }

    private fun actionLabel(action: DialogAction): String = when {
        talkMode && action == DialogAction.Talk -> "SEND"
        questMode() && action == DialogAction.Talk -> "ACCEPT"
        questMode() && action == DialogAction.Bye -> "DECLINE"
        bossClaimMode() && action == DialogAction.Talk -> "CLAIM"
        bossClaimMode() && action == DialogAction.Bye -> payload.closeLabel
        bossContractMode() && action == DialogAction.Talk -> if (talkMode) "SEND" else "TALK"
        bossContractMode() && action == DialogAction.Claim -> "CLAIM"
        (leagueChowfanMode() || leagueSelectMode() || leagueRecordMode()) && action == DialogAction.Talk -> if (talkMode) "SEND" else "TALK"
        leagueRetireMode() && action == DialogAction.Bye -> "CANCEL"
        gymTrainerMode() && action == DialogAction.Talk -> if (talkMode) "SEND" else "TALK"
        joinMode() && action == DialogAction.Talk -> "JOIN CONVERSATION"
        action == DialogAction.TechLicense -> payload.techLicenseLabel.ifBlank { "LICENSE" }
        action == DialogAction.Bye && payload.closeOnly -> payload.closeLabel
        action == DialogAction.Bye && (payload.classChangeAvailable || classChangeMode() || quizMode() || leagueSelectMode() || leagueRetireMode()) -> payload.closeLabel
        else -> action.label
    }

    private fun renderActionTooltip(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, action: DialogAction?) {
        if (!talkMode && action == DialogAction.Change) {
            guiGraphics.renderTooltip(font, Component.literal("Job change: ${payload.classChangeCost} chowcoins"), mouseX, mouseY)
            return
        }
        if (!talkMode && action == DialogAction.TechLicense) {
            guiGraphics.renderTooltip(font, Component.literal("Tech license: ${payload.techLicenseCost} chowcoins"), mouseX, mouseY)
            return
        }
        if (!talkMode && action == DialogAction.Challenge && !payload.challengeAvailable && payload.challengeDisabledReason.isNotBlank()) {
            guiGraphics.renderTooltip(font, Component.literal(payload.challengeDisabledReason), mouseX, mouseY)
            return
        }
        if (talkMode || action != DialogAction.Gift) return
        if (waitingForTalk) return
        if (payload.talkEnabled) return
        val giftStack = giftStack()
        if (giftStack.isEmpty) {
            guiGraphics.renderTooltip(font, Component.literal("Hold an item to gift."), mouseX, mouseY)
        } else {
            guiGraphics.renderTooltip(font, Component.literal("Gift ${giftStack.hoverName.string}"), mouseX, mouseY)
        }
    }

    private fun renderClassChangeTooltip(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, option: NpcClassChangeOption?) {
        if (option == null) return
        val text = if (option.warning.isBlank()) {
            "Replace ${option.displayName} for ${payload.classChangeCost} chowcoins"
        } else {
            "Replace ${option.displayName} for ${payload.classChangeCost} chowcoins. ${option.warning}"
        }
        guiGraphics.renderTooltip(font, Component.literal(text), mouseX, mouseY)
    }

    private fun renderQuizTooltip(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, choice: NpcQuizChoice?) {
        if (choice == null) return
        guiGraphics.renderTooltip(font, Component.literal(choice.text), mouseX, mouseY)
    }

    private fun giftStack(): ItemStack {
        val player = Minecraft.getInstance().player ?: return ItemStack.EMPTY
        return if (!player.mainHandItem.isEmpty) player.mainHandItem else player.offhandItem
    }

    private fun frameNpcCamera() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val npc = minecraft.level?.getEntity(payload.npcEntityId) as? LivingEntity ?: return
        val target = npc.eyePosition.add(0.0, npc.bbHeight * 0.08, 0.0)
        val eye = player.eyePosition
        val dx = target.x - eye.x
        val dy = target.y - eye.y
        val dz = target.z - eye.z
        val horizontal = sqrt(dx * dx + dz * dz).coerceAtLeast(0.0001)
        val targetYaw = (atan2(dz, dx) * 180.0 / PI - 90.0).toFloat()
        val targetPitch = (-(atan2(dy, horizontal) * 180.0 / PI)).toFloat()
        player.yRot = easeDegrees(player.yRot, targetYaw, CAMERA_EASE)
        player.xRot = easeDegrees(player.xRot, targetPitch, CAMERA_EASE).coerceIn(-70.0f, 70.0f)
        player.yHeadRot = player.yRot
        player.yBodyRot = player.yRot
    }

    private fun easeDegrees(current: Float, target: Float, amount: Float): Float {
        val delta = ((target - current + 540.0f) % 360.0f) - 180.0f
        val eased = (1.0f - (1.0f - amount) * (1.0f - amount)).coerceIn(0.0f, 1.0f)
        return current + delta * eased
    }

    private fun actionAt(mouseX: Int, mouseY: Int): DialogAction? {
        val layout = layout()
        val panelWidth = layout.panelWidth
        val panelHeight = layout.panelHeight
        val x = layout.x
        val y = layout.y
        val buttonX = x + panelWidth + BUTTON_OUTSIDE_GAP
        val currentActions = actions()
        val buttonY = y + buttonTop(panelHeight, currentActions.size)
        return currentActions.firstOrNull { action ->
            val top = buttonY + currentActions.indexOf(action) * BUTTON_STEP
            mouseX in buttonX until buttonX + BUTTON_WIDTH && mouseY in top until top + BUTTON_HEIGHT
        }
    }

    private fun actions(): List<DialogAction> = when {
        questMode() -> listOf(DialogAction.Talk, DialogAction.Bye)
        bossClaimMode() -> listOf(DialogAction.Talk, DialogAction.Bye)
        bossContractMode() -> listOf(DialogAction.Talk, DialogAction.Claim, DialogAction.Bye)
        leagueSelectMode() -> listOf(
            DialogAction.Kanto,
            DialogAction.Johto,
            DialogAction.Hoenn,
            DialogAction.Sinnoh,
            DialogAction.Unova,
            DialogAction.Kalos,
            DialogAction.Alola,
            DialogAction.Galar,
            DialogAction.Paldea,
            DialogAction.Talk,
            DialogAction.Bye,
        )
        leagueRecordMode() -> listOfNotNull(DialogAction.Talk, DialogAction.Compass.takeIf { payload.leagueCompassAvailable }, DialogAction.Retire, DialogAction.Bye)
        leagueRetireMode() -> listOf(DialogAction.Confirm, DialogAction.Bye)
        leagueChowfanMode() -> listOf(DialogAction.Talk, DialogAction.League, DialogAction.Bye)
        randomTrainerMode() -> listOf(DialogAction.Challenge, DialogAction.Bye)
        gymTrainerMode() || gymFriendlyMode() -> trainerActions()
        quizMode() -> listOf(DialogAction.Bye)
        joinMode() -> listOf(DialogAction.Talk, DialogAction.Bye)
        workMode() -> listOf(DialogAction.Move, DialogAction.Fire, DialogAction.Bye)
        classChangeMode() -> listOf(DialogAction.Bye)
        payload.classChangeAvailable -> listOf(DialogAction.Change, DialogAction.Bye)
        payload.closeOnly -> listOf(DialogAction.Bye)
        else -> listOfNotNull(DialogAction.Talk, DialogAction.League.takeIf { payload.leagueAvailable }, DialogAction.Contracts.takeIf { payload.bossContractsAvailable }, DialogAction.RetryBattle.takeIf { payload.retryBattleAvailable }, DialogAction.FriendlyBattle.takeIf { payload.friendlyBattleAvailable }, DialogAction.Buy.takeIf { payload.shopAvailable }, DialogAction.Gift, DialogAction.Work, DialogAction.TechLicense.takeIf { payload.techLicenseAvailable }, DialogAction.Training.takeIf { payload.trainingAvailable }, DialogAction.Bye)
    }

    private fun trainerActions(): List<DialogAction> = listOfNotNull(
        DialogAction.Talk,
        DialogAction.Challenge.takeIf { payload.challengeAvailable || payload.challengeDisabledReason.isNotBlank() },
        DialogAction.FriendlyBattle.takeIf { payload.friendlyBattleAvailable },
        DialogAction.Record,
        DialogAction.Bye,
    )

    private fun questMode(): Boolean = payload.dialogMode == "quest"

    private fun bossClaimMode(): Boolean = payload.dialogMode == "boss_claim"

    private fun bossContractMode(): Boolean = payload.dialogMode == "boss_contract"

    private fun leagueChowfanMode(): Boolean = payload.dialogMode == "league_chowfan"

    private fun leagueSelectMode(): Boolean = payload.dialogMode == "league_select"

    private fun leagueRecordMode(): Boolean = payload.dialogMode == "league_record"

    private fun leagueRetireMode(): Boolean = payload.dialogMode == "league_retire"

    private fun gymTrainerMode(): Boolean = payload.dialogMode == "gym_trainer"

    private fun gymFriendlyMode(): Boolean = payload.dialogMode == "gym_friendly"

    private fun randomTrainerMode(): Boolean = payload.dialogMode == "random_trainer"

    private fun joinMode(): Boolean = payload.dialogMode == "join"

    private fun workMode(): Boolean = payload.dialogMode == "work"

    private fun classChangeMode(): Boolean = payload.dialogMode == "class_change"

    private fun quizMode(): Boolean = payload.dialogMode == "quiz"

    private fun classChangeOptionAt(mouseX: Int, mouseY: Int): NpcClassChangeOption? {
        val area = classChangeArea(layout())
        return classChangePageOptions().firstOrNull { option ->
            val index = classChangePageOptions().indexOf(option)
            val top = area.y + index * CLASS_CHANGE_OPTION_STEP
            mouseX in area.x until area.x + area.width && mouseY in top until top + CLASS_CHANGE_OPTION_HEIGHT
        }
    }

    private fun classChangePagerAt(mouseX: Int, mouseY: Int): Int {
        if (classChangePageCount() <= 1) return 0
        val area = classChangeArea(layout())
        val pagerY = area.y + CLASS_CHANGE_VISIBLE_OPTIONS * CLASS_CHANGE_OPTION_STEP + 2
        if (mouseY !in pagerY until pagerY + CLASS_CHANGE_OPTION_HEIGHT) return 0
        if (classChangePage > 0 && mouseX in area.x until area.x + CLASS_CHANGE_PAGER_WIDTH) return -1
        if (classChangePage < classChangePageCount() - 1 && mouseX in area.x + area.width - CLASS_CHANGE_PAGER_WIDTH until area.x + area.width) return 1
        return 0
    }

    private fun classChangePageOptions(): List<NpcClassChangeOption> {
        val pageCount = classChangePageCount()
        classChangePage = classChangePage.coerceIn(0, pageCount - 1)
        return payload.classChangeOptions.drop(classChangePage * CLASS_CHANGE_VISIBLE_OPTIONS).take(CLASS_CHANGE_VISIBLE_OPTIONS)
    }

    private fun classChangePageCount(): Int {
        val size = if (quizMode()) payload.quizChoices.size else payload.classChangeOptions.size
        return ((size - 1) / CLASS_CHANGE_VISIBLE_OPTIONS + 1).coerceAtLeast(1)
    }

    private fun quizChoiceAt(mouseX: Int, mouseY: Int): NpcQuizChoice? {
        val area = classChangeArea(layout())
        return quizPageChoices().firstOrNull { choice ->
            val index = quizPageChoices().indexOf(choice)
            val top = area.y + index * CLASS_CHANGE_OPTION_STEP
            mouseX in area.x until area.x + area.width && mouseY in top until top + CLASS_CHANGE_OPTION_HEIGHT
        }
    }

    private fun quizPageChoices(): List<NpcQuizChoice> {
        val pageCount = classChangePageCount()
        classChangePage = classChangePage.coerceIn(0, pageCount - 1)
        return payload.quizChoices.drop(classChangePage * CLASS_CHANGE_VISIBLE_OPTIONS).take(CLASS_CHANGE_VISIBLE_OPTIONS)
    }

    private fun fitQuizChoice(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var candidate = text
        while (candidate.length > 4 && font.width("$candidate...") > maxWidth) candidate = candidate.dropLast(1)
        return "$candidate..."
    }

    private fun fitActionLabel(text: String): String {
        val maxWidth = BUTTON_WIDTH - 31
        if (font.width(ckdmSmallText(text)) <= maxWidth) return text
        var candidate = text
        while (candidate.length > 4 && font.width(ckdmSmallText("$candidate...")) > maxWidth) candidate = candidate.dropLast(1)
        return "$candidate..."
    }

    private fun classChangeArea(layout: DialogLayout): ClassChangeArea {
        val width = layout.panelWidth - PAD * 2
        return ClassChangeArea(layout.x + PAD, layout.y + PAD + AVATAR_SIZE + 36, width)
    }

    private fun classIconTexture(classId: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/classes/${classId.lowercase(Locale.ROOT)}.png")

    private fun isActionEnabled(action: DialogAction, giftStack: ItemStack = giftStack()): Boolean = when {
        waitingForTalk -> action == DialogAction.Bye
        questMode() -> true
        bossContractMode() && action == DialogAction.Claim -> payload.bossClaimAvailable
        bossContractMode() && action == DialogAction.Talk -> payload.talkEnabled
        (leagueChowfanMode() || leagueSelectMode() || leagueRecordMode()) && action == DialogAction.Talk -> payload.talkEnabled
        gymTrainerMode() && action == DialogAction.Talk -> payload.talkEnabled
        gymFriendlyMode() && action == DialogAction.Talk -> payload.talkEnabled
        randomTrainerMode() && action == DialogAction.Challenge -> payload.challengeAvailable
        (gymTrainerMode() || gymFriendlyMode()) && action == DialogAction.Challenge -> payload.challengeAvailable
        action == DialogAction.FriendlyBattle -> payload.friendlyBattleAvailable
        action == DialogAction.RetryBattle -> payload.retryBattleAvailable
        action == DialogAction.Buy -> payload.shopAvailable
        action == DialogAction.TechLicense -> payload.techLicenseAvailable && payload.techLicenseId.isNotBlank()
        action == DialogAction.Gift -> payload.talkEnabled || !giftStack.isEmpty
        action == DialogAction.Talk -> payload.talkEnabled
        else -> true
    }

    private fun isActionHighlighted(action: DialogAction): Boolean = when {
        action == DialogAction.Contracts && payload.bossClaimAvailable -> true
        action == DialogAction.League && payload.leagueAvailable -> true
        bossContractMode() && action == DialogAction.Claim && payload.bossClaimAvailable -> true
        (gymTrainerMode() || gymFriendlyMode()) && action == DialogAction.Challenge && payload.challengeAvailable -> true
        randomTrainerMode() && action == DialogAction.Challenge && payload.challengeAvailable -> true
        action == DialogAction.FriendlyBattle && payload.friendlyBattleAvailable -> true
        action == DialogAction.RetryBattle && payload.retryBattleAvailable -> true
        action == DialogAction.TechLicense && payload.techLicenseAvailable -> true
        bossClaimMode() && action == DialogAction.Talk -> true
        else -> false
    }

    private fun buttonTop(panelHeight: Int, actionCount: Int): Int {
        if (payload.closeOnly) return (panelHeight - BUTTON_HEIGHT) / 2
        val stackHeight = BUTTON_HEIGHT + (actionCount - 1).coerceAtLeast(0) * BUTTON_STEP
        return (panelHeight - stackHeight).coerceAtLeast(BUTTON_TOP_MIN)
    }

    private fun layout(): DialogLayout {
        val maxPanelWidth = (width - 24 - BUTTON_OUTSIDE_GAP - BUTTON_WIDTH).coerceAtLeast(260)
        val panelWidth = DIALOG_PANEL_WIDTH.coerceAtMost(maxPanelWidth)
        val dialogWidth = panelWidth - PAD * 2
        val fullLineCount = dialogLineCount(dialogWidth).coerceIn(1, BASE_DIALOG_LINES + MAX_EXTRA_DIALOG_LINES)
        val extraLines = (fullLineCount - BASE_DIALOG_LINES).coerceAtLeast(0)
        val classChangeReserve = if (classChangeMode() || quizMode()) CLASS_CHANGE_PANEL_RESERVE else 0
        val talkReserve = ((INPUT_HEIGHT + INPUT_GAP) * talkProgress()).toInt()
        val maxPanelHeight = (height - 24 - talkReserve).coerceAtLeast(BASE_PANEL_HEIGHT)
        val dynamicBottomPad = if (extraLines > 0) DYNAMIC_BOTTOM_PAD else 0
        val targetPanelHeight = (BASE_PANEL_HEIGHT + extraLines * LINE_HEIGHT + dynamicBottomPad + classChangeReserve).coerceAtMost(maxPanelHeight)
        val panelHeight = animatedPanelHeight(targetPanelHeight)
        val x = (width - panelWidth - BUTTON_OUTSIDE_GAP - BUTTON_WIDTH) / 2
        val y = height - panelHeight - 34 - talkReserve
        return DialogLayout(
            x = x,
            y = y,
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            inputX = x + PAD,
            inputY = y + panelHeight + INPUT_GAP,
            inputWidth = panelWidth - PAD * 2,
            dialogLineLimit = fullLineCount.coerceAtLeast(BASE_DIALOG_LINES),
        )
    }

    private fun updateTalkInput(layout: DialogLayout) {
        talkInput?.setX(layout.inputX)
        talkInput?.setY(layout.inputY)
        talkInput?.width = layout.inputWidth
        talkInput?.visible = talkMode
    }

    private fun animatedPanelHeight(targetPanelHeight: Int): Int {
        if (targetPanelHeight != panelHeightAnimationTarget) {
            panelHeightAnimationFrom = animatedPanelHeight
            panelHeightAnimationTarget = targetPanelHeight
            panelHeightAnimationStartedAtMs = System.currentTimeMillis()
        }
        val progress = ((System.currentTimeMillis() - panelHeightAnimationStartedAtMs).toFloat() / PANEL_HEIGHT_DURATION_MS).coerceIn(0.0f, 1.0f)
        val eased = 1.0f - (1.0f - progress) * (1.0f - progress) * (1.0f - progress)
        animatedPanelHeight = panelHeightAnimationFrom + (panelHeightAnimationTarget - panelHeightAnimationFrom) * eased
        return animatedPanelHeight.toInt()
    }

    private fun dialogLineCount(dialogWidth: Int): Int {
        val visibleMessage = stripDialogMarkup(displayMessage)
        val wrapWidth = (dialogWidth / DIALOG_TEXT_SCALE).toInt().coerceAtLeast(1)
        val wrapped = font.split(dialogTextComponent(visibleMessage), wrapWidth).size.coerceAtLeast(1)
        val averageCharsPerLine = (wrapWidth / 6).coerceAtLeast(24)
        val estimated = (visibleMessage.length + averageCharsPerLine - 1) / averageCharsPerLine
        return maxOf(wrapped, estimated)
    }

    private fun talkProgress(): Float {
        if (!talkMode) return 0.0f
        val progress = ((System.currentTimeMillis() - talkModeChangedAtMs).toFloat() / TALK_LAYOUT_DURATION_MS).coerceIn(0.0f, 1.0f)
        return 1.0f - (1.0f - progress) * (1.0f - progress)
    }

    private fun renderAvatar(guiGraphics: GuiGraphics, npcId: String, x: Int, y: Int) {
        val texture = npcTexture(npcId)
        guiGraphics.blit(texture, x, y, AVATAR_SIZE, AVATAR_SIZE, 8.0f, 8.0f, 8, 8, SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE)
        guiGraphics.blit(texture, x, y, AVATAR_SIZE, AVATAR_SIZE, 40.0f, 8.0f, 8, 8, SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE)
    }

    private fun renderNineSlice(guiGraphics: GuiGraphics, texture: ResourceLocation, x: Int, y: Int, width: Int, height: Int, textureWidth: Int, textureHeight: Int, sourceCorner: Int, destinationCorner: Int) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        val edgeX = textureWidth - sourceCorner
        val edgeY = textureHeight - sourceCorner
        val middleWidth = textureWidth - sourceCorner * 2
        val middleHeight = textureHeight - sourceCorner * 2
        val innerWidth = (width - destinationCorner * 2).coerceAtLeast(0)
        val innerHeight = (height - destinationCorner * 2).coerceAtLeast(0)
        blit(guiGraphics, texture, x, y, destinationCorner, destinationCorner, 0, 0, sourceCorner, sourceCorner, textureWidth, textureHeight)
        blit(guiGraphics, texture, x + destinationCorner, y, innerWidth, destinationCorner, sourceCorner, 0, middleWidth, sourceCorner, textureWidth, textureHeight)
        blit(guiGraphics, texture, x + width - destinationCorner, y, destinationCorner, destinationCorner, edgeX, 0, sourceCorner, sourceCorner, textureWidth, textureHeight)
        blit(guiGraphics, texture, x, y + destinationCorner, destinationCorner, innerHeight, 0, sourceCorner, sourceCorner, middleHeight, textureWidth, textureHeight)
        blit(guiGraphics, texture, x + destinationCorner, y + destinationCorner, innerWidth, innerHeight, sourceCorner, sourceCorner, middleWidth, middleHeight, textureWidth, textureHeight)
        blit(guiGraphics, texture, x + width - destinationCorner, y + destinationCorner, destinationCorner, innerHeight, edgeX, sourceCorner, sourceCorner, middleHeight, textureWidth, textureHeight)
        blit(guiGraphics, texture, x, y + height - destinationCorner, destinationCorner, destinationCorner, 0, edgeY, sourceCorner, sourceCorner, textureWidth, textureHeight)
        blit(guiGraphics, texture, x + destinationCorner, y + height - destinationCorner, innerWidth, destinationCorner, sourceCorner, edgeY, middleWidth, sourceCorner, textureWidth, textureHeight)
        blit(guiGraphics, texture, x + width - destinationCorner, y + height - destinationCorner, destinationCorner, destinationCorner, edgeX, edgeY, sourceCorner, sourceCorner, textureWidth, textureHeight)
    }

    private fun blit(guiGraphics: GuiGraphics, texture: ResourceLocation, x: Int, y: Int, width: Int, height: Int, sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int, textureWidth: Int, textureHeight: Int) {
        if (width <= 0 || height <= 0) return
        guiGraphics.blit(texture, x, y, width, height, sourceX.toFloat(), sourceY.toFloat(), sourceWidth, sourceHeight, textureWidth, textureHeight)
    }

    private fun ckdmText(text: String): Component = Component.literal(text.uppercase(Locale.ROOT)).withStyle { style -> style.withFont(CKDM_BOLD_FONT) }

    private fun ckdmSmallText(text: String): Component = Component.literal(text.uppercase(Locale.ROOT)).withStyle { style -> style.withFont(CKDM_SMALL_FONT) }

    private fun dialogTextComponent(text: String, shadow: Boolean = false): Component {
        val playerName = minecraft?.player?.gameProfile?.name.orEmpty()
        val normalizedText = normalizeDialogMarkupSpacing(text)
        val root = Component.literal("").withStyle { style -> style.withColor(if (shadow) DIALOG_SHADOW else DIALOG_COLOR) }
        var color = DIALOG_COLOR
        var cursor = 0
        DIALOG_TAG_REGEX.findAll(normalizedText).forEach { match ->
            appendDialogText(root, normalizedText.substring(cursor, match.range.first), color, playerName, shadow)
            val tag = match.groupValues[1].lowercase(Locale.ROOT)
            val closing = match.value.startsWith("</")
            color = if (closing) DIALOG_COLOR else when (tag) {
                "mission", "player" -> DIALOG_GOLD
                "coin" -> DIALOG_COIN
                "xp" -> DIALOG_XP
                "b" -> DIALOG_HIGHLIGHT
                else -> DIALOG_COLOR
            }
            cursor = match.range.last + 1
        }
        appendDialogText(root, normalizedText.substring(cursor).replace(Regex("<[^>]*$"), ""), color, playerName, shadow)
        return root
    }

    private fun normalizeDialogMarkupSpacing(text: String): String = text
        .let(::normalizeDisplaySpacing)
        .replace(Regex("(?<=[A-Za-z0-9])(?=<(?:mission|coin|xp|player|b)>)", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("(</(?:mission|coin|xp|player|b)>)(?=[A-Za-z0-9])", RegexOption.IGNORE_CASE), "$1 ")

    private fun normalizeDisplaySpacing(text: String): String = text
        .replace(Regex("(?<=[A-Za-z])(?=\\d)"), " ")
        .replace(Regex("(?<=\\d)(?=[A-Za-z]{2})"), " ")

    private fun appendDialogText(root: MutableComponent, text: String, color: Int, playerName: String, shadow: Boolean) {
        if (text.isEmpty()) return
        if (playerName.isBlank()) {
            root.append(styledDialogText(text, color, highlight = color != DIALOG_COLOR, shadow))
            return
        }
        val regex = Regex(Regex.escape(playerName), RegexOption.IGNORE_CASE)
        var cursor = 0
        regex.findAll(text).forEach { match ->
            root.append(styledDialogText(text.substring(cursor, match.range.first), color, highlight = color != DIALOG_COLOR, shadow))
            root.append(styledDialogText(match.value, DIALOG_GOLD, highlight = true, shadow))
            cursor = match.range.last + 1
        }
        root.append(styledDialogText(text.substring(cursor), color, highlight = color != DIALOG_COLOR, shadow))
    }

    private fun styledDialogText(text: String, color: Int, highlight: Boolean, shadow: Boolean): Component {
        val displayText = if (highlight) text.uppercase(Locale.ROOT) else text
        return Component.literal(displayText).withStyle { style ->
            val fontStyle = if (highlight) style.withFont(CKDM_CLAIM_FONT) else style
            fontStyle.withColor(if (shadow) DIALOG_SHADOW else color)
        }
    }

    private fun stripDialogMarkup(text: String): String = text.replace(DIALOG_TAG_REGEX, "").replace(Regex("<[^>]*$"), "")

    private fun enterTalkMode() {
        talkMode = true
        talkModeChangedAtMs = System.currentTimeMillis()
        talkInput?.visible = true
        talkInput?.isFocused = true
        setFocused(talkInput)
    }

    private fun exitTalkMode() {
        talkMode = false
        waitingForTalk = false
        talkInput?.visible = false
        setFocused(null)
    }

    private fun sendTalkMessage() {
        if (waitingForTalk) return
        val text = talkInput?.value?.trim().orEmpty()
        if (text.isBlank()) return
        talkInput?.value = ""
        waitingForTalk = true
        activeResponseToken = NpcDialogTokens.next()
        updateDisplayMessage("...")
        NpcNetwork.sendTalk(payload.npcId, text, activeResponseToken)
    }

    private fun skipPendingTalkResponse() {
        if (!waitingForTalk) return
        NpcClient.skipTalkResponse(activeResponseToken)
        waitingForTalk = false
        activeResponseToken = 0L
        streamingResponseActive = false
    }

    private fun updateDisplayMessage(message: String) {
        displayMessage = message
        messageStartedAtMs = System.currentTimeMillis()
        lastAnimaleseIndex = 0
        streamingResponseActive = false
        highestVisibleCharacters = 0
    }

    private fun updateStreamingDisplayMessage(message: String) {
        if (message == displayMessage) return
        val visibleBefore = if (streamingResponseActive) maxOf(visibleDialogCharacters(), highestVisibleCharacters).coerceAtMost(message.length) else 0
        val previousAnimaleseIndex = lastAnimaleseIndex.coerceAtMost(visibleBefore).coerceAtMost(message.length)
        displayMessage = message
        val visibleElapsedMs = ((visibleBefore / STREAMING_TYPEWRITER_CHARS_PER_SECOND) * 1000.0).toLong()
        messageStartedAtMs = System.currentTimeMillis() - TYPEWRITER_DELAY_MS - visibleElapsedMs
        lastAnimaleseIndex = previousAnimaleseIndex
        streamingResponseActive = true
        highestVisibleCharacters = visibleBefore
    }

    private fun currentRenderMessage(): String = if (waitingForTalk) ".".repeat(((System.currentTimeMillis() / 300L) % 3L + 1L).toInt()) else displayMessage

    private fun entranceProgress(): Float {
        val progress = ((System.currentTimeMillis() - openedAtMs).toFloat() / ENTRANCE_DURATION_MS).coerceIn(0.0f, 1.0f)
        return 1.0f - (1.0f - progress) * (1.0f - progress) * (1.0f - progress)
    }

    private fun visibleDialogCharacters(): Int {
        val elapsed = (System.currentTimeMillis() - messageStartedAtMs - TYPEWRITER_DELAY_MS).coerceAtLeast(0L)
        return ((elapsed / 1000.0) * typewriterCharsPerSecond()).toInt().coerceIn(0, currentRenderMessage().length)
    }

    private fun typewriterCharsPerSecond(): Double = if (streamingResponseActive) STREAMING_TYPEWRITER_CHARS_PER_SECOND else TYPEWRITER_CHARS_PER_SECOND

    private fun playAnimalese(visibleCharacters: Int) {
        if (visibleCharacters <= lastAnimaleseIndex) return
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val npc = level.getEntity(payload.npcEntityId)
        val x = npc?.x ?: player.x
        val y = npc?.y ?: player.y
        val z = npc?.z ?: player.z
        val distance = npc?.distanceTo(player) ?: 0.0f
        val radius = payload.animaleseRadius.coerceAtLeast(1.0f)
        if (distance > radius) {
            lastAnimaleseIndex = visibleCharacters
            return
        }
        val distanceVolume = (1.0f - distance / radius).coerceIn(0.0f, 1.0f)
        val volume = payload.animaleseVolume.coerceIn(0.0f, 1.0f) * distanceVolume
        if (volume <= 0.01f) {
            lastAnimaleseIndex = visibleCharacters
            return
        }
        val message = currentRenderMessage()
        val end = visibleCharacters.coerceAtMost(message.length)
        for (index in lastAnimaleseIndex until end) {
            val soundIndex = animaleseSoundIndex(message, index) ?: continue
            val sound = SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "npc.animalese.${animalesePitch()}.sound${soundIndex.toString().padStart(2, '0')}"))
            val pitch = (payload.animalesePitchMultiplier * (0.96f + player.random.nextFloat() * 0.08f)).coerceIn(0.5f, 2.0f)
            minecraft.soundManager.play(SimpleSoundInstance(sound, SoundSource.VOICE, volume, pitch, RandomSource.create(), x, y, z))
        }
        lastAnimaleseIndex = visibleCharacters
    }

    private fun animalesePitch(): String = payload.animalesePitch.lowercase(Locale.ROOT).let { value -> if (value in ANIMALESE_PITCHES) value else "med" }

    private fun animaleseSoundIndex(message: String, index: Int): Int? {
        val char = message[index].lowercaseChar()
        if (index > 0 && char == message[index - 1].lowercaseChar()) return null
        if (char == 's' && index + 1 < message.length && message[index + 1].lowercaseChar() == 'h') return 28
        if (char == 't' && index + 1 < message.length && message[index + 1].lowercaseChar() == 'h') return 27
        if (char == 'h' && index > 0) {
            return when (message[index - 1].lowercaseChar()) {
                't', 's' -> null
                else -> null
            }
        }
        if (char in 'a'..'z') return char - 'a' + 1
        return when (char) {
            '.', ',', '?' -> 30
            else -> null
        }
    }

    companion object {
        private val PANEL_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_container_grey.png")
        private val CKDM_BOLD_FONT = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold")
        private val CKDM_SMALL_FONT = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold_small")
        private val CKDM_CLAIM_FONT = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold_claim")
        private val BUTTON_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_gray.png")
        private val GREEN_BUTTON_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_green.png")
        private val BUTTON_HOVER_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_green_hover.png")
        private val RED_BUTTON_HOVER_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_btn_red_hover.png")
        private val HEART_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/heart.png")
        private val HEART_EMPTY_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/heart_empty.png")
        private val ANGRY_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/angry.png")
        private const val PANEL_TEXTURE_WIDTH = 1646
        private const val PANEL_TEXTURE_HEIGHT = 256
        private const val PANEL_SOURCE_CORNER = 75
        private const val PANEL_DEST_CORNER = 13
        private const val PAD = 14
        private const val DIALOG_PANEL_WIDTH = 440
        private const val BUTTON_OUTSIDE_GAP = 10
        private const val BASE_PANEL_HEIGHT = 134
        private const val AVATAR_SIZE = 42
        private const val SKIN_TEXTURE_SIZE = 64
        private const val TEXT_GAP = 12
        private const val LINE_HEIGHT = 11
        private const val DIALOG_TEXT_SCALE = 1.0f
        private const val NAME_Y = 19
        private const val NAME_SCALE = 1.25f
        private const val FRIENDSHIP_Y = 43
        private const val BASE_DIALOG_LINES = 4
        private const val MAX_EXTRA_DIALOG_LINES = 10
        private const val INPUT_HEIGHT = 18
        private const val INPUT_GAP = 16
        private const val DYNAMIC_BOTTOM_PAD = 11
        private const val BUTTON_WIDTH = 112
        private const val BUTTON_HEIGHT = 20
        private const val BUTTON_TOP_MIN = 4
        private const val BUTTON_STEP = 23
        private const val CLASS_CHANGE_PANEL_RESERVE = 110
        private const val CLASS_CHANGE_VISIBLE_OPTIONS = 4
        private const val CLASS_CHANGE_OPTION_HEIGHT = 20
        private const val CLASS_CHANGE_OPTION_STEP = 23
        private const val CLASS_CHANGE_PAGER_WIDTH = 52
        private const val BUTTON_TEXTURE_SIZE = 8
        private const val BUTTON_HOVER_TEXTURE_SIZE = 10
        private const val BUTTON_SOURCE_CORNER = 2
        private const val BUTTON_HOVER_SOURCE_CORNER = 3
        private const val BUTTON_DEST_CORNER = 4
        private const val ICON_SIZE = 14
        private const val ICON_SOURCE_SIZE = 16
        private const val FRIENDSHIP_ICON_COUNT = 10
        private const val FRIENDSHIP_ICON_SIZE = 10
        private const val FRIENDSHIP_ICON_STEP = 11
        private const val ENTRANCE_DURATION_MS = 180.0f
        private const val PANEL_HEIGHT_DURATION_MS = 140.0f
        private const val TALK_LAYOUT_DURATION_MS = 160.0f
        private const val DIALOG_KEEPALIVE_CLIENT_TICKS = 40
        private const val CAMERA_EASE = 0.12f
        private const val TYPEWRITER_DELAY_MS = 110L
        private const val TYPEWRITER_CHARS_PER_SECOND = 68.0
        private const val STREAMING_TYPEWRITER_CHARS_PER_SECOND = 118.0
        private const val NAME_COLOR = 0xFFFFFFFF.toInt()
        private const val NAME_SHADOW = 0xCC050505.toInt()
        private const val DIALOG_COLOR = 0xFFFFFFFF.toInt()
        private const val DIALOG_HIGHLIGHT = 0xFFFFFFFF.toInt()
        private const val DIALOG_SHADOW = 0xCC050505.toInt()
        private const val DIALOG_GOLD = 0xFFFFD24A.toInt()
        private const val DIALOG_COIN = 0xFFFFD35C.toInt()
        private const val DIALOG_XP = 0xFF61F27A.toInt()
        private const val FRIENDSHIP_DELTA_POSITIVE = 0xFF83F28F.toInt()
        private const val FRIENDSHIP_DELTA_NEGATIVE = 0xFFFF6F6F.toInt()
        private const val FRIENDSHIP_DELTA_DURATION_MS = 2200L
        private const val FRIENDSHIP_DELTA_FADE_MS = 260L
        private const val CONTRACT_COLOR = 0xFF83F28F.toInt()
        private const val DISABLED_COLOR = 0xFF8C8778.toInt()
        private val DIALOG_TAG_REGEX = Regex("(?i)</?(mission|coin|xp|player|b)>")
        private val ANIMALESE_PITCHES = setOf("high", "med", "low", "lowest")
    }
}

private data class DialogLayout(
    val x: Int,
    val y: Int,
    val panelWidth: Int,
    val panelHeight: Int,
    val inputX: Int,
    val inputY: Int,
    val inputWidth: Int,
    val dialogLineLimit: Int,
)

private data class ClassChangeArea(
    val x: Int,
    val y: Int,
    val width: Int,
)

private enum class DialogAction(val label: String, val icon: ResourceLocation) {
    Talk("TALK", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/chat_bubble_white.png")),
    Buy("BUY", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/shop.png")),
    Gift("GIFT", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/gift.png")),
    Work("WORK", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/shop.png")),
    League("LEAGUE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/trophy.png")),
    Compass("REQUEST COMPASS", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/trophy.png")),
    Kanto("KANTO LEAGUE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/trophy.png")),
    Johto("JOHTO LEAGUE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/trophy.png")),
    Hoenn("HOENN LEAGUE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/trophy.png")),
    Sinnoh("SINNOH LEAGUE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/trophy.png")),
    Unova("UNOVA LEAGUE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/trophy.png")),
    Kalos("KALOS LEAGUE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/trophy.png")),
    Alola("ALOLA LEAGUE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/trophy.png")),
    Galar("GALAR LEAGUE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/trophy.png")),
    Paldea("PALDEA LEAGUE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/trophy.png")),
    Retire("RETIRE RECORD", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/cancel.png")),
    Confirm("CONFIRM", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/accept.png")),
    Contracts("CONTRACTS", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/quest_log.png")),
    Challenge("CHALLENGE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/kilic.png")),
    FriendlyBattle("FRIENDLY BATTLE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/kilic.png")),
    RetryBattle("RETRY BATTLE", ResourceLocation.fromNamespaceAndPath("cobblemon", "textures/gui/ball/poke_ball.png")),
    Badge("BADGE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/trophy.png")),
    Record("RECORD", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/trophy.png")),
    Training("TRAINING", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/chat_bubble_white.png")),
    TechLicense("LICENSE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/quest_log.png")),
    Change("CHANGE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/coins.png")),
    Move("MOVE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/chat_bubble_white.png")),
    Fire("FIRE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/chat_bubble_orange.png")),
    Claim("CLAIM", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/battlepass-claimable.png.png")),
    Bye("BYE", ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/icons/chat_bubble_orange.png")),
}
