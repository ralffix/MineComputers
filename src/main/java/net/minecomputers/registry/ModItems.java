package net.minecomputers.registry;

import net.minecomputers.MineComputers;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * All items: a block item for each block, plus the three standalone items.
 * Crafting recipes come later (see docs/SPEC.md).
 */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MineComputers.MOD_ID);

    // Block items.
    public static final DeferredItem<BlockItem> LAPTOP = ITEMS.registerSimpleBlockItem("laptop", ModBlocks.LAPTOP, new Item.Properties().stacksTo(1));
    public static final DeferredItem<BlockItem> BRAIN = ITEMS.registerSimpleBlockItem(ModBlocks.BRAIN);
    public static final DeferredItem<BlockItem> DATA_CABLE = ITEMS.registerSimpleBlockItem(ModBlocks.DATA_CABLE);
    public static final DeferredItem<BlockItem> POWER_CABLE = ITEMS.registerSimpleBlockItem(ModBlocks.POWER_CABLE);
    public static final DeferredItem<BlockItem> POWER_DATA_CABLE = ITEMS.registerSimpleBlockItem(ModBlocks.POWER_DATA_CABLE);
    public static final DeferredItem<BlockItem> SERVO = ITEMS.registerSimpleBlockItem(ModBlocks.SERVO);
    public static final DeferredItem<BlockItem> SCREEN = ITEMS.registerSimpleBlockItem(ModBlocks.SCREEN);
    public static final DeferredItem<BlockItem> SPEAKER = ITEMS.registerSimpleBlockItem(ModBlocks.SPEAKER);
    public static final DeferredItem<BlockItem> REDSTONE_LINK = ITEMS.registerSimpleBlockItem(ModBlocks.REDSTONE_LINK);
    public static final DeferredItem<BlockItem> REMOTE_GATEWAY = ITEMS.registerSimpleBlockItem(ModBlocks.REMOTE_GATEWAY);

    public static final DeferredItem<BlockItem> POWER_SOURCE = ITEMS.registerSimpleBlockItem(ModBlocks.POWER_SOURCE);

    // Standalone items.
    public static final DeferredItem<Item> REMOTE_ACCESS = ITEMS.registerSimpleItem("remote_access", new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> LINK_TRANSMITTER = ITEMS.registerSimpleItem("link_transmitter", new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> CIPHER = ITEMS.registerSimpleItem("cipher", new Item.Properties().stacksTo(16));

    private ModItems() {
    }
}
