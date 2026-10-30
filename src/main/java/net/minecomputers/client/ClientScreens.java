package net.minecomputers.client;

import io.netty.buffer.Unpooled;
import net.minecomputers.block.entity.ScreenBlockEntity;
import net.minecomputers.network.ConsoleSync;
import net.minecomputers.network.LaptopNotice;
import net.minecomputers.network.LaptopSnapshot;
import net.minecomputers.network.ScreenUpdate;
import net.minecomputers.screen.ScreenTerminal;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;

/** Client-only entry points for GUIs. Only called from client-side packet handlers. */
public final class ClientScreens {
    /**
     * A snapshot arrived from the server. If the laptop GUI for that laptop is open, it is updated.
     * Otherwise it opens the GUI, but only when the player just right-clicked (open = true), so a late
     * answer to a save can never pop the GUI open again after it was closed.
     */
    public static void onSnapshot(LaptopSnapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof LaptopScreen screen) {
            if (screen.isFor(snapshot.laptop())) {
                screen.update(snapshot);
            }
        } else if (snapshot.open()) {
            minecraft.setScreen(new LaptopScreen(snapshot));
        }
    }

    /** The script console of the Brain changed. Only matters if the laptop GUI for that laptop is open. */
    public static void onConsole(ConsoleSync sync) {
        if (Minecraft.getInstance().screen instanceof LaptopScreen screen && screen.isFor(sync.laptop())) {
            screen.updateConsole(sync);
        }
    }

    /** A short message from the server (for example the result of an upload) for the open laptop GUI. */
    public static void onNotice(LaptopNotice notice) {
        if (Minecraft.getInstance().screen instanceof LaptopScreen screen && screen.isFor(notice.laptop())) {
            screen.showNotice(notice);
        }
    }

    /** The content of a Screen in the world changed: hand it to the block entity, which the renderer reads. */
    public static void onScreenUpdate(ScreenUpdate update) {
        Level level = Minecraft.getInstance().level;
        if (level == null || !(level.getBlockEntity(update.pos()) instanceof ScreenBlockEntity screen)) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(update.data()));
        try {
            screen.setClientTerminal(ScreenTerminal.read(buf));
        } catch (RuntimeException e) {
            screen.setClientTerminal(null); // data that makes no sense: show nothing rather than garbage
        } finally {
            buf.release();
        }
    }

    private ClientScreens() {
    }
}
