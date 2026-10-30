package net.minecomputers.screen;

import io.netty.buffer.Unpooled;
import net.minecomputers.block.entity.ScreenBlockEntity;
import net.minecomputers.network.ScreenUpdate;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sends Screen content to the clients, the way CC: Tweaked sends monitors: only the origin block of a screen
 * sends, the whole terminal goes in one packet, to the players tracking its chunk, only when it changed, and a
 * player who starts tracking the chunk gets the current content once. The server never sends frames of pixels
 * every tick: a screen that nobody changes costs nothing.
 */
public final class ScreenSync {
    private static ScreenUpdate packet(ScreenBlockEntity screen) {
        ScreenTerminal terminal = screen.terminal();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            terminal.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return new ScreenUpdate(screen.getBlockPos(), data);
        } finally {
            buf.release();
        }
    }

    /** Sends the screen's current content to everyone tracking its chunk. */
    public static void broadcast(ScreenBlockEntity screen) {
        if (!(screen.getLevel() instanceof ServerLevel level) || screen.terminal() == null) {
            return;
        }
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(screen.getBlockPos()), packet(screen));
    }

    /** A player just received this chunk: send them every screen in it that has something to show. */
    public static void onChunkSent(ChunkWatchEvent.Sent event) {
        ServerPlayer player = event.getPlayer();
        for (BlockEntity be : event.getChunk().getBlockEntities().values()) {
            if (be instanceof ScreenBlockEntity screen && screen.isOrigin() && screen.terminal() != null) {
                PacketDistributor.sendToPlayer(player, packet(screen));
            }
        }
    }

    private ScreenSync() {
    }
}
