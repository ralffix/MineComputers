package net.minecomputers.network;

import java.util.ArrayList;
import java.util.List;

import net.minecomputers.MineComputers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * What the laptop GUI shows, computed by the server and sent to the player who uses the laptop:
 * whether it is connected to a Brain, the Brain's folders, files and media, and the connected devices.
 * "open" is true when the player just right-clicked the laptop (open the GUI), false for an update
 * to a GUI that is already open.
 */
public record LaptopSnapshot(BlockPos laptop, boolean open, boolean connected, List<String> folders,
                             List<FileEntry> files, List<MediaEntry> media, List<DeviceEntry> devices)
        implements CustomPacketPayload {

    /** One text file of the Brain. */
    public record FileEntry(String path, String content) {
    }

    /** One dragged-in image, sound or video. Only its name and size are sent here. */
    public record MediaEntry(String path, int size) {
    }

    /** One connected device. The type is "brain", "screen", "servo", "speaker", "redstone" or "gateway". */
    public record DeviceEntry(String type, String id, int x, int y, int z) {
    }

    private static final int MAX_TEXT = 262144;

    public static final Type<LaptopSnapshot> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MineComputers.MOD_ID, "laptop_snapshot"));

    public static final StreamCodec<FriendlyByteBuf, LaptopSnapshot> STREAM_CODEC =
            StreamCodec.ofMember(LaptopSnapshot::write, LaptopSnapshot::read);

    public static LaptopSnapshot disconnected(BlockPos laptop, boolean open) {
        return new LaptopSnapshot(laptop, open, false, List.of(), List.of(), List.of(), List.of());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(laptop);
        buf.writeBoolean(open);
        buf.writeBoolean(connected);
        buf.writeVarInt(folders.size());
        for (String folder : folders) {
            buf.writeUtf(folder, 256);
        }
        buf.writeVarInt(files.size());
        for (FileEntry f : files) {
            buf.writeUtf(f.path(), 256);
            buf.writeUtf(f.content(), MAX_TEXT);
        }
        buf.writeVarInt(media.size());
        for (MediaEntry m : media) {
            buf.writeUtf(m.path(), 256);
            buf.writeVarInt(m.size());
        }
        buf.writeVarInt(devices.size());
        for (DeviceEntry d : devices) {
            buf.writeUtf(d.type(), 64);
            buf.writeUtf(d.id(), 64);
            buf.writeInt(d.x());
            buf.writeInt(d.y());
            buf.writeInt(d.z());
        }
    }

    private static LaptopSnapshot read(FriendlyByteBuf buf) {
        BlockPos laptop = buf.readBlockPos();
        boolean open = buf.readBoolean();
        boolean connected = buf.readBoolean();
        int folderCount = buf.readVarInt();
        List<String> folders = new ArrayList<>();
        for (int i = 0; i < folderCount; i++) {
            folders.add(buf.readUtf(256));
        }
        int fileCount = buf.readVarInt();
        List<FileEntry> files = new ArrayList<>();
        for (int i = 0; i < fileCount; i++) {
            files.add(new FileEntry(buf.readUtf(256), buf.readUtf(MAX_TEXT)));
        }
        int mediaCount = buf.readVarInt();
        List<MediaEntry> media = new ArrayList<>();
        for (int i = 0; i < mediaCount; i++) {
            media.add(new MediaEntry(buf.readUtf(256), buf.readVarInt()));
        }
        int deviceCount = buf.readVarInt();
        List<DeviceEntry> devices = new ArrayList<>();
        for (int i = 0; i < deviceCount; i++) {
            devices.add(new DeviceEntry(buf.readUtf(64), buf.readUtf(64), buf.readInt(), buf.readInt(), buf.readInt()));
        }
        return new LaptopSnapshot(laptop, open, connected, folders, files, media, devices);
    }

    @Override
    public Type<LaptopSnapshot> type() {
        return TYPE;
    }
}
