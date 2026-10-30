package net.minecomputers.script;

import net.minecomputers.block.entity.ScreenBlockEntity;
import net.minecomputers.screen.ScreenTerminal;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * What scripts can do with a Screen. The JavaScript wrapper in BrainRuntime turns get("screen:1").write("hi")
 * into call(pos, "write", "hi"). Runs on the server thread. All coordinates are 0-based; (0, 0) is the top left.
 * Bad arguments throw IllegalArgumentException, which the script sees as an error with that message.
 */
final class ScreenApi {
    static Object call(GlobalPos device, String op, Object a, Object b, Object c, Object d, Object e) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = server == null ? null : server.getLevel(device.dimension());
        if (level == null || !level.isLoaded(device.pos())) {
            throw new IllegalStateException("The screen is not loaded");
        }
        ScreenBlockEntity origin = ScreenBlockEntity.originAt(level, device.pos());
        ScreenTerminal t = origin == null ? null : origin.terminal();
        if (t == null) {
            throw new IllegalStateException("This screen is gone or was rebuilt: run the script again");
        }
        switch (op) {
            case "write" -> t.write(text(a));
            case "print" -> t.print(text(a));
            case "cursor" -> t.setCursor(num(a), num(b));
            case "curx" -> {
                return t.cursorX();
            }
            case "cury" -> {
                return t.cursorY();
            }
            case "fg" -> t.setTextColor(num(a));
            case "getfg" -> {
                return t.textColor();
            }
            case "bg" -> t.setBackgroundColor(num(a));
            case "getbg" -> {
                return t.backgroundColor();
            }
            case "clear" -> t.clear();
            case "cleartext" -> t.clearText();
            case "clearpixels" -> t.clearPixels();
            case "clearline" -> t.clearLine();
            case "scroll" -> t.scroll(num(a));
            case "cols" -> {
                return t.cols();
            }
            case "rows" -> {
                return t.rows();
            }
            case "scale" -> t.setTextScale(dbl(a));
            case "getscale" -> {
                return t.textScale();
            }
            case "palette" -> t.setPaletteColor(num(a), num(b));
            case "pixel" -> t.setPixel(num(a), num(b), num(c));
            case "rect" -> t.fillRect(num(a), num(b), num(c), num(d), num(e));
            case "line" -> t.drawLine(num(a), num(b), num(c), num(d), num(e));
            case "pw" -> {
                return t.pixelW();
            }
            case "ph" -> {
                return t.pixelH();
            }
            default -> throw new IllegalArgumentException("Unknown screen operation: " + op);
        }
        return null;
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static double dbl(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException("A number is required");
    }

    private static int num(Object o) {
        double value = dbl(o);
        if (Double.isNaN(value)) {
            return 0;
        }
        return (int) Math.max(-1.0e9, Math.min(1.0e9, value));
    }

    private ScreenApi() {
    }
}
