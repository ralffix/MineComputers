package net.minecomputers.network;

import java.util.regex.Pattern;

/** Rules for file and folder paths inside a Brain, and size limits. Used by both client and server. */
public final class PathRules {
    public static final int MAX_PATH = 128;
    public static final int MAX_DEPTH = 6;
    public static final int MAX_FILES = 64;
    public static final int MAX_FOLDERS = 64;
    /**
     * Largest file the laptop can save. The spec suggests 64 KB, but a serverbound custom payload
     * is capped at 32767 bytes by vanilla, so saves stay under that until chunked upload exists.
     */
    public static final int MAX_FILE_BYTES = 30000;
    /** Total size of all files in one Brain (spec: 512 KB). */
    public static final int MAX_TOTAL_BYTES = 512 * 1024;

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]{1,48}");

    /** A path like "main.js" or "lib/servo.js": 1 to 6 segments of letters, digits, '_', '-' and '.'. */
    public static boolean isValid(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_PATH) {
            return false;
        }
        String[] parts = path.split("/", -1);
        if (parts.length > MAX_DEPTH) {
            return false;
        }
        for (String part : parts) {
            if (!SEGMENT.matcher(part).matches() || part.chars().allMatch(c -> c == '.')) {
                return false;
            }
        }
        return true;
    }

    /** "lib/servo.js" gives "lib"; "main.js" gives "" (the top level). */
    public static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** "lib/servo.js" gives "servo.js". */
    public static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private PathRules() {
    }
}
