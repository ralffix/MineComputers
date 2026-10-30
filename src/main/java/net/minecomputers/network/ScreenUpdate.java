package net.minecomputers.network;

import net.minecomputers.MineComputers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The whole content of one Screen (see ScreenTerminal), sent to the players that can see it whenever it
 * changed. "pos" is the origin block (top left) of the screen; "data" is the terminal in its network form.
 */
public record ScreenUpdate(BlockPos pos, byte[] data) implements CustomPacketPayload {
    public static final Type<ScreenUpdate> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MineComputers.MOD_ID, "screen_update"));

    public static final StreamCodec<FriendlyByteBuf, ScreenUpdate> STREAM_CODEC =
            StreamCodec.ofMember(ScreenUpdate::write, ScreenUpdate::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeByteArray(data);
    }

    private static ScreenUpdate read(FriendlyByteBuf buf) {
        return new ScreenUpdate(buf.readBlockPos(), buf.readByteArray(1 << 20));
    }

    @Override
    public Type<ScreenUpdate> type() {
        return TYPE;
    }
}
