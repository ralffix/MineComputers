package net.minecomputers.screen;

import java.util.Arrays;

import net.minecraft.network.FriendlyByteBuf;

/**
 * What a Screen shows, the way CC: Tweaked monitors do it: a grid of colored characters (the "terminal"),
 * plus a small pixel layer drawn between the background and the text.
 *
 * The same class lives on the server (scripts change it) and on the client (it is rebuilt from a packet
 * and drawn). Coordinates are 0-based: (0, 0) is the top left. Colors are numbers 0 to 15 into a palette
 * of 16 colors (the CC palette by default; each entry can be changed).
 *
 * Size: a screen of W x H blocks has about (W * 60) / (6 * scale) columns and (H * 60) / (9 * scale) rows,
 * like CC (a character cell is 6 x 9 font pixels at scale 1). Text scale is 0.5 to 5 in steps of 0.5; a
 * bigger scale means fewer, bigger characters. The pixel layer has 128 x 128 pixels per block (512 x 512 for a 4x4 screen).
 */
public final class ScreenTerminal {
    public static final int CELL_W = 6;
    public static final int CELL_H = 9;
    /** Font pixels that fit across one block at scale 1. */
    public static final int AREA_PIXELS = 60;
    /** Pixels of the pixel layer per block side. */
    public static final int BLOCK_PIXELS = 128;
    public static final int COLORS = 16;
    /** Pixel value for "nothing here, show the background". */
    public static final byte CLEAR = -1;
    public static final int MAX_BLOCKS = 4;
    /** Text longer than this in one call is cut (a script can't flood the screen in one go). */
    public static final int MAX_TEXT_PER_CALL = 4096;

    /** The CC: Tweaked default colors: white, orange, magenta, light blue, yellow, lime, pink, gray, light gray, cyan, purple, blue, brown, green, red, black. */
    public static final int[] DEFAULT_PALETTE = {
            0xF0F0F0, 0xF2B233, 0xE57FD8, 0x99B2F2, 0xDEDE6C, 0x7FCC19, 0xF2B2CC, 0x4C4C4C,
            0x999999, 0x4C99B2, 0xB266E5, 0x3366CC, 0x7F664C, 0x57A64E, 0xCC4C4C, 0x111111};

    private final int blocksW;
    private final int blocksH;
    private final int pixelW;
    private final int pixelH;
    private final int[] palette = DEFAULT_PALETTE.clone();

    private int scale2 = 2; // text scale times two, so 1.0 is stored as 2
    private int cols;
    private int rows;
    private char[] chars;
    private byte[] fg;
    private byte[] bg;
    private int cursorX;
    private int cursorY;
    private int textColor = 0;
    private int backColor = 15;
    private byte[] pixels; // null until something is drawn
    private boolean dirty = true;

    public ScreenTerminal(int blocksW, int blocksH) {
        this.blocksW = Math.max(1, Math.min(MAX_BLOCKS, blocksW));
        this.blocksH = Math.max(1, Math.min(MAX_BLOCKS, blocksH));
        this.pixelW = this.blocksW * BLOCK_PIXELS;
        this.pixelH = this.blocksH * BLOCK_PIXELS;
        layout();
    }

    // ------------------------------------------------------------ geometry

    private void layout() {
        double scale = scale2 / 2.0;
        cols = Math.max(1, (int) Math.round(blocksW * AREA_PIXELS / (CELL_W * scale)));
        rows = Math.max(1, (int) Math.round(blocksH * AREA_PIXELS / (CELL_H * scale)));
        chars = new char[cols * rows];
        fg = new byte[cols * rows];
        bg = new byte[cols * rows];
        cursorX = 0;
        cursorY = 0;
        clearText();
    }

    public int blocksW() {
        return blocksW;
    }

    public int blocksH() {
        return blocksH;
    }

    public int cols() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    public int pixelW() {
        return pixelW;
    }

    public int pixelH() {
        return pixelH;
    }

    public double textScale() {
        return scale2 / 2.0;
    }

    /** Changes the text scale (0.5 to 5, steps of 0.5). The text is cleared because the grid changes. */
    public void setTextScale(double scale) {
        if (Double.isNaN(scale) || scale < 0.5 || scale > 5.0) {
            throw new IllegalArgumentException("Text scale must be between 0.5 and 5");
        }
        int next = (int) (scale * 2.0);
        if (next == scale2) {
            return;
        }
        scale2 = next;
        layout();
        dirty = true;
    }

    // --------------------------------------------------------- read access

    public char charAt(int index) {
        return chars[index];
    }

    public int fgAt(int index) {
        return fg[index];
    }

    public int bgAt(int index) {
        return bg[index];
    }

    /** The color (0xRRGGBB) of palette entry i. */
    public int color(int i) {
        return palette[i & 15];
    }

    /** The pixel layer (pixelW * pixelH values, -1 or 0..15), or null if nothing was drawn. */
    public byte[] pixels() {
        return pixels;
    }

    public int cursorX() {
        return cursorX;
    }

    public int cursorY() {
        return cursorY;
    }

    public int textColor() {
        return textColor;
    }

    public int backgroundColor() {
        return backColor;
    }

    /** True if something changed since the last call; clears the flag. Used by the server to send updates. */
    public boolean takeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    public void markDirty() {
        dirty = true;
    }

    // ------------------------------------------------------------ text ops

    private static byte checkColor(int color) {
        if (color < 0 || color >= COLORS) {
            throw new IllegalArgumentException("Color must be a number from 0 to 15");
        }
        return (byte) color;
    }

    public void setTextColor(int color) {
        textColor = checkColor(color);
    }

    public void setBackgroundColor(int color) {
        backColor = checkColor(color);
    }

    public void setPaletteColor(int index, int rgb) {
        checkColor(index);
        palette[index] = rgb & 0xFFFFFF;
        dirty = true;
    }

    public void setCursor(int x, int y) {
        cursorX = Math.max(-100000, Math.min(100000, x));
        cursorY = Math.max(-100000, Math.min(100000, y));
    }

    private void put(int x, int y, char c) {
        if (x >= 0 && x < cols && y >= 0 && y < rows) {
            int i = y * cols + x;
            chars[i] = c;
            fg[i] = (byte) textColor;
            bg[i] = (byte) backColor;
        }
    }

    private static char clean(char c) {
        return c < 32 || c == 127 ? ' ' : c;
    }

    /** Writes text at the cursor in the current colors and moves the cursor. No wrapping, like CC. */
    public void write(String text) {
        int n = Math.min(text.length(), MAX_TEXT_PER_CALL);
        for (int i = 0; i < n; i++) {
            put(cursorX, cursorY, clean(text.charAt(i)));
            cursorX++;
        }
        dirty = true;
    }

    /** Writes text with wrapping and newlines, then moves to the start of the next line (scrolling at the bottom). */
    public void print(String text) {
        int n = Math.min(text.length(), MAX_TEXT_PER_CALL);
        cursorY = Math.max(0, Math.min(rows - 1, cursorY));
        cursorX = Math.max(0, cursorX);
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                newLine();
                continue;
            }
            if (cursorX >= cols) {
                newLine();
            }
            put(cursorX, cursorY, clean(c));
            cursorX++;
        }
        newLine();
        dirty = true;
    }

    private void newLine() {
        cursorX = 0;
        if (cursorY >= rows - 1) {
            scroll(1);
            cursorY = rows - 1;
        } else {
            cursorY++;
        }
    }

    private void fillCells(int from, int to) {
        for (int i = from; i < to; i++) {
            chars[i] = ' ';
            fg[i] = (byte) textColor;
            bg[i] = (byte) backColor;
        }
    }

    public void clearText() {
        fillCells(0, chars.length);
        dirty = true;
    }

    public void clearPixels() {
        if (pixels != null) {
            pixels = null;
            dirty = true;
        }
    }

    /** Clears the text and the pixels. */
    public void clear() {
        clearText();
        clearPixels();
    }

    public void clearLine() {
        if (cursorY >= 0 && cursorY < rows) {
            fillCells(cursorY * cols, (cursorY + 1) * cols);
            dirty = true;
        }
    }

    /** Moves the text up by n lines (down for a negative n). New lines are blank in the current background. */
    public void scroll(int n) {
        if (n == 0) {
            return;
        }
        if (Math.abs(n) >= rows) {
            clearText();
            return;
        }
        int shift = Math.abs(n) * cols;
        int total = cols * rows;
        if (n > 0) {
            System.arraycopy(chars, shift, chars, 0, total - shift);
            System.arraycopy(fg, shift, fg, 0, total - shift);
            System.arraycopy(bg, shift, bg, 0, total - shift);
            fillCells(total - shift, total);
        } else {
            System.arraycopy(chars, 0, chars, shift, total - shift);
            System.arraycopy(fg, 0, fg, shift, total - shift);
            System.arraycopy(bg, 0, bg, shift, total - shift);
            fillCells(0, shift);
        }
        dirty = true;
    }

    // ----------------------------------------------------------- pixel ops

    private static byte checkPixelColor(int color) {
        if (color < 0) {
            return CLEAR;
        }
        return checkColor(color);
    }

    private void ensurePixels() {
        if (pixels == null) {
            pixels = new byte[pixelW * pixelH];
            Arrays.fill(pixels, CLEAR);
        }
    }

    /** Sets one pixel. A negative color erases it. Outside the screen is ignored. */
    public void setPixel(int x, int y, int color) {
        byte value = checkPixelColor(color);
        if (x < 0 || y < 0 || x >= pixelW || y >= pixelH) {
            return;
        }
        if (pixels == null && value == CLEAR) {
            return;
        }
        ensurePixels();
        pixels[y * pixelW + x] = value;
        dirty = true;
    }

    public void fillRect(int x, int y, int w, int h, int color) {
        byte value = checkPixelColor(color);
        long x1 = Math.max(0L, (long) x);
        long y1 = Math.max(0L, (long) y);
        long x2 = Math.min((long) pixelW, (long) x + w);
        long y2 = Math.min((long) pixelH, (long) y + h);
        if (x1 >= x2 || y1 >= y2 || (pixels == null && value == CLEAR)) {
            return;
        }
        ensurePixels();
        for (int py = (int) y1; py < (int) y2; py++) {
            Arrays.fill(pixels, py * pixelW + (int) x1, py * pixelW + (int) x2, value);
        }
        dirty = true;
    }

    /** A straight line of pixels (Bresenham). */
    public void drawLine(int x0, int y0, int x1, int y1, int color) {
        byte value = checkPixelColor(color);
        if (pixels == null && value == CLEAR) {
            return;
        }
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        int guard = (pixelW + pixelH) * 2 + 8; // stops a huge line from running for a long time
        ensurePixels();
        while (guard-- > 0) {
            if (x >= 0 && y >= 0 && x < pixelW && y < pixelH) {
                pixels[y * pixelW + x] = value;
            }
            if (x == x1 && y == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
        dirty = true;
    }

    // -------------------------------------------------------- network form

    public void write(FriendlyByteBuf buf) {
        buf.writeByte(blocksW);
        buf.writeByte(blocksH);
        buf.writeByte(scale2);
        for (int i = 0; i < COLORS; i++) {
            buf.writeInt(palette[i]);
        }
        for (int i = 0; i < chars.length; i++) {
            buf.writeChar(chars[i]);
            buf.writeByte((fg[i] << 4) | bg[i]);
        }
        buf.writeBoolean(pixels != null);
        if (pixels != null) {
            // Run-length encoded: (value, run length) pairs.
            int i = 0;
            while (i < pixels.length) {
                byte value = pixels[i];
                int run = 1;
                while (i + run < pixels.length && pixels[i + run] == value) {
                    run++;
                }
                buf.writeByte(value);
                buf.writeVarInt(run);
                i += run;
            }
        }
    }

    /** Reads a terminal from a packet. Throws IllegalArgumentException for data that makes no sense. */
    public static ScreenTerminal read(FriendlyByteBuf buf) {
        int w = buf.readByte();
        int h = buf.readByte();
        int scale = buf.readByte();
        if (w < 1 || w > MAX_BLOCKS || h < 1 || h > MAX_BLOCKS || scale < 1 || scale > 10) {
            throw new IllegalArgumentException("Bad screen data");
        }
        ScreenTerminal t = new ScreenTerminal(w, h);
        if (scale != t.scale2) {
            t.scale2 = scale;
            t.layout();
        }
        for (int i = 0; i < COLORS; i++) {
            t.palette[i] = buf.readInt() & 0xFFFFFF;
        }
        for (int i = 0; i < t.chars.length; i++) {
            t.chars[i] = buf.readChar();
            int packed = buf.readByte();
            t.fg[i] = (byte) ((packed >> 4) & 15);
            t.bg[i] = (byte) (packed & 15);
        }
        if (buf.readBoolean()) {
            int total = t.pixelW * t.pixelH;
            t.pixels = new byte[total];
            int i = 0;
            while (i < total) {
                byte value = buf.readByte();
                int run = buf.readVarInt();
                if (run < 1 || run > total - i || value < -1 || value > 15) {
                    throw new IllegalArgumentException("Bad screen pixel data");
                }
                Arrays.fill(t.pixels, i, i + run, value);
                i += run;
            }
        }
        t.dirty = false;
        return t;
    }
}
