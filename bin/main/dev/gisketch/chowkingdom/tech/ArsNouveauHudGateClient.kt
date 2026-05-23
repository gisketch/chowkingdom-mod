package dev.gisketch.chowkingdom.tech

import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent
import net.neoforged.neoforge.common.NeoForge

object ArsNouveauHudGateClient {
    private val ARS_MANA_HUD = ResourceLocation.fromNamespaceAndPath("ars_nouveau", "mana_hud")

    fun register(modBus: IEventBus) {
        NeoForge.EVENT_BUS.addListener(::hideManaHudWhenUnlicensed)
    }

    private fun hideManaHudWhenUnlicensed(event: RenderGuiLayerEvent.Pre) {
        if (event.name != ARS_MANA_HUD) return
        if (!TechLicenseClientState.hasLicense("ars")) event.isCanceled = true
    }
}
