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
            if ('/' !in skin) {
                return ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/entity/random_trainers/$skin.png")
            }
            folderSkin(entity.uuid, skin)?.let { return it }
            return ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/entity/random_trainers/$skin/default.png")
        }
        return ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "textures/entity/npc/prof_chowfan.png")
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
        val index = Math.floorMod(uuid.hashCode(), resources.size)
        return resources[index]
    }

    companion object {
        private val skinCache: MutableMap<String, List<ResourceLocation>> = linkedMapOf()
    }
}
