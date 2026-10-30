package net.minecomputers.network;

import net.minecomputers.MineComputers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A short message from the server for the open laptop GUI (for example the result of an upload). */
public record LaptopNotice(BlockPos laptop, String text, boolean error) implements CustomPacketPayload {
    public static final Type<LaptopNotice> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MineComputers.MOD_ID, "laptop_notice"));

    public static final StreamCodec<FriendlyByteBuf, LaptopNotice> STREAM_CODEC =
            StreamCodec.ofMember(LaptopNotice::write, LaptopNotice::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(laptop);
        buf.writeUtf(text, 256);
        buf.writeBoolean(error);
    }

    private static LaptopNotice read(FriendlyByteBuf buf) {
        return new LaptopNotice(buf.readBlockPos(), buf.readUtf(256), buf.readBoolean());
    }

    @Override
    public Type<LaptopNotice> type() {
        return TYPE;
    }
}
