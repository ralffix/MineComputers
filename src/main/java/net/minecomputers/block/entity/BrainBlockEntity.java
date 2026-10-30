package net.minecomputers.block.entity;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.minecomputers.network.MediaRules;
import net.minecomputers.network.PathRules;
import net.minecomputers.registry.ModBlockEntities;
import net.minecomputers.script.ScriptManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Brain remembers the code: all files and folders of its system, the media files dragged into it
 * (the bytes live in the world's minecomputers/media folder, the Brain only keeps name, id and size),
 * plus the device IDs it handed out. Everything is saved with the world. All changes are validated here,
 * so a bad request can't corrupt it.
 */
public class BrainBlockEntity extends BlockEntity {
    /** A media file: the id (SHA-256 of its content, the file name in the media store) and its size in bytes. */
    public record MediaInfo(String id, int size) {
    }

    /** Path (for example "main.js" or "lib/servo.js") to file content. Sorted so the tree is stable. */
    private final Map<String, String> files = new TreeMap<>();
    /** Media path (for example "media/intro.png") to its id and size. */
    private final Map<String, MediaInfo> media = new TreeMap<>();
    /** Folder paths, including every parent folder of every file. */
    private final Set<String> folders = new TreeSet<>();
    /** Block position (as long) to the ID given to the device there, so IDs stay the same. */
    private final Map<Long, String> deviceIds = new HashMap<>();
    /** Per device type, the last number handed out. */
    private final Map<String, Integer> counters = new HashMap<>();

    public BrainBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BRAIN.get(), pos, state);
    }

    public Map<String, String> files() {
        return files;
    }

    public Map<String, MediaInfo> media() {
        return media;
    }

    public Set<String> folders() {
        return folders;
    }

    private boolean pathTaken(String path) {
        return files.containsKey(path) || folders.contains(path) || media.containsKey(path);
    }

    /** Creates an empty file (and its parent folders). Returns false if the path is invalid or taken. */
    public boolean createFile(String path) {
        if (!PathRules.isValid(path) || pathTaken(path)
                || files.size() >= PathRules.MAX_FILES || !parentsAreFree(path)) {
            return false;
        }
        addParents(path);
        files.put(path, "");
        setChanged();
        return true;
    }

    /** Creates a folder (and its parent folders). Returns false if the path is invalid or taken. */
    public boolean createFolder(String path) {
        if (!PathRules.isValid(path) || pathTaken(path)
                || folders.size() >= PathRules.MAX_FOLDERS || !parentsAreFree(path)) {
            return false;
        }
        addParents(path);
        folders.add(path);
        setChanged();
        return true;
    }

    /** Replaces the content of an existing file. Returns false if it doesn't exist or is too big. */
    public boolean saveFile(String path, String content) {
        if (!files.containsKey(path)) {
            return false;
        }
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > PathRules.MAX_FILE_BYTES) {
            return false;
        }
        int total = bytes;
        for (Map.Entry<String, String> entry : files.entrySet()) {
            if (!entry.getKey().equals(path)) {
                total += entry.getValue().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        if (total > PathRules.MAX_TOTAL_BYTES) {
            return false;
        }
        files.put(path, content);
        setChanged();
        return true;
    }

    /** True if `path` is `base` itself or lies inside the folder `base`. */
    private static boolean isSameOrInside(String path, String base) {
        return path.equals(base) || path.startsWith(base + "/");
    }

    /**
     * Renames or moves a file, media file or folder (a folder takes everything inside it along).
     * Returns a problem text for the player, or null on success. Everything is checked here again.
     */
    public String renamePath(String from, String to) {
        if (!PathRules.isValid(from) || !PathRules.isValid(to)) {
            return "Rename failed: invalid name.";
        }
        boolean isFile = files.containsKey(from);
        boolean isMedia = media.containsKey(from);
        boolean isFolder = folders.contains(from);
        if (!isFile && !isMedia && !isFolder) {
            return "Rename failed: it no longer exists.";
        }
        if (from.equals(to)) {
            return null;
        }
        if (pathTaken(to)) {
            return "A file or folder with this name already exists.";
        }
        if (isFolder && isSameOrInside(to, from)) {
            return "A folder can't be moved into itself.";
        }
        if (!parentsAreFree(to)) {
            return "Rename failed: a folder in this path is a file.";
        }
        if (isMedia && MediaRules.kindOf(to) != MediaRules.kindOf(from)) {
            return "Rename failed: a media file must keep its type (" + from.substring(from.lastIndexOf('.') + 1) + ").";
        }

        // Work out every new path first and check them all, so a failure changes nothing.
        Map<String, String> movedFiles = new TreeMap<>();
        Map<String, String> movedMedia = new TreeMap<>();
        Set<String> movedFolders = new TreeSet<>();
        for (String path : files.keySet()) {
            if (isSameOrInside(path, from)) {
                movedFiles.put(to + path.substring(from.length()), path);
            }
        }
        for (String path : media.keySet()) {
            if (isSameOrInside(path, from)) {
                movedMedia.put(to + path.substring(from.length()), path);
            }
        }
        for (String path : folders) {
            if (isSameOrInside(path, from)) {
                movedFolders.add(to + path.substring(from.length()));
            }
        }
        for (String newPath : movedFiles.keySet()) {
            if (!PathRules.isValid(newPath)) {
                return "Rename failed: the new path is too long or too deep.";
            }
        }
        for (String newPath : movedMedia.keySet()) {
            if (!PathRules.isValid(newPath)) {
                return "Rename failed: the new path is too long or too deep.";
            }
        }
        for (String newPath : movedFolders) {
            if (!PathRules.isValid(newPath)) {
                return "Rename failed: the new path is too long or too deep.";
            }
        }

        // Apply.
        Map<String, String> fileContents = new TreeMap<>();
        movedFiles.forEach((newPath, oldPath) -> fileContents.put(newPath, files.remove(oldPath)));
        Map<String, MediaInfo> mediaInfos = new TreeMap<>();
        movedMedia.forEach((newPath, oldPath) -> mediaInfos.put(newPath, media.remove(oldPath)));
        folders.removeIf(path -> isSameOrInside(path, from));
        files.putAll(fileContents);
        media.putAll(mediaInfos);
        folders.addAll(movedFolders);
        addParents(to);
        if (isFolder) {
            folders.add(to);
        }
        setChanged();
        return null;
    }

    /**
     * Deletes a file, media file or folder (with everything inside it). Returns false if there was nothing to delete.
     * The bytes of a deleted media file stay in the world's media store, because another Brain may use the same file.
     */
    public boolean deletePath(String path) {
        if (!PathRules.isValid(path)) {
            return false;
        }
        boolean removed = files.keySet().removeIf(p -> isSameOrInside(p, path));
        removed |= media.keySet().removeIf(p -> isSameOrInside(p, path));
        removed |= folders.removeIf(p -> isSameOrInside(p, path));
        if (removed) {
            setChanged();
        }
        return removed;
    }

    public long totalMediaBytes() {
        long total = 0;
        for (MediaInfo info : media.values()) {
            total += info.size();
        }
        return total;
    }

    /** Why a media file of this path and size can't be added, in words for the player, or null if it can. */
    public String mediaProblem(String path, int size) {
        if (!PathRules.isValid(path) || MediaRules.kindOf(path) == MediaRules.NONE) {
            return "Upload rejected: invalid file name or type.";
        }
        if (pathTaken(path)) {
            return "A file or folder with this name already exists.";
        }
        if (!parentsAreFree(path)) {
            return "Upload rejected: a folder in this path is a file.";
        }
        if (media.size() >= MediaRules.MAX_FILES) {
            return "Too many media files in this Brain (max " + MediaRules.MAX_FILES + ").";
        }
        if (totalMediaBytes() + size > MediaRules.MAX_TOTAL_BYTES) {
            return "This Brain's media storage is full (max " + MediaRules.MAX_TOTAL_BYTES / (1024 * 1024) + " MB).";
        }
        return null;
    }

    /** Adds a media file whose bytes are already stored under this id. Returns a problem text, or null on success. */
    public String addMedia(String path, String id, int size) {
        String problem = mediaProblem(path, size);
        if (problem != null) {
            return problem;
        }
        addParents(path);
        media.put(path, new MediaInfo(id, size));
        setChanged();
        return null;
    }

    /** No parent of this path may already be a file. */
    private boolean parentsAreFree(String path) {
        String parent = PathRules.parentOf(path);
        while (!parent.isEmpty()) {
            if (files.containsKey(parent) || media.containsKey(parent)) {
                return false;
            }
            parent = PathRules.parentOf(parent);
        }
        return true;
    }

    private void addParents(String path) {
        String parent = PathRules.parentOf(path);
        while (!parent.isEmpty()) {
            folders.add(parent);
            parent = PathRules.parentOf(parent);
        }
    }

    /** The ID of the device of this type at this position. Assigned on first sight, then kept. */
    public String idFor(String type, BlockPos devicePos) {
        long key = devicePos.asLong();
        String id = deviceIds.get(key);
        if (id != null && id.startsWith(type + ":")) {
            return id;
        }
        int number = counters.merge(type, 1, Integer::sum);
        id = type + ":" + number;
        deviceIds.put(key, id);
        setChanged();
        return id;
    }

    /**
     * Like idFor, for a multi-block device (a Screen group). When blocks are added, the group's origin block can
     * move to another position; the group then keeps the lowest ID any of its blocks already had, instead of
     * getting a new one.
     */
    public String idForGroup(String type, BlockPos origin, java.util.List<BlockPos> members) {
        String keep = null;
        int best = Integer.MAX_VALUE;
        for (BlockPos member : members) {
            String id = deviceIds.get(member.asLong());
            if (id != null && id.startsWith(type + ":")) {
                try {
                    int number = Integer.parseInt(id.substring(type.length() + 1));
                    if (number < best) {
                        best = number;
                        keep = id;
                    }
                } catch (NumberFormatException ignored) {
                    // Not a number: ignore this entry.
                }
            }
        }
        if (keep == null) {
            return idFor(type, origin);
        }
        boolean changed = !keep.equals(deviceIds.get(origin.asLong()));
        for (BlockPos member : members) {
            if (!member.equals(origin) && deviceIds.remove(member.asLong()) != null) {
                changed = true;
            }
        }
        if (changed) {
            deviceIds.put(origin.asLong(), keep);
            setChanged();
        }
        return keep;
    }

    /** A Brain that is broken or unloaded stops its script. */
    @Override
    public void setRemoved() {
        super.setRemoved();
        discardScript();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        discardScript();
    }

    private void discardScript() {
        if (level != null && !level.isClientSide) {
            ScriptManager.discard(GlobalPos.of(level.dimension(), worldPosition));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        CompoundTag fileTag = new CompoundTag();
        files.forEach(fileTag::putString);
        tag.put("files", fileTag);

        ListTag mediaTag = new ListTag();
        media.forEach((path, info) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("path", path);
            entry.putString("id", info.id());
            entry.putInt("size", info.size());
            mediaTag.add(entry);
        });
        tag.put("media", mediaTag);

        ListTag folderTag = new ListTag();
        for (String folder : folders) {
            folderTag.add(StringTag.valueOf(folder));
        }
        tag.put("folders", folderTag);

        CompoundTag idTag = new CompoundTag();
        deviceIds.forEach((pos, id) -> idTag.putString(Long.toString(pos), id));
        tag.put("device_ids", idTag);

        CompoundTag counterTag = new CompoundTag();
        counters.forEach(counterTag::putInt);
        tag.put("counters", counterTag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        files.clear();
        CompoundTag fileTag = tag.getCompound("files");
        for (String key : fileTag.getAllKeys()) {
            files.put(key, fileTag.getString(key));
        }

        media.clear();
        ListTag mediaTag = tag.getList("media", Tag.TAG_COMPOUND);
        for (int i = 0; i < mediaTag.size(); i++) {
            CompoundTag entry = mediaTag.getCompound(i);
            media.put(entry.getString("path"), new MediaInfo(entry.getString("id"), entry.getInt("size")));
        }

        folders.clear();
        ListTag folderTag = tag.getList("folders", Tag.TAG_STRING);
        for (int i = 0; i < folderTag.size(); i++) {
            folders.add(folderTag.getString(i));
        }

        deviceIds.clear();
        CompoundTag idTag = tag.getCompound("device_ids");
        for (String key : idTag.getAllKeys()) {
            try {
                deviceIds.put(Long.parseLong(key), idTag.getString(key));
            } catch (NumberFormatException ignored) {
                // Skip a corrupt entry.
            }
        }

        counters.clear();
        CompoundTag counterTag = tag.getCompound("counters");
        for (String key : counterTag.getAllKeys()) {
            counters.put(key, counterTag.getInt(key));
        }
    }
}
