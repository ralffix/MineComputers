package net.minecomputers.block;

import net.minecomputers.block.entity.ScreenBlockEntity;
import net.minecomputers.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/**
 * The Screen: shows what a script writes to it. It faces the player who placed it, like a furnace.
 * The model has its front on the south side; the blockstate file rotates it to match FACING.
 */
public class ScreenBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public ScreenBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ScreenBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != ModBlockEntities.SCREEN.get()) {
            return null;
        }
        return (tickLevel, pos, tickState, entity) -> {
            if (entity instanceof ScreenBlockEntity screen) {
                screen.serverTick();
            }
        };
    }
}
