package dev.gisketch.chowkingdom.compat

import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.npc.NpcBodyScaleDefinition
import dev.gisketch.chowkingdom.roles.BodyScaleChoice
import net.minecraft.world.entity.Entity
import net.neoforged.fml.ModList

object PehkuiScaleBridge {
    private val api by lazy { runCatching { PehkuiApi() }.getOrNull() }

    fun apply(entity: Entity, scale: BodyScaleChoice) {
        apply(entity, scale.height.toFloat(), scale.weight.toFloat())
    }

    fun apply(entity: Entity, scale: NpcBodyScaleDefinition) {
        apply(entity, scale.height.toFloat(), scale.weight.toFloat())
    }

    fun apply(entity: Entity, height: Double, weight: Double) {
        apply(entity, height.toFloat(), weight.toFloat())
    }

    fun heightScale(entity: Entity): Float = scale(entity, "HEIGHT")

    fun widthScale(entity: Entity): Float = scale(entity, "WIDTH")

    private fun apply(entity: Entity, height: Float, weight: Float) {
        if (!ModList.get().isLoaded("pehkui")) return
        val api = api ?: return
        runCatching {
            api.setScale(entity, "HEIGHT", height)
            api.setScale(entity, "WIDTH", weight)
        }.onFailure { exception ->
            ChowKingdomMod.LOGGER.debug("Failed to apply Pehkui scale", exception)
        }
    }

    private fun scale(entity: Entity, fieldName: String): Float {
        if (!ModList.get().isLoaded("pehkui")) return 1.0f
        val api = api ?: return 1.0f
        return runCatching { api.scale(entity, fieldName) }
            .onFailure { exception -> ChowKingdomMod.LOGGER.debug("Failed to read Pehkui scale", exception) }
            .getOrDefault(1.0f)
    }

    private class PehkuiApi {
        private val scaleTypesClass = Class.forName("virtuoel.pehkui.api.ScaleTypes")
        private val scaleTypeClass = Class.forName("virtuoel.pehkui.api.ScaleType")
        private val scaleDataClass = Class.forName("virtuoel.pehkui.api.ScaleData")
        private val getScaleData = scaleTypeClass.getMethod("getScaleData", Entity::class.java)
        private val setScale = scaleDataClass.getMethod("setScale", java.lang.Float.TYPE)
        private val getScale = scaleDataClass.getMethod("getScale")

        fun setScale(entity: Entity, fieldName: String, value: Float) {
            val type = scaleTypesClass.getField(fieldName).get(null)
            val data = getScaleData.invoke(type, entity)
            setScale.invoke(data, value.coerceIn(0.6f, 1.4f))
        }

        fun scale(entity: Entity, fieldName: String): Float {
            val type = scaleTypesClass.getField(fieldName).get(null)
            val data = getScaleData.invoke(type, entity)
            return ((getScale.invoke(data) as? Number)?.toFloat() ?: 1.0f).takeIf { it > 0.0f } ?: 1.0f
        }
    }
}
