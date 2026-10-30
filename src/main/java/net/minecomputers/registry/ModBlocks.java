package net.minecomputers.registry;

import net.minecomputers.MineComputers;
import net.minecomputers.block.BrainBlock;
import net.minecomputers.block.LaptopBlock;
import net.minecomputers.block.ScreenBlock;
import net.minecomputers.block.ServoBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * All blocks. For now every block is a plain placeholder block.
 * Real behavior (block entities, networking, screens, servo) comes in later milestones.
 */
public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MineComputers.MOD_ID);

    // Where the code sits and runs. Needs no FE.
    public static final DeferredBlock<Block> BRAIN = BLOCKS.<Block>registerBlock("brain", BrainBlock::new, machine());

    // Cables: data only, FE only, and FE + data on the same line.
    public static final DeferredBlock<Block> DATA_CABLE = BLOCKS.registerSimpleBlock("data_cable", cable());
    public static final DeferredBlock<Block> POWER_CABLE = BLOCKS.registerSimpleBlock("power_cable", cable());
    public static final DeferredBlock<Block> POWER_DATA_CABLE = BLOCKS.registerSimpleBlock("power_data_cable", cable());

    // The laptop is placed in the world (on a cable or a brain), so it is a block with an item.
    public static final DeferredBlock<Block> LAPTOP = BLOCKS.<Block>registerBlock("laptop", LaptopBlock::new,
            BlockBehaviour.Properties.of().strength(1.0F).sound(SoundType.METAL).noOcclusion());

    // Devices.
    public static final DeferredBlock<Block> SERVO = BLOCKS.<Block>registerBlock("servo", ServoBlock::new, machine());
    public static final DeferredBlock<Block> SCREEN = BLOCKS.<Block>registerBlock("screen", ScreenBlock::new, machine());
    public static final DeferredBlock<Block> SPEAKER = BLOCKS.registerSimpleBlock("speaker", machine());
    public static final DeferredBlock<Block> REDSTONE_LINK = BLOCKS.registerSimpleBlock("redstone_link", machine());
    public static final DeferredBlock<Block> REMOTE_GATEWAY = BLOCKS.registerSimpleBlock("remote_gateway", machine());

    // A block that gives unlimited FE to everything it touches (and to cables it touches). For testing and creative use.
    public static final DeferredBlock<Block> POWER_SOURCE = BLOCKS.registerSimpleBlock("power_source", machine());

    private static BlockBehaviour.Properties machine() {
        return BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(SoundType.METAL).requiresCorrectToolForDrops();
    }

    private static BlockBehaviour.Properties cable() {
        return BlockBehaviour.Properties.of().strength(0.5F).sound(SoundType.METAL).noOcclusion();
    }

    private ModBlocks() {
    }
}
