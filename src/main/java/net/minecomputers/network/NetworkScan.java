package net.minecomputers.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecomputers.block.entity.BrainBlockEntity;
import net.minecomputers.block.entity.ScreenBlockEntity;
import net.minecomputers.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Finds what a laptop is connected to. Data travels through Data Cables and Power Data Cables
 * (a plain Power Cable carries FE only). A device is connected when it touches the Brain or a data cable.
 * Server side only.
 */
public final class NetworkScan {
    /** Safety cap so a huge cable network can't lag the server. */
    private static final int MAX_BLOCKS = 1024;

    private record Scan(BlockPos brainPos, List<BlockPos> devices) {
    }

    private static Scan scan(Level level, BlockPos laptop) {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> devicePositions = new ArrayList<>();
        BlockPos brainPos = null;

        visited.add(laptop);
        queue.add(laptop);
        while (!queue.isEmpty() && visited.size() < MAX_BLOCKS) {
            BlockPos current = queue.poll();
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (!level.isLoaded(next) || !visited.add(next)) {
                    continue;
                }
                Block block = level.getBlockState(next).getBlock();
                if (block == ModBlocks.BRAIN.get()) {
                    if (brainPos == null) {
                        brainPos = next;
                    }
                    queue.add(next);
                } else if (block == ModBlocks.DATA_CABLE.get() || block == ModBlocks.POWER_DATA_CABLE.get()) {
                    queue.add(next);
                } else if (deviceType(block) != null) {
                    // A multi-block Screen is one device: it is listed once, by its origin block (top left).
                    BlockPos devicePos = next;
                    if (block == ModBlocks.SCREEN.get()) {
                        ScreenBlockEntity origin = ScreenBlockEntity.originAt(level, next);
                        if (origin != null) {
                            devicePos = origin.getBlockPos();
                        }
                    }
                    if (!devicePositions.contains(devicePos)) {
                        devicePositions.add(devicePos);
                    }
                }
            }
        }
        return new Scan(brainPos, devicePositions);
    }

    /** The Brain this laptop is connected to, or null if it is not connected to one. */
    public static BrainBlockEntity findBrain(Level level, BlockPos laptop) {
        Scan scan = scan(level, laptop);
        if (scan.brainPos() != null && level.getBlockEntity(scan.brainPos()) instanceof BrainBlockEntity brain) {
            return brain;
        }
        return null;
    }

    /** Builds what the laptop at this position should show. */
    public static LaptopSnapshot snapshot(Level level, BlockPos laptop, boolean open) {
        Scan scan = scan(level, laptop);
        if (scan.brainPos() == null || !(level.getBlockEntity(scan.brainPos()) instanceof BrainBlockEntity brain)) {
            return LaptopSnapshot.disconnected(laptop, open);
        }
        BlockPos brainPos = scan.brainPos();

        List<LaptopSnapshot.FileEntry> files = new ArrayList<>();
        brain.files().forEach((path, content) -> files.add(new LaptopSnapshot.FileEntry(path, content)));

        // Sorted by position so IDs are handed out in a stable order the first time.
        List<BlockPos> devicePositions = new ArrayList<>(scan.devices());
        devicePositions.sort(Comparator.comparingLong(BlockPos::asLong));
        List<LaptopSnapshot.DeviceEntry> devices = new ArrayList<>();
        devices.add(new LaptopSnapshot.DeviceEntry("brain", "brain:1", brainPos.getX(), brainPos.getY(), brainPos.getZ()));
        for (BlockPos pos : devicePositions) {
            String type = deviceType(level.getBlockState(pos).getBlock());
            String id;
            if ("screen".equals(type) && level.getBlockEntity(pos) instanceof ScreenBlockEntity screen) {
                id = brain.idForGroup(type, pos, screen.memberPositions()); // a bigger screen keeps its ID
            } else {
                id = brain.idFor(type, pos);
            }
            devices.add(new LaptopSnapshot.DeviceEntry(type, id, pos.getX(), pos.getY(), pos.getZ()));
        }
        List<LaptopSnapshot.MediaEntry> media = new ArrayList<>();
        brain.media().forEach((path, info) -> media.add(new LaptopSnapshot.MediaEntry(path, info.size())));
        return new LaptopSnapshot(laptop, open, true, new ArrayList<>(brain.folders()), files, media, devices);
    }

    /** The device type name used in IDs, or null if this block is not a device. */
    private static String deviceType(Block block) {
        if (block == ModBlocks.SERVO.get()) {
            return "servo";
        }
        if (block == ModBlocks.SCREEN.get()) {
            return "screen";
        }
        if (block == ModBlocks.SPEAKER.get()) {
            return "speaker";
        }
        if (block == ModBlocks.REDSTONE_LINK.get()) {
            return "redstone";
        }
        if (block == ModBlocks.REMOTE_GATEWAY.get()) {
            return "gateway";
        }
        return null;
    }

    private NetworkScan() {
    }
}
