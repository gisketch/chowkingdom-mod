package dev.gisketch.chowkingdom.skilltree

import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.snackbar.SnackbarIcons
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext

object ClassSkillTreeNetwork {
    fun register(modBus: IEventBus) {
        modBus.addListener(::registerPayloads)
    }

    fun requestOpen() {
        runCatching { PacketDistributor.sendToServer(ClassSkillTreeOpenRequestPayload) }
    }

    fun selectRoot(rootSkillId: String) {
        runCatching { PacketDistributor.sendToServer(ClassSkillTreeSelectRootPayload(rootSkillId)) }
    }

    fun unlock(rootSkillId: String, skillId: String) {
        runCatching { PacketDistributor.sendToServer(ClassSkillTreeUnlockPayload(rootSkillId, skillId)) }
    }

    fun syncTo(player: ServerPlayer, openScreen: Boolean) {
        PacketDistributor.sendToPlayer(player, ClassSkillTrees.syncPayload(player, openScreen))
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("2")
        registrar.playToServer(ClassSkillTreeOpenRequestPayload.TYPE, ClassSkillTreeOpenRequestPayload.STREAM_CODEC, ::handleOpenRequest)
        registrar.playToServer(ClassSkillTreeSelectRootPayload.TYPE, ClassSkillTreeSelectRootPayload.STREAM_CODEC, ::handleSelectRoot)
        registrar.playToServer(ClassSkillTreeUnlockPayload.TYPE, ClassSkillTreeUnlockPayload.STREAM_CODEC, ::handleUnlock)
        registrar.playToClient(ClassSkillTreeSyncPayload.TYPE, ClassSkillTreeSyncPayload.STREAM_CODEC, ::handleSync)
    }

    private fun handleOpenRequest(payload: ClassSkillTreeOpenRequestPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        ClassSkillTrees.reconcile(player)
        syncTo(player, openScreen = true)
    }

    private fun handleSelectRoot(payload: ClassSkillTreeSelectRootPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        ClassSkillTrees.selectRoot(player, payload.rootSkillId)
    }

    private fun handleUnlock(payload: ClassSkillTreeUnlockPayload, context: IPayloadContext) {
        val player = context.player() as? ServerPlayer ?: return
        val result = ClassSkillTrees.unlock(player, payload.rootSkillId, payload.skillId)
        if (!result.unlocked) {
            ChowKingdomMod.LOGGER.info(
                "Rejected CKDM class skill unlock for {} root={} skill={} reason={}",
                player.gameProfile.name,
                payload.rootSkillId,
                payload.skillId,
                result.reason,
            )
            SnackbarNetwork.send(
                player,
                SnackbarNotification.item(SnackbarIcons.ERROR, "SKILL LOCKED", result.reason, SnackbarType.ERROR, SnackbarSounds.ERROR),
            )
            syncTo(player, openScreen = true)
        }
    }

    private fun handleSync(payload: ClassSkillTreeSyncPayload, context: IPayloadContext) {
        if (!FMLEnvironment.dist.isClient) return
        context.enqueueWork {
            runCatching {
                val client = Class.forName("dev.gisketch.chowkingdom.skilltree.ClassSkillTreeClient")
                client.getMethod("sync", ClassSkillTreeSyncPayload::class.java).invoke(client.getField("INSTANCE").get(null), payload)
            }.onFailure { exception ->
                ChowKingdomMod.LOGGER.warn("Failed to sync CKDM class skill tree screen", exception)
            }
        }
    }
}

object ClassSkillTreeOpenRequestPayload : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ClassSkillTreeOpenRequestPayload> = TYPE

    val TYPE: CustomPacketPayload.Type<ClassSkillTreeOpenRequestPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "class_skills/open"))
    val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ClassSkillTreeOpenRequestPayload> = object : StreamCodec<RegistryFriendlyByteBuf, ClassSkillTreeOpenRequestPayload> {
        override fun decode(buffer: RegistryFriendlyByteBuf): ClassSkillTreeOpenRequestPayload = ClassSkillTreeOpenRequestPayload
        override fun encode(buffer: RegistryFriendlyByteBuf, value: ClassSkillTreeOpenRequestPayload) = Unit
    }
}

data class ClassSkillTreeSelectRootPayload(val rootSkillId: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ClassSkillTreeSelectRootPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ClassSkillTreeSelectRootPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "class_skills/select_root"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ClassSkillTreeSelectRootPayload> = object : StreamCodec<RegistryFriendlyByteBuf, ClassSkillTreeSelectRootPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): ClassSkillTreeSelectRootPayload = ClassSkillTreeSelectRootPayload(buffer.readUtf(MAX_ID_LENGTH))
            override fun encode(buffer: RegistryFriendlyByteBuf, value: ClassSkillTreeSelectRootPayload) {
                buffer.writeUtf(value.rootSkillId.take(MAX_ID_LENGTH), MAX_ID_LENGTH)
            }
        }
    }
}

data class ClassSkillTreeUnlockPayload(val rootSkillId: String, val skillId: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ClassSkillTreeUnlockPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ClassSkillTreeUnlockPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "class_skills/unlock"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ClassSkillTreeUnlockPayload> = object : StreamCodec<RegistryFriendlyByteBuf, ClassSkillTreeUnlockPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): ClassSkillTreeUnlockPayload = ClassSkillTreeUnlockPayload(buffer.readUtf(MAX_ID_LENGTH), buffer.readUtf(MAX_ID_LENGTH))
            override fun encode(buffer: RegistryFriendlyByteBuf, value: ClassSkillTreeUnlockPayload) {
                buffer.writeUtf(value.rootSkillId.take(MAX_ID_LENGTH), MAX_ID_LENGTH)
                buffer.writeUtf(value.skillId.take(MAX_ID_LENGTH), MAX_ID_LENGTH)
            }
        }
    }
}

data class ClassSkillTreeSyncPayload(
    val openScreen: Boolean,
    val overallLevel: Int,
    val budget: Int,
    val spent: Int,
    val pointsLeft: Int,
    val selectedRootSkillId: String,
    val classes: List<ClassSkillTreeClassPayload>,
    val roots: List<ClassSkillTreeRootPayload>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ClassSkillTreeSyncPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<ClassSkillTreeSyncPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "class_skills/sync"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ClassSkillTreeSyncPayload> = object : StreamCodec<RegistryFriendlyByteBuf, ClassSkillTreeSyncPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): ClassSkillTreeSyncPayload = ClassSkillTreeSyncPayload(
                openScreen = buffer.readBoolean(),
                overallLevel = buffer.readVarInt(),
                budget = buffer.readVarInt(),
                spent = buffer.readVarInt(),
                pointsLeft = buffer.readVarInt(),
                selectedRootSkillId = buffer.readUtf(MAX_ID_LENGTH),
                classes = List(buffer.readVarInt().coerceIn(0, MAX_CLASSES)) { ClassSkillTreeClassPayload.decode(buffer) },
                roots = List(buffer.readVarInt().coerceIn(0, MAX_ROOTS)) { ClassSkillTreeRootPayload.decode(buffer) },
            )

            override fun encode(buffer: RegistryFriendlyByteBuf, value: ClassSkillTreeSyncPayload) {
                buffer.writeBoolean(value.openScreen)
                buffer.writeVarInt(value.overallLevel)
                buffer.writeVarInt(value.budget)
                buffer.writeVarInt(value.spent)
                buffer.writeVarInt(value.pointsLeft)
                buffer.writeUtf(value.selectedRootSkillId.take(MAX_ID_LENGTH), MAX_ID_LENGTH)
                buffer.writeVarInt(value.classes.take(MAX_CLASSES).size)
                value.classes.take(MAX_CLASSES).forEach { it.encode(buffer) }
                buffer.writeVarInt(value.roots.take(MAX_ROOTS).size)
                value.roots.take(MAX_ROOTS).forEach { it.encode(buffer) }
            }
        }
    }
}

data class ClassSkillTreeClassPayload(
    val classId: String,
    val displayName: String,
    val icon: String,
    val rootSkillId: String,
    val selected: Boolean,
) {
    fun encode(buffer: RegistryFriendlyByteBuf) {
        buffer.writeUtf(classId.take(MAX_ID_LENGTH), MAX_ID_LENGTH)
        buffer.writeUtf(displayName.take(MAX_TEXT_LENGTH), MAX_TEXT_LENGTH)
        buffer.writeUtf(icon.take(MAX_ICON_LENGTH), MAX_ICON_LENGTH)
        buffer.writeUtf(rootSkillId.take(MAX_ID_LENGTH), MAX_ID_LENGTH)
        buffer.writeBoolean(selected)
    }

    companion object {
        fun decode(buffer: RegistryFriendlyByteBuf): ClassSkillTreeClassPayload = ClassSkillTreeClassPayload(
            classId = buffer.readUtf(MAX_ID_LENGTH),
            displayName = buffer.readUtf(MAX_TEXT_LENGTH),
            icon = buffer.readUtf(MAX_ICON_LENGTH),
            rootSkillId = buffer.readUtf(MAX_ID_LENGTH),
            selected = buffer.readBoolean(),
        )
    }
}

data class ClassSkillTreeRootPayload(
    val rootSkillId: String,
    val selected: Boolean,
    val nodes: List<ClassSkillTreeNodePayload>,
    val connections: List<ClassSkillTreeConnectionPayload>,
    val exclusiveConnections: List<ClassSkillTreeConnectionPayload>,
) {
    fun encode(buffer: RegistryFriendlyByteBuf) {
        buffer.writeUtf(rootSkillId.take(MAX_ID_LENGTH), MAX_ID_LENGTH)
        buffer.writeBoolean(selected)
        buffer.writeVarInt(nodes.take(MAX_NODES).size)
        nodes.take(MAX_NODES).forEach { it.encode(buffer) }
        buffer.writeVarInt(connections.take(MAX_CONNECTIONS).size)
        connections.take(MAX_CONNECTIONS).forEach { it.encode(buffer) }
        buffer.writeVarInt(exclusiveConnections.take(MAX_CONNECTIONS).size)
        exclusiveConnections.take(MAX_CONNECTIONS).forEach { it.encode(buffer) }
    }

    companion object {
        fun decode(buffer: RegistryFriendlyByteBuf): ClassSkillTreeRootPayload = ClassSkillTreeRootPayload(
            rootSkillId = buffer.readUtf(MAX_ID_LENGTH),
            selected = buffer.readBoolean(),
            nodes = List(buffer.readVarInt().coerceIn(0, MAX_NODES)) { ClassSkillTreeNodePayload.decode(buffer) },
            connections = List(buffer.readVarInt().coerceIn(0, MAX_CONNECTIONS)) { ClassSkillTreeConnectionPayload.decode(buffer) },
            exclusiveConnections = List(buffer.readVarInt().coerceIn(0, MAX_CONNECTIONS)) { ClassSkillTreeConnectionPayload.decode(buffer) },
        )
    }
}

data class ClassSkillTreeNodePayload(
    val skillId: String,
    val definitionId: String,
    val titleKey: String,
    val descriptionKey: String,
    val icon: String,
    val x: Int,
    val y: Int,
    val root: Boolean,
    val cost: Int,
    val unlocked: Boolean,
    val available: Boolean,
    val blocked: Boolean,
    val blockedReason: String,
) {
    fun encode(buffer: RegistryFriendlyByteBuf) {
        buffer.writeUtf(skillId.take(MAX_ID_LENGTH), MAX_ID_LENGTH)
        buffer.writeUtf(definitionId.take(MAX_ID_LENGTH), MAX_ID_LENGTH)
        buffer.writeUtf(titleKey.take(MAX_ICON_LENGTH), MAX_ICON_LENGTH)
        buffer.writeUtf(descriptionKey.take(MAX_ICON_LENGTH), MAX_ICON_LENGTH)
        buffer.writeUtf(icon.take(MAX_ICON_LENGTH), MAX_ICON_LENGTH)
        buffer.writeVarInt(x)
        buffer.writeVarInt(y)
        buffer.writeBoolean(root)
        buffer.writeVarInt(cost)
        buffer.writeBoolean(unlocked)
        buffer.writeBoolean(available)
        buffer.writeBoolean(blocked)
        buffer.writeUtf(blockedReason.take(MAX_TEXT_LENGTH), MAX_TEXT_LENGTH)
    }

    companion object {
        fun decode(buffer: RegistryFriendlyByteBuf): ClassSkillTreeNodePayload = ClassSkillTreeNodePayload(
            skillId = buffer.readUtf(MAX_ID_LENGTH),
            definitionId = buffer.readUtf(MAX_ID_LENGTH),
            titleKey = buffer.readUtf(MAX_ICON_LENGTH),
            descriptionKey = buffer.readUtf(MAX_ICON_LENGTH),
            icon = buffer.readUtf(MAX_ICON_LENGTH),
            x = buffer.readVarInt(),
            y = buffer.readVarInt(),
            root = buffer.readBoolean(),
            cost = buffer.readVarInt(),
            unlocked = buffer.readBoolean(),
            available = buffer.readBoolean(),
            blocked = buffer.readBoolean(),
            blockedReason = buffer.readUtf(MAX_TEXT_LENGTH),
        )
    }
}

data class ClassSkillTreeConnectionPayload(val fromSkillId: String, val toSkillId: String) {
    fun encode(buffer: RegistryFriendlyByteBuf) {
        buffer.writeUtf(fromSkillId.take(MAX_ID_LENGTH), MAX_ID_LENGTH)
        buffer.writeUtf(toSkillId.take(MAX_ID_LENGTH), MAX_ID_LENGTH)
    }

    companion object {
        fun decode(buffer: RegistryFriendlyByteBuf): ClassSkillTreeConnectionPayload =
            ClassSkillTreeConnectionPayload(buffer.readUtf(MAX_ID_LENGTH), buffer.readUtf(MAX_ID_LENGTH))
    }
}

private const val MAX_CLASSES = 32
private const val MAX_ROOTS = 32
private const val MAX_NODES = 96
private const val MAX_CONNECTIONS = 192
private const val MAX_ID_LENGTH = 96
private const val MAX_TEXT_LENGTH = 128
private const val MAX_ICON_LENGTH = 256
