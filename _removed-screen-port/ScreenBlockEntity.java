package net.minecomputers.block.entity;

import java.util.ArrayList;
import java.util.List;

import net.minecomputers.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a Screen block shows: the last few lines of text a script wrote with get("screen:1").log(...).
 * Saved with the world and synced to nearby clients, which draw it in ScreenBlockEntityRenderer.
 * (Single block for now; the multi-block 2x2 to 4x4 screens, pixels and video come later.)
 * Idea and sync approach taken from the Monitor of the first version of the mod.
 */
public class ScreenBlockEntity extends BlockEntity {
    public static final int MAX_LINES = 12;
    public static final int MAX_LINE_CHARS = 64;

    private final List<String> lines = new ArrayList<>();
    /** Many writes in one tick make one update packet: the ticker sends it. */
    private boolean syncPending;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SCREEN.get(), pos, state);
    }

    public List<String> lines() {
        return lines;
    }

    /** Adds text; a newline in it starts a new line. Keeps only the last MAX_LINES lines. */
    public void addLine(String text) {
        String clean = text.replace('\r', ' ').replace('\t', ' ');
        for (String part : clean.split("\n", -1)) {
            lines.add(part.length() > MAX_LINE_CHARS ? part.substring(0, MAX_LINE_CHARS) : part);
        }
        while (lines.size() > MAX_LINES) {
            lines.remove(0);
        }
        changed();
    }

    public void clear() {
        if (!lines.isEmpty()) {
            lines.clear();
            changed();
        }
    }

    private void changed() {
        setChanged();
        syncPending = true;
    }

    /** Called every server tick by the block. */
    public void serverTick() {
        if (syncPending && level != null) {
            syncPending = false;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (String line : lines) {
            list.add(StringTag.valueOf(line));
        }
        tag.put("lines", list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        lines.clear();
        ListTag list = tag.getList("lines", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            lines.add(list.getString(i));
        }
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
