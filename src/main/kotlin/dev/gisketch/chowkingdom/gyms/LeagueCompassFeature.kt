package dev.gisketch.chowkingdom.gyms

import com.mojang.brigadier.context.CommandContext
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.npc.ChowNpcEntity
import dev.gisketch.chowkingdom.npc.NpcFeature
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.LodestoneTracker
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier

object LeagueCompassFeature {
    private val ITEMS: DeferredRegister<Item> = DeferredRegister.create(Registries.ITEM, ChowKingdomMod.MOD_ID)
    val LEAGUE_COMPASS: DeferredHolder<Item, LeagueCompassItem> = ITEMS.register("league_compass", Supplier { LeagueCompassItem(Item.Properties().stacksTo(1)) })
    private var nextCompassTick = 0L
    private val trainerTargetCache: MutableMap<String, CachedLeagueNpcTarget> = linkedMapOf()

    fun register(modBus: IEventBus) {
        ITEMS.register(modBus)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
    }

    fun updateFor(player: ServerPlayer) {
        val stacks = player.inventory.items.filter(::isLeagueCompass) + player.inventory.offhand.filter(::isLeagueCompass)
        if (stacks.isEmpty()) return
        val target = targetFor(player)
        stacks.forEach { stack -> decorate(stack, player.uuid, target) }
    }

    fun hasInInventory(player: ServerPlayer): Boolean =
        player.inventory.items.any(::isLeagueCompass) || player.inventory.offhand.any(::isLeagueCompass)

    fun giveTo(player: ServerPlayer): ItemStack {
        val stack = createStack(player)
        player.inventory.placeItemBackInInventory(stack)
        updateFor(player)
        return stack
    }

    fun giveCommand(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        giveTo(player)
        context.source.sendSuccess({ Component.literal("Gave ${player.gameProfile.name} a League Compass.") }, true)
        return 1
    }

    fun giveCommand(context: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        giveTo(player)
        context.source.sendSuccess({ Component.literal("Gave ${player.gameProfile.name} a League Compass.") }, true)
        return 1
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        updateFor(player)
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        val now = event.server.overworld().gameTime
        if (now < nextCompassTick) return
        nextCompassTick = now + 40L
        event.server.playerList.players.forEach(::updateFor)
    }

    private fun createStack(player: ServerPlayer): ItemStack {
        val stack = ItemStack(Items.COMPASS)
        val tag = CompoundTag()
        tag.putBoolean(TAG_LEAGUE_COMPASS, true)
        tag.putString(TAG_OWNER, player.uuid.toString())
        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag)
        decorate(stack, player.uuid, targetFor(player))
        return stack
    }

    private fun isLeagueCompass(stack: ItemStack): Boolean {
        if (stack.isEmpty || stack.item != Items.COMPASS) return false
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBoolean(TAG_LEAGUE_COMPASS)
    }

    private fun targetFor(player: ServerPlayer): LeagueCompassTarget {
        val activeLeagueId = GymLeagueStore.activeLeague(player)
        val league = GymLeagueConfig.league(activeLeagueId) ?: return LeagueCompassTarget.noSignal("No active league record.", "", null)
        val encounter = GymLeagueStore.nextPlayerEncounter(player, league) ?: return LeagueCompassTarget.noSignal("League record complete.", league.displayName, null)
        val trainer = league.trainer(encounter.trainer) ?: return LeagueCompassTarget.noSignal("Next trainer paperwork is missing.", league.displayName, GymLeagueText.encounterLabel(league, encounter))
        val npcTarget = cachedTrainerTarget(player, trainer.npcId)
            ?: return LeagueCompassTarget.noSignal("No signal: ${trainer.name} is not posted yet.", league.displayName, GymLeagueText.encounterLabel(league, encounter))
        return LeagueCompassTarget(
            leagueName = league.displayName,
            nextFight = GymLeagueText.encounterLabel(league, encounter),
            trainerName = trainer.name,
            status = "Signal locked: Skylands stadium.",
            dimension = npcTarget.dimension,
            pos = npcTarget.pos,
            globalPos = npcTarget.globalPos,
        )
    }

    private fun cachedTrainerTarget(player: ServerPlayer, npcId: String): CachedLeagueNpcTarget? {
        val now = player.server.overworld().gameTime
        trainerTargetCache[npcId]?.takeIf { cached -> now - cached.checkedAtTick < TRAINER_TARGET_CACHE_TICKS }?.let { cached ->
            return cached.takeIf { it.globalPos != null }
        }
        val npc = NpcFeature.existingNpc(player.server, npcId)?.takeUnless(ChowNpcEntity::isRemoved)
        val target = if (npc != null) {
            val pos = npc.blockPosition().immutable()
            CachedLeagueNpcTarget(now, npc.level().dimension().location().toString(), pos, GlobalPos.of(npc.level().dimension(), pos))
        } else {
            CachedLeagueNpcTarget(now, "", null, null)
        }
        trainerTargetCache[npcId] = target
        return target.takeIf { it.globalPos != null }
    }

    private fun decorate(stack: ItemStack, owner: UUID, target: LeagueCompassTarget) {
        val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
        tag.putBoolean(TAG_LEAGUE_COMPASS, true)
        tag.putString(TAG_OWNER, owner.toString())
        tag.putString(TAG_LEAGUE, target.leagueName)
        tag.putString(TAG_NEXT, target.nextFight.orEmpty())
        tag.putString(TAG_TRAINER, target.trainerName)
        tag.putString(TAG_STATUS, target.status)
        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag)

        stack.set(DataComponents.ITEM_NAME, Component.literal("League Compass").withStyle(ChatFormatting.AQUA))
        stack.set(
            DataComponents.LORE,
            ItemLore(
                listOf(
                    Component.literal("Next fight: ${target.nextFight ?: target.trainerName.ifBlank { "None" }}").withStyle(ChatFormatting.GOLD),
                    Component.literal(target.status).withStyle(if (target.globalPos != null) ChatFormatting.GREEN else ChatFormatting.GRAY),
                ),
            ),
        )
        if (target.globalPos != null) {
            stack.set(DataComponents.LODESTONE_TRACKER, LodestoneTracker(Optional.of(target.globalPos), false))
        } else {
            stack.remove(DataComponents.LODESTONE_TRACKER)
        }
    }

    private data class LeagueCompassTarget(
        val leagueName: String,
        val nextFight: String?,
        val trainerName: String,
        val status: String,
        val dimension: String,
        val pos: BlockPos?,
        val globalPos: GlobalPos?,
    ) {
        companion object {
            fun noSignal(status: String, leagueName: String, nextFight: String?): LeagueCompassTarget =
                LeagueCompassTarget(leagueName, nextFight, "", status, "", null, null)
        }
    }

    private data class CachedLeagueNpcTarget(
        val checkedAtTick: Long,
        val dimension: String,
        val pos: BlockPos?,
        val globalPos: GlobalPos?,
    )

    private const val TAG_LEAGUE_COMPASS = "ckdm_league_compass"
    private const val TAG_OWNER = "owner"
    private const val TAG_LEAGUE = "league"
    private const val TAG_NEXT = "next"
    private const val TAG_TRAINER = "trainer"
    private const val TAG_STATUS = "status"
    private const val TRAINER_TARGET_CACHE_TICKS = 80L
}

class LeagueCompassItem(properties: Properties) : Item(properties) {
    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltipComponents: MutableList<Component>, tooltipFlag: TooltipFlag) {
        val tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
        val league = tag.getString("league")
        val next = tag.getString("next")
        val trainer = tag.getString("trainer")
        val status = tag.getString("status")
        if (league.isNotBlank()) tooltipComponents += Component.literal(league).withStyle(ChatFormatting.GRAY)
        tooltipComponents += Component.literal("Next fight: ${next.ifBlank { trainer.ifBlank { "None" } }}").withStyle(ChatFormatting.GOLD)
        tooltipComponents += Component.literal(status.ifBlank { "No active league signal." }).withStyle(ChatFormatting.GRAY)
    }
}
