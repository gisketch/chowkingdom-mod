package dev.gisketch.chowkingdom

import com.mojang.logging.LogUtils
import dev.gisketch.chowkingdom.battlepass.BattlepassClient
import dev.gisketch.chowkingdom.battlepass.BattlepassCommands
import dev.gisketch.chowkingdom.battlepass.BattlepassElytraGate
import dev.gisketch.chowkingdom.battlepass.BattlepassFarmersDelightEventIntegration
import dev.gisketch.chowkingdom.battlepass.BattlepassMissionProgressStore
import dev.gisketch.chowkingdom.battlepass.BattlepassNetwork
import dev.gisketch.chowkingdom.battlepass.BattlepassPassRegistry
import dev.gisketch.chowkingdom.battlepass.BattlepassQualityFoodEventIntegration
import dev.gisketch.chowkingdom.battlepass.BattlepassVanillaEventIntegration
import dev.gisketch.chowkingdom.battlepass.BattlepassWorldData
import dev.gisketch.chowkingdom.bosses.BossEventsFeature
import dev.gisketch.chowkingdom.battlepass.BattlepassXpStore
import dev.gisketch.chowkingdom.battlepass.CobblemonBattlepassIntegration
import dev.gisketch.chowkingdom.client.ChowKingdomConfigScreen
import dev.gisketch.chowkingdom.client.ChowDeathScreenClient
import dev.gisketch.chowkingdom.client.ChowKingdomHud
import dev.gisketch.chowkingdom.client.InventoryMenuClient
import dev.gisketch.chowkingdom.client.ParagliderAttackInputClient
import dev.gisketch.chowkingdom.client.PlayerListHudClient
import dev.gisketch.chowkingdom.compat.SpellEngineClassLockBridge
import dev.gisketch.chowkingdom.compat.SkillTreePackFeature
import dev.gisketch.chowkingdom.compat.SkylandsSeedCompat
import dev.gisketch.chowkingdom.compat.PunchyFirstPersonSuppressionBridge
import dev.gisketch.chowkingdom.compat.UnifiedStaminaFeature
import dev.gisketch.chowkingdom.compat.XaeroNpcMapCompat
import dev.gisketch.chowkingdom.config.TomlConfigIO
import dev.gisketch.chowkingdom.cosmetics.PokeClothingCosmeticsFeature
import dev.gisketch.chowkingdom.debug.ExtractCommands
import dev.gisketch.chowkingdom.discord.DiscordFeature
import dev.gisketch.chowkingdom.discord.DiscordScreenshotClient
import dev.gisketch.chowkingdom.exploration.ExplorationDiscoveryFeature
import dev.gisketch.chowkingdom.gyms.GymLeagueFeature
import dev.gisketch.chowkingdom.gyms.GymLeagueNetwork
import dev.gisketch.chowkingdom.gyms.GymTransitionClient
import dev.gisketch.chowkingdom.gyms.GymTransitionNetwork
import dev.gisketch.chowkingdom.gyms.LeagueCompassFeature
import dev.gisketch.chowkingdom.mobility.MobilityLicenseFeature
import dev.gisketch.chowkingdom.npc.NpcBossBarClient
import dev.gisketch.chowkingdom.npc.NpcClient
import dev.gisketch.chowkingdom.npc.NpcFeature
import dev.gisketch.chowkingdom.randomtrainers.RandomTrainerClient
import dev.gisketch.chowkingdom.randomtrainers.RandomTrainerFeature
import dev.gisketch.chowkingdom.recipes.RecipeDisablerFeature
import dev.gisketch.chowkingdom.relicroulette.RelicRouletteFeature
import dev.gisketch.chowkingdom.revive.ReviveClient
import dev.gisketch.chowkingdom.revive.ReviveFeature
import dev.gisketch.chowkingdom.roles.RolesFeature
import dev.gisketch.chowkingdom.shipping.ShippingBinClient
import dev.gisketch.chowkingdom.profiles.ProfilesFeature
import dev.gisketch.chowkingdom.shipping.ShippingBinFeature
import dev.gisketch.chowkingdom.shops.ShopsClient
import dev.gisketch.chowkingdom.shops.ShopsFeature
import dev.gisketch.chowkingdom.scaling.ScalingFeature
import dev.gisketch.chowkingdom.snackbar.SnackbarClient
import dev.gisketch.chowkingdom.snackbar.SnackbarFeature
import dev.gisketch.chowkingdom.skilltree.ClassSkillTreeFeature
import dev.gisketch.chowkingdom.tech.ArsNouveauHudGateClient
import dev.gisketch.chowkingdom.tech.TechLicenseFeature
import dev.gisketch.chowkingdom.trading.TradingClient
import dev.gisketch.chowkingdom.trading.TradingFeature
import dev.gisketch.chowkingdom.town.TownReturnFeature
import dev.gisketch.chowkingdom.wallets.WalletsFeature
import dev.gisketch.chowkingdom.worldborder.WorldBorderFeature
import dev.gisketch.chowkingdom.worlds.WorldsFeature
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import org.slf4j.Logger

@Mod(ChowKingdomMod.MOD_ID)
class ChowKingdomMod(modBus: IEventBus, container: ModContainer) {
    init {
        LOGGER.info("Loading {}", container.modInfo.displayName)
        TomlConfigIO.migrateModConfigTree()
        ChowSounds.register(modBus)
        ChowClockConfig.load()
        SkylandsSeedCompat.load()
        SkylandsSeedCompat.register()
        BattlepassPassRegistry.reload()
        BattlepassXpStore.load()
        BattlepassMissionProgressStore.load()
        BattlepassNetwork.register(modBus)
        BattlepassWorldData.register()
        BattlepassVanillaEventIntegration.register()
        BattlepassQualityFoodEventIntegration.register()
        BattlepassFarmersDelightEventIntegration.register()
        CobblemonBattlepassIntegration.register()
        BattlepassElytraGate.register()
        MobilityLicenseFeature.register()
        TechLicenseFeature.register(modBus)
        BattlepassCommands.register()
        BossEventsFeature.register()
        ScalingFeature.register()
        GymTransitionNetwork.register(modBus)
        GymLeagueNetwork.register(modBus)
        LeagueCompassFeature.register(modBus)
        GymLeagueFeature.register()
        ExplorationDiscoveryFeature.register()
        ExtractCommands.register()
        WalletsFeature.register(modBus)
        ShippingBinFeature.register(modBus)
        WorldBorderFeature.register()
        ShopsFeature.register(modBus)
        ProfilesFeature.register(modBus)
        RolesFeature.register(modBus)
        SkillTreePackFeature.register(modBus)
        SpellEngineClassLockBridge.register()
        UnifiedStaminaFeature.register()
        ReviveFeature.register(modBus)
        TradingFeature.register(modBus)
        DiscordFeature.register()
        SnackbarFeature.register(modBus)
        RelicRouletteFeature.register(modBus)
        PokeClothingCosmeticsFeature.register(modBus)
        RecipeDisablerFeature.register(modBus)
        NpcFeature.register(modBus)
        RandomTrainerFeature.register(modBus)
        ClassSkillTreeFeature.register(modBus)
        TownReturnFeature.register(modBus)
        WorldsFeature.register()
        ParrySoundFeature.register()
        if (FMLEnvironment.dist == Dist.CLIENT) {
            container.registerExtensionPoint(IConfigScreenFactory::class.java) {
                IConfigScreenFactory { _, modListScreen -> ChowKingdomConfigScreen(modListScreen) }
            }
            BattlepassClient.register(modBus)
            ChowDeathScreenClient.register()
            ChowKingdomHud.register(modBus)
            ParagliderAttackInputClient.register()
            InventoryMenuClient.register()
            PlayerListHudClient.register(modBus)
            DiscordScreenshotClient.register(modBus)
            ShippingBinClient.register(modBus)
            ShopsClient.register(modBus)
            ReviveClient.register(modBus)
            TradingClient.register(modBus)
            SnackbarClient.register(modBus)
            GymTransitionClient.register(modBus)
            NpcBossBarClient.register(modBus)
            NpcClient.register(modBus)
            RandomTrainerClient.register(modBus)
            ArsNouveauHudGateClient.register(modBus)
            PunchyFirstPersonSuppressionBridge.register()
            XaeroNpcMapCompat.register()
        }
    }

    companion object {
        const val MOD_ID = "gisketchs_chowkingdom_mod"
        val LOGGER: Logger = LogUtils.getLogger()
    }
}
