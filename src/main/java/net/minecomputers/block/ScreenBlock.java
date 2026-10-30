package net.minecomputers.block;

import java.util.ArrayList;
import java.util.List;

import net.minecomputers.block.entity.ScreenBlockEntity;
import net.minecomputers.registry.ModBlockEntities;
import net.minecomputers.screen.ScreenGroups;
import net.minecomputers.screen.ScreenTerminal;
import net.minecomputers.script.ScriptManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Screen: a multi-block display (1x1 up to 4x4) like a CC: Tweaked monitor. It faces the player who placed
 * it. Screens that touch each other (same facing, same wall) join into the biggest square that fits, see
 * {@link ScreenGroups}. The model has its front on the south side; the blockstate file rotates it to match FACING.
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

    /** The block entity does not exist yet while onPlace runs, so the joining is done one tick later. */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && !oldState.is(this)) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        ScreenGroups.merge(level, pos);
    }

    /** Breaking one block of a group splits it: the other blocks become single screens again and then re-join. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        List<BlockPos> others = new ArrayList<>();
        if (!level.isClientSide && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof ScreenBlockEntity screen && !screen.isSingle()) {
            Direction right = ScreenBlockEntity.rightOf(state.getValue(FACING));
            BlockPos origin = screen.originPos();
            for (int y = 0; y < screen.groupHeight(); y++) {
                for (int x = 0; x < screen.groupWidth(); x++) {
                    BlockPos other = origin.relative(right, x).relative(Direction.DOWN, y);
                    if (!other.equals(pos)) {
                        others.add(other);
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
        for (BlockPos other : others) {
            if (level.getBlockEntity(other) instanceof ScreenBlockEntity remaining) {
                remaining.setGrid(0, 0, 1, 1);
                level.scheduleTick(other, this, 1);
            }
        }
    }

    /**
     * Right-clicking the front of a screen is a "touch": the script that owns the screen gets the character cell
     * and the pixel that was hit (os.on("touch", ...)). Nothing happens if no script listens.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        Direction facing = state.getValue(FACING);
        if (hit.getDirection() != facing) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            // Say "used" on the client too, so it doesn't also try to place a held block (that flickers and looks janky).
            return InteractionResult.SUCCESS;
        }
        ScreenBlockEntity origin = ScreenBlockEntity.originAt(level, pos);
        ScreenTerminal terminal = origin == null ? null : origin.terminal();
        if (terminal == null) {
            return InteractionResult.SUCCESS;
        }
        Direction right = ScreenBlockEntity.rightOf(facing);
        BlockPos op = origin.getBlockPos();
        Vec3 at = hit.getLocation();
        Direction.Axis axis = right.getAxis();
        int step = right.getAxisDirection().getStep();
        double base = step > 0 ? op.get(axis) : op.get(axis) + 1;
        double u = (at.get(axis) - base) * step;
        double v = (op.getY() + 1) - at.y;
        double fu = Math.max(0.0, Math.min(0.9999, u / terminal.blocksW()));
        double fv = Math.max(0.0, Math.min(0.9999, v / terminal.blocksH()));
        ScriptManager.screenTouched(GlobalPos.of(level.dimension(), op),
                (int) (fu * terminal.cols()), (int) (fv * terminal.rows()),
                (int) (fu * terminal.pixelW()), (int) (fv * terminal.pixelH()));
        return InteractionResult.SUCCESS;
    }
}
