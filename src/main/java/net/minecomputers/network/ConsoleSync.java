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
 * The state of the script console of the Brain a laptop is connected to. Sent by the server in answer
 * to Run, Stop and the GUI's regular polls (only when something changed since the version the GUI has).
 */
public record ConsoleSync(BlockPos laptop, long version, boolean running, List<ConsoleLine> lines)
        implements CustomPacketPayload {

    public static final Type<ConsoleSync> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MineComputers.MOD_ID, "console_sync"));

    public static final StreamCodec<FriendlyByteBuf, ConsoleSync> STREAM_CODEC =
            StreamCodec.ofMember(ConsoleSync::write, ConsoleSync::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(laptop);
        buf.writeLong(version);
        buf.writeBoolean(running);
        buf.writeVarInt(lines.size());
        for (ConsoleLine line : lines) {
            buf.writeUtf(line.tag(), 64);
            buf.writeUtf(line.text(), 1024);
            buf.writeByte(line.level());
        }
    }

    private static ConsoleSync read(FriendlyByteBuf buf) {
        BlockPos laptop = buf.readBlockPos();
        long version = buf.readLong();
        boolean running = buf.readBoolean();
        int count = buf.readVarInt();
        List<ConsoleLine> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(new ConsoleLine(buf.readUtf(64), buf.readUtf(1024), buf.readByte()));
        }
        return new ConsoleSync(laptop, version, running, lines);
    }

    @Override
    public Type<ConsoleSync> type() {
        return TYPE;
    }
}
