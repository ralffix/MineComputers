package net.minecomputers.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.logging.LogUtils;
import net.minecomputers.block.entity.BrainBlockEntity;
import net.minecomputers.registry.ModBlocks;
import net.minecomputers.script.BrainRuntime;
import net.minecomputers.script.ScriptManager;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/** Server-side handling of laptop requests. Everything is validated; the client is never trusted. */
public final class ServerHandlers {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** How far the player may be from the laptop (blocks, squared), so a GUI left open can't act from afar. */
    private static final double MAX_DISTANCE_SQR = 8.0 * 8.0;

    /** An upload in progress: one per player, held in memory until the last chunk arrived. */
    private static final class UploadSession {
        final BlockPos laptop;
        final String path;
        final int total;
        final ByteArrayOutputStream data;
        int nextIndex;

        UploadSession(BlockPos laptop, String path, int total) {
            this.laptop = laptop;
            this.path = path;
            this.total = total;
            this.data = new ByteArrayOutputStream(Math.min(total, 1 << 20));
        }
    }

    private static final Map<UUID, UploadSession> UPLOADS = new HashMap<>();

    public static void handleAction(LaptopAction action, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        Level level = player.level();
        BlockPos pos = action.laptop();
        if (!laptopUsable(player, level, pos)) {
            return;
        }
        BrainBlockEntity brain = NetworkScan.findBrain(level, pos);
        if (brain == null) {
            return;
        }
        GlobalPos key = GlobalPos.of(level.dimension(), brain.getBlockPos());

        switch (action.action()) {
            case LaptopAction.CREATE_FILE -> {
                brain.createFile(action.path());
                sendSnapshot(player, level, pos);
            }
            case LaptopAction.CREATE_FOLDER -> {
                brain.createFolder(action.path());
                sendSnapshot(player, level, pos);
            }
            case LaptopAction.SAVE_FILE -> {
                brain.saveFile(action.path(), action.content());
                sendSnapshot(player, level, pos);
            }
            case LaptopAction.RENAME -> {
                String problem = brain.renamePath(action.path(), action.content());
                if (problem != null) {
                    notice(player, pos, problem, true);
                }
                sendSnapshot(player, level, pos);
            }
            case LaptopAction.DELETE -> {
                brain.deletePath(action.path());
                sendSnapshot(player, level, pos);
            }
            case LaptopAction.RUN -> {
                Map<String, String> devices = new HashMap<>();
                Map<String, GlobalPos> positions = new HashMap<>();
                for (LaptopSnapshot.DeviceEntry device : NetworkScan.snapshot(level, pos, false).devices()) {
                    devices.put(device.id(), device.type());
                    positions.put(device.id(), GlobalPos.of(level.dimension(), new BlockPos(device.x(), device.y(), device.z())));
                }
                BrainRuntime runtime = ScriptManager.run(key, new HashMap<>(brain.files()), devices, positions);
                if (runtime == null) {
                    PacketDistributor.sendToPlayer(player, new ConsoleSync(pos, 0, false, List.of(new ConsoleLine(
                            BrainRuntime.BRAIN_TAG, "The JavaScript engine failed to load. See latest.log.", 2))));
                } else {
                    sendConsole(player, pos, key, -1);
                }
            }
            case LaptopAction.STOP -> {
                ScriptManager.stop(key);
                sendConsole(player, pos, key, -1);
            }
            case LaptopAction.POLL -> sendConsole(player, pos, key, parseVersion(action.content()));
            default -> {
            }
        }
    }

    /** One chunk of a dragged-in file. Chunk 0 starts the upload; the last chunk stores the file. */
    public static void handleUpload(LaptopUpload upload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        UUID id = player.getUUID();
        Level level = player.level();
        BlockPos pos = upload.laptop();
        BrainBlockEntity brain = laptopUsable(player, level, pos) ? NetworkScan.findBrain(level, pos) : null;
        if (brain == null) {
            UPLOADS.remove(id);
            return;
        }

        if (upload.index() == 0) {
            UPLOADS.remove(id);
            String problem = startProblem(brain, upload);
            if (problem != null) {
                notice(player, pos, problem, true);
                return;
            }
            UPLOADS.put(id, new UploadSession(pos, upload.path(), upload.totalBytes()));
        }

        UploadSession session = UPLOADS.get(id);
        byte[] chunk = upload.data();
        if (session == null) {
            return; // stray chunk of an upload that was already rejected
        }
        if (!session.laptop.equals(pos) || !session.path.equals(upload.path()) || upload.index() != session.nextIndex
                || chunk.length == 0 || session.data.size() + chunk.length > session.total) {
            UPLOADS.remove(id);
            notice(player, pos, "Upload failed: the data arrived out of order.", true);
            return;
        }
        session.data.write(chunk, 0, chunk.length);
        session.nextIndex++;
        if (session.data.size() == session.total) {
            UPLOADS.remove(id);
            finishUpload(player, level, pos, brain, session.path, session.data.toByteArray());
        }
    }

    /** A player who leaves loses an upload in progress. */
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UPLOADS.remove(event.getEntity().getUUID());
    }

    private static String startProblem(BrainBlockEntity brain, LaptopUpload upload) {
        String path = upload.path();
        if (!PathRules.isValid(path)) {
            return "Upload rejected: invalid file name.";
        }
        int kind = MediaRules.kindOf(path);
        if (kind == MediaRules.NONE) {
            return "Upload rejected: this file type is not supported.";
        }
        if (upload.totalBytes() <= 0 || upload.totalBytes() > MediaRules.maxBytes(kind)) {
            return "Upload rejected: the file is too big for its type.";
        }
        return brain.mediaProblem(path, upload.totalBytes());
    }

    /** Hashes and writes the file off the server thread, then adds it to the Brain back on the server thread. */
    private static void finishUpload(ServerPlayer player, Level level, BlockPos pos, BrainBlockEntity brain,
                                     String path, byte[] data) {
        MinecraftServer server = player.server;
        CompletableFuture.supplyAsync(() -> {
            try {
                return MediaStore.put(server, data);
            } catch (IOException e) {
                LOGGER.error("MineComputers: could not store an uploaded file", e);
                return null;
            }
        }, Util.ioPool()).thenAcceptAsync(hash -> {
            if (hash == null) {
                notice(player, pos, "Upload failed: the server could not store the file.", true);
                return;
            }
            if (brain.isRemoved()) {
                return;
            }
            String problem = brain.addMedia(path, hash, data.length);
            if (problem != null) {
                notice(player, pos, problem, true);
                return;
            }
            sendSnapshot(player, level, pos);
            notice(player, pos, "Uploaded " + path, false);
        }, server);
    }

    private static boolean laptopUsable(ServerPlayer player, Level level, BlockPos pos) {
        return level.isLoaded(pos)
                && level.getBlockState(pos).is(ModBlocks.LAPTOP.get())
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_DISTANCE_SQR;
    }

    private static void notice(ServerPlayer player, BlockPos laptop, String text, boolean error) {
        PacketDistributor.sendToPlayer(player, new LaptopNotice(laptop, text, error));
    }

    private static void sendSnapshot(ServerPlayer player, Level level, BlockPos laptop) {
        // Always answer with the real state, so the GUI shows what the server actually has.
        PacketDistributor.sendToPlayer(player, NetworkScan.snapshot(level, laptop, false));
    }

    /** Sends the console unless the GUI already has this version of it. */
    private static void sendConsole(ServerPlayer player, BlockPos laptop, GlobalPos key, long knownVersion) {
        BrainRuntime runtime = ScriptManager.get(key);
        if (runtime == null) {
            if (knownVersion != 0) {
                PacketDistributor.sendToPlayer(player, new ConsoleSync(laptop, 0, false, List.of()));
            }
            return;
        }
        if (runtime.version() != knownVersion) {
            PacketDistributor.sendToPlayer(player,
                    new ConsoleSync(laptop, runtime.version(), runtime.isRunning(), runtime.lines()));
        }
    }

    private static long parseVersion(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private ServerHandlers() {
    }
}
