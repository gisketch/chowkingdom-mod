package dev.gisketch.chowkingdom.trading

import dev.gisketch.chowkingdom.ChowKingdomMod
import net.minecraft.core.registries.Registries
import net.minecraft.world.inventory.MenuType
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

object TradingFeature {
    private val MENUS: DeferredRegister<MenuType<*>> = DeferredRegister.create(Registries.MENU, ChowKingdomMod.MOD_ID)

    val TRADE_MENU: DeferredHolder<MenuType<*>, MenuType<TradingMenu>> = MENUS.register(
        "trade",
        Supplier { IMenuTypeExtension.create { containerId, inventory, buffer -> TradingMenu.client(containerId, inventory, buffer) } },
    )

    fun register(modBus: IEventBus) {
        MENUS.register(modBus)
        TradingNetwork.register(modBus)
        TradingManager.register()
    }
}
