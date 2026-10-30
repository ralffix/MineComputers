package net.minecomputers.network;

import net.minecomputers.MineComputers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * One chunk of a file dragged into the laptop. A file is sent as chunks 0, 1, 2, ... in order; chunk 0
 * starts the upload (the server checks the name, type and size then). The server checks everything again.
 */
public record LaptopUpload(BlockPos laptop, String path, int totalBytes, int index, byte[] data)
        implements CustomPacketPayload {

    public static final Type<LaptopUpload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MineComputers.MOD_ID, "laptop_upload"));

    public static final StreamCodec<FriendlyByteBuf, LaptopUpload> STREAM_CODEC =
            StreamCodec.ofMember(LaptopUpload::write, LaptopUpload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(laptop);
        buf.writeUtf(path, 256);
        buf.writeVarInt(totalBytes);
        buf.writeVarInt(index);
        buf.writeByteArray(data);
    }

    private static LaptopUpload read(FriendlyByteBuf buf) {
        return new LaptopUpload(buf.readBlockPos(), buf.readUtf(256), buf.readVarInt(), buf.readVarInt(),
                buf.readByteArray(MediaRules.CHUNK_BYTES));
    }

    @Override
    public Type<LaptopUpload> type() {
        return TYPE;
    }
}
