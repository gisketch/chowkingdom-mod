package dev.gisketch.chowkingdom.skilltree

import com.mojang.blaze3d.systems.RenderSystem
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.roles.roleIconStack
import dev.gisketch.chowkingdom.roles.roleIconTexture
import net.minecraft.ChatFormatting
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.resources.language.I18n
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.common.NeoForge
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.roundToInt

object ClassSkillTreeClient {
    private var latest: ClassSkillTreeSyncPayload? = null

    fun register() {
        NeoForge.EVENT_BUS.addListener(::onScreenOpening)
    }

    fun open() {
        ClassSkillTreeNetwork.requestOpen()
    }

    @JvmStatic
    fun sync(payload: ClassSkillTreeSyncPayload) {
        latest = payload
        val minecraft = Minecraft.getInstance()
        val current = minecraft.screen as? ClassSkillTreeScreen
        if (current != null) current.update(payload)
        if (payload.openScreen && current == null) minecraft.setScreen(ClassSkillTreeScreen(payload))
    }

    private fun onScreenOpening(event: ScreenEvent.Opening) {
        val screen = event.newScreen ?: return
        val name = screen.javaClass.name.lowercase(Locale.ROOT)
        if (!name.contains("puffish") || !name.contains("skill")) return
        event.newScreen = ClassSkillTreeScreen(latest ?: ClassSkillTreeSyncPayload(false, 0, 0, 0, 0, "", emptyList(), emptyList()))
        ClassSkillTreeNetwork.requestOpen()
    }
}

private class ClassSkillTreeScreen(private var payload: ClassSkillTreeSyncPayload) : Screen(Component.literal("Class Skills")) {
    private val fontRef get() = Minecraft.getInstance().font
    private var openedAtMs = Util.getMillis()
    private var panX = 0.0
    private var panY = 0.0
    private var dragging = false
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0
    private var hoveredNode: ClassSkillTreeNodePayload? = null
    private var lastSelectedRoot = payload.selectedRootSkillId
    private var noticeText = ""
    private var noticeUntilMs = 0L

    fun update(next: ClassSkillTreeSyncPayload) {
        val previous = payload.selectedRootSkillId
        payload = next
        if (previous != next.selectedRootSkillId) {
            panX = 0.0
            panY = 0.0
        }
    }

    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) = Unit

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        hoveredNode = null
        renderParallaxBackground(guiGraphics, mouseX, mouseY)
        val layout = layout()
        renderNineSlice(guiGraphics, GREY_CONTAINER_TEXTURE, layout.left, CONTAINER_TEXTURE_WIDTH, CONTAINER_TEXTURE_HEIGHT, CONTAINER_SOURCE_CORNER, CONTAINER_DEST_CORNER, 0.95f)
        renderNineSlice(guiGraphics, GREY_CONTAINER_TEXTURE, layout.graph, CONTAINER_TEXTURE_WIDTH, CONTAINER_TEXTURE_HEIGHT, CONTAINER_SOURCE_CORNER, CONTAINER_DEST_CORNER, 0.95f)
        renderHeader(guiGraphics, layout)
        renderClassTabs(guiGraphics, layout.left, mouseX, mouseY)
        renderPaperDoll(guiGraphics, layout.center, mouseX, mouseY)
        renderGraph(guiGraphics, layout.graph, mouseX, mouseY)
        hoveredNode?.let { node -> renderNodeTooltip(guiGraphics, node, mouseX, mouseY) }
        super.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(mouseX, mouseY, button)
        val layout = layout()
        classSlots(layout.left).firstOrNull { it.rect.contains(mouseX.toInt(), mouseY.toInt()) }?.let { slot ->
            if (!slot.clazz.selected) {
                playClick()
                ClassSkillTreeNetwork.selectRoot(slot.clazz.rootSkillId)
            }
            return true
        }
        val graphContent = graphContent(layout.graph)
        if (graphContent.contains(mouseX.toInt(), mouseY.toInt())) {
            nodeSlots(graphContent).firstOrNull { it.rect.contains(mouseX.toInt(), mouseY.toInt()) }?.let { slot ->
                if (slot.node.available) {
                    playClick()
                    ClassSkillTreeNetwork.unlock(selectedRoot()?.rootSkillId.orEmpty(), slot.node.skillId)
                    return true
                }
                showNotice(clickNotice(slot.node))
                return true
            }
        }
        if (layout.graph.contains(mouseX.toInt(), mouseY.toInt())) {
            dragging = true
            lastMouseX = mouseX
            lastMouseY = mouseY
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (dragging) {
            panX += mouseX - lastMouseX
            panY += mouseY - lastMouseY
            lastMouseX = mouseX
            lastMouseY = mouseY
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        dragging = false
        return super.mouseReleased(mouseX, mouseY, button)
    }

    private fun renderHeader(guiGraphics: GuiGraphics, layout: Layout) {
        drawCkdm(guiGraphics, "SKILLS", layout.graph.x + 18, layout.graph.y + 16, GOLD, CKDM_LARGE)
        drawCkdm(guiGraphics, "OVERALL LV. ${payload.overallLevel}", layout.graph.right - 170, layout.graph.y + 18, WHITE_MUTED, CKDM_SMALL)
        drawCkdm(guiGraphics, "POINTS ${payload.pointsLeft}/${payload.budget}", layout.graph.right - 170, layout.graph.y + 32, if (payload.pointsLeft > 0) GOLD else WHITE_MUTED, CKDM_SMALL)
        if (noticeText.isNotBlank() && Util.getMillis() < noticeUntilMs) {
            drawCkdm(guiGraphics, fitText(noticeText, layout.graph.width - 220, CKDM_SMALL), layout.graph.x + 18, layout.graph.y + 40, RED, CKDM_SMALL)
        }
        drawCenteredCkdm(guiGraphics, "CLASSES", layout.left.x + 16, layout.left.y + 16, layout.left.width - 32, WHITE, CKDM_BOLD)
    }

    private fun renderClassTabs(guiGraphics: GuiGraphics, rect: Rect, mouseX: Int, mouseY: Int) {
        if (payload.classes.isEmpty()) {
            drawPlain(guiGraphics, "Unlock a class license to open a skill path.", rect.x + 16, rect.y + 48, WHITE_MUTED, rect.width - 32)
            return
        }
        val list = Rect(rect.x + 10, rect.y + 44, rect.width - 20, rect.height - 58)
        guiGraphics.enableScissor(list.x, list.y, list.right, list.bottom)
        try {
            classSlots(rect).forEach { slot ->
                val hovered = slot.rect.contains(mouseX, mouseY)
                val selected = slot.clazz.selected
                val texture = when {
                    selected -> GOLD_CONTAINER_TEXTURE
                    hovered -> YELLOW_CONTAINER_TEXTURE
                    else -> GREY_CONTAINER_TEXTURE
                }
                renderNineSlice(guiGraphics, texture, slot.rect, CONTAINER_TEXTURE_WIDTH, CONTAINER_TEXTURE_HEIGHT, CONTAINER_SOURCE_CORNER, TILE_DEST_CORNER, if (selected || hovered) 1.0f else 0.86f)
                renderRoleIcon(guiGraphics, slot.clazz.icon, Rect(slot.rect.x + 7, slot.rect.y + 6, 22, 22))
                drawCkdm(guiGraphics, fitText(slot.clazz.displayName, slot.rect.width - 43, CKDM_SMALL), slot.rect.x + 36, slot.rect.y + 9, WHITE, CKDM_SMALL)
                drawCkdm(guiGraphics, fitText("PATH", slot.rect.width - 43, CKDM_SMALL), slot.rect.x + 36, slot.rect.y + 22, if (selected) GOLD else WHITE_MUTED, CKDM_SMALL)
            }
        } finally {
            guiGraphics.disableScissor()
        }
    }

    private fun renderPaperDoll(guiGraphics: GuiGraphics, rect: Rect, mouseX: Int, mouseY: Int) {
        val clazz = selectedClass()
        clazz?.let {
            drawCenteredCkdm(guiGraphics, fitText(it.displayName, rect.width - 20, CKDM_BOLD), rect.x + 10, rect.y + 4, rect.width - 20, WHITE, CKDM_BOLD)
            renderNineSlice(guiGraphics, YELLOW_CONTAINER_TEXTURE, Rect(rect.x + rect.width / 2 - 24, rect.y + 24, 48, 48), CONTAINER_TEXTURE_WIDTH, CONTAINER_TEXTURE_HEIGHT, CONTAINER_SOURCE_CORNER, TILE_DEST_CORNER, 0.9f)
            renderRoleIcon(guiGraphics, it.icon, Rect(rect.x + rect.width / 2 - 14, rect.y + 34, 28, 28))
        }
        val player = Minecraft.getInstance().player ?: return
        val mainHand = player.mainHandItem.copy()
        val offHand = player.offhandItem.copy()
        val previewStack = clazz?.icon?.let(::roleIconStack) ?: ItemStack.EMPTY
        val scale = (rect.height / 2.8f).toInt().coerceIn(42, 92)
        try {
            if (!previewStack.isEmpty) {
                player.setItemInHand(InteractionHand.MAIN_HAND, previewStack.copyWithCount(1))
                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY)
            }
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                guiGraphics,
                rect.x + 2,
                rect.y + 74,
                rect.right - 2,
                rect.bottom - 4,
                scale,
                0.04f,
                mouseX.toFloat(),
                mouseY.toFloat(),
                player,
            )
        } finally {
            if (!previewStack.isEmpty) {
                player.setItemInHand(InteractionHand.MAIN_HAND, mainHand)
                player.setItemInHand(InteractionHand.OFF_HAND, offHand)
            }
        }
    }

    private fun renderGraph(guiGraphics: GuiGraphics, rect: Rect, mouseX: Int, mouseY: Int) {
        val root = selectedRoot() ?: run {
            drawCkdm(guiGraphics, "NO CLASS SKILL TREE", rect.x + 18, rect.y + 54, WHITE_MUTED, CKDM_BOLD)
            return
        }
        val content = graphContent(rect)
        val slots = nodeSlots(content)
        val byId = slots.associateBy { it.node.skillId }
        guiGraphics.enableScissor(content.x, content.y, content.right, content.bottom)
        try {
            root.connections.forEach { connection ->
                val a = byId[connection.fromSkillId]?.rect ?: return@forEach
                val b = byId[connection.toSkillId]?.rect ?: return@forEach
                val unlocked = byId[connection.fromSkillId]?.node?.unlocked == true && byId[connection.toSkillId]?.node?.unlocked == true
                renderConnection(guiGraphics, a, b, if (unlocked) GOLD else LINE_COLOR)
            }
            root.exclusiveConnections.forEach { connection ->
                val a = byId[connection.fromSkillId]?.rect ?: return@forEach
                val b = byId[connection.toSkillId]?.rect ?: return@forEach
                renderConnection(guiGraphics, a, b, EXCLUSIVE_LINE_COLOR)
            }
            slots.forEach { slot -> renderNode(guiGraphics, slot, mouseX, mouseY) }
        } finally {
            guiGraphics.disableScissor()
        }
    }

    private fun renderConnection(guiGraphics: GuiGraphics, a: Rect, b: Rect, color: Int) {
        val ax = a.x + a.width / 2
        val ay = a.y + a.height / 2
        val bx = b.x + b.width / 2
        val by = b.y + b.height / 2
        val midX = (ax + bx) / 2
        guiGraphics.hLine(minOf(ax, midX), maxOf(ax, midX), ay, color)
        guiGraphics.vLine(midX, minOf(ay, by), maxOf(ay, by), color)
        guiGraphics.hLine(minOf(midX, bx), maxOf(midX, bx), by, color)
    }

    private fun renderNode(guiGraphics: GuiGraphics, slot: NodeSlot, mouseX: Int, mouseY: Int) {
        val node = slot.node
        val hovered = slot.rect.contains(mouseX, mouseY)
        if (hovered) hoveredNode = node
        val texture = when {
            hovered -> YELLOW_CONTAINER_TEXTURE
            node.unlocked -> GOLD_CONTAINER_TEXTURE
            else -> GREY_CONTAINER_TEXTURE
        }
        renderNineSlice(guiGraphics, texture, slot.rect, CONTAINER_TEXTURE_WIDTH, CONTAINER_TEXTURE_HEIGHT, CONTAINER_SOURCE_CORNER, NODE_DEST_CORNER, 1.0f)
        renderRoleIcon(guiGraphics, node.icon, Rect(slot.rect.x + 5, slot.rect.y + 5, slot.rect.width - 10, slot.rect.height - 10))
        if (node.blocked) {
            guiGraphics.fill(slot.rect.x, slot.rect.y, slot.rect.right, slot.rect.bottom, 0x88000000.toInt())
            renderLock(guiGraphics, slot.rect)
        }
        if (node.root) drawCenteredCkdm(guiGraphics, "ROOT", slot.rect.x - 4, slot.rect.bottom + 2, slot.rect.width + 8, GOLD, CKDM_SMALL)
        if (node.available) drawCenteredCkdm(guiGraphics, "+", slot.rect.right - 8, slot.rect.y - 2, 10, GOLD, CKDM_BOLD)
    }

    private fun renderLock(guiGraphics: GuiGraphics, rect: Rect) {
        val size = 20
        val x = rect.x + (rect.width - size) / 2
        val y = rect.y + (rect.height - size) / 2
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        guiGraphics.blit(LOCKED_TEXTURE, x, y, size, size, 0.0f, 0.0f, 16, 16, 16, 16)
    }

    private fun renderNodeTooltip(guiGraphics: GuiGraphics, node: ClassSkillTreeNodePayload, mouseX: Int, mouseY: Int) {
        val lines = mutableListOf<Component>()
        lines += nodeTitle(node)
        lines += nodeDescription(node)
        lines += nodeCost(node)
        lines += nodeStatus(node)
        if (node.blocked && node.blockedReason.isNotBlank()) lines += Component.literal(node.blockedReason).withStyle(ChatFormatting.RED)
        guiGraphics.renderTooltip(fontRef, lines.flatMap { line -> fontRef.split(line, 220) }, mouseX, mouseY)
    }

    private fun nodeTitle(node: ClassSkillTreeNodePayload): Component =
        Component.literal(if (I18n.exists(node.titleKey)) I18n.get(node.titleKey) else titleCase(node.definitionId))
            .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)

    private fun nodeDescription(node: ClassSkillTreeNodePayload): Component {
        val titleBase = node.titleKey.removeSuffix(".title")
        val candidates = listOf(
            node.descriptionKey,
            "skill.${ChowKingdomMod.MOD_ID}.${node.definitionId}.description",
            "spell.${ChowKingdomMod.MOD_ID}.${node.definitionId}.description",
            "$titleBase.description",
            titleBase.replaceFirst("skill.", "spell.") + ".description",
        ).distinct()
        val translated = candidates.firstNotNullOfOrNull { key ->
            if (!I18n.exists(key)) null else I18n.get(key).takeUnless(::hasUnresolvedPlaceholder)
        }
        if (translated != null) return Component.literal(translated).withStyle(ChatFormatting.GRAY)
        val title = if (I18n.exists(node.titleKey)) I18n.get(node.titleKey) else titleCase(node.definitionId)
        return Component.literal(if (node.root) "Unlocks this class skill path." else "Unlocks $title.")
            .withStyle(ChatFormatting.GRAY)
    }

    private fun nodeCost(node: ClassSkillTreeNodePayload): Component =
        Component.literal(if (node.root) "Free class root" else "Cost: ${node.cost} skill point${if (node.cost == 1) "" else "s"}")
            .withStyle(if (node.root) ChatFormatting.DARK_GRAY else ChatFormatting.YELLOW)

    private fun nodeStatus(node: ClassSkillTreeNodePayload): Component {
        val (text, color) = when {
            node.root -> "Root unlocked" to ChatFormatting.DARK_GRAY
            node.unlocked -> "Unlocked" to ChatFormatting.GREEN
            node.available -> "Click to unlock" to ChatFormatting.GREEN
            else -> "Locked" to ChatFormatting.RED
        }
        return Component.literal(text).withStyle(color, ChatFormatting.BOLD)
    }

    private fun clickNotice(node: ClassSkillTreeNodePayload): String = when {
        node.root -> "Root is already active"
        node.unlocked -> "Skill already unlocked"
        node.blockedReason.isNotBlank() -> node.blockedReason
        else -> "Skill is locked"
    }

    private fun showNotice(text: String) {
        noticeText = text
        noticeUntilMs = Util.getMillis() + NOTICE_MS
    }

    private fun hasUnresolvedPlaceholder(text: String): Boolean =
        PLACEHOLDER_PATTERN.containsMatchIn(text)

    private fun selectedRoot(): ClassSkillTreeRootPayload? =
        payload.roots.firstOrNull { it.rootSkillId == payload.selectedRootSkillId } ?: payload.roots.firstOrNull()

    private fun selectedClass(): ClassSkillTreeClassPayload? =
        payload.classes.firstOrNull(ClassSkillTreeClassPayload::selected) ?: payload.classes.firstOrNull()

    private fun classSlots(rect: Rect): List<ClassSlot> =
        payload.classes.mapIndexed { index, clazz ->
            ClassSlot(Rect(rect.x + 14, rect.y + 44 + index * 42, rect.width - 28, 38), clazz)
        }

    private fun nodeSlots(rect: Rect): List<NodeSlot> {
        val root = selectedRoot() ?: return emptyList()
        if (lastSelectedRoot != root.rootSkillId) {
            panX = 0.0
            panY = 0.0
            lastSelectedRoot = root.rootSkillId
        }
        val layout = verticalNodeLayout(root, rect)
        return root.nodes.map { node ->
            val point = layout[node.skillId] ?: rect.center()
            val x = point.first - NODE_SIZE / 2
            val y = point.second - NODE_SIZE / 2
            NodeSlot(Rect(x, y, NODE_SIZE, NODE_SIZE), node)
        }
    }

    private fun verticalNodeLayout(root: ClassSkillTreeRootPayload, rect: Rect): Map<String, Pair<Int, Int>> {
        val rootId = root.nodes.firstOrNull { it.root }?.skillId ?: root.nodes.firstOrNull()?.skillId ?: return emptyMap()
        val depths = directedDepths(root, rootId).toMutableMap()
        if (depths.size < root.nodes.size) depths.putAll(undirectedDepths(root, rootId).filterKeys { key -> key !in depths })
        val visitOrder = visitOrder(root, rootId)
        return root.nodes.groupBy { node -> depths[node.skillId] ?: 0 }
            .toSortedMap()
            .flatMap { (depth, nodes) ->
                val ordered = nodes.sortedWith(compareBy<ClassSkillTreeNodePayload> { visitOrder[it.skillId] ?: Int.MAX_VALUE }.thenBy { it.definitionId })
                val totalWidth = (ordered.size - 1).coerceAtLeast(0) * NODE_HORIZONTAL_GAP
                val startX = rect.x + rect.width / 2 - totalWidth / 2
                val y = rect.y + 24 + depth * NODE_VERTICAL_GAP + panY.roundToInt()
                ordered.mapIndexed { index, node ->
                    val x = startX + index * NODE_HORIZONTAL_GAP + panX.roundToInt()
                    node.skillId to (x to y)
                }
            }
            .toMap()
    }

    private fun directedDepths(root: ClassSkillTreeRootPayload, rootId: String): Map<String, Int> {
        val children = root.connections.groupBy { it.fromSkillId }.mapValues { (_, values) -> values.map { it.toSkillId } }
        return breadthFirstDepths(rootId, children)
    }

    private fun undirectedDepths(root: ClassSkillTreeRootPayload, rootId: String): Map<String, Int> {
        val neighbors = linkedMapOf<String, MutableList<String>>()
        root.connections.forEach { connection ->
            neighbors.getOrPut(connection.fromSkillId) { mutableListOf() }.add(connection.toSkillId)
            neighbors.getOrPut(connection.toSkillId) { mutableListOf() }.add(connection.fromSkillId)
        }
        return breadthFirstDepths(rootId, neighbors)
    }

    private fun breadthFirstDepths(rootId: String, children: Map<String, List<String>>): Map<String, Int> {
        val depths = linkedMapOf(rootId to 0)
        val queue = ArrayDeque<String>()
        queue += rootId
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val depth = depths[current] ?: 0
            children[current].orEmpty().forEach { child ->
                if (child !in depths) {
                    depths[child] = depth + 1
                    queue += child
                }
            }
        }
        return depths
    }

    private fun visitOrder(root: ClassSkillTreeRootPayload, rootId: String): Map<String, Int> {
        val directed = root.connections.groupBy { it.fromSkillId }.mapValues { (_, values) -> values.map { it.toSkillId } }
        val order = linkedMapOf<String, Int>()
        val queue = ArrayDeque<String>()
        queue += rootId
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in order) continue
            order[current] = order.size
            directed[current].orEmpty().forEach { queue += it }
        }
        return order
    }

    private fun renderParallaxBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val xOffset = ((mouseX.toFloat() / width.coerceAtLeast(1)) - 0.5f) * -BACKGROUND_PARALLAX
        val yOffset = ((mouseY.toFloat() / height.coerceAtLeast(1)) - 0.5f) * -BACKGROUND_PARALLAX
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, BACKGROUND_ALPHA)
        guiGraphics.blit(BACKGROUND_TEXTURE, -BACKGROUND_PADDING + xOffset.toInt(), -BACKGROUND_PADDING + yOffset.toInt(), width + BACKGROUND_PADDING * 2, height + BACKGROUND_PADDING * 2, 0.0f, 0.0f, BG_TEXTURE_WIDTH, BG_TEXTURE_HEIGHT, BG_TEXTURE_WIDTH, BG_TEXTURE_HEIGHT)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        guiGraphics.fill(0, 0, width, height, 0x99000000.toInt())
    }

    private fun renderRoleIcon(guiGraphics: GuiGraphics, rawIcon: String, rect: Rect) {
        val stack = roleIconStack(rawIcon)
        if (!stack.isEmpty) {
            renderItem(guiGraphics, stack, rect)
            return
        }
        val texture = roleIconTexture(rawIcon) ?: runCatching { ResourceLocation.parse(rawIcon) }.getOrNull() ?: return
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        guiGraphics.blit(texture, rect.x, rect.y, rect.width, rect.height, 0.0f, 0.0f, 16, 16, 16, 16)
    }

    private fun renderItem(guiGraphics: GuiGraphics, stack: ItemStack, rect: Rect) {
        val pose = guiGraphics.pose()
        pose.pushPose()
        val scale = rect.width.coerceAtMost(rect.height) / 16.0f
        pose.translate(rect.x.toFloat(), rect.y.toFloat(), 90.0f)
        pose.scale(scale, scale, 1.0f)
        guiGraphics.renderItem(stack, 0, 0)
        pose.popPose()
    }

    private fun renderNineSlice(guiGraphics: GuiGraphics, texture: ResourceLocation, rect: Rect, textureWidth: Int, textureHeight: Int, sourceCorner: Int, destinationCorner: Int, alpha: Float) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha)
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

    private fun drawCkdm(guiGraphics: GuiGraphics, text: String, x: Int, y: Int, color: Int, fontId: ResourceLocation) {
        guiGraphics.drawString(fontRef, ckdmText(text, fontId), x, y, color, false)
    }

    private fun drawCenteredCkdm(guiGraphics: GuiGraphics, text: String, x: Int, y: Int, width: Int, color: Int, fontId: ResourceLocation) {
        val component = ckdmText(text, fontId)
        guiGraphics.drawString(fontRef, component, x + (width - fontRef.width(component)) / 2, y, color, false)
    }

    private fun drawPlain(guiGraphics: GuiGraphics, text: String, x: Int, y: Int, color: Int, maxWidth: Int) {
        fontRef.split(Component.literal(text), maxWidth).take(4).forEachIndexed { index, line ->
            guiGraphics.drawString(fontRef, line, x, y + index * 12, color, false)
        }
    }

    private fun ckdmText(text: String, fontId: ResourceLocation): Component =
        Component.literal(text.uppercase(Locale.ROOT)).withStyle { style -> style.withFont(fontId) }

    private fun fitText(text: String, maxWidth: Int, fontId: ResourceLocation): String {
        if (fontRef.width(ckdmText(text, fontId)) <= maxWidth) return text
        var value = text
        while (value.isNotEmpty() && fontRef.width(ckdmText("$value...", fontId)) > maxWidth) value = value.dropLast(1)
        return "$value..."
    }

    private fun titleCase(id: String): String =
        id.replace('_', ' ').split(' ').joinToString(" ") { part -> part.replaceFirstChar { char -> char.titlecase(Locale.ROOT) } }

    private fun layout(): Layout {
        val margin = (width / 34).coerceIn(18, 28)
        val gap = 14
        val top = 34
        val bottom = 28
        val panelHeight = (height - top - bottom).coerceAtLeast(170)
        val available = (width - margin * 2 - gap * 2).coerceAtLeast(320)
        val leftWidth = (available * 0.27f).toInt().coerceIn(150, 210)
        val centerWidth = (available * 0.25f).toInt().coerceIn(120, 210)
        val left = Rect(margin, top, leftWidth, panelHeight)
        val center = Rect(left.right + gap, top, centerWidth, panelHeight)
        val graph = Rect(center.right + gap, top, width - margin - (center.right + gap), panelHeight)
        return Layout(left, center, graph)
    }

    private fun graphContent(rect: Rect): Rect = Rect(rect.x + 18, rect.y + 58, rect.width - 36, rect.height - 78)

    private fun playClick() {
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.55f))
    }

    private data class Layout(val left: Rect, val center: Rect, val graph: Rect)
    private data class ClassSlot(val rect: Rect, val clazz: ClassSkillTreeClassPayload)
    private data class NodeSlot(val rect: Rect, val node: ClassSkillTreeNodePayload)
    private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right: Int get() = x + width
        val bottom: Int get() = y + height
        fun contains(pointX: Int, pointY: Int): Boolean = pointX >= x && pointX < right && pointY >= y && pointY < bottom
        fun center(): Pair<Int, Int> = x + width / 2 to y + height / 2
    }

    companion object {
        private const val NODE_SIZE = 34
        private const val NODE_HORIZONTAL_GAP = 104
        private const val NODE_VERTICAL_GAP = 64
        private const val NODE_DEST_CORNER = 8
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val WHITE_MUTED = 0xFFD8D0B8.toInt()
        private const val GOLD = 0xFFFFD66B.toInt()
        private const val RED = 0xFFFF7676.toInt()
        private const val LINE_COLOR = 0x887A725B.toInt()
        private const val EXCLUSIVE_LINE_COLOR = 0x88C55252.toInt()
        private const val CONTAINER_TEXTURE_WIDTH = 1646
        private const val CONTAINER_TEXTURE_HEIGHT = 256
        private const val CONTAINER_SOURCE_CORNER = 75
        private const val CONTAINER_DEST_CORNER = 14
        private const val TILE_DEST_CORNER = 8
        private const val BG_TEXTURE_WIDTH = 1919
        private const val BG_TEXTURE_HEIGHT = 1080
        private const val BACKGROUND_PADDING = 36
        private const val BACKGROUND_PARALLAX = 18.0f
        private const val BACKGROUND_ALPHA = 0.5f
        private const val NOTICE_MS = 2_400L
        private val PLACEHOLDER_PATTERN = Regex("\\{[A-Za-z0-9_]+}")
        private val YELLOW_CONTAINER_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_container_yellow.png")
        private val GREY_CONTAINER_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_container_grey.png")
        private val GOLD_CONTAINER_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/9slice_container_gold.png")
        private val LOCKED_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/locked.png")
        private val BACKGROUND_TEXTURE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/gui/bg_onboarding.png")
        private val CKDM_BOLD = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold")
        private val CKDM_SMALL = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold_small")
        private val CKDM_LARGE = ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "ckdm_bold_large")
    }
}
