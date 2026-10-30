package net.minecomputers.network;

import java.util.Locale;
import java.util.Set;

/** Which dragged-in files the laptop accepts, and the size limits. Used by both client and server. */
public final class MediaRules {
    public static final int NONE = 0;
    public static final int IMAGE = 1;
    public static final int AUDIO = 2;
    public static final int VIDEO = 3;

    /** Bytes per upload packet. Vanilla caps a serverbound custom payload at 32767 bytes. */
    public static final int CHUNK_BYTES = 30000;
    public static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    public static final int MAX_AUDIO_BYTES = 8 * 1024 * 1024;
    public static final int MAX_VIDEO_BYTES = 32 * 1024 * 1024;
    /** All media of one Brain together. */
    public static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    public static final int MAX_FILES = 32;

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("ogg", "mp3", "wav", "flac", "m4a", "opus");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "webm", "mkv");

    /** IMAGE, AUDIO or VIDEO from the file extension, or NONE if the type is not supported. */
    public static int kindOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return NONE;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return IMAGE;
        }
        if (AUDIO_EXTENSIONS.contains(extension)) {
            return AUDIO;
        }
        if (VIDEO_EXTENSIONS.contains(extension)) {
            return VIDEO;
        }
        return NONE;
    }

    public static int maxBytes(int kind) {
        return switch (kind) {
            case IMAGE -> MAX_IMAGE_BYTES;
            case AUDIO -> MAX_AUDIO_BYTES;
            case VIDEO -> MAX_VIDEO_BYTES;
            default -> 0;
        };
    }

    /** Turns any file name into one that passes {@link PathRules}: letters, digits, '_', '-', '.', at most 48 chars. */
    public static String sanitizeName(String fileName) {
        String clean = fileName.replaceAll("[^A-Za-z0-9_.-]", "_");
        while (clean.startsWith(".")) {
            clean = clean.substring(1);
        }
        int dot = clean.lastIndexOf('.');
        String base = dot > 0 ? clean.substring(0, dot) : clean;
        String extension = dot > 0 ? clean.substring(dot).toLowerCase(Locale.ROOT) : "";
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        if (base.isEmpty()) {
            base = "file";
        }
        if (extension.length() > 8) {
            extension = extension.substring(0, 8);
        }
        return base + extension;
    }

    private MediaRules() {
    }
}
