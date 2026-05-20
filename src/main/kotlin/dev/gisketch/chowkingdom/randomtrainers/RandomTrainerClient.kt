package dev.gisketch.chowkingdom.randomtrainers

import dev.gisketch.chowkingdom.ChowKingdomMod
import net.minecraft.client.Minecraft
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import java.util.Locale
import java.util.UUID

object RandomTrainerClient {
    fun register(modBus: IEventBus) {
        modBus.addListener(::registerRenderers)
    }

    private fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerEntityRenderer(RandomTrainerFeature.RANDOM_TRAINER_ENTITY.get()) { context -> RandomTrainerRenderer(context) }
    }
}

private class RandomTrainerRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<RandomTrainerEntity, PlayerModel<RandomTrainerEntity>>(context, PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f) {
    private val normalModel = PlayerModel<RandomTrainerEntity>(context.bakeLayer(ModelLayers.PLAYER), false)
    private val slimModel = PlayerModel<RandomTrainerEntity>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true)

    init {
        addLayer(
            HumanoidArmorLayer(
                this,
                HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.modelManager,
            ),
        )
        addLayer(ItemInHandLayer(this, context.itemInHandRenderer))
    }

    override fun render(entity: RandomTrainerEntity, entityYaw: Float, partialTicks: Float, poseStack: com.mojang.blaze3d.vertex.PoseStack, buffer: net.minecraft.client.renderer.MultiBufferSource, packedLight: Int) {
        model = if (entity.trainerGender.lowercase(Locale.ROOT) == "female") slimModel else normalModel
        model.rightArmPose = if (entity.mainHandItem.isEmpty) HumanoidModel.ArmPose.EMPTY else HumanoidModel.ArmPose.ITEM
        model.leftArmPose = if (entity.offhandItem.isEmpty) HumanoidModel.ArmPose.EMPTY else HumanoidModel.ArmPose.ITEM
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight)
    }

    override fun getTextureLocation(entity: RandomTrainerEntity): ResourceLocation {
        val skin = entity.skinSet.trim().lowercase(Locale.ROOT).replace('\\', '/').trim('/').takeIf { it.isNotBlank() }
        if (skin != null) {
            skinFolderCandidates(entity, skin).forEach { folder ->
                folderSkin(entity.uuid, folder)?.let { return it }
            }
            if ('/' !in skin) return ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/entity/random_trainers/$skin.png")
            return ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/entity/random_trainers/$skin/default.png")
        }
        return ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/entity/npc/prof_chowfan.png")
    }

    private fun skinFolderCandidates(entity: RandomTrainerEntity, skin: String): List<String> {
        val gender = cleanRandomTrainerId(entity.trainerGender).takeIf { it == "male" || it == "female" }
        val title = cleanRandomTrainerId(entity.trainerTitle)
        val folders = linkedSetOf<String>()
        fun addGendered(base: String) {
            if (gender != null) folders += "$base/$gender"
        }
        if ('/' in skin) {
            folders += skin
            val base = skin.substringBefore('/').trim('/')
            if (base.isNotBlank()) {
                skinBaseAliases(base).forEach { alias ->
                    addGendered(alias)
                }
            }
        } else {
            skinBaseAliases(skin).forEach { alias ->
                addGendered(alias)
            }
        }
        if (title.isNotBlank()) {
            skinBaseAliases(title).forEach { alias ->
                addGendered(alias)
            }
        }
        return folders.filter(String::isNotBlank)
    }

    private fun skinBaseAliases(base: String): List<String> {
        val clean = cleanRandomTrainerId(base)
        val aliases = linkedSetOf(clean)
        when (clean) {
            "swimmerf", "swimmerm" -> aliases += "swimmer"
        }
        listOf("_female", "_male", "female", "male").firstOrNull { clean.endsWith(it) }?.let { suffix ->
            aliases += clean.removeSuffix(suffix).trim('_')
        }
        if (clean.endsWith("f") || clean.endsWith("m")) aliases += clean.dropLast(1).trim('_')
        return aliases.filter(String::isNotBlank)
    }

    private fun folderSkin(uuid: UUID, folder: String): ResourceLocation? {
        val base = "textures/entity/random_trainers/$folder"
        val resources = skinCache.getOrPut(folder) {
            Minecraft.getInstance().resourceManager
                .listResources(base) { location -> location.path.endsWith(".png") }
                .keys
                .sortedBy(ResourceLocation::toString)
        }
        if (resources.isEmpty()) return null
        return chosenSkinCache.getOrPut("$uuid|$folder") {
            val index = Math.floorMod(uuid.hashCode(), resources.size)
            resources[index]
        }
    }

    companion object {
        private val skinCache: MutableMap<String, List<ResourceLocation>> = linkedMapOf()
        private val chosenSkinCache: MutableMap<String, ResourceLocation> = linkedMapOf()
    }
}
