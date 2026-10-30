package net.minecomputers.network;

import net.minecomputers.MineComputers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A request from the laptop GUI to the server: create, rename or delete a file or folder, save a file, run or
 * stop the script, or ask for the console.
 * The server checks everything again; the client is never trusted.
 * (Vanilla caps a serverbound custom payload at 32767 bytes, so content stays under PathRules.MAX_FILE_BYTES.)
 */
public record LaptopAction(BlockPos laptop, int action, String path, String content) implements CustomPacketPayload {
    public static final int CREATE_FILE = 0;
    public static final int CREATE_FOLDER = 1;
    public static final int SAVE_FILE = 2;
    /** Run main.js of the connected Brain. */
    public static final int RUN = 3;
    /** Stop the script of the connected Brain. */
    public static final int STOP = 4;
    /** Ask for the console; content holds the console version the GUI already has. */
    public static final int POLL = 5;
    /** Rename or move a file, media file or folder: path is the old path, content is the new path. */
    public static final int RENAME = 6;
    /** Delete a file, media file or folder (with everything in it). */
    public static final int DELETE = 7;

    public static final Type<LaptopAction> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MineComputers.MOD_ID, "laptop_action"));

    public static final StreamCodec<FriendlyByteBuf, LaptopAction> STREAM_CODEC =
            StreamCodec.ofMember(LaptopAction::write, LaptopAction::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(laptop);
        buf.writeVarInt(action);
        buf.writeUtf(path, 256);
        buf.writeUtf(content, PathRules.MAX_FILE_BYTES);
    }

    private static LaptopAction read(FriendlyByteBuf buf) {
        return new LaptopAction(buf.readBlockPos(), buf.readVarInt(), buf.readUtf(256), buf.readUtf(PathRules.MAX_FILE_BYTES));
    }

    @Override
    public Type<LaptopAction> type() {
        return TYPE;
    }
}
