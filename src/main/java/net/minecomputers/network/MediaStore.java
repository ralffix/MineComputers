package net.minecomputers.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Stores dragged-in media next to the world save, in minecomputers/media/, named by the SHA-256 of their
 * content (the same file added twice is stored once). The Brain only remembers the name and size.
 * Call {@link #put} off the server thread: it hashes and writes the whole file.
 */
public final class MediaStore {
    public static Path directory(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("minecomputers").resolve("media");
    }

    /** Stores the bytes and returns their id (hex SHA-256). */
    public static String put(MinecraftServer server, byte[] data) throws IOException {
        String id;
        try {
            id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        Path directory = directory(server);
        Files.createDirectories(directory);
        Path target = directory.resolve(id + ".bin");
        if (!Files.exists(target)) {
            Path temporary = directory.resolve(id + ".tmp");
            Files.write(temporary, data);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return id;
    }

    private MediaStore() {
    }
}
