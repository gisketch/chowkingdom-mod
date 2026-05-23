package dev.gisketch.chowkingdom.shops

import dev.gisketch.chowkingdom.ChowKingdomMod
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

object ShopsFeature {
    private val BLOCKS: DeferredRegister<Block> = DeferredRegister.create(Registries.BLOCK, ChowKingdomMod.MOD_ID)
    private val ITEMS: DeferredRegister<Item> = DeferredRegister.create(Registries.ITEM, ChowKingdomMod.MOD_ID)
    private val BLOCK_ENTITIES: DeferredRegister<BlockEntityType<*>> = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ChowKingdomMod.MOD_ID)
    private val MENUS: DeferredRegister<MenuType<*>> = DeferredRegister.create(Registries.MENU, ChowKingdomMod.MOD_ID)

    private val woodShopBlocks = mutableListOf<DeferredHolder<Block, FacingShopBlock>>()
    private val allShopBlocks = mutableListOf<DeferredHolder<Block, out StockShopBlock>>()

    private val WOOD_VARIANTS = listOf(
        "acacia",
        "bamboo",
        "birch",
        "cherry",
        "crimson",
        "dark_oak",
        "mangrove",
        "spruce",
        "warped",
        "jungle",
        "oak",
    )

    private val COLOURS = listOf(
        "red",
        "white",
        "blue",
        "purple",
        "green",
        "lime",
        "orange",
        "gray",
        "black",
        "light_gray",
        "brown",
        "yellow",
        "light_blue",
        "cyan",
        "magenta",
        "pink",
    )

    private val RUG_BLOCKS = listOf(
        "rug_shop",
        "rug_shop_white",
        "rug_shop_orange",
        "rug_shop_magenta",
        "rug_shop_light_blue",
        "rug_shop_yellow",
        "rug_shop_lime",
        "rug_shop_pink",
        "rug_shop_gray",
        "rug_shop_light_gray",
        "rug_shop_cyan",
        "rug_shop_purple",
        "rug_shop_blue",
        "rug_shop_brown",
        "rug_shop_green",
        "rug_shop_black",
    )

    init {
        WOOD_VARIANTS.forEach { variant ->
            woodShopBlocks += registerWoodShop("shop_$variant")
        }

        registerBlock("hook_shop") { FacingShopBlock(shopProperties(Blocks.CHAIN), ShopRenderStyle.HOOK, HookShopBlock.SHAPE) }
        registerBlock("crate_shop") { FacingShopBlock(shopProperties(Blocks.OAK_PLANKS), ShopRenderStyle.CRATE) }
        registerBlock("shop_window_calcite") {
            FacingShopBlock(
                shopProperties(Blocks.STONE),
                ShopRenderStyle.WINDOW,
                WindowShopBlock.SHAPE_NORTH_SOUTH,
                WindowShopBlock.SHAPE_EAST_WEST,
            )
        }
        registerBlock("shop_window_andesite") {
            FacingShopBlock(
                shopProperties(Blocks.STONE),
                ShopRenderStyle.WINDOW,
                WindowShopBlock.SHAPE_NORTH_SOUTH,
                WindowShopBlock.SHAPE_EAST_WEST,
            )
        }

        RUG_BLOCKS.forEach { id ->
            registerBlock(id) { RugShopBlock(shopProperties(Blocks.RED_CARPET)) }
        }

        WOOD_VARIANTS.forEach { variant ->
            registerBlock("shelf_shop_$variant") { ShelfShopBlock(shopProperties(Blocks.OAK_PLANKS)) }
        }
    }

    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    val SHOP_BLOCK_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<ShopBlockEntity>> =
        BLOCK_ENTITIES.register("shop", Supplier {
            BlockEntityType.Builder.of(::ShopBlockEntity, *allShopBlocks.map { it.get() }.toTypedArray()).build(null)
        })

    val SHOP_STOCK_MENU: DeferredHolder<MenuType<*>, MenuType<ShopStockMenu>> = MENUS.register(
        "shop_stock",
        Supplier { IMenuTypeExtension.create { containerId, inventory, buffer -> ShopStockMenu.client(containerId, inventory, buffer) } },
    )

    val VENDOR_CONTRACT_ITEM: DeferredHolder<Item, VendorContractItem> = ITEMS.register(
        "vendor_contract",
        Supplier { VendorContractItem(Item.Properties().stacksTo(1)) },
    )

    fun register(modBus: IEventBus) {
        BLOCKS.register(modBus)
        ITEMS.register(modBus)
        BLOCK_ENTITIES.register(modBus)
        MENUS.register(modBus)
        ShopStockNetwork.register(modBus)
        VendorContractFeature.register(modBus)
        StoreShopFeature.register(modBus)
        NeoForge.EVENT_BUS.addListener(::onRegisterCommands)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        event.dispatcher.register(shopRoot("shop"))
        event.dispatcher.register(Commands.literal("chowkingdom").then(shopRoot("shop")))
        event.dispatcher.register(Commands.literal("ck").then(shopRoot("shop")))
        StoreShopFeature.registerCommands(event.dispatcher)
    }

    private fun shopRoot(name: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .then(Commands.literal("debug").requires { source -> source.hasPermission(2) }.executes(::debugShopOwner))

    private fun debugShopOwner(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val hit = player.pick(6.0, 0.0f, false) as? BlockHitResult
        if (hit == null || hit.type != HitResult.Type.BLOCK) {
            context.source.sendFailure(Component.literal("Look at a shop first."))
            return 0
        }
        val shop = player.level().getBlockEntity(hit.blockPos) as? ShopBlockEntity
        if (shop == null) {
            context.source.sendFailure(Component.literal("Target block is not a shop."))
            return 0
        }
        shop.debugClaimByOther(player)
        context.source.sendSuccess({ Component.literal("Shop owner changed to Debug Seller.") }, true)
        return 1
    }

    private fun registerWoodShop(id: String): DeferredHolder<Block, FacingShopBlock> {
        val block = registerBlock(id) { FacingShopBlock(shopProperties(Blocks.OAK_PLANKS), ShopRenderStyle.ANGLED) }
        COLOURS.forEach { colour ->
            ITEMS.register("${id}_$colour", Supplier { BlockItem(block.get(), Item.Properties()) })
        }
        return block
    }

    private fun <T : StockShopBlock> registerBlock(id: String, factory: () -> T): DeferredHolder<Block, T> {
        val block = BLOCKS.register(id, Supplier { factory() })
        allShopBlocks += block
        ITEMS.register(id, Supplier { BlockItem(block.get(), Item.Properties()) })
        return block
    }

    private fun shopProperties(example: Block): BlockBehaviour.Properties =
        BlockBehaviour.Properties.ofFullCopy(example)
            .noOcclusion()
            .strength(2.0f, Float.MAX_VALUE)
}

enum class ShopRenderStyle {
    ANGLED,
    HOOK,
    CRATE,
    WINDOW,
    RUG,
    SHELF,
}

abstract class StockShopBlock(
    properties: BlockBehaviour.Properties,
    val renderStyle: ShopRenderStyle,
) : Block(properties), EntityBlock {
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ShopBlockEntity(pos, state)

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): ItemInteractionResult {
        val shop = level.getBlockEntity(pos) as? ShopBlockEntity ?: return ItemInteractionResult.FAIL
        if (shop.isClaimedByOther(player) && shop.hasDisplayItem) {
            if (!level.isClientSide && player is ServerPlayer) ShopStockNetwork.openBuyDialog(player, shop)
            return ItemInteractionResult.SUCCESS
        }
        if (stack.isEmpty) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        if (level.isClientSide) return ItemInteractionResult.SUCCESS
        val added = shop.addStock(player, stack)
        val message = when {
            added > 0 -> "Added $added stock (${shop.stockCount}/${ShopBlockEntity.MAX_STOCK})"
            shop.isClaimedByOther(player) -> "Cannot add stock - owned by ${shop.ownerName.ifBlank { "another player" }}"
            shop.hasDisplayItem -> "This shop only accepts ${shop.displayItem.hoverName.string}"
            else -> "Could not add stock"
        }
        player.displayClientMessage(Component.literal(message), true)
        return if (added > 0) ItemInteractionResult.SUCCESS else ItemInteractionResult.FAIL
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        if (!level.isClientSide && player is ServerPlayer) {
            val shop = level.getBlockEntity(pos) as? ShopBlockEntity ?: return InteractionResult.FAIL
            if (shop.isClaimedByOther(player) && shop.hasDisplayItem) {
                ShopStockNetwork.openBuyDialog(player, shop)
                return InteractionResult.SUCCESS
            }
            player.openMenu(createMenuProvider(shop)) { buffer -> writeMenuData(buffer, shop, player) }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    override fun attack(state: BlockState, level: Level, pos: BlockPos, player: Player) {
        val shop = level.getBlockEntity(pos) as? ShopBlockEntity
        if (shop != null && shop.isClaimedByOther(player)) {
            if (level.isClientSide) player.displayClientMessage(Component.literal("Cannot break - owned by ${shop.ownerName.ifBlank { "another player" }}"), true)
            return
        }
        super.attack(state, level, pos, player)
    }

    override fun getDestroyProgress(state: BlockState, player: Player, level: BlockGetter, pos: BlockPos): Float {
        val shop = level.getBlockEntity(pos) as? ShopBlockEntity
        if (shop != null && shop.isClaimedByOther(player)) return 0.0f
        return super.getDestroyProgress(state, player, level, pos)
    }

    override fun onDestroyedByPlayer(state: BlockState, world: Level, pos: BlockPos, player: Player, willHarvest: Boolean, fluid: FluidState): Boolean {
        val shop = world.getBlockEntity(pos) as? ShopBlockEntity
        if (shop != null && shop.isClaimedByOther(player)) {
            if (world.isClientSide) player.displayClientMessage(Component.literal("Cannot break - owned by ${shop.ownerName.ifBlank { "another player" }}"), true)
            return false
        }
        return super.onDestroyedByPlayer(state, world, pos, player, willHarvest, fluid)
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean) {
        if (!state.`is`(newState.block)) {
            val shop = level.getBlockEntity(pos) as? ShopBlockEntity
            if (shop != null) {
                shop.dropStock(level)
                level.updateNeighbourForOutputSignal(pos, this)
            }
        }
        super.onRemove(state, level, pos, newState, isMoving)
    }

    private fun createMenuProvider(shop: ShopBlockEntity): SimpleMenuProvider =
        SimpleMenuProvider(
            { containerId, playerInventory, _ -> ShopStockMenu.server(containerId, playerInventory, shop) },
            Component.literal("Shop Stock"),
        )

    private fun writeMenuData(buffer: RegistryFriendlyByteBuf, shop: ShopBlockEntity, player: Player) {
        buffer.writeBlockPos(shop.blockPos)
        buffer.writeVarInt(shop.stockCount)
        buffer.writeVarLong(shop.price)
        buffer.writeUtf(shop.ownerName, 64)
        buffer.writeBoolean(!shop.isClaimedByOther(player))
        buffer.writeVarLong(shop.soldCount)
        buffer.writeVarLong(shop.totalRevenue)
        buffer.writeVarLong(shop.claimableRevenue)
    }
}

private open class FacingShopBlock(
    properties: BlockBehaviour.Properties,
    renderStyle: ShopRenderStyle,
    private val northSouthShape: VoxelShape = Shapes.block(),
    private val eastWestShape: VoxelShape = northSouthShape,
) : StockShopBlock(properties, renderStyle) {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun rotate(state: BlockState, level: LevelAccessor, pos: BlockPos, direction: Rotation): BlockState =
        state.setValue(FACING, direction.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.setValue(FACING, mirror.getRotation(state.getValue(FACING)).rotate(state.getValue(FACING)))

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        when (state.getValue(FACING)) {
            Direction.EAST, Direction.WEST -> eastWestShape
            else -> northSouthShape
        }

    companion object {
        val FACING: DirectionProperty = HorizontalDirectionalBlock.FACING
    }
}

private class RugShopBlock(properties: BlockBehaviour.Properties) : StockShopBlock(properties, ShopRenderStyle.RUG) {
    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(NORTH, false)
                .setValue(EAST, false)
                .setValue(SOUTH, false)
                .setValue(WEST, false),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(NORTH, EAST, SOUTH, WEST)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        val level = context.level
        val pos = context.clickedPos
        return defaultBlockState()
            .setValue(NORTH, level.getBlockState(pos.north()).block == this)
            .setValue(EAST, level.getBlockState(pos.east()).block == this)
            .setValue(SOUTH, level.getBlockState(pos.south()).block == this)
            .setValue(WEST, level.getBlockState(pos.west()).block == this)
    }

    override fun updateShape(state: BlockState, direction: Direction, neighborState: BlockState, level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos): BlockState {
        val property = when (direction) {
            Direction.NORTH -> NORTH
            Direction.EAST -> EAST
            Direction.SOUTH -> SOUTH
            Direction.WEST -> WEST
            else -> return state
        }
        return state.setValue(property, neighborState.block == this)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    companion object {
        val NORTH: BooleanProperty = BooleanProperty.create("north")
        val EAST: BooleanProperty = BooleanProperty.create("east")
        val SOUTH: BooleanProperty = BooleanProperty.create("south")
        val WEST: BooleanProperty = BooleanProperty.create("west")
        val SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 1.0, 16.0)
    }
}

private class ShelfShopBlock(properties: BlockBehaviour.Properties) : FacingShopBlock(properties, ShopRenderStyle.SHELF) {
    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(TYPE, SlabType.DOUBLE),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, TYPE)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState()
            .setValue(FACING, context.horizontalDirection.opposite)
            .setValue(TYPE, if (context.clickLocation.y - context.clickedPos.y > 0.5) SlabType.TOP else SlabType.BOTTOM)

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape {
        val facing = state.getValue(FACING)
        val index = when (facing) {
            Direction.WEST -> 1
            Direction.SOUTH -> 2
            Direction.EAST -> 3
            else -> 0
        }
        return when (state.getValue(TYPE)) {
            SlabType.TOP -> TOP_SHAPES[index]
            SlabType.BOTTOM -> BOTTOM_SHAPES[index]
            else -> DOUBLE_SHAPES[index]
        }
    }

    companion object {
        val TYPE = BlockStateProperties.SLAB_TYPE
        private val TOP_SHAPES = arrayOf(
            box(0.5, 8.0, 8.0, 15.5, 14.0, 16.0),
            box(8.0, 8.0, 0.5, 16.0, 14.0, 15.5),
            box(0.5, 8.0, 0.0, 15.5, 14.0, 8.0),
            box(0.0, 8.0, 0.5, 8.0, 14.0, 15.5),
        )
        private val BOTTOM_SHAPES = arrayOf(
            box(0.5, 0.0, 8.0, 15.5, 6.0, 16.0),
            box(8.0, 0.0, 0.5, 16.0, 6.0, 15.5),
            box(0.5, 0.0, 0.0, 15.5, 6.0, 8.0),
            box(0.0, 0.0, 0.5, 8.0, 6.0, 15.5),
        )
        private val DOUBLE_SHAPES = Array(4) { index -> Shapes.or(TOP_SHAPES[index], BOTTOM_SHAPES[index]) }
    }
}

private object HookShopBlock {
    val SHAPE: VoxelShape = Block.box(5.0, -1.0, 5.0, 11.0, 16.0, 11.0)
}

private object WindowShopBlock {
    val SHAPE_NORTH_SOUTH: VoxelShape = Block.box(0.0, -1.0, -1.0, 16.0, 2.0, 17.0)
    val SHAPE_EAST_WEST: VoxelShape = Block.box(-1.0, -1.0, 0.0, 17.0, 2.0, 16.0)
}
