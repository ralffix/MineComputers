package net.minecomputers.screen;

import net.minecomputers.block.ScreenBlock;
import net.minecomputers.block.entity.ScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Joins touching Screen blocks into one big screen. Allowed sizes are 1x1, 2x2, 3x3 and 4x4 (squares only).
 * Blocks join when they face the same way and sit on the same wall. When a block is placed, the biggest
 * square that contains it and is completely made of screens is formed; groups already inside that square
 * are taken over (so 2x2 plus more blocks grows into a 3x3), groups that would be cut in two are left alone.
 */
public final class ScreenGroups {
    /** Forms the biggest possible group around the screen at this position (server side). */
    public static void merge(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ScreenBlock) || !(level.getBlockEntity(pos) instanceof ScreenBlockEntity)) {
            return;
        }
        Direction facing = state.getValue(ScreenBlock.FACING);
        Direction right = ScreenBlockEntity.rightOf(facing);
        for (int n = ScreenTerminal.MAX_BLOCKS; n >= 2; n--) {
            for (int row = 0; row < n; row++) {
                for (int col = 0; col < n; col++) {
                    // pos sits in column `col` and row `row` of this window; this is the window's top left block.
                    BlockPos origin = pos.relative(right, -col).relative(Direction.UP, row);
                    if (fits(level, origin, facing, right, n)) {
                        form(level, origin, right, n);
                        return;
                    }
                }
            }
        }
        // No bigger group: this block stays a screen of its own, with a blank terminal ready.
        if (level.getBlockEntity(pos) instanceof ScreenBlockEntity single && single.isOrigin()) {
            single.terminal();
        }
    }

    private static boolean fits(Level level, BlockPos origin, Direction facing, Direction right, int n) {
        for (int cy = 0; cy < n; cy++) {
            for (int cx = 0; cx < n; cx++) {
                BlockPos p = origin.relative(right, cx).relative(Direction.DOWN, cy);
                BlockState s = level.getBlockState(p);
                if (!(s.getBlock() instanceof ScreenBlock) || s.getValue(ScreenBlock.FACING) != facing
                        || !(level.getBlockEntity(p) instanceof ScreenBlockEntity be)) {
                    return false;
                }
                // The group this block already belongs to has to lie completely inside the window.
                int groupX = cx - be.xIndex();
                int groupY = cy - be.yIndex();
                if (groupX < 0 || groupY < 0 || groupX + be.groupWidth() > n || groupY + be.groupHeight() > n) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void form(Level level, BlockPos origin, Direction right, int n) {
        if (level.getBlockEntity(origin) instanceof ScreenBlockEntity first
                && first.isOrigin() && first.groupWidth() == n && first.groupHeight() == n) {
            return; // this square already is one screen
        }
        for (int cy = 0; cy < n; cy++) {
            for (int cx = 0; cx < n; cx++) {
                BlockPos p = origin.relative(right, cx).relative(Direction.DOWN, cy);
                if (level.getBlockEntity(p) instanceof ScreenBlockEntity be) {
                    be.setGrid(cx, cy, n, n);
                }
            }
        }
        if (level.getBlockEntity(origin) instanceof ScreenBlockEntity top) {
            top.terminal(); // creates a fresh blank terminal, so clients drop what they showed before
        }
    }

    private ScreenGroups() {
    }
}
