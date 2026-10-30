package net.minecomputers.block;

import net.minecomputers.block.entity.BrainBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The Brain: where the code sits. Holds its files in a block entity. */
public class BrainBlock extends Block implements EntityBlock {
    public BrainBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BrainBlockEntity(pos, state);
    }
}
