package dev.gisketch.chowkingdom.shops

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import dev.gisketch.chowkingdom.ChowKingdomMod
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.util.Locale

class ShopBlockEntityRenderer(private val context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<ShopBlockEntity> {
    override fun render(
        shop: ShopBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val stack = shop.displayItem
        if (stack.isEmpty) return

        when (shop.renderStyle) {
            ShopRenderStyle.ANGLED -> renderAngled(shop, stack, partialTick, poseStack, bufferSource, packedLight, packedOverlay)
            ShopRenderStyle.HOOK -> renderHook(shop, stack, poseStack, bufferSource, packedLight, packedOverlay)
            ShopRenderStyle.CRATE -> renderCrate(shop, stack, poseStack, bufferSource, packedLight, packedOverlay)
            ShopRenderStyle.WINDOW -> renderWindow(shop, stack, partialTick, poseStack, bufferSource, packedLight, packedOverlay)
            ShopRenderStyle.RUG -> renderRug(shop, stack, partialTick, poseStack, bufferSource, packedLight, packedOverlay)
            ShopRenderStyle.SHELF -> renderShelf(shop, stack, poseStack, bufferSource, packedLight, packedOverlay)
        }
        renderPriceTag(shop, poseStack, bufferSource, packedLight)
    }

    private fun renderAngled(
        shop: ShopBlockEntity,
        stack: ItemStack,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        poseStack.pushPose()
        poseStack.translate(0.5, 1.04, 0.5)
        poseStack.mulPose(Axis.YP.rotationDegrees(spin(shop, partialTick)))
        poseStack.mulPose(Axis.XP.rotationDegrees(-67.5f))
        poseStack.scale(0.4f, 0.4f, 0.4f)
        renderStockItem(shop, stack, ItemDisplayContext.GUI, poseStack, bufferSource, light, overlay)
        poseStack.popPose()
        renderAngledLabels(shop, poseStack, bufferSource, light)
    }

    private fun renderHook(
        shop: ShopBlockEntity,
        stack: ItemStack,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)
        poseStack.mulPose(Axis.YP.rotationDegrees(facingRotation(facing(shop))))
        poseStack.translate(0.0, -0.5, 0.0)
        poseStack.scale(0.7f, 0.7f, 0.7f)
        poseStack.mulPose(Axis.ZP.rotationDegrees(45.0f))
        renderStockItem(shop, stack, ItemDisplayContext.GUI, poseStack, bufferSource, light, overlay)
        poseStack.popPose()
        renderHookLabels(shop, poseStack, bufferSource, light)
    }

    private fun renderWindow(
        shop: ShopBlockEntity,
        stack: ItemStack,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        poseStack.pushPose()
        when (facing(shop)) {
            Direction.NORTH -> poseStack.translate(0.6, 0.5, 0.45)
            Direction.EAST -> poseStack.translate(0.55, 0.5, 0.6)
            Direction.SOUTH -> poseStack.translate(0.4, 0.5, 0.55)
            Direction.WEST -> poseStack.translate(0.45, 0.5, 0.4)
            else -> poseStack.translate(0.5, 0.5, 0.5)
        }
        poseStack.mulPose(Axis.YP.rotationDegrees(spin(shop, partialTick)))
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f))
        poseStack.translate(0.0, 0.0, -0.3)
        poseStack.scale(0.8f, 0.8f, 0.8f)
        renderStockItem(shop, stack, ItemDisplayContext.GUI, poseStack, bufferSource, light, overlay)
        poseStack.popPose()
        renderWindowLabels(shop, poseStack, bufferSource, light)
    }

    private fun renderRug(
        shop: ShopBlockEntity,
        stack: ItemStack,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        poseStack.pushPose()
        poseStack.translate(0.5, 0.18, 0.5)
        poseStack.translate(0.0, -0.16, 0.0)
        poseStack.scale(0.8f, 0.8f, 0.8f)
        poseStack.translate(0.0, 0.5 + Math.sin((spin(shop, partialTick) / 36.0)).toFloat() * 0.15, 0.0)
        poseStack.mulPose(Axis.YP.rotationDegrees(spin(shop, partialTick)))
        poseStack.mulPose(Axis.XP.rotationDegrees(spin(shop, partialTick) * 1.25f))
        poseStack.scale(0.8f, 0.8f, 0.8f)
        renderStockItem(shop, stack, ItemDisplayContext.GUI, poseStack, bufferSource, light, overlay)
        poseStack.popPose()
        renderRugLabels(shop, poseStack, bufferSource, light)
    }

    private fun renderShelf(
        shop: ShopBlockEntity,
        stack: ItemStack,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)
        poseStack.mulPose(Axis.YP.rotationDegrees(facingRotation(facing(shop))))

        val type = if (shop.blockState.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            shop.blockState.getValue(BlockStateProperties.SLAB_TYPE)
        } else {
            SlabType.DOUBLE
        }

        if (type == SlabType.BOTTOM || type == SlabType.DOUBLE) {
            renderShelfPair(shop, stack, poseStack, bufferSource, light, overlay)
        }
        if (type == SlabType.TOP || type == SlabType.DOUBLE) {
            poseStack.translate(0.0, 0.44, 0.0)
            renderShelfPair(shop, stack, poseStack, bufferSource, light, overlay)
        }
        poseStack.popPose()
    }

    private fun renderShelfPair(
        shop: ShopBlockEntity,
        stack: ItemStack,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        poseStack.pushPose()
        poseStack.translate(0.0, -0.216, 0.3)
        poseStack.translate(0.0, -0.08, 0.0)
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0f))
        poseStack.scale(0.4f, 0.4f, 0.4f)
        for (index in 0 until 3) {
            poseStack.pushPose()
            poseStack.translate(-0.5, 0.0, -index * 0.05)
            poseStack.mulPose(Axis.ZP.rotationDegrees(55.0f + (index + 1) * 55.0f))
            renderStockItem(shop, stack, ItemDisplayContext.GUI, poseStack, bufferSource, light, overlay)
            poseStack.popPose()

            poseStack.pushPose()
            poseStack.translate(0.5, 0.0, -index * 0.05)
            poseStack.mulPose(Axis.ZP.rotationDegrees(127.0f + (index + 1) * 55.0f))
            renderStockItem(shop, stack, ItemDisplayContext.GUI, poseStack, bufferSource, light, overlay)
            poseStack.popPose()
        }
        poseStack.popPose()
        renderShelfPairLabels(shop, poseStack, bufferSource, light)
    }

    private fun renderCrate(
        shop: ShopBlockEntity,
        stack: ItemStack,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        poseStack.pushPose()
        poseStack.translate(0.5, 0.0, 0.5)
        poseStack.mulPose(Axis.YP.rotationDegrees(facingRotation(facing(shop))))
        poseStack.pushPose()
        poseStack.translate(0.0, 0.3, 0.0)
        poseStack.mulPose(Axis.XP.rotationDegrees(40.0f))
        poseStack.translate(0.0, -0.3, 0.0)
        CRATE_STOCK_POSITIONS.take(shop.stockCount.coerceIn(0, CRATE_STOCK_POSITIONS.size)).forEach { factor ->
            poseStack.pushPose()
            poseStack.translate(factor.x.toDouble(), factor.y.toDouble(), -factor.z.toDouble())
            poseStack.mulPose(Axis.YP.rotationDegrees(factor.rotationY))
            poseStack.mulPose(Axis.XP.rotationDegrees(factor.rotationX))
            poseStack.scale(0.5f, 0.5f, 0.8f)
            renderStockItem(shop, stack, ItemDisplayContext.GUI, poseStack, bufferSource, light, overlay)
            poseStack.popPose()
        }
        poseStack.popPose()
        renderCrateLabels(shop, poseStack, bufferSource, light)
        poseStack.popPose()
    }

    private fun renderStockItem(
        shop: ShopBlockEntity,
        stack: ItemStack,
        displayContext: ItemDisplayContext,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        val renderStack = stack.copy()
        renderStack.count = 1
        val renderer = context.itemRenderer
        val alpha = if (shop.stockCount <= 0) 0.5f else 1.0f
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha)
        renderer.render(
            renderStack,
            displayContext,
            false,
            poseStack,
            bufferSource,
            light,
            overlay,
            renderer.getModel(renderStack, shop.level, null, 0),
        )
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun renderAngledLabels(shop: ShopBlockEntity, poseStack: PoseStack, bufferSource: MultiBufferSource, light: Int) {
        if (!isHovered(shop)) return
        poseStack.pushPose()
        when (facing(shop)) {
            Direction.NORTH -> {
                poseStack.translate(0.54, 0.514375, 0.0)
                poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f))
                poseStack.mulPose(Axis.XP.rotationDegrees(-22.5f))
            }
            Direction.EAST -> {
                poseStack.translate(1.0, 0.514375, 0.54)
                poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f))
                poseStack.mulPose(Axis.YP.rotationDegrees(90.0f))
                poseStack.mulPose(Axis.XP.rotationDegrees(-22.5f))
            }
            Direction.SOUTH -> {
                poseStack.translate(0.46, 0.514375, 1.0)
                poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f))
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0f))
                poseStack.mulPose(Axis.XP.rotationDegrees(-22.5f))
            }
            Direction.WEST -> {
                poseStack.translate(0.0, 0.514375, 0.46)
                poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f))
                poseStack.mulPose(Axis.YP.rotationDegrees(270.0f))
                poseStack.mulPose(Axis.XP.rotationDegrees(-22.5f))
            }
            else -> {}
        }
        renderText(shop.stockText(), STOCK_TEXT_COLOR, 0.018f, poseStack, bufferSource, light, displayMode = Font.DisplayMode.NORMAL)
        poseStack.popPose()
    }

    private fun renderCrateLabels(shop: ShopBlockEntity, poseStack: PoseStack, bufferSource: MultiBufferSource, light: Int) {
        if (!isHovered(shop)) return
        poseStack.pushPose()
        poseStack.translate(0.06, 0.14, -0.664)
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f))
        poseStack.mulPose(Axis.XP.rotationDegrees(-22.5f))
        renderText(shop.stockText(), STOCK_TEXT_COLOR, 0.018f, poseStack, bufferSource, light)
        poseStack.popPose()
    }

    private fun renderShelfPairLabels(shop: ShopBlockEntity, poseStack: PoseStack, bufferSource: MultiBufferSource, light: Int) {
        if (!isHovered(shop)) return
        poseStack.pushPose()
        poseStack.translate(-0.08, -0.16, 0.43749)
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f))
        renderText(shop.stockText(), STOCK_TEXT_COLOR, 0.016f, poseStack, bufferSource, light, invertZ = true)
        poseStack.popPose()
    }

    private fun renderWindowLabels(shop: ShopBlockEntity, poseStack: PoseStack, bufferSource: MultiBufferSource, light: Int) {
        if (!isHovered(shop)) return
        poseStack.pushPose()
        poseStack.translate(0.5, 0.0, 0.5)
        poseStack.mulPose(Axis.YP.rotationDegrees(facingRotation(facing(shop))))
        poseStack.translate(-0.5, 0.0, -0.5)
        poseStack.translate(0.30125, 0.22, 0.9167)
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f))
        poseStack.mulPose(Axis.XP.rotationDegrees(-22.5f))
        renderText(shop.stockText(), STOCK_TEXT_COLOR, 0.018f, poseStack, bufferSource, light, invertZ = true)
        poseStack.popPose()
    }

    private fun renderHookLabels(shop: ShopBlockEntity, poseStack: PoseStack, bufferSource: MultiBufferSource, light: Int) {
        if (!isHovered(shop)) return
        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)
        poseStack.mulPose(Axis.YP.rotationDegrees(facingRotation(facing(shop))))
        poseStack.translate(0.05, 0.18, -0.03126)
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f))
        renderText(shop.stockText(), STOCK_TEXT_COLOR, 0.016f, poseStack, bufferSource, light, invertZ = true)
        poseStack.popPose()
    }

    private fun renderRugLabels(shop: ShopBlockEntity, poseStack: PoseStack, bufferSource: MultiBufferSource, light: Int) {
        if (!isHovered(shop)) return
        poseStack.pushPose()
        poseStack.translate(0.5, 0.18, 0.5)
        poseStack.translate(0.0, RUG_LABEL_LIFT, 0.0)

        poseStack.pushPose()
        poseStack.translate(-0.37, -0.178, 0.37)
        poseStack.mulPose(Axis.YP.rotationDegrees(-45.0f))
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0f))
        renderText(shop.stockText(), STOCK_TEXT_COLOR, 0.014f, poseStack, bufferSource, light)
        poseStack.popPose()

        poseStack.popPose()
    }

    private fun renderPriceTag(shop: ShopBlockEntity, poseStack: PoseStack, bufferSource: MultiBufferSource, light: Int) {
        val text = format(shop.price)
        val font = context.font
        val textWidth = font.width(text)
        val contentWidth = PRICE_ICON_SIZE + PRICE_TEXT_GAP + textWidth
        val startX = -contentWidth / 2.0f
        poseStack.pushPose()
        poseStack.translate(0.5, PRICE_TAG_Y, 0.5)
        poseStack.mulPose(Minecraft.getInstance().entityRenderDispatcher.cameraOrientation())
        poseStack.scale(-PRICE_TAG_SCALE, -PRICE_TAG_SCALE, PRICE_TAG_SCALE)
        renderTextureQuad(poseStack, bufferSource, CHOWCOIN_TEXTURE, startX, -PRICE_ICON_SIZE / 2.0f, PRICE_ICON_SIZE.toFloat(), PRICE_ICON_SIZE.toFloat(), light)
        font.drawInBatch(
            text,
            startX + PRICE_ICON_SIZE + PRICE_TEXT_GAP,
            -4.0f,
            PRICE_TEXT_COLOR,
            false,
            poseStack.last().pose(),
            bufferSource,
            Font.DisplayMode.SEE_THROUGH,
            PRICE_BACKGROUND_COLOR,
            light,
        )
        poseStack.popPose()
    }

    private fun renderTextureQuad(poseStack: PoseStack, bufferSource: MultiBufferSource, texture: ResourceLocation, x: Float, y: Float, width: Float, height: Float, light: Int) {
        val matrix = poseStack.last().pose()
        val consumer = bufferSource.getBuffer(RenderType.text(texture))
        consumer.addVertex(matrix, x, y + height, 0.0f).setColor(255, 255, 255, 255).setUv(0.0f, 1.0f).setLight(light)
        consumer.addVertex(matrix, x + width, y + height, 0.0f).setColor(255, 255, 255, 255).setUv(1.0f, 1.0f).setLight(light)
        consumer.addVertex(matrix, x + width, y, 0.0f).setColor(255, 255, 255, 255).setUv(1.0f, 0.0f).setLight(light)
        consumer.addVertex(matrix, x, y, 0.0f).setColor(255, 255, 255, 255).setUv(0.0f, 0.0f).setLight(light)
    }

    private fun isHovered(shop: ShopBlockEntity): Boolean {
        val hit = Minecraft.getInstance().hitResult as? BlockHitResult ?: return false
        return hit.type == HitResult.Type.BLOCK && hit.blockPos == shop.blockPos
    }

    private fun renderText(
        text: String,
        color: Int,
        scale: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        light: Int,
        invertZ: Boolean = false,
        x: Float = quantityTextX(text),
        displayMode: Font.DisplayMode = Font.DisplayMode.SEE_THROUGH,
        rotateHalfTurn: Boolean = false,
        doubleSided: Boolean = true,
    ) {
        val font = context.font
        val faceOffset = if (invertZ) -TEXT_FACE_OFFSET else TEXT_FACE_OFFSET
        poseStack.pushPose()
        poseStack.translate(0.0, 0.0, faceOffset)
        if (rotateHalfTurn) poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f))
        poseStack.scale(scale, scale, if (invertZ) -scale else scale)
        drawTextFace(font, text, color, poseStack, bufferSource, light, x, displayMode)
        poseStack.popPose()

        if (!doubleSided) return

        poseStack.pushPose()
        poseStack.translate(0.0, 0.0, -faceOffset)
        if (rotateHalfTurn) poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f))
        poseStack.scale(scale, scale, if (invertZ) -scale else scale)
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0f))
        drawTextFace(font, text, color, poseStack, bufferSource, light, -x - font.width(text), displayMode)
        poseStack.popPose()
    }

    private fun drawTextFace(
        font: Font,
        text: String,
        color: Int,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        light: Int,
        x: Float,
        displayMode: Font.DisplayMode,
    ) {
        font.drawInBatch(
            text,
            x,
            -4.0f,
            color,
            false,
            poseStack.last().pose(),
            bufferSource,
            displayMode,
            0,
            light,
        )
    }

    private fun ShopBlockEntity.stockText(): String = stockCount.toString()

    private fun format(amount: Long): String = String.format(Locale.US, "%,d", amount)

    private fun quantityTextX(text: String): Float =
        when {
            text.length >= 3 -> -10.5f
            text.length == 2 -> -7.0f
            else -> -2.5f
        }

    private fun facing(shop: ShopBlockEntity): Direction =
        if (shop.blockState.hasProperty(HorizontalDirectionalBlock.FACING)) {
            shop.blockState.getValue(HorizontalDirectionalBlock.FACING)
        } else {
            Direction.NORTH
        }

    private fun facingRotation(direction: Direction): Float =
        when (direction) {
            Direction.EAST -> 270.0f
            Direction.SOUTH -> 180.0f
            Direction.WEST -> 90.0f
            else -> 0.0f
        }

    private fun spin(shop: ShopBlockEntity, partialTick: Float): Float =
        (((shop.level?.gameTime ?: 0L).toFloat() + partialTick) * 3.0f) % 360.0f

    private data class CrateStockPosition(val x: Float, val y: Float, val z: Float, val rotationY: Float, val rotationX: Float)

    companion object {
        private const val STOCK_TEXT_COLOR = 0xffffff
        private const val PRICE_TEXT_COLOR = 0xffffff
        private const val PRICE_BACKGROUND_COLOR = 0x66000000
        private const val PRICE_TAG_Y = 1.32
        private const val PRICE_TAG_SCALE = 0.025f
        private const val PRICE_ICON_SIZE = 8
        private const val PRICE_TEXT_GAP = 3
        private const val RUG_LABEL_LIFT = 0.03
        private const val TEXT_FACE_OFFSET = 0.002
        private val CHOWCOIN_TEXTURE: ResourceLocation = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/chowcoin.png")
        private val CRATE_STOCK_POSITIONS = listOf(
            CrateStockPosition(0.02f, 0.2f, 0.33f, -8f, 0f),
            CrateStockPosition(-0.23f, 0.53f, 0.26f, -4f, -4f),
            CrateStockPosition(0.02f, 0.4f, 0.28f, 175f, 5f),
            CrateStockPosition(0.08f, 0.6f, 0.28f, -175f, 5f),
            CrateStockPosition(0.23f, 0.7f, 0.22f, 17f, -8f),
            CrateStockPosition(-0.25f, 0.34f, 0.29f, -170f, 0f),
            CrateStockPosition(-0.23f, 0.7f, 0.24f, 170f, 187f),
            CrateStockPosition(-0.24f, 0.18f, 0.24f, -170f, -16f),
            CrateStockPosition(0.24f, 0.16f, 0.3f, 165f, 0f),
            CrateStockPosition(0.22f, 0.45f, 0.3f, -10f, -8f),
            CrateStockPosition(0.23f, 0.72f, 0.19f, 170f, -170f),
            CrateStockPosition(-0.04f, 0.77f, 0.26f, 0f, -14f),
        )
    }
}
