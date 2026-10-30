package net.minecomputers.block.entity;

import net.minecomputers.block.ScreenBlock;
import net.minecomputers.registry.ModBlockEntities;
import net.minecomputers.screen.ScreenSync;
import net.minecomputers.screen.ScreenTerminal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One block of a Screen. Screens are multi-block (1x1 up to 4x4): every block knows its place in the group
 * (xIndex to the right, yIndex downwards, seen from the front) and the group's size. The block at (0, 0), the
 * top left one, is the origin and owns the terminal: that is what scripts draw on and what the client renders.
 * The group layout is saved with the world; the terminal's content is not (like CC monitors, it starts blank
 * after a restart, because the script that filled it is not running any more).
 */
public class ScreenBlockEntity extends BlockEntity {
    private int xIndex;
    private int yIndex;
    private int width = 1;
    private int height = 1;

    /** Server: what scripts draw on (origin only). Client: what the last packet said. Null until there is something. */
    private ScreenTerminal terminal;
    private long lastSent = Long.MIN_VALUE / 2;

    /** Client only: something the renderer created for this block (a texture) that is released with the block. */
    private AutoCloseable renderResource;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SCREEN.get(), pos, state);
    }

    // ---------------------------------------------------------------- group

    public int xIndex() {
        return xIndex;
    }

    public int yIndex() {
        return yIndex;
    }

    public int groupWidth() {
        return width;
    }

    public int groupHeight() {
        return height;
    }

    public boolean isOrigin() {
        return xIndex == 0 && yIndex == 0;
    }

    /** True if this block is a screen of its own (not joined with others). */
    public boolean isSingle() {
        return width == 1 && height == 1;
    }

    /** Sets this block's place in a group. Any change throws away the old terminal (the group is a new screen). */
    public void setGrid(int x, int y, int w, int h) {
        if (x == xIndex && y == yIndex && w == width && h == height) {
            return;
        }
        terminal = null;
        xIndex = x;
        yIndex = y;
        width = w;
        height = h;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    private Direction facing() {
        return getBlockState().getValue(ScreenBlock.FACING);
    }

    /** The direction to the viewer's right when looking at the front of the screen. */
    public static Direction rightOf(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** The position of the origin block (top left) of this block's group. */
    public BlockPos originPos() {
        return worldPosition.relative(rightOf(facing()), -xIndex).relative(Direction.UP, yIndex);
    }

    /** The positions of all blocks of this group (call on the origin). */
    public java.util.List<BlockPos> memberPositions() {
        java.util.List<BlockPos> members = new java.util.ArrayList<>();
        Direction right = rightOf(facing());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                members.add(worldPosition.relative(right, x).relative(Direction.DOWN, y));
            }
        }
        return members;
    }

    /** The origin block entity of the group that contains this position, or null. */
    public static ScreenBlockEntity originAt(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ScreenBlockEntity here)
                || !(level.getBlockState(pos).getBlock() instanceof ScreenBlock)) {
            return null;
        }
        if (here.isOrigin()) {
            return here;
        }
        return level.getBlockEntity(here.originPos()) instanceof ScreenBlockEntity origin ? origin : null;
    }

    // ------------------------------------------------------------- terminal

    /**
     * The terminal of this group (origin only). On the server it is created when first needed.
     * Returns null on a block that is not the origin, or on the client before the first packet.
     */
    public ScreenTerminal terminal() {
        if (!isOrigin()) {
            return null;
        }
        if (terminal == null && level != null && !level.isClientSide) {
            terminal = new ScreenTerminal(width, height);
        }
        return terminal;
    }

    /** Client: the terminal a packet delivered. */
    public void setClientTerminal(ScreenTerminal next) {
        terminal = next;
    }

    public ScreenTerminal clientTerminal() {
        return terminal;
    }

    public AutoCloseable renderResource() {
        return renderResource;
    }

    public void setRenderResource(AutoCloseable resource) {
        renderResource = resource;
    }

    /** Called every server tick by the block: sends a changed terminal to the players that can see it. */
    public void serverTick() {
        if (level == null || level.isClientSide || terminal == null || !isOrigin()) {
            return;
        }
        long now = level.getGameTime();
        // At most one update every 2 ticks (10 per second), so a script that draws all the time is not a flood.
        if (now - lastSent >= 2 && terminal.takeDirty()) {
            lastSent = now;
            ScreenSync.broadcast(this);
        }
    }

    private void releaseRenderResource() {
        if (renderResource != null) {
            try {
                renderResource.close();
            } catch (Exception ignored) {
                // Nothing more to do.
            }
            renderResource = null;
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        releaseRenderResource();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        releaseRenderResource();
    }

    // ----------------------------------------------------------------- save

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // Not "x" and "y": Minecraft writes the block's world position under those names when it saves a block
        // entity, which overwrote the group position and made every screen a single block after a relog.
        tag.putInt("grid_x", xIndex);
        tag.putInt("grid_y", yIndex);
        tag.putInt("grid_w", width);
        tag.putInt("grid_h", height);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        int w = tag.contains("grid_w") ? tag.getInt("grid_w") : 1;
        int h = tag.contains("grid_h") ? tag.getInt("grid_h") : 1;
        int x = tag.getInt("grid_x");
        int y = tag.getInt("grid_y");
        boolean valid = w >= 1 && w <= ScreenTerminal.MAX_BLOCKS && h >= 1 && h <= ScreenTerminal.MAX_BLOCKS
                && x >= 0 && x < w && y >= 0 && y < h;
        if (!valid) {
            w = 1;
            h = 1;
            x = 0;
            y = 0;
        }
        if (w != width || h != height) {
            terminal = null;
        }
        xIndex = x;
        yIndex = y;
        width = w;
        height = h;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
