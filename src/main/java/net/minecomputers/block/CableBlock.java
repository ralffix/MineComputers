package net.minecomputers.block;

import java.util.Map;

import net.minecomputers.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Data Cable, Power Cable and Power Data Cable. One class with a Kind. The six boolean properties say which sides the
 * cable visually connects to; the blockstate file picks the model from them (all three cables share the same models).
 * Connecting is only visual: the data and FE networks are still searched in NetworkScan and PowerNet.
 */
public class CableBlock extends Block {
    public enum Kind {
        DATA, POWER, POWER_DATA
    }

    private static final Direction[] ORDER = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP, Direction.DOWN };
    private static final Map<Direction, BooleanProperty> PROPS = Map.of(
            Direction.NORTH, BlockStateProperties.NORTH,
            Direction.EAST, BlockStateProperties.EAST,
            Direction.SOUTH, BlockStateProperties.SOUTH,
            Direction.WEST, BlockStateProperties.WEST,
            Direction.UP, BlockStateProperties.UP,
            Direction.DOWN, BlockStateProperties.DOWN);

    private static final VoxelShape CENTER = Block.box(5, 5, 5, 11, 11, 11);
    private static final VoxelShape[] ARMS = {
            Block.box(6, 6, 0, 10, 10, 5),   // north
            Block.box(11, 6, 6, 16, 10, 10), // east
            Block.box(6, 6, 11, 10, 10, 16), // south
            Block.box(0, 6, 6, 5, 10, 10),   // west
            Block.box(6, 11, 6, 10, 16, 10), // up
            Block.box(6, 0, 6, 10, 5, 10)    // down
    };

    private final Kind kind;
    /** One shape for each of the 64 combinations of connected sides (bit i = ORDER[i]). */
    private final VoxelShape[] shapes = new VoxelShape[64];

    public CableBlock(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        BlockState state = stateDefinition.any();
        for (Direction direction : ORDER) {
            state = state.setValue(PROPS.get(direction), false);
        }
        registerDefaultState(state);
        for (int mask = 0; mask < 64; mask++) {
            VoxelShape shape = CENTER;
            for (int i = 0; i < 6; i++) {
                if ((mask & (1 << i)) != 0) {
                    shape = Shapes.or(shape, ARMS[i]);
                }
            }
            shapes[mask] = shape;
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        for (Direction direction : ORDER) {
            builder.add(PROPS.get(direction));
        }
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = defaultBlockState();
        for (Direction direction : ORDER) {
            BlockState neighbor = level.getBlockState(pos.relative(direction));
            state = state.setValue(PROPS.get(direction), connectsTo(level, pos, direction, neighbor));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level,
            BlockPos pos, BlockPos neighborPos) {
        return state.setValue(PROPS.get(direction), connectsTo(level, pos, direction, neighborState));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int mask = 0;
        for (int i = 0; i < 6; i++) {
            if (state.getValue(PROPS.get(ORDER[i]))) {
                mask |= 1 << i;
            }
        }
        return shapes[mask];
    }

    private boolean carriesData() {
        return kind != Kind.POWER;
    }

    private boolean carriesPower() {
        return kind != Kind.DATA;
    }

    /** Whether this cable should draw an arm towards the neighbor in this direction. */
    private boolean connectsTo(LevelAccessor level, BlockPos pos, Direction direction, BlockState neighborState) {
        Block neighbor = neighborState.getBlock();
        if (neighbor instanceof CableBlock other) {
            return (carriesData() && other.carriesData()) || (carriesPower() && other.carriesPower());
        }
        // Every device takes data and FE.
        if (neighbor == ModBlocks.SERVO.get() || neighbor == ModBlocks.SCREEN.get() || neighbor == ModBlocks.SPEAKER.get()
                || neighbor == ModBlocks.REDSTONE_LINK.get() || neighbor == ModBlocks.REMOTE_GATEWAY.get()) {
            return true;
        }
        if (carriesData() && (neighbor == ModBlocks.BRAIN.get() || neighbor == ModBlocks.LAPTOP.get())) {
            return true;
        }
        if (carriesPower()) {
            if (neighbor == ModBlocks.POWER_SOURCE.get()) {
                return true;
            }
            // Any block from any mod that offers FE (a generator, a battery).
            if (level instanceof Level real) {
                return real.getCapability(Capabilities.EnergyStorage.BLOCK, pos.relative(direction), direction.getOpposite()) != null;
            }
        }
        return false;
    }
}
