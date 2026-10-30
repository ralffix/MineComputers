package net.minecomputers.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

import net.minecomputers.network.ConsoleLine;
import net.minecomputers.network.ConsoleSync;
import net.minecomputers.network.LaptopAction;
import net.minecomputers.network.LaptopNotice;
import net.minecomputers.network.LaptopSnapshot;
import net.minecomputers.network.LaptopUpload;
import net.minecomputers.network.MediaRules;
import net.minecomputers.network.PathRules;
import net.minecomputers.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The laptop IDE (layout approved in docs/SPEC.md section 9).
 *
 * Explorer on the left (create files and folders), tabbed editor in the middle with a Console under it,
 * connected devices on the right. The editor has selection, copy/cut/paste, undo/redo, comment toggling,
 * indent/outdent, word movement and auto-indent, plus (ported from the old mod's editor) auto-closing brackets and
 * quotes, matching-bracket highlight, richer JavaScript colors, wrapped console lines, and click-a-device-to-insert-its-ID.
 * All changes go to the server, which validates them.
 * Not done yet: rename/delete, find, media preview.
 */
public class LaptopScreen extends Screen {
    // Colors (ARGB).
    private static final int CHROME = 0xFFE4EAED;
    private static final int PANEL = 0xFFDCE4E8;
    private static final int BORDER = 0xFFB3C0C6;
    private static final int TEXT = 0xFF26343B;
    private static final int MUTED = 0xFF6A7B84;
    private static final int TEAL = 0xFF1F7F8C;
    private static final int TEAL_LIGHT = 0xFFCDEBEE;
    private static final int EDITOR_BG = 0xFF13191D;
    private static final int EDITOR_TAB_BG = 0xFF0D1114;
    private static final int CONSOLE_BG = 0xFF0F1417;
    private static final int CODE_TEXT = 0xFFD4DADD;
    private static final int CODE_COMMENT = 0xFF66767D;
    private static final int CODE_KEYWORD = 0xFFE0759A;
    private static final int CODE_STRING = 0xFF8CCB8C;
    private static final int CODE_NUMBER = 0xFFE5A15A;
    private static final int CODE_FUNC = 0xFF7CC4E8;
    private static final int GUTTER = 0xFF5B6A71;
    private static final int ERROR = 0xFFD9534F;
    private static final int SELECTION = 0x66419CB0;
    private static final int CODE_CONTROL = 0xFFB48EE0;
    private static final int BRACKET_MATCH = 0x99357A8A;
    private static final int BRACKET_BAD = 0x99B04A4A;

    /** Declarations and operators (pink). */
    private static final Set<String> DECLARATIONS = Set.of("const", "let", "var", "function", "new", "class", "extends",
            "super", "this", "static", "typeof", "instanceof", "in", "of", "async", "await", "yield", "import", "export",
            "from", "delete", "void");
    /** Flow control (purple). */
    private static final Set<String> CONTROL = Set.of("if", "else", "for", "while", "do", "return", "break", "continue",
            "switch", "case", "default", "try", "catch", "finally", "throw");
    /** Values (orange, like numbers). */
    private static final Set<String> LITERALS = Set.of("true", "false", "null", "undefined", "NaN", "Infinity");

    private static final String OPENERS = "([{";
    private static final String CLOSERS = ")]}";

    private static final int ROW_H = 11;
    private static final int LINE_H = 10;
    private static final int GUTTER_W = 22;
    private static final int TAB_H = 14;
    private static final int CRUMB_H = 11;
    private static final int MIN_TAB_W = 34;
    private static final int MAX_TAB_W = 120;
    private static final int MAX_UNDO = 100;
    private static final int MENU_ROW = 12;
    /** Ticks (20 per second) without typing before an automatic save. */
    private static final int AUTOSAVE_TICKS = 40;

    /** media: 0 for folders and text files, otherwise MediaRules.IMAGE, AUDIO or VIDEO. */
    private record TreeRow(int indent, String name, String path, boolean folder, int media) {
    }

    private record Card(ItemStack icon, String name, String pos, String id) {
    }

    /** One screen row of the Console: a line (or one wrapped piece of it). */
    private record ConsoleRow(ConsoleLine line, FormattedCharSequence text, boolean first) {
    }

    private record UndoState(List<String> lines, int row, int col) {
    }

    /** One entry of the Explorer's right-click menu. */
    private record MenuItem(String id, String label) {
    }

    private LaptopSnapshot snapshot;

    /** Latest known content of each file (what the server has, or what we saved and wait to be confirmed). */
    private final Map<String, String> local = new HashMap<>();
    /** Saves sent to the server and not yet confirmed. */
    private final Map<String, String> pending = new HashMap<>();
    private final Set<String> folderSet = new TreeSet<>();
    private final List<TreeRow> tree = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    /** Per visible device card from the last frame: {left, top, right, bottom, index into cards}. */
    private final List<int[]> cardHit = new ArrayList<>();
    /** Mouse position of the current frame, for hover effects. */
    private int hoverX = -1;
    private int hoverY = -1;

    // Explorer: collapsed folders, the row last clicked (F2 and Delete act on it), right-click menu,
    // delete confirmation, and a rename that was sent to the server and waits for its answer.
    private final Set<String> collapsed = new HashSet<>();
    private String selectedEntry = "";
    private boolean explorerFocus;
    private int treeBottom;
    // Dragging an Explorer entry into a folder.
    private String dragPath;
    private boolean dragActive;
    private int dragStartX;
    private int dragStartY;
    private String dragTarget;
    private String dragFolderToggle;
    private List<MenuItem> menuItems = List.of();
    private String menuPath = "";
    private final int[] menuRect = new int[4];
    private String deleteTarget;
    private final int[] deleteYes = new int[4];
    private final int[] deleteNo = new int[4];
    private String promptTarget = "";
    private String renameFrom;
    private String renameTo;
    private int renameUntil;

    // Find and replace bar (Ctrl+F, Ctrl+H).
    private boolean findOpen;
    private boolean findFocused;
    private boolean findShowReplace;
    private boolean findFocusReplace;
    private boolean findMatchCase;
    private final StringBuilder findText = new StringBuilder();
    private final StringBuilder replaceText = new StringBuilder();
    private final int[] findBar = new int[4];
    private final int[] findField = new int[4];
    private final int[] replaceField = new int[4];
    private final int[] findToggleBtn = new int[4];
    private final int[] findCaseBtn = new int[4];
    private final int[] findPrevBtn = new int[4];
    private final int[] findNextBtn = new int[4];
    private final int[] findCloseBtn = new int[4];
    private final int[] replaceOneBtn = new int[4];
    private final int[] replaceAllBtn = new int[4];
    private boolean hasGateway;
    private int otherNonBlankLines;

    // Explorer state.
    private String selectedFolder = "";
    private String promptKind; // null, "file" or "folder"
    private final StringBuilder promptText = new StringBuilder();
    private boolean promptInvalid;
    private String openAfterCreate;
    private int treeScroll;
    private int treeVisibleRows = 1;
    private int deviceScroll;

    // Media (dragged-in images, sound and video) known to the Brain: path to size in bytes.
    private final Map<String, Integer> mediaSizes = new HashMap<>();
    /** The media file whose info card is shown instead of the code, or null. */
    private String mediaInfoPath;

    // Dragging files in: queue, the file being read or sent, and the progress.
    private record PendingUpload(Path source, String dest) {
    }

    private final Deque<PendingUpload> uploadQueue = new ArrayDeque<>();
    private CompletableFuture<byte[]> reading;
    private byte[] uploadData;
    private String uploadPath;
    private String uploadLabel = "";
    private int uploadSent;
    private int uploadChunk;

    // Short message shown above the status bar (upload progress and results).
    private String noticeText = "";
    private boolean noticeError;
    private int noticeUntil;

    // Autocomplete popup.
    private boolean completionOpen;
    private List<Completions.Item> completionItems = List.of();
    private int completionIndex;
    private int completionStart;
    private final int[] popupRect = new int[4];

    // Tabs.
    private final List<String> tabs = new ArrayList<>();
    /** Per file: cursor row, column, horizontal and vertical scroll, restored when its tab is selected again. */
    private final Map<String, int[]> views = new HashMap<>();
    private int tabScroll;
    private boolean revealActiveTab;
    /** Per tab on screen: {left, right} in screen coordinates, from the last frame. */
    private final List<int[]> tabRects = new ArrayList<>();

    // Editor state. The anchor is where a selection started; there is a selection when it differs from the cursor.
    private String openPath;
    private List<String> lines = new ArrayList<>(List.of(""));
    private int curRow;
    private int curCol;
    private int anchorRow;
    private int anchorCol;
    private int scrollY;
    private int scrollX;
    private boolean dirty;
    private boolean tooBig;
    private int tickCount;
    private int lastEditTick;
    private int lastEditKind;
    private boolean dragging;
    private long lastClickTime;
    private int lastClickRow = -1;
    private int lastClickCol = -1;

    // Undo history (per file).
    private final Map<String, Deque<UndoState>> undoMap = new HashMap<>();
    private final Map<String, Deque<UndoState>> redoMap = new HashMap<>();
    private Deque<UndoState> undo = new ArrayDeque<>();
    private Deque<UndoState> redo = new ArrayDeque<>();

    // Script console (output of the running script, sent by the server).
    private List<ConsoleLine> console = new ArrayList<>();
    private boolean scriptRunning;
    private long consoleVersion = -1;

    // Layout from the last frame, used for mouse input.
    private int exX;
    private int exY;
    private int exW;
    private int exH;
    private int treeTop;
    private int rpX;
    private int rpY;
    private int rpW;
    private int rpH;
    private final int[] fileBtn = new int[4];
    private final int[] folderBtn = new int[4];
    private final int[] promptBox = new int[4];
    private final int[] runBtn = new int[4];
    private final int[] stopBtn = new int[4];
    private final int[] tabBar = new int[4];
    private int codeAreaX;
    private int codeAreaY;
    private int codeAreaW;
    private int codeAreaH;

    public LaptopScreen(LaptopSnapshot snapshot) {
        super(Component.literal("Laptop"));
        applySnapshot(snapshot, true);
    }

    public boolean isFor(BlockPos laptop) {
        return snapshot.laptop().equals(laptop);
    }

    /** A new state arrived from the server (after a create or save). The GUI keeps its editing state. */
    public void update(LaptopSnapshot newSnapshot) {
        applySnapshot(newSnapshot, false);
    }

    /** The script console changed (answer to Run, Stop or a poll). */
    public void updateConsole(ConsoleSync sync) {
        consoleVersion = sync.version();
        scriptRunning = sync.running();
        console = new ArrayList<>(sync.lines());
    }

    // ------------------------------------------------------------ data

    private void applySnapshot(LaptopSnapshot s, boolean first) {
        this.snapshot = s;

        // A rename we sent has been done by the server: move tabs, undo history and view state to the new path.
        if (renameFrom != null && hasPath(s, renameTo)) {
            remapState();
            renameFrom = null;
            renameTo = null;
        }

        Map<String, String> merged = new HashMap<>();
        for (LaptopSnapshot.FileEntry f : s.files()) {
            String content = f.content();
            String waiting = pending.get(f.path());
            if (waiting != null) {
                if (waiting.equals(content)) {
                    pending.remove(f.path());
                } else {
                    content = waiting; // our newer save is still on its way
                }
            }
            merged.put(f.path(), content);
        }
        pending.keySet().retainAll(merged.keySet());
        local.clear();
        local.putAll(merged);
        mediaSizes.clear();
        for (LaptopSnapshot.MediaEntry m : s.media()) {
            mediaSizes.put(m.path(), m.size());
        }
        if (mediaInfoPath != null && !mediaSizes.containsKey(mediaInfoPath)) {
            mediaInfoPath = null;
        }

        tabs.removeIf(p -> !local.containsKey(p));
        if (openPath != null && !local.containsKey(openPath)) {
            openPath = null;
            lines = new ArrayList<>(List.of(""));
            dirty = false;
            curRow = curCol = anchorRow = anchorCol = 0;
            if (!tabs.isEmpty()) {
                openFile(tabs.get(0));
            }
        }

        rebuildTree();
        if (!folderSet.contains(selectedFolder)) {
            selectedFolder = "";
        }

        if (openPath == null && first) {
            for (TreeRow row : tree) {
                if (!row.folder() && row.media() == 0) {
                    openFile(row.path());
                    break;
                }
            }
        } else if (openPath != null && !dirty) {
            String content = local.getOrDefault(openPath, "");
            if (!content.equals(String.join("\n", lines))) {
                loadLines(content);
                curRow = Math.min(curRow, lines.size() - 1);
                curCol = Math.min(curCol, lines.get(curRow).length());
                collapse();
            }
        }
        if (openAfterCreate != null && local.containsKey(openAfterCreate)) {
            openFile(openAfterCreate);
            openAfterCreate = null;
        }
        recountOtherLines();

        cards.clear();
        hasGateway = false;
        for (LaptopSnapshot.DeviceEntry d : s.devices()) {
            if (d.type().equals("gateway")) {
                hasGateway = true;
            }
            cards.add(new Card(new ItemStack(iconFor(d.type())), nameFor(d.type()),
                    "x " + d.x() + " y " + d.y() + " z " + d.z(), d.id()));
        }
    }

    private static boolean hasPath(LaptopSnapshot s, String path) {
        if (s.folders().contains(path)) {
            return true;
        }
        for (LaptopSnapshot.FileEntry f : s.files()) {
            if (f.path().equals(path)) {
                return true;
            }
        }
        for (LaptopSnapshot.MediaEntry m : s.media()) {
            if (m.path().equals(path)) {
                return true;
            }
        }
        return false;
    }

    /** The new path of `path` after the pending rename (renameFrom to renameTo), or the same path if it is not affected. */
    private String remapPath(String path) {
        if (path == null) {
            return null;
        }
        if (path.equals(renameFrom)) {
            return renameTo;
        }
        if (path.startsWith(renameFrom + "/")) {
            return renameTo + path.substring(renameFrom.length());
        }
        return path;
    }

    private <T> void remapKeys(Map<String, T> map) {
        Map<String, T> moved = new HashMap<>();
        map.forEach((key, value) -> moved.put(remapPath(key), value));
        map.clear();
        map.putAll(moved);
    }

    /** Moves everything the GUI remembers per path (tabs, open file, undo, view, collapsed folders) to the renamed path. */
    private void remapState() {
        tabs.replaceAll(this::remapPath);
        openPath = remapPath(openPath);
        mediaInfoPath = remapPath(mediaInfoPath);
        selectedFolder = remapPath(selectedFolder);
        selectedEntry = remapPath(selectedEntry);
        remapKeys(views);
        remapKeys(undoMap);
        remapKeys(redoMap);
        remapKeys(pending);
        Set<String> moved = new HashSet<>();
        for (String path : collapsed) {
            moved.add(remapPath(path));
        }
        collapsed.clear();
        collapsed.addAll(moved);
    }

    /** Opens the folders above a path so that the file shows in the tree. */
    private void revealPath(String path) {
        boolean changed = false;
        String parent = PathRules.parentOf(path);
        while (!parent.isEmpty()) {
            changed |= collapsed.remove(parent);
            parent = PathRules.parentOf(parent);
        }
        if (changed) {
            rebuildTree();
        }
    }

    private void rebuildTree() {
        folderSet.clear();
        folderSet.addAll(snapshot.folders());
        List<String> allPaths = new ArrayList<>(local.keySet());
        allPaths.addAll(mediaSizes.keySet());
        for (String path : allPaths) {
            String parent = PathRules.parentOf(path);
            while (!parent.isEmpty()) {
                folderSet.add(parent);
                parent = PathRules.parentOf(parent);
            }
        }
        tree.clear();
        emitLevel("", 0);
    }

    private void emitLevel(String parent, int indent) {
        List<String> subFolders = folderSet.stream().filter(f -> PathRules.parentOf(f).equals(parent)).sorted().toList();
        for (String folder : subFolders) {
            tree.add(new TreeRow(indent, PathRules.nameOf(folder), folder, true, 0));
            if (!collapsed.contains(folder)) {
                emitLevel(folder, indent + 1);
            }
        }
        List<String> subFiles = local.keySet().stream().filter(p -> PathRules.parentOf(p).equals(parent)).sorted().toList();
        for (String file : subFiles) {
            tree.add(new TreeRow(indent, PathRules.nameOf(file), file, false, 0));
        }
        List<String> subMedia = mediaSizes.keySet().stream().filter(p -> PathRules.parentOf(p).equals(parent)).sorted().toList();
        for (String file : subMedia) {
            tree.add(new TreeRow(indent, PathRules.nameOf(file), file, false, MediaRules.kindOf(file)));
        }
    }

    private void recountOtherLines() {
        otherNonBlankLines = 0;
        for (Map.Entry<String, String> e : local.entrySet()) {
            if (!e.getKey().equals(openPath)) {
                for (String line : e.getValue().split("\n")) {
                    if (!line.isBlank()) {
                        otherNonBlankLines++;
                    }
                }
            }
        }
    }

    private static Item iconFor(String type) {
        return switch (type) {
            case "screen" -> ModItems.SCREEN.get();
            case "servo" -> ModItems.SERVO.get();
            case "speaker" -> ModItems.SPEAKER.get();
            case "redstone" -> ModItems.REDSTONE_LINK.get();
            case "gateway" -> ModItems.REMOTE_GATEWAY.get();
            default -> ModItems.BRAIN.get();
        };
    }

    private static String nameFor(String type) {
        return switch (type) {
            case "screen" -> "Screen";
            case "servo" -> "Servo";
            case "speaker" -> "Speaker";
            case "redstone" -> "Redstone Link";
            case "gateway" -> "Remote Gateway";
            default -> "Brain";
        };
    }

    // ------------------------------------------- files, tabs and saving

    private void openFile(String path) {
        mediaInfoPath = null;
        closeCompletion();
        revealPath(path);
        if (path.equals(openPath)) {
            selectedFolder = PathRules.parentOf(path);
            return;
        }
        flushIfDirty();
        rememberView();
        if (!tabs.contains(path)) {
            tabs.add(path);
        }
        openPath = path;
        loadLines(local.getOrDefault(path, ""));
        int[] view = views.get(path);
        curRow = 0;
        curCol = 0;
        scrollX = 0;
        scrollY = 0;
        if (view != null) {
            curRow = Math.max(0, Math.min(view[0], lines.size() - 1));
            curCol = Math.max(0, Math.min(view[1], lines.get(curRow).length()));
            scrollX = view[2];
            scrollY = view[3];
        }
        collapse();
        undo = undoMap.computeIfAbsent(path, k -> new ArrayDeque<>());
        redo = redoMap.computeIfAbsent(path, k -> new ArrayDeque<>());
        dirty = false;
        tooBig = false;
        lastEditKind = 0;
        revealActiveTab = true;
        selectedFolder = PathRules.parentOf(path);
        recountOtherLines();
    }

    private void rememberView() {
        if (openPath != null) {
            views.put(openPath, new int[] {curRow, curCol, scrollX, scrollY});
        }
    }

    private void closeTab(String path) {
        int index = tabs.indexOf(path);
        if (index < 0) {
            return;
        }
        boolean wasOpen = path.equals(openPath);
        if (wasOpen) {
            flushIfDirty();
            rememberView();
        }
        tabs.remove(index);
        if (wasOpen) {
            openPath = null;
            lines = new ArrayList<>(List.of(""));
            dirty = false;
            curRow = curCol = anchorRow = anchorCol = 0;
            scrollX = scrollY = 0;
            undo = new ArrayDeque<>();
            redo = new ArrayDeque<>();
            if (!tabs.isEmpty()) {
                openFile(tabs.get(Math.min(index, tabs.size() - 1)));
            }
            recountOtherLines();
        }
    }

    private void switchTab(int delta) {
        int index = tabs.indexOf(openPath);
        if (index < 0 || tabs.size() < 2) {
            return;
        }
        openFile(tabs.get(Math.floorMod(index + delta, tabs.size())));
    }

    private void loadLines(String content) {
        lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
    }

    private void flushIfDirty() {
        if (openPath != null && dirty) {
            saveNow();
        }
    }

    private void saveNow() {
        if (openPath == null || !snapshot.connected()) {
            return;
        }
        String text = String.join("\n", lines);
        if (text.getBytes(StandardCharsets.UTF_8).length > PathRules.MAX_FILE_BYTES) {
            tooBig = true;
            return;
        }
        tooBig = false;
        pending.put(openPath, text);
        local.put(openPath, text);
        dirty = false;
        PacketDistributor.sendToServer(new LaptopAction(snapshot.laptop(), LaptopAction.SAVE_FILE, openPath, text));
    }

    @Override
    public void tick() {
        tickCount++;
        if (renameFrom != null && tickCount > renameUntil) {
            renameFrom = null; // the server never confirmed it: stop waiting
            renameTo = null;
        }
        if (dirty && tickCount - lastEditTick > AUTOSAVE_TICKS) {
            saveNow();
        }
        // Twice a second, ask whether the console changed (the server only answers if it did).
        if (snapshot.connected() && tickCount % 10 == 1) {
            PacketDistributor.sendToServer(new LaptopAction(snapshot.laptop(), LaptopAction.POLL, "",
                    String.valueOf(consoleVersion)));
        }
        tickUploads();
    }

    @Override
    public void removed() {
        flushIfDirty();
        super.removed();
    }

    // ------------------------------------------------------ new file/folder

    private void beginPrompt(String kind) {
        if (!snapshot.connected()) {
            return;
        }
        promptKind = kind;
        promptText.setLength(0);
        promptInvalid = false;
    }

    private void confirmPrompt() {
        if ("rename".equals(promptKind)) {
            confirmRename();
            return;
        }
        String name = promptText.toString().trim();
        String path = selectedFolder.isEmpty() ? name : selectedFolder + "/" + name;
        boolean taken = local.containsKey(path) || folderSet.contains(path);
        if (!PathRules.isValid(path) || taken) {
            promptInvalid = true;
            return;
        }
        boolean isFile = "file".equals(promptKind);
        if (isFile) {
            openAfterCreate = path;
        }
        PacketDistributor.sendToServer(new LaptopAction(snapshot.laptop(),
                isFile ? LaptopAction.CREATE_FILE : LaptopAction.CREATE_FOLDER, path, ""));
        promptKind = null;
    }

    // ------------------------------------------- rename, delete, context menu

    /** The folder an entry would land in if dropped at this mouse position, or null if it can't be dropped there. */
    private String dropTargetAt(int mx, int my) {
        if (mx < exX || mx >= exX + exW || my < treeTop || my >= treeBottom) {
            return null;
        }
        int index = treeScroll + (my - treeTop) / ROW_H;
        if (index < 0 || index >= tree.size()) {
            return ""; // empty space: the top level
        }
        TreeRow row = tree.get(index);
        return row.folder() ? row.path() : PathRules.parentOf(row.path());
    }

    /** Moves an entry into a folder (the same RENAME action as the rename prompt). Problems are shown as a notice. */
    private void moveInto(String from, String folder) {
        String to = folder.isEmpty() ? PathRules.nameOf(from) : folder + "/" + PathRules.nameOf(from);
        if (to.equals(from)) {
            return;
        }
        boolean taken = local.containsKey(to) || folderSet.contains(to) || mediaSizes.containsKey(to);
        boolean intoSelf = folderSet.contains(from) && (folder.equals(from) || folder.startsWith(from + "/"));
        String problem = null;
        if (intoSelf) {
            problem = "A folder can't be moved into itself.";
        } else if (taken) {
            problem = "A file or folder with this name already exists there.";
        } else if (!PathRules.isValid(to)) {
            problem = "That path is too long or too deep.";
        }
        if (problem != null) {
            showNotice(new LaptopNotice(snapshot.laptop(), problem, true));
            return;
        }
        flushIfDirty();
        renameFrom = from;
        renameTo = to;
        renameUntil = tickCount + 60;
        PacketDistributor.sendToServer(new LaptopAction(snapshot.laptop(), LaptopAction.RENAME, from, to));
    }

    private void drawDragGhost(GuiGraphics g, int mx, int my) {
        if (!dragActive || dragPath == null) {
            return;
        }
        String name = PathRules.nameOf(dragPath);
        int w = this.font.width(name) + 8;
        g.fill(mx + 6, my + 2, mx + 6 + w, my + 14, 0xDD1D6F7A);
        g.drawString(this.font, name, mx + 10, my + 4, 0xFFFFFFFF, false);
        String hint = dragTarget == null ? "Can't drop here" : (dragTarget.isEmpty() ? "Move to top level" : "Move into " + dragTarget);
        g.drawString(this.font, this.font.plainSubstrByWidth(hint, 150), mx + 10, my + 16, dragTarget == null ? ERROR : 0xFFFFFFFF, true);
    }

    /** True if `path` is `target` itself or lies inside the folder `target`. */
    private static boolean affects(String path, String target) {
        return path.equals(target) || path.startsWith(target + "/");
    }

    /** Starts renaming a file, media file or folder: the name prompt opens with the current name. */
    private void beginRename(String path) {
        if (!snapshot.connected() || path == null || path.isEmpty()) {
            return;
        }
        closeMenu();
        promptKind = "rename";
        promptTarget = path;
        promptText.setLength(0);
        promptText.append(PathRules.nameOf(path));
        promptInvalid = false;
    }

    /** The name typed in the prompt keeps the folder; typing a/b moves the item into the folder a. */
    private void confirmRename() {
        String name = promptText.toString().trim();
        String from = promptTarget;
        String parent = PathRules.parentOf(from);
        String to = parent.isEmpty() ? name : parent + "/" + name;
        if (to.equals(from)) {
            promptKind = null;
            return;
        }
        boolean taken = local.containsKey(to) || folderSet.contains(to) || mediaSizes.containsKey(to);
        boolean intoSelf = folderSet.contains(from) && to.startsWith(from + "/");
        boolean wrongType = mediaSizes.containsKey(from) && MediaRules.kindOf(to) != MediaRules.kindOf(from);
        if (!PathRules.isValid(to) || taken || intoSelf || wrongType) {
            promptInvalid = true;
            return;
        }
        flushIfDirty(); // the server must have the latest text before the file moves
        renameFrom = from;
        renameTo = to;
        renameUntil = tickCount + 60;
        PacketDistributor.sendToServer(new LaptopAction(snapshot.laptop(), LaptopAction.RENAME, from, to));
        promptKind = null;
    }

    /** Opens the "Delete?" confirmation for a file or folder. */
    private void askDelete(String path) {
        if (!snapshot.connected() || path == null || path.isEmpty()) {
            return;
        }
        closeMenu();
        promptKind = null;
        deleteTarget = path;
    }

    private void confirmDelete() {
        String target = deleteTarget;
        deleteTarget = null;
        if (target == null || !snapshot.connected()) {
            return;
        }
        deleteLocally(target);
        PacketDistributor.sendToServer(new LaptopAction(snapshot.laptop(), LaptopAction.DELETE, target, ""));
    }

    /** Removes the deleted path from the GUI right away; the server's answer then confirms it. */
    private void deleteLocally(String target) {
        if (openPath != null && affects(openPath, target)) {
            dirty = false; // the file is going away, there is nothing to save
        }
        for (String tab : new ArrayList<>(tabs)) {
            if (affects(tab, target)) {
                closeTab(tab);
            }
        }
        if (mediaInfoPath != null && affects(mediaInfoPath, target)) {
            mediaInfoPath = null;
        }
        pending.keySet().removeIf(p -> affects(p, target));
        local.keySet().removeIf(p -> affects(p, target));
        mediaSizes.keySet().removeIf(p -> affects(p, target));
        views.keySet().removeIf(p -> affects(p, target));
        undoMap.keySet().removeIf(p -> affects(p, target));
        redoMap.keySet().removeIf(p -> affects(p, target));
        collapsed.removeIf(p -> affects(p, target));
        if (affects(selectedFolder, target)) {
            selectedFolder = PathRules.parentOf(target);
        }
        selectedEntry = "";
        rebuildTree();
        recountOtherLines();
    }

    private boolean menuOpen() {
        return !menuItems.isEmpty();
    }

    private void closeMenu() {
        menuItems = List.of();
    }

    /** Right-click in the Explorer: the menu depends on what is under the mouse. */
    private void openMenu(int mx, int my) {
        int index = treeScroll + (my - treeTop) / ROW_H;
        TreeRow row = index >= 0 && index < tree.size() ? tree.get(index) : null;
        List<MenuItem> items = new ArrayList<>();
        if (row == null) {
            menuPath = "";
            selectedFolder = "";
            selectedEntry = "";
            items.add(new MenuItem("newfile", "New file"));
            items.add(new MenuItem("newfolder", "New folder"));
        } else {
            menuPath = row.path();
            selectedEntry = row.path();
            if (row.folder()) {
                selectedFolder = row.path();
                items.add(new MenuItem("newfile", "New file here"));
                items.add(new MenuItem("newfolder", "New folder here"));
                items.add(new MenuItem("toggle", collapsed.contains(row.path()) ? "Expand" : "Collapse"));
            } else {
                items.add(new MenuItem("open", row.media() != 0 ? "Show info" : "Open"));
            }
            items.add(new MenuItem("rename", "Rename (F2)"));
            items.add(new MenuItem("delete", "Delete (Del)"));
        }
        int w = 0;
        for (MenuItem item : items) {
            w = Math.max(w, this.font.width(item.label()));
        }
        w += 16;
        int h = items.size() * MENU_ROW + 4;
        int x1 = Math.max(2, Math.min(mx, this.width - w - 2));
        int y1 = Math.max(2, Math.min(my, this.height - h - 2));
        setRect(menuRect, x1, y1, x1 + w, y1 + h);
        menuItems = items;
    }

    private void runMenuItem(String id) {
        switch (id) {
            case "newfile" -> beginPrompt("file");
            case "newfolder" -> beginPrompt("folder");
            case "toggle" -> {
                if (!collapsed.remove(menuPath)) {
                    collapsed.add(menuPath);
                }
                rebuildTree();
            }
            case "open" -> {
                if (mediaSizes.containsKey(menuPath)) {
                    mediaInfoPath = menuPath;
                } else if (local.containsKey(menuPath)) {
                    openFile(menuPath);
                }
            }
            case "rename" -> beginRename(menuPath);
            case "delete" -> askDelete(menuPath);
            default -> {
            }
        }
    }

    private void drawMenu(GuiGraphics g) {
        if (menuItems.isEmpty()) {
            return;
        }
        int x1 = menuRect[0];
        int y1 = menuRect[1];
        int x2 = menuRect[2];
        int y2 = menuRect[3];
        g.fill(x1 + 1, y1 + 2, x2 + 2, y2 + 3, 0x55000000);
        box(g, x1, y1, x2, y2, 0xFFF4F7F8, BORDER);
        for (int i = 0; i < menuItems.size(); i++) {
            MenuItem item = menuItems.get(i);
            int ry = y1 + 2 + i * MENU_ROW;
            if (hoverX >= x1 && hoverX < x2 && hoverY >= ry && hoverY < ry + MENU_ROW) {
                g.fill(x1 + 1, ry, x2 - 1, ry + MENU_ROW, TEAL_LIGHT);
            }
            g.drawString(this.font, item.label(), x1 + 7, ry + 2, item.id().equals("delete") ? 0xFFC0392B : TEXT, false);
        }
    }

    /** The "Delete?" box: dims the window, Enter or the red button deletes, Esc or Cancel keeps it. */
    private void drawDeleteDialog(GuiGraphics g, int x0, int y0, int w, int h) {
        setRect(deleteYes, 0, 0, 0, 0);
        setRect(deleteNo, 0, 0, 0, 0);
        if (deleteTarget == null) {
            return;
        }
        g.fill(x0, y0, x0 + w, y0 + h, 0x99000000);
        int dw = Math.min(w - 20, 214);
        int dh = 64;
        int dx = x0 + (w - dw) / 2;
        int dy = y0 + (h - dh) / 2;
        box(g, dx, dy, dx + dw, dy + dh, 0xFFF4F7F8, BORDER);

        boolean folder = folderSet.contains(deleteTarget);
        int count = 0;
        if (folder) {
            for (String path : local.keySet()) {
                if (path.startsWith(deleteTarget + "/")) {
                    count++;
                }
            }
            for (String path : mediaSizes.keySet()) {
                if (path.startsWith(deleteTarget + "/")) {
                    count++;
                }
            }
        }
        String title = "Delete " + PathRules.nameOf(deleteTarget) + "?";
        g.drawCenteredString(this.font, this.font.plainSubstrByWidth(title, dw - 16), dx + dw / 2, dy + 8, TEXT);
        String detail;
        if (!folder) {
            detail = "This can't be undone.";
        } else if (count == 0) {
            detail = "This empty folder will be deleted.";
        } else {
            detail = "This folder and the " + count + (count == 1 ? " file" : " files") + " in it will be deleted.";
        }
        int ty = dy + 21;
        for (FormattedCharSequence line : this.font.split(Component.literal(detail), dw - 20)) {
            g.drawCenteredString(this.font, line, dx + dw / 2, ty, MUTED);
            ty += 9;
        }

        int by1 = dy + dh - 18;
        int by2 = by1 + 12;
        int bw = 54;
        setRect(deleteYes, dx + dw / 2 - bw - 4, by1, dx + dw / 2 - 4, by2);
        setRect(deleteNo, dx + dw / 2 + 4, by1, dx + dw / 2 + 4 + bw, by2);
        boolean overYes = inside(deleteYes, hoverX, hoverY);
        box(g, deleteYes[0], deleteYes[1], deleteYes[2], deleteYes[3], overYes ? 0xFFD9534F : 0xFFC0392B,
                overYes ? 0xFFD9534F : 0xFFC0392B);
        g.drawCenteredString(this.font, "Delete", (deleteYes[0] + deleteYes[2]) / 2, deleteYes[1] + 2, 0xFFFFFFFF);
        boolean overNo = inside(deleteNo, hoverX, hoverY);
        box(g, deleteNo[0], deleteNo[1], deleteNo[2], deleteNo[3], overNo ? TEAL_LIGHT : 0xFFFFFFFF, BORDER);
        g.drawCenteredString(this.font, "Cancel", (deleteNo[0] + deleteNo[2]) / 2, deleteNo[1] + 2, TEXT);
    }

    // ------------------------------------------------------ find and replace

    /** Ctrl+F (find) and Ctrl+H (find and replace). A one-line selection becomes the search text. */
    private void openFind(boolean withReplace) {
        if (!editing()) {
            return;
        }
        closeCompletion();
        if (hasSelection() && anchorRow == curRow) {
            String seed = selectedText();
            if (seed.length() <= 80) {
                findText.setLength(0);
                findText.append(seed);
            }
        }
        findOpen = true;
        findFocused = true;
        findFocusReplace = false;
        if (withReplace) {
            findShowReplace = true;
        }
    }

    private void closeFind() {
        findOpen = false;
        findFocused = false;
    }

    private static int indexOfText(String line, String needle, int from, boolean matchCase) {
        int last = line.length() - needle.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (line.regionMatches(!matchCase, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOfText(String line, String needle, int maxStart, boolean matchCase) {
        for (int i = Math.min(maxStart, line.length() - needle.length()); i >= 0; i--) {
            if (line.regionMatches(!matchCase, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    /** Selects the next (or previous) match, wrapping around the file. Returns false if there is none. */
    private boolean findStep(boolean forward) {
        String needle = findText.toString();
        int count = lines.size();
        if (needle.isEmpty() || count == 0) {
            return false;
        }
        int[] s = selection();
        lastEditKind = 0;
        if (forward) {
            for (int i = 0; i <= count; i++) {
                int row = (s[2] + i) % count;
                int at = indexOfText(lines.get(row), needle, i == 0 ? s[3] : 0, findMatchCase);
                if (at >= 0) {
                    selectMatch(row, at, needle.length());
                    return true;
                }
            }
        } else {
            for (int i = 0; i <= count; i++) {
                int row = Math.floorMod(s[0] - i, count);
                String line = lines.get(row);
                int at = lastIndexOfText(line, needle, i == 0 ? s[1] - 1 : line.length(), findMatchCase);
                if (at >= 0) {
                    selectMatch(row, at, needle.length());
                    return true;
                }
            }
        }
        return false;
    }

    private void selectMatch(int row, int col, int length) {
        anchorRow = row;
        anchorCol = col;
        curRow = row;
        curCol = col + length;
        ensureCursorVisible();
    }

    /** While typing in the Find field: keep the current match, or jump to the next one after the cursor. */
    private void findIncremental() {
        int[] s = selection();
        curRow = anchorRow = s[0];
        curCol = anchorCol = s[1];
        findStep(true);
    }

    /** Replaces the selected match (if the selection is one) and moves on to the next. */
    private void replaceOne() {
        String needle = findText.toString();
        if (needle.isEmpty()) {
            return;
        }
        if (hasSelection() && anchorRow == curRow) {
            int[] s = selection();
            String line = lines.get(s[0]);
            if (s[3] - s[1] == needle.length() && line.regionMatches(!findMatchCase, s[1], needle, 0, needle.length())) {
                String replacement = replaceText.toString();
                if (totalChars() - needle.length() + replacement.length() > PathRules.MAX_FILE_BYTES) {
                    notice("Replace would make the file too big.", true);
                    return;
                }
                beginEdit(0);
                lines.set(s[0], line.substring(0, s[1]) + replacement + line.substring(s[3]));
                curRow = s[0];
                anchorRow = s[0];
                curCol = s[1] + replacement.length();
                anchorCol = curCol;
                edited();
            }
        }
        findStep(true);
    }

    private void replaceAll() {
        String needle = findText.toString();
        if (needle.isEmpty()) {
            return;
        }
        String replacement = replaceText.toString();
        int count = 0;
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            StringBuilder sb = new StringBuilder();
            int pos = 0;
            while (true) {
                int at = indexOfText(line, needle, pos, findMatchCase);
                if (at < 0) {
                    break;
                }
                sb.append(line, pos, at).append(replacement);
                pos = at + needle.length();
                count++;
            }
            sb.append(line, pos, line.length());
            result.add(sb.toString());
        }
        if (count == 0) {
            notice("No matches.", false);
            return;
        }
        int total = Math.max(0, result.size() - 1);
        for (String line : result) {
            total += line.length();
        }
        if (total > PathRules.MAX_FILE_BYTES) {
            notice("Replace would make the file too big.", true);
            return;
        }
        beginEdit(0);
        lines = result;
        curRow = Math.min(curRow, lines.size() - 1);
        curCol = Math.min(curCol, lines.get(curRow).length());
        collapse();
        edited();
        notice("Replaced " + count + (count == 1 ? " match." : " matches."), false);
    }

    /** {number of matches, index of the selected match or 0}. */
    private int[] findStats() {
        String needle = findText.toString();
        if (needle.isEmpty()) {
            return new int[] {0, 0};
        }
        int[] s = selection();
        boolean selectionIsMatch = hasSelection() && s[0] == s[2] && s[3] - s[1] == needle.length();
        int total = 0;
        int current = 0;
        for (int r = 0; r < lines.size(); r++) {
            String line = lines.get(r);
            int pos = 0;
            while (true) {
                int at = indexOfText(line, needle, pos, findMatchCase);
                if (at < 0) {
                    break;
                }
                total++;
                if (selectionIsMatch && r == s[0] && at == s[1]) {
                    current = total;
                }
                pos = at + needle.length();
                if (total >= 9999) {
                    return new int[] {total, current};
                }
            }
        }
        return new int[] {total, current};
    }

    /** Keys while the Find or Replace field has the focus. Everything is swallowed so nothing reaches the editor. */
    private boolean findKey(int keyCode) {
        boolean ctrl = Screen.hasControlDown();
        boolean shift = Screen.hasShiftDown();
        StringBuilder target = findFocusReplace ? replaceText : findText;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeFind();
        } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (ctrl) {
                runScript();
            } else if (findFocusReplace) {
                replaceOne();
            } else {
                findStep(!shift);
            }
        } else if (keyCode == GLFW.GLFW_KEY_F3) {
            findStep(!shift);
        } else if (keyCode == GLFW.GLFW_KEY_TAB) {
            if (findShowReplace) {
                findFocusReplace = !findFocusReplace;
            }
        } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (target.length() > 0) {
                target.setLength(target.length() - 1);
                if (!findFocusReplace) {
                    findIncremental();
                }
            }
        } else if (Screen.isPaste(keyCode)) {
            String clip = this.minecraft.keyboardHandler.getClipboard();
            int newline = clip.indexOf('\n');
            if (newline >= 0) {
                clip = clip.substring(0, newline);
            }
            for (char c : clip.toCharArray()) {
                if (c >= 32 && c != 127 && target.length() < 80) {
                    target.append(c);
                }
            }
            if (!findFocusReplace) {
                findIncremental();
            }
        } else if (ctrl && keyCode == GLFW.GLFW_KEY_F) {
            openFind(false);
        } else if (ctrl && keyCode == GLFW.GLFW_KEY_H) {
            openFind(true);
        } else if (ctrl && keyCode == GLFW.GLFW_KEY_S) {
            saveNow();
        }
        return true;
    }

    private void drawFindHits(GuiGraphics g, String line, int row, int x, int y) {
        String needle = findText.toString();
        int pos = 0;
        while (true) {
            int at = indexOfText(line, needle, pos, findMatchCase);
            if (at < 0) {
                break;
            }
            int x1 = x + this.font.width(line.substring(0, at));
            int x2 = x + this.font.width(line.substring(0, at + needle.length()));
            boolean current = hasSelection() && anchorRow == curRow && curRow == row
                    && Math.min(anchorCol, curCol) == at && Math.max(anchorCol, curCol) == at + needle.length();
            g.fill(x1, y - 1, Math.max(x2, x1 + 1), y + LINE_H - 1, current ? 0xAAE5A15A : 0x55E5C07B);
            pos = at + Math.max(1, needle.length());
        }
    }

    private void drawFindField(GuiGraphics g, int[] r, StringBuilder text, String placeholder, boolean focused) {
        box(g, r[0], r[1], r[2], r[3], 0xFF13191D, focused ? 0xFF3AA6B3 : 0xFF3A4850);
        String shown = text.toString();
        int tx = r[0] + 3;
        if (shown.isEmpty()) {
            g.drawString(this.font, placeholder, tx, r[1] + 2, 0xFF5B6A71, false);
        } else {
            shown = this.font.plainSubstrByWidth(shown, Math.max(4, r[2] - r[0] - 8), true);
            g.drawString(this.font, shown, tx, r[1] + 2, CODE_TEXT, false);
        }
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = tx + this.font.width(shown);
            g.fill(cx, r[1] + 2, cx + 1, r[1] + 9, 0xFF7FD6E0);
        }
    }

    private void drawFindButton(GuiGraphics g, int[] r, String label, boolean on) {
        if (on) {
            g.fill(r[0], r[1], r[2], r[3], 0xFF234C57);
        } else if (inside(r, hoverX, hoverY)) {
            g.fill(r[0], r[1], r[2], r[3], 0xFF2A353B);
        }
        smallCentered(g, label, (r[0] + r[2]) / 2, r[1] + 2, 0.9f, on ? 0xFF9FE3EC : 0xFFB5C3C9, r[2] - r[0] - 2);
    }

    /** The find bar, top right of the code area like in VS Code. */
    private void drawFindBar(GuiGraphics g) {
        setRect(findBar, 0, 0, 0, 0);
        setRect(findField, 0, 0, 0, 0);
        setRect(replaceField, 0, 0, 0, 0);
        setRect(findToggleBtn, 0, 0, 0, 0);
        setRect(findCaseBtn, 0, 0, 0, 0);
        setRect(findPrevBtn, 0, 0, 0, 0);
        setRect(findNextBtn, 0, 0, 0, 0);
        setRect(findCloseBtn, 0, 0, 0, 0);
        setRect(replaceOneBtn, 0, 0, 0, 0);
        setRect(replaceAllBtn, 0, 0, 0, 0);
        if (!findOpen || !editing()) {
            return;
        }
        int w = Math.min(codeAreaW - 24, 236);
        if (w < 170) {
            return;
        }
        int fieldH = 11;
        int h = 6 + fieldH + (findShowReplace ? fieldH + 3 : 0);
        int x2 = codeAreaX + codeAreaW - 8;
        int x1 = x2 - w;
        int y1 = codeAreaY + 3;
        setRect(findBar, x1, y1, x2, y1 + h);
        g.fill(x1 + 1, y1 + 2, x2 + 1, y1 + h + 2, 0x55000000);
        box(g, x1, y1, x2, y1 + h, 0xFF1E272D, 0xFF3A4850);

        int ry = y1 + 3;
        setRect(findToggleBtn, x1 + 3, ry, x1 + 12, ry + fieldH);
        setRect(findCloseBtn, x2 - 14, ry, x2 - 3, ry + fieldH);
        setRect(findNextBtn, x2 - 27, ry, x2 - 16, ry + fieldH);
        setRect(findPrevBtn, x2 - 40, ry, x2 - 29, ry + fieldH);
        setRect(findCaseBtn, x2 - 56, ry, x2 - 42, ry + fieldH);

        int[] stats = findStats();
        String count = "";
        int countColor = GUTTER;
        if (findText.length() > 0) {
            if (stats[0] == 0) {
                count = "No results";
                countColor = 0xFFF28B82;
            } else {
                count = (stats[1] > 0 ? String.valueOf(stats[1]) : "-") + "/" + stats[0];
            }
        }
        int countW = Math.round(this.font.width(count) * 0.8f);
        int countLeft = x2 - 60 - countW;
        smallLeft(g, count, countLeft, ry + 3, 0.8f, countColor, countW + 2);
        setRect(findField, x1 + 15, ry, countLeft - 3, ry + fieldH);
        drawFindField(g, findField, findText, "Find", findFocused && !findFocusReplace);

        drawFindButton(g, findToggleBtn, findShowReplace ? "v" : ">", false);
        drawFindButton(g, findCaseBtn, "Aa", findMatchCase);
        drawFindButton(g, findPrevBtn, "<", false);
        drawFindButton(g, findNextBtn, ">", false);
        drawFindButton(g, findCloseBtn, "x", false);

        if (findShowReplace) {
            int ry2 = ry + fieldH + 3;
            setRect(replaceAllBtn, x2 - 3 - 24, ry2, x2 - 3, ry2 + fieldH);
            setRect(replaceOneBtn, x2 - 3 - 24 - 3 - 36, ry2, x2 - 3 - 24 - 3, ry2 + fieldH);
            setRect(replaceField, x1 + 15, ry2, x2 - 3 - 24 - 3 - 36 - 3, ry2 + fieldH);
            drawFindField(g, replaceField, replaceText, "Replace", findFocused && findFocusReplace);
            drawFindButton(g, replaceOneBtn, "Replace", false);
            drawFindButton(g, replaceAllBtn, "All", false);
        }
    }

    // ---------------------------------------------------------- line commands

    /** Ctrl+Shift+D: duplicates the touched lines below themselves. */
    private void duplicateLines() {
        int[] rows = touchedRows();
        List<String> block = new ArrayList<>(lines.subList(rows[0], rows[1] + 1));
        int added = block.size();
        for (String line : block) {
            added += line.length();
        }
        if (totalChars() + added > PathRules.MAX_FILE_BYTES) {
            return;
        }
        beginEdit(0);
        lines.addAll(rows[1] + 1, block);
        int step = rows[1] - rows[0] + 1;
        curRow += step;
        anchorRow += step;
        edited();
    }

    /** Ctrl+Shift+K: deletes the touched lines. */
    private void deleteLines() {
        int[] rows = touchedRows();
        beginEdit(0);
        for (int r = rows[1]; r >= rows[0]; r--) {
            lines.remove(r);
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        curRow = Math.min(rows[0], lines.size() - 1);
        curCol = Math.min(curCol, lines.get(curRow).length());
        collapse();
        edited();
    }

    /** Alt+Up / Alt+Down: moves the touched lines one row up or down. */
    private void moveLines(int direction) {
        int[] rows = touchedRows();
        if ((direction < 0 && rows[0] == 0) || (direction > 0 && rows[1] >= lines.size() - 1)) {
            return;
        }
        beginEdit(0);
        if (direction < 0) {
            String above = lines.remove(rows[0] - 1);
            lines.add(rows[1], above);
        } else {
            String below = lines.remove(rows[1] + 1);
            lines.add(rows[0], below);
        }
        curRow += direction;
        anchorRow += direction;
        edited();
    }

    // ------------------------------------------------- selection helpers

    private boolean hasSelection() {
        return anchorRow != curRow || anchorCol != curCol;
    }

    private void collapse() {
        anchorRow = curRow;
        anchorCol = curCol;
    }

    /** The selection as {startRow, startCol, endRow, endCol}, start before end. */
    private int[] selection() {
        if (anchorRow < curRow || (anchorRow == curRow && anchorCol <= curCol)) {
            return new int[] {anchorRow, anchorCol, curRow, curCol};
        }
        return new int[] {curRow, curCol, anchorRow, anchorCol};
    }

    private String selectedText() {
        int[] s = selection();
        if (s[0] == s[2]) {
            return lines.get(s[0]).substring(s[1], s[3]);
        }
        StringBuilder sb = new StringBuilder(lines.get(s[0]).substring(s[1]));
        for (int r = s[0] + 1; r < s[2]; r++) {
            sb.append('\n').append(lines.get(r));
        }
        sb.append('\n').append(lines.get(s[2]), 0, s[3]);
        return sb.toString();
    }

    private void deleteSelection() {
        int[] s = selection();
        String first = lines.get(s[0]).substring(0, s[1]);
        String last = lines.get(s[2]).substring(s[3]);
        for (int r = s[2]; r > s[0]; r--) {
            lines.remove(r);
        }
        lines.set(s[0], first + last);
        curRow = s[0];
        curCol = s[1];
        collapse();
    }

    private void selectAll() {
        anchorRow = 0;
        anchorCol = 0;
        curRow = lines.size() - 1;
        curCol = lines.get(curRow).length();
        lastEditKind = 0;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void selectWordAt(int row, int col) {
        String line = lines.get(row);
        int start = Math.min(col, line.length());
        int end = start;
        while (start > 0 && isWordChar(line.charAt(start - 1))) {
            start--;
        }
        while (end < line.length() && isWordChar(line.charAt(end))) {
            end++;
        }
        curRow = anchorRow = row;
        anchorCol = start;
        curCol = end;
    }

    // ------------------------------------------------------------- editing

    private int totalChars() {
        int total = Math.max(0, lines.size() - 1);
        for (String line : lines) {
            total += line.length();
        }
        return total;
    }

    private UndoState snapshotState() {
        return new UndoState(new ArrayList<>(lines), curRow, curCol);
    }

    /**
     * Call before changing the text. Starts a new undo step, except that typing (kind 1), Backspace (2) and
     * Delete (3) in a row are merged into one step. Kind 0 never merges.
     */
    private void beginEdit(int kind) {
        if (kind == 0 || kind != lastEditKind || tickCount - lastEditTick > 15 || undo.isEmpty()) {
            undo.push(snapshotState());
            while (undo.size() > MAX_UNDO) {
                undo.removeLast();
            }
        }
        redo.clear();
        lastEditKind = kind;
    }

    private void edited() {
        dirty = true;
        lastEditTick = tickCount;
        ensureCursorVisible();
    }

    private void undoEdit() {
        if (undo.isEmpty()) {
            return;
        }
        redo.push(snapshotState());
        restore(undo.pop());
    }

    private void redoEdit() {
        if (redo.isEmpty()) {
            return;
        }
        undo.push(snapshotState());
        restore(redo.pop());
    }

    private void restore(UndoState state) {
        lines = new ArrayList<>(state.lines());
        curRow = Math.min(state.row(), lines.size() - 1);
        curCol = Math.min(state.col(), lines.get(curRow).length());
        collapse();
        lastEditKind = 0;
        edited();
    }

    private void insertText(String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        int removed = hasSelection() ? selectedText().length() : 0;
        if (text.isEmpty() || totalChars() - removed + text.length() > PathRules.MAX_FILE_BYTES) {
            return;
        }
        beginEdit(text.length() == 1 ? 1 : 0);
        if (hasSelection()) {
            deleteSelection();
        }
        String[] parts = text.split("\n", -1);
        String line = lines.get(curRow);
        String before = line.substring(0, curCol);
        String after = line.substring(curCol);
        if (parts.length == 1) {
            lines.set(curRow, before + parts[0] + after);
            curCol += parts[0].length();
        } else {
            lines.set(curRow, before + parts[0]);
            for (int i = 1; i < parts.length - 1; i++) {
                lines.add(curRow + i, parts[i]);
            }
            String last = parts[parts.length - 1];
            lines.add(curRow + parts.length - 1, last + after);
            curRow += parts.length - 1;
            curCol = last.length();
        }
        collapse();
        edited();
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '\'' || c == '`';
    }

    private static boolean isBracket(char c) {
        return OPENERS.indexOf(c) >= 0 || CLOSERS.indexOf(c) >= 0;
    }

    /**
     * Typing one character. Typing a closer or quote that is already right after the cursor just moves past it;
     * typing an opener or quote adds its partner (only when the next character is the end of the line, a space or
     * a closer, and for quotes not right after a word, so apostrophes in words stay normal).
     */
    private void typeChar(char c) {
        if (!hasSelection()) {
            String line = lines.get(curRow);
            char next = curCol < line.length() ? line.charAt(curCol) : '\0';
            char prev = curCol > 0 ? line.charAt(curCol - 1) : '\0';
            if (next == c && (CLOSERS.indexOf(c) >= 0 || isQuote(c))) {
                lastEditKind = 0;
                curCol++;
                collapse();
                ensureCursorVisible();
                return;
            }
            boolean room = next == '\0' || Character.isWhitespace(next) || CLOSERS.indexOf(next) >= 0
                    || next == ',' || next == ';';
            char closer = 0;
            int open = OPENERS.indexOf(c);
            if (open >= 0 && room) {
                closer = CLOSERS.charAt(open);
            } else if (isQuote(c) && room && !isWordChar(prev) && prev != '\\') {
                closer = c;
            }
            if (closer != 0 && totalChars() + 2 <= PathRules.MAX_FILE_BYTES) {
                insertText(String.valueOf(c));
                String now = lines.get(curRow);
                lines.set(curRow, now.substring(0, curCol) + closer + now.substring(curCol));
                edited();
                return;
            }
        }
        insertText(String.valueOf(c));
    }

    private void newline() {
        beginEdit(0);
        if (hasSelection()) {
            deleteSelection();
        }
        String line = lines.get(curRow);
        String before = line.substring(0, curCol);
        String after = line.substring(curCol);
        int base = 0;
        while (base < before.length() && before.charAt(base) == ' ') {
            base++;
        }
        String trimmed = before.stripTrailing();
        boolean opened = !trimmed.isEmpty() && OPENERS.indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0;
        int indent = base + (opened ? 2 : 0);
        // Enter between a bracket pair: the closer goes on its own line, the cursor on an indented line between.
        int pairIndex = before.isEmpty() ? -1 : OPENERS.indexOf(before.charAt(before.length() - 1));
        boolean split = pairIndex >= 0 && !after.isEmpty() && CLOSERS.charAt(pairIndex) == after.charAt(0);
        if (totalChars() + 1 + indent + (split ? 1 + base : 0) > PathRules.MAX_FILE_BYTES) {
            collapse();
            edited();
            return;
        }
        String pad = " ".repeat(indent);
        lines.set(curRow, before);
        lines.add(curRow + 1, pad + (split ? "" : after));
        if (split) {
            lines.add(curRow + 2, " ".repeat(base) + after);
        }
        curRow++;
        curCol = pad.length();
        collapse();
        edited();
    }

    private void backspace() {
        if (hasSelection()) {
            beginEdit(0);
            deleteSelection();
            edited();
            return;
        }
        if (curCol == 0 && curRow == 0) {
            return;
        }
        beginEdit(2);
        if (curCol > 0) {
            String line = lines.get(curRow);
            // Backspace between an empty pair like () or "" removes both characters.
            int removeAfter = 0;
            if (curCol < line.length()) {
                char before = line.charAt(curCol - 1);
                char after = line.charAt(curCol);
                int oi = OPENERS.indexOf(before);
                if ((oi >= 0 && CLOSERS.charAt(oi) == after) || (isQuote(before) && before == after)) {
                    removeAfter = 1;
                }
            }
            lines.set(curRow, line.substring(0, curCol - 1) + line.substring(curCol + removeAfter));
            curCol--;
        } else {
            String previous = lines.get(curRow - 1);
            curCol = previous.length();
            lines.set(curRow - 1, previous + lines.get(curRow));
            lines.remove(curRow);
            curRow--;
        }
        collapse();
        edited();
    }

    private void deleteForward() {
        if (hasSelection()) {
            beginEdit(0);
            deleteSelection();
            edited();
            return;
        }
        String line = lines.get(curRow);
        if (curCol >= line.length() && curRow >= lines.size() - 1) {
            return;
        }
        beginEdit(3);
        if (curCol < line.length()) {
            lines.set(curRow, line.substring(0, curCol) + line.substring(curCol + 1));
        } else {
            lines.set(curRow, line + lines.get(curRow + 1));
            lines.remove(curRow + 1);
        }
        collapse();
        edited();
    }

    private void copySelection() {
        String text = hasSelection() ? selectedText() : lines.get(curRow) + "\n";
        this.minecraft.keyboardHandler.setClipboard(text);
    }

    private void cutSelection() {
        copySelection();
        beginEdit(0);
        if (hasSelection()) {
            deleteSelection();
        } else if (lines.size() > 1) {
            lines.remove(curRow);
            curRow = Math.min(curRow, lines.size() - 1);
            curCol = 0;
            collapse();
        } else {
            lines.set(0, "");
            curCol = 0;
            collapse();
        }
        edited();
    }

    /** First and last row touched by the selection (or just the cursor row). */
    private int[] touchedRows() {
        if (!hasSelection()) {
            return new int[] {curRow, curRow};
        }
        int[] s = selection();
        int last = s[2];
        if (s[3] == 0 && s[2] > s[0]) {
            last--; // a selection that ends at the start of a line doesn't include that line
        }
        return new int[] {s[0], last};
    }

    /** Ctrl+/: comment or uncomment the touched lines. */
    private void toggleComment() {
        int[] rows = touchedRows();
        boolean allCommented = true;
        boolean anyText = false;
        int minIndent = Integer.MAX_VALUE;
        for (int r = rows[0]; r <= rows[1]; r++) {
            String line = lines.get(r);
            if (line.isBlank()) {
                continue;
            }
            anyText = true;
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            minIndent = Math.min(minIndent, indent);
            if (!line.startsWith("//", indent)) {
                allCommented = false;
            }
        }
        if (!anyText) {
            return;
        }
        beginEdit(0);
        for (int r = rows[0]; r <= rows[1]; r++) {
            String line = lines.get(r);
            if (line.isBlank()) {
                continue;
            }
            int delta;
            if (allCommented) {
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') {
                    indent++;
                }
                int remove = line.startsWith("// ", indent) ? 3 : 2;
                lines.set(r, line.substring(0, indent) + line.substring(indent + remove));
                delta = -remove;
            } else {
                lines.set(r, line.substring(0, minIndent) + "// " + line.substring(minIndent));
                delta = 3;
            }
            if (curRow == r) {
                curCol = Math.max(0, curCol + delta);
            }
            if (anchorRow == r) {
                anchorCol = Math.max(0, anchorCol + delta);
            }
        }
        edited();
    }

    /** Tab / Shift+Tab on a selection or on the current line: indent or outdent by two spaces. */
    private void indentLines(boolean outdent) {
        int[] rows = touchedRows();
        beginEdit(0);
        for (int r = rows[0]; r <= rows[1]; r++) {
            String line = lines.get(r);
            int delta;
            if (outdent) {
                int remove = 0;
                while (remove < 2 && remove < line.length() && line.charAt(remove) == ' ') {
                    remove++;
                }
                lines.set(r, line.substring(remove));
                delta = -remove;
            } else {
                if (line.isEmpty()) {
                    continue;
                }
                lines.set(r, "  " + line);
                delta = 2;
            }
            if (curRow == r) {
                curCol = Math.max(0, curCol + delta);
            }
            if (anchorRow == r) {
                anchorCol = Math.max(0, anchorCol + delta);
            }
        }
        edited();
    }

    // ------------------------------------------------------------ movement

    private void move(boolean extendSelection, Runnable mover) {
        lastEditKind = 0;
        mover.run();
        if (!extendSelection) {
            collapse();
        }
        ensureCursorVisible();
    }

    private void moveLeft() {
        if (curCol > 0) {
            curCol--;
        } else if (curRow > 0) {
            curRow--;
            curCol = lines.get(curRow).length();
        }
    }

    private void moveRight() {
        if (curCol < lines.get(curRow).length()) {
            curCol++;
        } else if (curRow < lines.size() - 1) {
            curRow++;
            curCol = 0;
        }
    }

    private void wordLeft() {
        if (curCol == 0) {
            moveLeft();
            return;
        }
        String line = lines.get(curRow);
        int c = curCol;
        while (c > 0 && !isWordChar(line.charAt(c - 1))) {
            c--;
        }
        while (c > 0 && isWordChar(line.charAt(c - 1))) {
            c--;
        }
        curCol = c;
    }

    private void wordRight() {
        String line = lines.get(curRow);
        if (curCol >= line.length()) {
            moveRight();
            return;
        }
        int c = curCol;
        while (c < line.length() && !isWordChar(line.charAt(c))) {
            c++;
        }
        while (c < line.length() && isWordChar(line.charAt(c))) {
            c++;
        }
        curCol = c;
    }

    private void moveRows(int delta) {
        int target = Math.max(0, Math.min(lines.size() - 1, curRow + delta));
        if (target == curRow) {
            curCol = delta < 0 ? 0 : lines.get(curRow).length();
            return;
        }
        curRow = target;
        curCol = Math.min(curCol, lines.get(curRow).length());
    }

    /** Home: first press goes to the first non-space character, second press to the start of the line. */
    private void smartHome() {
        String line = lines.get(curRow);
        int first = 0;
        while (first < line.length() && line.charAt(first) == ' ') {
            first++;
        }
        curCol = curCol == first ? 0 : first;
    }

    private void lineEnd() {
        curCol = lines.get(curRow).length();
    }

    private void docStart() {
        curRow = 0;
        curCol = 0;
    }

    private void docEnd() {
        curRow = lines.size() - 1;
        curCol = lines.get(curRow).length();
    }

    private void ensureCursorVisible() {
        int visibleRows = Math.max(1, (codeAreaH - 6) / LINE_H);
        if (curRow < scrollY) {
            scrollY = curRow;
        }
        if (curRow >= scrollY + visibleRows) {
            scrollY = curRow - visibleRows + 1;
        }
        int avail = Math.max(24, codeAreaW - GUTTER_W - 14);
        int cursorX = this.font.width(lines.get(curRow).substring(0, curCol));
        if (cursorX - scrollX > avail - 4) {
            scrollX = cursorX - avail + 4;
        }
        if (cursorX < scrollX) {
            scrollX = Math.max(0, cursorX - 16);
        }
    }

    private int colAt(String line, int px) {
        for (int i = 0; i < line.length(); i++) {
            int before = this.font.width(line.substring(0, i));
            int after = this.font.width(line.substring(0, i + 1));
            if (px < (before + after) / 2) {
                return i;
            }
        }
        return line.length();
    }

    private int rowAtMouse(int my) {
        return Math.max(0, Math.min(lines.size() - 1, scrollY + (my - codeAreaY - 4) / LINE_H));
    }

    private int colAtMouse(int row, int mx) {
        return colAt(lines.get(row), mx - (codeAreaX + GUTTER_W + 4) + scrollX);
    }

    // -------------------------------------------------------------- input

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (deleteTarget != null) {
            return true;
        }
        if (promptKind != null) {
            if (codePoint >= 32 && codePoint != 127 && promptText.length() < 48) {
                promptText.append(codePoint);
                promptInvalid = false;
            }
            return true;
        }
        if (findOpen && findFocused && editing()) {
            if (codePoint >= 32 && codePoint != 127) {
                StringBuilder target = findFocusReplace ? replaceText : findText;
                if (target.length() < 80) {
                    target.append(codePoint);
                    if (!findFocusReplace) {
                        findIncremental();
                    }
                }
            }
            return true;
        }
        if (editing()) {
            explorerFocus = false;
            if (codePoint >= 32 && codePoint != 127) {
                typeChar(codePoint);
                if (Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '$'
                        || codePoint == '.' || codePoint == '"' || codePoint == ':') {
                    refreshCompletion(false);
                } else {
                    closeCompletion();
                }
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (deleteTarget != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmDelete();
            } else if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                deleteTarget = null;
            }
            return true;
        }
        if (menuOpen()) {
            closeMenu();
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                return true;
            }
        }
        if (promptKind != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                promptKind = null;
            } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmPrompt();
            } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (promptText.length() > 0) {
                    promptText.setLength(promptText.length() - 1);
                }
                promptInvalid = false;
            } else if (Screen.isPaste(keyCode)) {
                String clip = this.minecraft.keyboardHandler.getClipboard();
                for (char c : clip.toCharArray()) {
                    if (c >= 32 && c != 127 && promptText.length() < 48) {
                        promptText.append(c);
                    }
                }
                promptInvalid = false;
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_F5) {
            runScript();
            return true;
        }

        // F2 renames and Delete deletes the entry last clicked in the Explorer (the open file for F2 otherwise).
        if (explorerFocus && snapshot.connected() && !selectedEntry.isEmpty()) {
            if (keyCode == GLFW.GLFW_KEY_F2) {
                beginRename(selectedEntry);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DELETE) {
                askDelete(selectedEntry);
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_F2 && openPath != null && mediaInfoPath == null && snapshot.connected()) {
            beginRename(openPath);
            return true;
        }
        if (findOpen && findFocused && editing()) {
            return findKey(keyCode);
        }

        if (editing()) {
            boolean ctrl = Screen.hasControlDown();
            boolean shift = Screen.hasShiftDown();

            // Autocomplete popup: Up/Down choose, Tab or Enter accept, Esc closes, Ctrl+Space opens it.
            if (completionOpen) {
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    closeCompletion();
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_UP) {
                    completionIndex = Math.floorMod(completionIndex - 1, completionItems.size());
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_DOWN) {
                    completionIndex = Math.floorMod(completionIndex + 1, completionItems.size());
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_TAB || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                    acceptCompletion();
                    return true;
                }
            }
            if (ctrl && keyCode == GLFW.GLFW_KEY_SPACE) {
                refreshCompletion(true);
                return true;
            }
            if (ctrl) {
                closeCompletion();
            }

            if (ctrl) {
                switch (keyCode) {
                    case GLFW.GLFW_KEY_S -> {
                        saveNow();
                        return true;
                    }
                    case GLFW.GLFW_KEY_A -> {
                        selectAll();
                        return true;
                    }
                    case GLFW.GLFW_KEY_C -> {
                        copySelection();
                        return true;
                    }
                    case GLFW.GLFW_KEY_X -> {
                        cutSelection();
                        return true;
                    }
                    case GLFW.GLFW_KEY_V -> {
                        insertText(this.minecraft.keyboardHandler.getClipboard());
                        return true;
                    }
                    case GLFW.GLFW_KEY_Z -> {
                        if (shift) {
                            redoEdit();
                        } else {
                            undoEdit();
                        }
                        return true;
                    }
                    case GLFW.GLFW_KEY_Y -> {
                        redoEdit();
                        return true;
                    }
                    case GLFW.GLFW_KEY_SLASH -> {
                        toggleComment();
                        return true;
                    }
                    case GLFW.GLFW_KEY_F -> {
                        openFind(false);
                        return true;
                    }
                    case GLFW.GLFW_KEY_H -> {
                        openFind(true);
                        return true;
                    }
                    case GLFW.GLFW_KEY_D -> {
                        if (shift) {
                            duplicateLines();
                            return true;
                        }
                    }
                    case GLFW.GLFW_KEY_K -> {
                        if (shift) {
                            deleteLines();
                            return true;
                        }
                    }
                    case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                        runScript();
                        return true;
                    }
                    case GLFW.GLFW_KEY_W -> {
                        closeTab(openPath);
                        return true;
                    }
                    case GLFW.GLFW_KEY_TAB -> {
                        switchTab(shift ? -1 : 1);
                        return true;
                    }
                    case GLFW.GLFW_KEY_PAGE_DOWN -> {
                        switchTab(1);
                        return true;
                    }
                    case GLFW.GLFW_KEY_PAGE_UP -> {
                        switchTab(-1);
                        return true;
                    }
                    default -> {
                    }
                }
            }

            switch (keyCode) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> newline();
                case GLFW.GLFW_KEY_BACKSPACE -> backspace();
                case GLFW.GLFW_KEY_DELETE -> deleteForward();
                case GLFW.GLFW_KEY_TAB -> {
                    if (shift) {
                        indentLines(true);
                    } else if (hasSelection() && anchorRow != curRow) {
                        indentLines(false);
                    } else {
                        insertText("  ");
                    }
                }
                case GLFW.GLFW_KEY_LEFT -> {
                    if (hasSelection() && !shift && !ctrl) {
                        int[] s = selection();
                        curRow = s[0];
                        curCol = s[1];
                        collapse();
                        lastEditKind = 0;
                        ensureCursorVisible();
                    } else {
                        move(shift, ctrl ? this::wordLeft : this::moveLeft);
                    }
                }
                case GLFW.GLFW_KEY_RIGHT -> {
                    if (hasSelection() && !shift && !ctrl) {
                        int[] s = selection();
                        curRow = s[2];
                        curCol = s[3];
                        collapse();
                        lastEditKind = 0;
                        ensureCursorVisible();
                    } else {
                        move(shift, ctrl ? this::wordRight : this::moveRight);
                    }
                }
                case GLFW.GLFW_KEY_UP -> {
                    if (Screen.hasAltDown()) {
                        moveLines(-1);
                    } else {
                        move(shift, () -> moveRows(-1));
                    }
                }
                case GLFW.GLFW_KEY_DOWN -> {
                    if (Screen.hasAltDown()) {
                        moveLines(1);
                    } else {
                        move(shift, () -> moveRows(1));
                    }
                }
                case GLFW.GLFW_KEY_F3 -> findStep(!shift);
                case GLFW.GLFW_KEY_ESCAPE -> {
                    if (!findOpen) {
                        return super.keyPressed(keyCode, scanCode, modifiers);
                    }
                    closeFind();
                }
                case GLFW.GLFW_KEY_PAGE_UP -> move(shift, () -> moveRows(-10));
                case GLFW.GLFW_KEY_PAGE_DOWN -> move(shift, () -> moveRows(10));
                case GLFW.GLFW_KEY_HOME -> move(shift, ctrl ? this::docStart : this::smartHome);
                case GLFW.GLFW_KEY_END -> move(shift, ctrl ? this::docEnd : this::lineEnd);
                default -> {
                    return super.keyPressed(keyCode, scanCode, modifiers);
                }
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                refreshCompletion(false);
            } else {
                closeCompletion();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;

        // The delete confirmation is modal: only its two buttons react.
        if (deleteTarget != null) {
            if (button == 0 && inside(deleteYes, mx, my)) {
                confirmDelete();
            } else if (button == 0 && inside(deleteNo, mx, my)) {
                deleteTarget = null;
            }
            return true;
        }
        // The right-click menu: a click on an entry runs it, any other click closes the menu and goes on.
        if (menuOpen()) {
            if (button == 0 && inside(menuRect, mx, my)) {
                int item = (my - menuRect[1] - 2) / MENU_ROW;
                if (item >= 0 && item < menuItems.size()) {
                    String id = menuItems.get(item).id();
                    closeMenu();
                    runMenuItem(id);
                }
                return true;
            }
            closeMenu();
        }
        explorerFocus = false;

        if (completionOpen) {
            if (button == 0 && inside(popupRect, mx, my)) {
                completionIndex = Math.max(0, Math.min(completionItems.size() - 1, (my - popupRect[1] - 2) / LINE_H));
                acceptCompletion();
                return true;
            }
            closeCompletion();
        }

        // Tabs: left click selects (or closes when on the x), middle click closes.
        if ((button == 0 || button == 2) && inside(tabBar, mx, my)) {
            for (int i = 0; i < tabRects.size() && i < tabs.size(); i++) {
                int[] rect = tabRects.get(i);
                if (mx >= rect[0] && mx < rect[1]) {
                    String path = tabs.get(i);
                    if (button == 2 || mx >= rect[1] - 12) {
                        closeTab(path);
                    } else {
                        openFile(path);
                    }
                    return true;
                }
            }
            return true;
        }
        if (button == 1 && snapshot.connected() && mx >= exX && mx < exX + exW && my >= treeTop && my < treeBottom) {
            promptKind = null;
            explorerFocus = true;
            openMenu(mx, my);
            return true;
        }
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        if (promptKind != null && inside(promptBox, mx, my)) {
            return true;
        }
        promptKind = null;

        if (findOpen && editing() && inside(findBar, mx, my)) {
            if (inside(findCloseBtn, mx, my)) {
                closeFind();
            } else if (inside(findToggleBtn, mx, my)) {
                findShowReplace = !findShowReplace;
                if (!findShowReplace) {
                    findFocusReplace = false;
                }
            } else if (inside(findCaseBtn, mx, my)) {
                findMatchCase = !findMatchCase;
            } else if (inside(findPrevBtn, mx, my)) {
                findStep(false);
            } else if (inside(findNextBtn, mx, my)) {
                findStep(true);
            } else if (inside(replaceOneBtn, mx, my)) {
                replaceOne();
            } else if (inside(replaceAllBtn, mx, my)) {
                replaceAll();
            } else if (inside(findField, mx, my)) {
                findFocused = true;
                findFocusReplace = false;
            } else if (inside(replaceField, mx, my)) {
                findFocused = true;
                findFocusReplace = true;
            }
            return true;
        }
        if (inside(runBtn, mx, my)) {
            runScript();
            return true;
        }
        if (inside(stopBtn, mx, my)) {
            if (snapshot.connected() && scriptRunning) {
                PacketDistributor.sendToServer(new LaptopAction(snapshot.laptop(), LaptopAction.STOP, "", ""));
            }
            return true;
        }
        if (editing()) {
            for (int[] hit : cardHit) {
                if (inside(hit, mx, my)) {
                    insertText(cards.get(hit[4]).id()); // lands inside get("") when the cursor is there
                    closeCompletion();
                    return true;
                }
            }
        }
        if (inside(fileBtn, mx, my)) {
            beginPrompt("file");
            return true;
        }
        if (inside(folderBtn, mx, my)) {
            beginPrompt("folder");
            return true;
        }
        if (mx >= exX && mx < exX + exW && my >= treeTop && my < exY + exH) {
            explorerFocus = true;
            int index = treeScroll + (my - treeTop) / ROW_H;
            if (index >= 0 && index < tree.size() && my < treeBottom) {
                TreeRow row = tree.get(index);
                selectedEntry = row.path();
                dragPath = row.path();
                dragActive = false;
                dragStartX = mx;
                dragStartY = my;
                dragTarget = null;
                dragFolderToggle = null;
                if (row.folder()) {
                    // A click selects the folder (new files go into it); it opens or closes when the button is released
                    // without a drag.
                    selectedFolder = row.path();
                    dragFolderToggle = row.path();
                } else if (row.media() != 0) {
                    mediaInfoPath = row.path(); // media can't be edited: show its info card
                    selectedFolder = PathRules.parentOf(row.path());
                } else {
                    openFile(row.path());
                }
            } else {
                selectedFolder = "";
                selectedEntry = "";
            }
            return true;
        }
        if (editing()
                && mx >= codeAreaX && mx < codeAreaX + codeAreaW && my >= codeAreaY && my < codeAreaY + codeAreaH) {
            int row = rowAtMouse(my);
            int col = colAtMouse(row, mx);
            findFocused = false;
            long now = System.currentTimeMillis();
            boolean doubleClick = now - lastClickTime < 350 && row == lastClickRow && col == lastClickCol;
            lastClickTime = now;
            lastClickRow = row;
            lastClickCol = col;
            lastEditKind = 0;
            if (doubleClick) {
                selectWordAt(row, col);
            } else {
                curRow = row;
                curCol = col;
                if (!Screen.hasShiftDown()) {
                    collapse();
                }
                dragging = true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragPath != null && button == 0) {
            if (!dragActive && Math.abs(mouseX - dragStartX) + Math.abs(mouseY - dragStartY) > 5) {
                dragActive = true;
                dragFolderToggle = null;
            }
            if (dragActive) {
                dragTarget = dropTargetAt((int) mouseX, (int) mouseY);
                return true;
            }
        }
        if (dragging && button == 0 && editing()) {
            curRow = rowAtMouse((int) mouseY);
            curCol = colAtMouse(curRow, (int) mouseX);
            ensureCursorVisible();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragPath != null) {
            String from = dragPath;
            boolean wasDrag = dragActive;
            String target = dragActive ? dropTargetAt((int) mouseX, (int) mouseY) : null;
            String toggle = dragFolderToggle;
            dragPath = null;
            dragActive = false;
            dragTarget = null;
            dragFolderToggle = null;
            if (wasDrag && target != null && snapshot.connected()) {
                moveInto(from, target);
            } else if (toggle != null) {
                if (!collapsed.remove(toggle)) {
                    collapsed.add(toggle);
                }
                rebuildTree();
            }
            return true;
        }
        dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDeltaX, double scrollDeltaY) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        int step = -(int) Math.signum(scrollDeltaY);
        if (inside(tabBar, mx, my)) {
            tabScroll += step * 24;
            return true;
        }
        if (mx >= exX && mx < exX + exW && my >= treeTop && my < exY + exH) {
            treeScroll += step;
            return true;
        }
        if (mx >= rpX && mx < rpX + rpW && my >= rpY && my < rpY + rpH) {
            deviceScroll += step;
            return true;
        }
        if (openPath != null && mx >= codeAreaX && mx < codeAreaX + codeAreaW
                && my >= codeAreaY && my < codeAreaY + codeAreaH) {
            if (Screen.hasShiftDown()) {
                scrollX = Math.max(0, scrollX + step * 24);
            } else {
                scrollY = Math.max(0, Math.min(lines.size() - 1, scrollY + step * 3));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollDeltaX, scrollDeltaY);
    }

    private static boolean inside(int[] rect, int x, int y) {
        return x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3];
    }

    // -------------------------------------------------------------- render

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0x99000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        hoverX = mouseX;
        hoverY = mouseY;

        int w = Math.min(this.width - 10, 620);
        int h = Math.min(this.height - 10, 350);
        int x0 = (this.width - w) / 2;
        int y0 = (this.height - h) / 2;

        int topH = 18;
        int statusH = 12;
        int bodyY = y0 + topH;
        int bodyH = h - topH - statusH;
        int leftW = Math.round(w * 0.22f);
        int rightW = Math.round(w * 0.30f);
        int midW = w - leftW - rightW;

        // Window frame with a soft shadow.
        g.fill(x0 + 2, y0 + 3, x0 + w + 4, y0 + h + 5, 0x44000000);
        g.fill(x0 + 1, y0 + 2, x0 + w + 3, y0 + h + 4, 0x33000000);
        box(g, x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, CHROME, BORDER);

        drawTopBar(g, x0, y0, w, topH);
        drawExplorer(g, x0, bodyY, leftW, bodyH, mouseX, mouseY);
        drawEditor(g, x0 + leftW, bodyY, midW, bodyH, mouseX, mouseY);
        drawDevices(g, x0 + leftW + midW, bodyY, rightW, bodyH);
        drawStatusBar(g, x0, y0 + h - statusH, w, statusH);
        drawToast(g, x0, y0 + h - statusH, w);
        drawMenu(g);
        drawDeleteDialog(g, x0, y0, w, h);
        drawDragGhost(g, mouseX, mouseY);
        if (deleteTarget == null && !menuOpen() && !dragActive) {
            drawHoverTips(g, mouseX, mouseY);
        }
    }

    /** Small tooltips for the Run and Stop buttons and the device cards. Drawn last so they sit on top. */
    private void drawHoverTips(GuiGraphics g, int mx, int my) {
        if (inside(runBtn, mx, my)) {
            g.renderTooltip(this.font, Component.literal(snapshot.connected()
                    ? "Run main.js (F5 or Ctrl+Enter)" : "Not connected to a Brain"), mx, my);
        } else if (inside(stopBtn, mx, my) && snapshot.connected() && scriptRunning) {
            g.renderTooltip(this.font, Component.literal("Stop the script"), mx, my);
        } else if (editing()) {
            for (int[] hit : cardHit) {
                if (inside(hit, mx, my)) {
                    g.renderTooltip(this.font, Component.literal("Insert " + cards.get(hit[4]).id()), mx, my);
                    break;
                }
            }
        }
    }

    private void drawTopBar(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, CHROME);
        g.fill(x, y + h - 1, x + w, y + h, BORDER);

        int textY = y + (h - 8) / 2;
        int cx = x + 6;
        g.drawString(this.font, Component.literal("MineComputers").withStyle(ChatFormatting.BOLD), cx, textY, TEXT, false);
        // Measure the bold title (bold text is wider than plain text), or the pills overlap it.
        cx += this.font.width(Component.literal("MineComputers").withStyle(ChatFormatting.BOLD)) + 8;

        // Pills only use the space left of the Stop button; the gateway label shortens if needed.
        int limit = x + w - 6 - 30 - 4 - 30 - 4;
        if (snapshot.connected()) {
            cx += pill(g, cx, y + 3, h - 6, "brain:1 linked", 0xFF2FA866) + 4;
        } else {
            cx += pill(g, cx, y + 3, h - 6, "not connected", 0xFFB5C0C5) + 4;
        }
        if (snapshot.connected() && hasGateway) {
            for (String label : new String[] {"Remote Gateway, 50 blocks", "Gateway, 50 blocks", "50 blocks"}) {
                if (cx + this.font.width(label) + 8 <= limit) {
                    pill(g, cx, y + 3, h - 6, label, -1);
                    break;
                }
            }
        }

        // Run / Stop.
        boolean connected = snapshot.connected();
        boolean canStop = connected && scriptRunning;
        int runW = 30;
        int stopW = 30;
        int bx = x + w - 6 - runW;
        setRect(runBtn, bx, y + 3, bx + runW, y + h - 3);
        int runColor = !connected ? 0xFF9FB4B9 : (inside(runBtn, hoverX, hoverY) ? 0xFF2896A5 : TEAL);
        box(g, bx, y + 3, bx + runW, y + h - 3, runColor, runColor);
        g.drawCenteredString(this.font, "Run", bx + runW / 2, textY, 0xFFFFFFFF);
        bx -= stopW + 4;
        setRect(stopBtn, bx, y + 3, bx + stopW, y + h - 3);
        boolean overStop = canStop && inside(stopBtn, hoverX, hoverY);
        box(g, bx, y + 3, bx + stopW, y + h - 3, canStop ? (overStop ? 0xFFFBE9E8 : 0xFFFFFFFF) : PANEL, overStop ? ERROR : BORDER);
        g.drawCenteredString(this.font, "Stop", bx + stopW / 2, textY, canStop ? TEXT : 0xFF9AA8AE);
    }

    /** A small label with an optional colored dot. Returns its width. */
    private int pill(GuiGraphics g, int x, int y, int h, String text, int dotColor) {
        int pad = 4;
        int dot = dotColor == -1 ? 0 : 8;
        int pw = this.font.width(text) + pad * 2 + dot;
        box(g, x, y, x + pw, y + h, 0xFFF4F7F8, BORDER);
        if (dotColor != -1) {
            g.fill(x + pad, y + h / 2 - 2, x + pad + 4, y + h / 2 + 2, dotColor);
        }
        g.drawString(this.font, text, x + pad + dot, y + (h - 8) / 2 + 1, TEXT, false);
        return pw;
    }

    // --------------------------------------------------------------- explorer

    private void drawExplorer(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        exX = x;
        exY = y;
        exW = w;
        exH = h;
        boolean connected = snapshot.connected();

        g.fill(x, y, x + w, y + h, PANEL);
        g.fill(x + w - 1, y, x + w, y + h, BORDER);
        g.drawString(this.font, "EXPLORER", x + 6, y + 5, MUTED, false);

        // "+ File" and "+ Folder" buttons.
        int btnY1 = y + 16;
        int btnY2 = btnY1 + 12;
        int half = (w - 12 - 4) / 2;
        setRect(fileBtn, x + 5, btnY1, x + 5 + half, btnY2);
        setRect(folderBtn, x + 5 + half + 4, btnY1, x + 5 + half + 4 + half, btnY2);
        drawButton(g, fileBtn, "+ File", connected);
        drawButton(g, folderBtn, "+ Folder", connected);

        int top = btnY2 + 4;
        // Name prompt, shown after pressing one of the buttons.
        setRect(promptBox, 0, 0, 0, 0);
        if (promptKind != null) {
            String where = selectedFolder.isEmpty() ? "top level" : selectedFolder;
            String label = "rename".equals(promptKind)
                    ? "Rename (use a/b to move)"
                    : "New " + promptKind + " in " + where;
            smallLeft(g, label, x + 6, top, 0.7f, MUTED, w - 12);
            int py1 = top + 8;
            int py2 = py1 + 12;
            setRect(promptBox, x + 5, py1, x + w - 6, py2);
            box(g, x + 5, py1, x + w - 6, py2, 0xFFFFFFFF, promptInvalid ? ERROR : TEAL);
            String shown = promptText.toString();
            int textX = x + 8;
            if (shown.isEmpty()) {
                g.drawString(this.font, "folder".equals(promptKind) ? "name" : "name.js", textX, py1 + 2, 0xFFA8B6BC, false);
            } else {
                String clipped = this.font.plainSubstrByWidth(shown, w - 20, true);
                g.drawString(this.font, clipped, textX, py1 + 2, TEXT, false);
                shown = clipped;
            }
            if ((System.currentTimeMillis() / 500) % 2 == 0) {
                int caretX = textX + this.font.width(shown);
                g.fill(caretX, py1 + 2, caretX + 1, py1 + 10, TEXT);
            }
            top = py2 + 4;
        }

        treeTop = top;
        int dzH = 36;
        int dx1 = x + 5;
        int dx2 = x + w - 6;
        int dy2 = y + h - 6;
        int dy1 = dy2 - dzH;
        treeBottom = dy1;
        treeVisibleRows = Math.max(1, (dy1 - 2 - treeTop) / ROW_H);
        treeScroll = Math.max(0, Math.min(treeScroll, Math.max(0, tree.size() - treeVisibleRows)));

        boolean mouseInTree = mouseX >= x && mouseX < x + w && mouseY >= treeTop && mouseY < dy1;
        int hoverIndex = mouseInTree ? treeScroll + (mouseY - treeTop) / ROW_H : -1;
        for (int i = treeScroll; i < tree.size() && i < treeScroll + treeVisibleRows; i++) {
            TreeRow e = tree.get(i);
            int ry = treeTop + (i - treeScroll) * ROW_H;
            int ex = x + 4 + e.indent() * 8;
            boolean activeRow = !e.folder() && (e.media() != 0
                    ? e.path().equals(mediaInfoPath)
                    : (mediaInfoPath == null && e.path().equals(openPath)));
            if (dragActive && e.folder() && e.path().equals(dragTarget)) {
                g.fill(x + 1, ry - 1, x + w - 1, ry + ROW_H - 2, 0xFFBFDDE3);
            } else if (activeRow) {
                g.fill(x + 1, ry - 1, x + w - 1, ry + ROW_H - 2, TEAL_LIGHT);
            } else if (explorerFocus && e.path().equals(selectedEntry)) {
                g.fill(x + 1, ry - 1, x + w - 1, ry + ROW_H - 2, 0xFFD3E6EA);
            } else if (e.folder() && e.path().equals(selectedFolder)) {
                g.fill(x + 1, ry - 1, x + w - 1, ry + ROW_H - 2, 0xFFE9F1F3);
            } else if (i == hoverIndex) {
                g.fill(x + 1, ry - 1, x + w - 1, ry + ROW_H - 2, 0xFFE4ECEF);
            }
            // Icon: amber for folders, grey page for files.
            if (e.folder()) {
                // Arrow: right when collapsed, down when open.
                int ac = 0xFF6B7C84;
                if (collapsed.contains(e.path())) {
                    g.fill(ex + 1, ry + 1, ex + 2, ry + 8, ac);
                    g.fill(ex + 2, ry + 2, ex + 3, ry + 7, ac);
                    g.fill(ex + 3, ry + 3, ex + 4, ry + 6, ac);
                    g.fill(ex + 4, ry + 4, ex + 5, ry + 5, ac);
                } else {
                    g.fill(ex, ry + 2, ex + 7, ry + 3, ac);
                    g.fill(ex + 1, ry + 3, ex + 6, ry + 4, ac);
                    g.fill(ex + 2, ry + 4, ex + 5, ry + 5, ac);
                    g.fill(ex + 3, ry + 5, ex + 4, ry + 6, ac);
                }
                g.fill(ex + 8, ry + 2, ex + 14, ry + 7, 0xFFC9A04A);
            } else if (e.media() != 0) {
                g.fill(ex + 9, ry + 1, ex + 14, ry + 8, mediaColor(e.media()));
            } else {
                g.fill(ex + 9, ry + 1, ex + 14, ry + 8, 0xFF8A9AA2);
            }
            String name = this.font.plainSubstrByWidth(e.name(), w - (ex - x) - 24);
            g.drawString(this.font, name, ex + 18, ry, TEXT, false);
        }
        if (tree.size() > treeVisibleRows) {
            // Thin scroll bar.
            int trackH = treeVisibleRows * ROW_H;
            int thumbH = Math.max(8, trackH * treeVisibleRows / tree.size());
            int thumbY = treeTop + (trackH - thumbH) * treeScroll / Math.max(1, tree.size() - treeVisibleRows);
            g.fill(x + w - 4, thumbY, x + w - 2, thumbY + thumbH, 0xFF9FB0B7);
        }
        if (tree.isEmpty() && connected && promptKind == null) {
            List<FormattedCharSequence> hint = this.font.split(
                    Component.literal("Empty. Press + File to create your first file, for example main.js."), Math.round((w - 12) / 0.7f));
            g.pose().pushPose();
            g.pose().translate(x + 6, treeTop, 0);
            g.pose().scale(0.7f, 0.7f, 1f);
            int hy = 0;
            for (FormattedCharSequence line : hint) {
                g.drawString(this.font, line, 0, hy, MUTED, false);
                hy += 9;
            }
            g.pose().popPose();
        }

        // Drop zone (drag-in comes with the media step).
        boolean uploading = uploadData != null || reading != null || storing != null || !uploadQueue.isEmpty();
        dashedBox(g, dx1, dy1, dx2, dy2, uploading ? TEAL : 0xFF93A3AB);
        List<FormattedCharSequence> dropLines = this.font.split(Component.literal(
                uploading ? "Uploading " + (uploadLabel.isEmpty() ? "..." : uploadLabel) : "Drag images, sound or video here"),
                dx2 - dx1 - 6);
        int ty = dy1 + (dzH - dropLines.size() * 9) / 2;
        for (FormattedCharSequence line : dropLines) {
            g.drawCenteredString(this.font, line, (dx1 + dx2) / 2, ty, MUTED);
            ty += 9;
        }
    }

    private void drawButton(GuiGraphics g, int[] r, String label, boolean enabled) {
        boolean hot = enabled && inside(r, hoverX, hoverY);
        box(g, r[0], r[1], r[2], r[3], hot ? TEAL_LIGHT : (enabled ? 0xFFF4F7F8 : PANEL), enabled ? TEAL : BORDER);
        smallCentered(g, label, (r[0] + r[2]) / 2, r[1] + 3, 0.8f, enabled ? TEAL : 0xFF9AA8AE, r[2] - r[0] - 4);
    }

    private static void setRect(int[] r, int x1, int y1, int x2, int y2) {
        r[0] = x1;
        r[1] = y1;
        r[2] = x2;
        r[3] = y2;
    }

    // ----------------------------------------------------------------- editor

    private void drawEditor(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        int consoleH = Math.round(h * 0.26f);
        int codeY = y + TAB_H + (openPath != null ? CRUMB_H : 0);
        int codeH = h - (codeY - y) - consoleH;
        codeAreaX = x;
        codeAreaY = codeY;
        codeAreaW = w;
        codeAreaH = codeH;

        drawTabs(g, x, y, w, mouseX, mouseY);

        // Code area.
        g.fill(x, y + TAB_H, x + w, codeY, 0xFF10161A);
        g.fill(x, codeY, x + w, codeY + codeH, EDITOR_BG);
        if (mediaInfoPath != null) {
            drawMediaInfo(g, x, codeY, w, codeH);
        } else if (openPath == null) {
            String hint;
            if (!snapshot.connected()) {
                hint = "Not connected. Place the laptop next to a Brain or a Data Cable that leads to one.";
            } else if (tree.isEmpty()) {
                hint = "No files yet. Use + File on the left.";
            } else {
                hint = "Open a file on the left.";
            }
            List<FormattedCharSequence> hintLines = this.font.split(Component.literal(hint), w - 24);
            int ty = codeY + (codeH - hintLines.size() * 9) / 2;
            for (FormattedCharSequence line : hintLines) {
                g.drawCenteredString(this.font, line, x + w / 2, ty, GUTTER);
                ty += 9;
            }
        } else {
            drawCrumbs(g, x, y + TAB_H, w);
            drawCode(g, x, codeY, w, codeH);
        }

        // Console: output of the running script (console.log and screen logs), newest at the bottom.
        int consoleY = codeY + codeH;
        g.fill(x, consoleY, x + w, y + h, CONSOLE_BG);
        g.fill(x, consoleY, x + w, consoleY + 1, 0xFF2A353B);
        g.drawString(this.font, "CONSOLE", x + 6, consoleY + 4, GUTTER, false);
        String status = scriptRunning ? "Running" : (console.isEmpty() ? "" : "Stopped");
        g.drawString(this.font, status, x + w - 6 - this.font.width(status), consoleY + 4,
                scriptRunning ? 0xFF5FD38D : GUTTER, false);
        if (!status.isEmpty()) {
            int dotX = x + w - 6 - this.font.width(status) - 7;
            g.fill(dotX, consoleY + 5, dotX + 4, consoleY + 9, scriptRunning ? 0xFF5FD38D : 0xFF4A5A61);
        }

        int rowsTop = consoleY + 15;
        int rows = Math.max(0, (y + h - 2 - rowsTop) / 11);
        // Long lines wrap to the console width. Newest lines are kept; an older line is shown only if all of it fits.
        Deque<ConsoleRow> shownRows = new ArrayDeque<>();
        int free = rows;
        for (int i = console.size() - 1; i >= 0 && free > 0; i--) {
            ConsoleLine line = console.get(i);
            int tagW = this.font.width(line.tag()) + 6;
            List<FormattedCharSequence> parts = this.font.split(Component.literal(line.text()), Math.max(10, w - tagW - 20));
            if (parts.isEmpty()) {
                parts = List.of(FormattedCharSequence.EMPTY);
            }
            if (parts.size() > free && !shownRows.isEmpty()) {
                break;
            }
            int use = Math.min(parts.size(), free);
            for (int k = use - 1; k >= 0; k--) {
                shownRows.addFirst(new ConsoleRow(line, parts.get(k), k == 0));
            }
            free -= use;
        }
        int cy = rowsTop;
        for (ConsoleRow entry : shownRows) {
            ConsoleLine line = entry.line();
            int tagW = this.font.width(line.tag()) + 6;
            if (entry.first()) {
                g.fill(x + 6, cy - 1, x + 6 + tagW, cy + 9, tagColor(line.tag()));
                g.drawString(this.font, line.tag(), x + 9, cy, CODE_TEXT, false);
            }
            int textColor = line.level() == 2 ? 0xFFF28B82 : (line.level() == 1 ? 0xFFE5C07B : CODE_TEXT);
            g.drawString(this.font, entry.text(), x + 6 + tagW + 6, cy, textColor, false);
            cy += 11;
        }
        if (console.isEmpty() && rows > 0) {
            String hint = snapshot.connected()
                    ? "Press Run (F5) to start main.js. console.log output shows up here."
                    : "Connect the laptop to a Brain to run scripts.";
            g.drawString(this.font, this.font.plainSubstrByWidth(hint, w - 12), x + 6, rowsTop, 0xFF4A5A61, false);
        }
        drawFindBar(g);
        drawCompletionPopup(g, y + h);
    }

    /** The tab bar. Tabs share the width and shrink as more are opened; past a minimum width the bar scrolls. */
    private void drawTabs(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY) {
        setRect(tabBar, x, y, x + w, y + TAB_H);
        g.fill(x, y, x + w, y + TAB_H, EDITOR_TAB_BG);
        tabRects.clear();
        int n = tabs.size();
        if (n == 0) {
            return;
        }

        int[] ideal = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            ideal[i] = Math.max(48, Math.min(MAX_TAB_W, this.font.width(PathRules.nameOf(tabs.get(i))) + 28));
            total += ideal[i];
        }
        int[] widths = new int[n];
        int used = 0;
        for (int i = 0; i < n; i++) {
            widths[i] = total <= w ? ideal[i] : Math.max(MIN_TAB_W, ideal[i] * w / total);
            used += widths[i];
        }
        int maxScroll = Math.max(0, used - w);

        if (revealActiveTab) {
            int index = tabs.indexOf(openPath);
            if (index >= 0) {
                int start = 0;
                for (int i = 0; i < index; i++) {
                    start += widths[i];
                }
                int end = start + widths[index];
                if (start < tabScroll) {
                    tabScroll = start;
                }
                if (end > tabScroll + w) {
                    tabScroll = end - w;
                }
            }
            revealActiveTab = false;
        }
        tabScroll = Math.max(0, Math.min(tabScroll, maxScroll));

        g.enableScissor(x, y, x + w, y + TAB_H);
        int left = x - tabScroll;
        for (int i = 0; i < n; i++) {
            int right = left + widths[i];
            tabRects.add(new int[] {left, right});
            String path = tabs.get(i);
            boolean active = path.equals(openPath);
            boolean hover = mouseX >= Math.max(left, x) && mouseX < Math.min(right, x + w) && mouseY >= y && mouseY < y + TAB_H;
            g.fill(left, y, right, y + TAB_H, active ? EDITOR_BG : (hover ? 0xFF171E23 : EDITOR_TAB_BG));
            g.fill(right - 1, y, right, y + TAB_H, 0xFF1B2328);
            if (active) {
                g.fill(left, y, right - 1, y + 1, 0xFF3AA6B3);
            }
            String name = PathRules.nameOf(path);
            int room = widths[i] - 22;
            if (this.font.width(name) > room) {
                name = this.font.plainSubstrByWidth(name, Math.max(0, room - this.font.width("..")), false) + "..";
            }
            g.drawString(this.font, name, left + 6, y + 3, active ? CODE_TEXT : 0xFF8A99A1, false);

            boolean overClose = hover && mouseX >= right - 12;
            if (active && dirty && !hover) {
                g.drawString(this.font, "*", right - 9, y + 3, CODE_NUMBER, false);
            } else if (active || hover) {
                g.drawString(this.font, "x", right - 9, y + 3, overClose ? 0xFFF28B82 : 0xFF6F7F87, false);
            }
            left = right;
        }
        g.disableScissor();
    }

    /** Path of the open file under the tabs, like "brain:1 > lib > servo.js". */
    private void drawCrumbs(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + CRUMB_H, 0xFF10161A);
        StringBuilder sb = new StringBuilder("brain:1");
        for (String part : openPath.split("/")) {
            sb.append(" > ").append(part);
        }
        String text = this.font.plainSubstrByWidth(sb.toString(), w - 12);
        g.drawString(this.font, text, x + 6, y + 2, 0xFF6F7F87, false);
    }

    private void drawCode(GuiGraphics g, int x, int codeY, int w, int codeH) {
        scrollY = Math.max(0, Math.min(scrollY, lines.size() - 1));
        int visibleRows = Math.max(1, (codeH - 6) / LINE_H);
        int[] sel = hasSelection() ? selection() : null;
        int[] pair = editing() && sel == null ? bracketPair() : null;
        boolean inBlock = blockCommentAt(scrollY);
        int textX = x + GUTTER_W + 4;

        // Line numbers.
        int ly = codeY + 4;
        for (int i = scrollY; i < lines.size() && i < scrollY + visibleRows + 1; i++) {
            String number = String.valueOf(i + 1);
            int color = i == curRow ? CODE_TEXT : GUTTER;
            g.drawString(this.font, number, x + GUTTER_W - 4 - this.font.width(number), ly, color, false);
            ly += LINE_H;
        }

        // Code text, selection and cursor, clipped so long lines don't spill over the gutter.
        g.enableScissor(textX - 2, codeY, x + w - 4, codeY + codeH);
        ly = codeY + 4;
        boolean caretOn = (System.currentTimeMillis() / 500) % 2 == 0;
        for (int i = scrollY; i < lines.size() && i < scrollY + visibleRows + 1; i++) {
            String line = lines.get(i);
            if (sel == null && i == curRow) {
                g.fill(textX - 2, ly - 1, x + w - 4, ly + LINE_H - 1, 0xFF1B242A);
            }
            if (sel != null && i >= sel[0] && i <= sel[2]) {
                int from = Math.min(i == sel[0] ? sel[1] : 0, line.length());
                int to = Math.min(i == sel[2] ? sel[3] : line.length(), line.length());
                int sx = textX - scrollX + this.font.width(line.substring(0, from));
                int ex = textX - scrollX + this.font.width(line.substring(0, to)) + (i < sel[2] ? 4 : 0);
                g.fill(sx, ly - 1, Math.max(ex, sx + 1), ly + LINE_H - 1, SELECTION);
            }
            // Faint indent guides every two spaces.
            int lead = 0;
            while (lead < line.length() && line.charAt(lead) == ' ') {
                lead++;
            }
            if (lead < line.length()) {
                for (int k = 2; k < lead; k += 2) {
                    int gx = textX - scrollX + this.font.width(" ".repeat(k));
                    g.fill(gx, ly - 1, gx + 1, ly + LINE_H - 1, 0xFF212C33);
                }
            }
            if (findOpen && findText.length() > 0 && editing()) {
                drawFindHits(g, line, i, textX - scrollX, ly);
            }
            if (pair != null) {
                int boxColor = pair[4] == 1 ? BRACKET_MATCH : BRACKET_BAD;
                drawBracketBox(g, line, i, pair[0], pair[1], textX - scrollX, ly, boxColor);
                if (pair[4] == 1) {
                    drawBracketBox(g, line, i, pair[2], pair[3], textX - scrollX, ly, boxColor);
                }
            }
            inBlock = drawCodeLine(g, line, textX - scrollX, ly, inBlock);
            if (i == curRow && promptKind == null && caretOn) {
                int cx = textX - scrollX + this.font.width(line.substring(0, Math.min(curCol, line.length())));
                g.fill(cx, ly - 1, cx + 1, ly + 9, 0xFF7FD6E0);
            }
            ly += LINE_H;
        }
        g.disableScissor();

        // Vertical scroll bar.
        if (lines.size() > visibleRows) {
            int trackX = x + w - 4;
            g.fill(trackX, codeY, trackX + 4, codeY + codeH, 0xFF171E23);
            int thumbH = Math.max(12, codeH * visibleRows / lines.size());
            int thumbY = codeY + (codeH - thumbH) * scrollY / Math.max(1, lines.size() - visibleRows);
            g.fill(trackX + 1, thumbY, trackX + 3, thumbY + thumbH, 0xFF3A4850);
        }
    }

    /** Whether row `row` starts inside a multi-line block comment (scans the rows above without drawing). */
    private boolean blockCommentAt(int row) {
        boolean inBlock = false;
        for (int r = 0; r < row && r < lines.size(); r++) {
            inBlock = drawCodeLine(null, lines.get(r), 0, 0, inBlock);
        }
        return inBlock;
    }

    /**
     * JavaScript colorizer: // and block comments (also over several lines), strings in " ' and `, numbers,
     * declarations, flow control, literals, calls. With g == null it only tracks state and draws nothing.
     *
     * @return whether the line ends inside a block comment
     */
    private boolean drawCodeLine(GuiGraphics g, String line, int x, int y, boolean inBlock) {
        int i = 0;
        int n = line.length();
        if (inBlock) {
            int end = line.indexOf("*/");
            int stop = end < 0 ? n : end + 2;
            x = put(g, line.substring(0, stop), x, y, CODE_COMMENT);
            if (end < 0) {
                return true;
            }
            i = stop;
        }
        while (i < n) {
            char c = line.charAt(i);
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                put(g, line.substring(i), x, y, CODE_COMMENT);
                return false;
            }
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                int end = line.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                x = put(g, line.substring(i, stop), x, y, CODE_COMMENT);
                if (end < 0) {
                    return true;
                }
                i = stop;
            } else if (isQuote(c)) {
                int j = i + 1;
                while (j < n && line.charAt(j) != c) {
                    if (line.charAt(j) == '\\' && j + 1 < n) {
                        j++;
                    }
                    j++;
                }
                j = Math.min(n, j + 1);
                x = put(g, line.substring(i, j), x, y, CODE_STRING);
                i = j;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(line.charAt(j)) || line.charAt(j) == '.')) {
                    j++;
                }
                x = put(g, line.substring(i, j), x, y, CODE_NUMBER);
                i = j;
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(line.charAt(j)) || line.charAt(j) == '_' || line.charAt(j) == '$')) {
                    j++;
                }
                String word = line.substring(i, j);
                int color;
                if (CONTROL.contains(word)) {
                    color = CODE_CONTROL;
                } else if (DECLARATIONS.contains(word)) {
                    color = CODE_KEYWORD;
                } else if (LITERALS.contains(word)) {
                    color = CODE_NUMBER;
                } else {
                    color = j < n && line.charAt(j) == '(' ? CODE_FUNC : CODE_TEXT;
                }
                x = put(g, word, x, y, color);
                i = j;
            } else {
                x = put(g, String.valueOf(c), x, y, CODE_TEXT);
                i++;
            }
        }
        return false;
    }

    private int put(GuiGraphics g, String s, int x, int y, int color) {
        if (g == null) {
            return x;
        }
        g.drawString(this.font, s, x, y, color, false);
        return x + this.font.width(s);
    }

    // ------------------------------------------------------- bracket matching

    /**
     * The bracket touching the cursor and its partner, as {row, col, partnerRow, partnerCol, matched (1 or 0)},
     * or null. A plain depth count: brackets inside strings and comments are counted too.
     */
    private int[] bracketPair() {
        String line = lines.get(curRow);
        int col = -1;
        if (curCol < line.length() && isBracket(line.charAt(curCol))) {
            col = curCol;
        } else if (curCol > 0 && curCol <= line.length() && isBracket(line.charAt(curCol - 1))) {
            col = curCol - 1;
        }
        if (col < 0) {
            return null;
        }
        char bracket = line.charAt(col);
        int open = OPENERS.indexOf(bracket);
        int[] match = open >= 0
                ? scanBracket(curRow, col + 1, bracket, CLOSERS.charAt(open), 1)
                : scanBracket(curRow, col - 1, bracket, OPENERS.charAt(CLOSERS.indexOf(bracket)), -1);
        return match == null ? new int[] {curRow, col, -1, -1, 0} : new int[] {curRow, col, match[0], match[1], 1};
    }

    private int[] scanBracket(int row, int col, char same, char partner, int dir) {
        int depth = 1;
        while (row >= 0 && row < lines.size()) {
            String text = lines.get(row);
            int c = dir > 0 ? col : Math.min(col, text.length() - 1);
            while (c >= 0 && c < text.length()) {
                char ch = text.charAt(c);
                if (ch == same) {
                    depth++;
                } else if (ch == partner && --depth == 0) {
                    return new int[] {row, c};
                }
                c += dir;
            }
            row += dir;
            if (row >= 0 && row < lines.size()) {
                col = dir > 0 ? 0 : lines.get(row).length() - 1;
            }
        }
        return null;
    }

    private void drawBracketBox(GuiGraphics g, String line, int row, int boxRow, int boxCol, int x, int y, int color) {
        if (row != boxRow || boxCol < 0 || boxCol >= line.length()) {
            return;
        }
        int bx = x + this.font.width(line.substring(0, boxCol));
        int bw = Math.max(2, this.font.width(String.valueOf(line.charAt(boxCol))));
        g.fill(bx, y - 1, bx + bw, y + LINE_H - 1, color);
    }

    // ---------------------------------------------------------------- devices

    private void drawDevices(GuiGraphics g, int x, int y, int w, int h) {
        rpX = x;
        rpY = y;
        rpW = w;
        rpH = h;
        cardHit.clear();
        g.fill(x, y, x + w, y + h, PANEL);

        g.drawString(this.font, "DEVICES", x + 6, y + 5, MUTED, false);
        String count = cards.size() + " on this network";
        if (this.font.width("DEVICES") + this.font.width(count) + 30 > w) {
            count = String.valueOf(cards.size());
        }
        int cw = this.font.width(count) + 8;
        box(g, x + w - 6 - cw, y + 3, x + w - 6, y + 14, 0xFFF4F7F8, BORDER);
        g.drawString(this.font, count, x + w - 6 - cw + 4, y + 5, MUTED, false);

        int hy = y + 18;
        float hs = 0.7f;
        List<FormattedCharSequence> hint = this.font.split(
                Component.literal("Click a card to insert its ID, like get(\"screen:1\")."), Math.round((w - 12) / hs));
        g.pose().pushPose();
        g.pose().translate(x + 6, hy, 0);
        g.pose().scale(hs, hs, 1f);
        int hintY = 0;
        for (FormattedCharSequence line : hint) {
            g.drawString(this.font, line, 0, hintY, MUTED, false);
            hintY += 9;
        }
        g.pose().popPose();
        hy += Math.round(hint.size() * 9 * hs);

        int gap = 4;
        int gridY = hy + 3;
        int cardW = (w - gap * 3) / 2;
        int cardH = (y + h - 4 - gridY - gap * 2) / 3;
        // Three rows of two cards are visible; more devices scroll with the mouse wheel, a row at a time.
        int totalRows = (cards.size() + 1) / 2;
        deviceScroll = Math.max(0, Math.min(deviceScroll, Math.max(0, totalRows - 3)));
        for (int slot = 0; slot < 6; slot++) {
            int index = deviceScroll * 2 + slot;
            if (index >= cards.size()) {
                break;
            }
            int col = slot % 2;
            int row = slot / 2;
            int cx = x + gap + col * (cardW + gap);
            int cy = gridY + row * (cardH + gap);
            cardHit.add(new int[] {cx, cy, cx + cardW, cy + cardH, index});
            drawDeviceCard(g, cards.get(index), cx, cy, cardW, cardH);
        }
    }

    private void drawDeviceCard(GuiGraphics g, Card d, int x, int y, int w, int h) {
        boolean hot = editing() && hoverX >= x && hoverX < x + w && hoverY >= y && hoverY < y + h;
        box(g, x, y, x + w, y + h, hot ? 0xFFFFFFFF : 0xFFF4F7F8, hot ? TEAL : BORDER);

        // Laid out bottom-up: ID box, then position, then name; the icon gets the space above.
        int idH = 10;
        int idBottom = y + h - 3;
        int idTop = idBottom - idH;
        g.fill(x + 3, idTop, x + w - 3, idBottom, 0xFF1B2A30);
        smallCentered(g, d.id(), x + w / 2, idTop + 2, 0.8f, 0xFF7FD6E0, w - 8);
        smallCentered(g, d.pos(), x + w / 2, idTop - 6, 0.55f, MUTED, w - 6);
        smallCentered(g, d.name(), x + w / 2, idTop - 14, 0.8f, TEXT, w - 6);

        // Block icon, scaled to the free space (placeholder for the 3D model).
        float iconScale = Math.max(0.75f, Math.min(1.75f, (idTop - 16 - (y + 3)) / 16f));
        int iconPx = Math.round(16 * iconScale);
        g.pose().pushPose();
        g.pose().translate(x + (w - iconPx) / 2f, y + 3, 0);
        g.pose().scale(iconScale, iconScale, 1f);
        g.renderItem(d.icon(), 0, 0);
        g.pose().popPose();
    }

    /** Centered text at a smaller scale; shrinks further if it would be wider than maxWidth. */
    private void smallCentered(GuiGraphics g, String text, int cx, int y, float scale, int color, int maxWidth) {
        float s = Math.min(scale, maxWidth / (float) Math.max(1, this.font.width(text)));
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(s, s, 1f);
        g.drawString(this.font, text, -this.font.width(text) / 2, 0, color, false);
        g.pose().popPose();
    }

    /** Left-aligned text at a smaller scale; shrinks further if it would be wider than maxWidth. */
    private void smallLeft(GuiGraphics g, String text, int x, int y, float scale, int color, int maxWidth) {
        float s = Math.min(scale, maxWidth / (float) Math.max(1, this.font.width(text)));
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(s, s, 1f);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    // -------------------------------------------------------------- status bar

    private void drawStatusBar(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, CHROME);
        g.fill(x, y, x + w, y + 1, BORDER);
        int ty = y + (h - 8) / 2 + 1;

        int fileCount = local.size();
        String filesText = fileCount + (fileCount == 1 ? " file" : " files");
        String left;
        if (openPath != null) {
            int total = otherNonBlankLines;
            for (String line : lines) {
                if (!line.isBlank()) {
                    total++;
                }
            }
            String selected = hasSelection() ? "  (" + selectedText().length() + " selected)" : "";
            left = "JavaScript  Ln " + (curRow + 1) + ", Col " + (curCol + 1) + selected + "  " + filesText + ", " + total + " lines";
        } else {
            left = "JavaScript  " + filesText;
        }
        g.drawString(this.font, left, x + 6, ty, MUTED, false);

        String right;
        int rightColor = MUTED;
        if (!snapshot.connected()) {
            right = "Not connected";
        } else if (tooBig) {
            right = "File too big, not saved";
            rightColor = ERROR;
        } else {
            String saved = dirty ? "Unsaved" : "Saved";
            right = "Cipher: none  Spaces: 2  UTF-8  " + saved;
            if (this.font.width(left) + this.font.width(right) + 24 > w) {
                right = "Cipher: none  " + saved;
            }
        }
        g.drawString(this.font, right, x + w - 6 - this.font.width(right), ty, rightColor, false);
    }

    // ------------------------------------------------------ media drag-in

    /** Saves what is on screen and starts the script (Run button, F5, Ctrl+Enter). */
    private void runScript() {
        if (snapshot.connected()) {
            flushIfDirty(); // the server must run the code that is on screen
            PacketDistributor.sendToServer(new LaptopAction(snapshot.laptop(), LaptopAction.RUN, "", ""));
        }
    }

    /** Typing into the open file is possible (a file is open, no media card covers it, connected to a Brain). */
    private boolean editing() {
        return openPath != null && mediaInfoPath == null && snapshot.connected()
                && renameFrom == null && deleteTarget == null;
    }

    /** Set after the last chunk is sent, until the server answers that the file was stored. */
    private String storing;

    /** Files dragged from the file manager into the game window. */
    @Override
    public void onFilesDrop(List<Path> paths) {
        if (!snapshot.connected()) {
            notice("Connect the laptop to a Brain before adding files.", true);
            return;
        }
        for (Path path : paths) {
            if (uploadQueue.size() >= 8) {
                notice("Too many files at once, add the rest afterwards.", true);
                break;
            }
            Path fileName = path.getFileName();
            String name = fileName == null ? "" : fileName.toString();
            int kind = MediaRules.kindOf(name);
            if (kind == MediaRules.NONE) {
                notice("Not supported: " + name + ". Use png, jpg, webp, ogg, mp3, wav, flac, mp4, webm or mkv.", true);
                continue;
            }
            long size;
            try {
                size = Files.size(path);
            } catch (IOException e) {
                notice("Could not read " + name + ".", true);
                continue;
            }
            if (size <= 0 || size > MediaRules.maxBytes(kind)) {
                notice(name + " is too big. The limit for this type is " + MediaRules.maxBytes(kind) / (1024 * 1024) + " MB.", true);
                continue;
            }
            uploadQueue.add(new PendingUpload(path, uniqueMediaPath(name)));
        }
    }

    /** media/<clean name>, with -2, -3, ... added if that name is already used. */
    private String uniqueMediaPath(String fileName) {
        String clean = MediaRules.sanitizeName(fileName);
        int dot = clean.lastIndexOf('.');
        String base = dot > 0 ? clean.substring(0, dot) : clean;
        String extension = dot > 0 ? clean.substring(dot) : "";
        String candidate = "media/" + clean;
        for (int n = 2; mediaPathTaken(candidate); n++) {
            candidate = "media/" + base + "-" + n + extension;
        }
        return candidate;
    }

    private boolean mediaPathTaken(String path) {
        if (mediaSizes.containsKey(path) || local.containsKey(path) || folderSet.contains(path)) {
            return true;
        }
        for (PendingUpload pending : uploadQueue) {
            if (pending.dest().equals(path)) {
                return true;
            }
        }
        return path.equals(uploadPath) && (reading != null || uploadData != null);
    }

    /** A message from the server, for example the result of an upload. */
    public void showNotice(LaptopNotice notice) {
        storing = null;
        if (notice.error()) {
            renameFrom = null; // a rejected rename: the server keeps the old name
            renameTo = null;
        }
        notice(notice.text(), notice.error());
    }

    private void notice(String text, boolean error) {
        noticeText = text;
        noticeError = error;
        noticeUntil = tickCount + 100;
    }

    /** Reads the next queued file in the background and sends it to the server in chunks, a few per tick. */
    private void tickUploads() {
        if (!snapshot.connected()) {
            uploadQueue.clear();
            reading = null;
            uploadData = null;
            return;
        }
        if (reading == null && uploadData == null && !uploadQueue.isEmpty()) {
            PendingUpload next = uploadQueue.poll();
            uploadPath = next.dest();
            uploadLabel = PathRules.nameOf(next.dest());
            reading = CompletableFuture.supplyAsync(() -> {
                try {
                    return Files.readAllBytes(next.source());
                } catch (IOException e) {
                    return null;
                }
            }, Util.backgroundExecutor());
        }
        if (reading != null && reading.isDone()) {
            byte[] data;
            try {
                data = reading.getNow(null);
            } catch (RuntimeException e) {
                data = null;
            }
            reading = null;
            if (data == null || data.length == 0 || data.length > MediaRules.maxBytes(MediaRules.kindOf(uploadPath))) {
                notice("Could not read " + uploadLabel + ".", true);
            } else {
                uploadData = data;
                uploadSent = 0;
                uploadChunk = 0;
            }
        }
        if (uploadData != null) {
            for (int i = 0; i < 4 && uploadData != null; i++) {
                int length = Math.min(MediaRules.CHUNK_BYTES, uploadData.length - uploadSent);
                byte[] chunk = Arrays.copyOfRange(uploadData, uploadSent, uploadSent + length);
                PacketDistributor.sendToServer(new LaptopUpload(snapshot.laptop(), uploadPath, uploadData.length, uploadChunk, chunk));
                uploadSent += length;
                uploadChunk++;
                if (uploadSent >= uploadData.length) {
                    uploadData = null;
                    storing = uploadLabel;
                }
            }
        }
    }

    /** Upload progress and results, in a small box above the status bar. */
    private void drawToast(GuiGraphics g, int x, int statusY, int w) {
        String text = null;
        boolean error = false;
        if (uploadData != null) {
            text = "Uploading " + uploadLabel + " " + (int) (100L * uploadSent / uploadData.length) + "%";
        } else if (reading != null) {
            text = "Reading " + uploadLabel + "...";
        } else if (storing != null) {
            text = "Storing " + storing + "...";
        } else if (tickCount < noticeUntil && !noticeText.isEmpty()) {
            text = noticeText;
            error = noticeError;
        }
        if (text == null) {
            return;
        }
        String shown = this.font.plainSubstrByWidth(text, w - 40);
        int x2 = x + w - 6;
        int x1 = x2 - (this.font.width(shown) + 12);
        int y2 = statusY - 4;
        int y1 = y2 - 13;
        box(g, x1, y1, x2, y2, error ? 0xFF3A1F1F : 0xFF1B2A30, error ? ERROR : TEAL);
        g.drawString(this.font, shown, x1 + 6, y1 + 3, error ? 0xFFF5B5B1 : 0xFF9FE3EC, false);
    }

    /** What is shown instead of the code when a media file is selected in the Explorer. */
    private void drawMediaInfo(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, EDITOR_BG);
        int kind = MediaRules.kindOf(mediaInfoPath);
        String kindName = kind == MediaRules.IMAGE ? "Image" : (kind == MediaRules.AUDIO ? "Audio" : "Video");
        String badge = kind == MediaRules.IMAGE ? "IMG" : (kind == MediaRules.AUDIO ? "AUD" : "VID");
        int size = mediaSizes.getOrDefault(mediaInfoPath, 0);
        int cx = x + w / 2;
        int ty = y + Math.max(4, h / 2 - 34);

        g.fill(cx - 16, ty, cx + 16, ty + 22, mediaColor(kind));
        g.drawCenteredString(this.font, badge, cx, ty + 7, 0xFFFFFFFF);
        g.drawCenteredString(this.font, this.font.plainSubstrByWidth(PathRules.nameOf(mediaInfoPath), w - 20), cx, ty + 28, CODE_TEXT);
        g.drawCenteredString(this.font, kindName + ", " + formatSize(size), cx, ty + 40, GUTTER);
        List<FormattedCharSequence> note = this.font.split(Component.literal(
                "Stored on the server with this Brain. Showing and playing it comes with the Screen and Speaker."), w - 40);
        int ny = ty + 56;
        for (FormattedCharSequence line : note) {
            if (ny + 9 > y + h) {
                break;
            }
            g.drawCenteredString(this.font, line, cx, ny, 0xFF4F5E65);
            ny += 9;
        }
    }

    private static int mediaColor(int kind) {
        return switch (kind) {
            case MediaRules.IMAGE -> 0xFF3A8FA3;
            case MediaRules.AUDIO -> 0xFF7F66B8;
            default -> 0xFFB8873A;
        };
    }

    private static String formatSize(int bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ---------------------------------------------------------- autocomplete

    /** Works out the suggestions at the cursor and opens or closes the popup. */
    private void refreshCompletion(boolean force) {
        if (!editing() || hasSelection() || promptKind != null) {
            closeCompletion();
            return;
        }
        Completions.Result result = Completions.compute(lines, curRow, curCol, snapshot.devices(), force);
        if (result == null) {
            closeCompletion();
            return;
        }
        completionItems = result.items();
        completionStart = result.startCol();
        completionIndex = 0;
        completionOpen = true;
    }

    private void closeCompletion() {
        completionOpen = false;
        completionItems = List.of();
    }

    /** Replaces the word being typed with the chosen suggestion. */
    private void acceptCompletion() {
        if (!completionOpen || completionItems.isEmpty() || completionStart > curCol) {
            closeCompletion();
            return;
        }
        Completions.Item item = completionItems.get(Math.max(0, Math.min(completionIndex, completionItems.size() - 1)));
        if (totalChars() - (curCol - completionStart) + item.insert().length() > PathRules.MAX_FILE_BYTES) {
            closeCompletion();
            return;
        }
        beginEdit(0);
        String line = lines.get(curRow);
        lines.set(curRow, line.substring(0, completionStart) + item.insert() + line.substring(curCol));
        curCol = completionStart + item.insert().length();
        collapse();
        closeCompletion();
        edited();
    }

    /** The suggestion list, drawn under the cursor (above it if there is no room below). */
    private void drawCompletionPopup(GuiGraphics g, int bottom) {
        setRect(popupRect, 0, 0, 0, 0);
        if (!completionOpen || completionItems.isEmpty() || openPath == null || mediaInfoPath != null) {
            return;
        }
        int visibleRows = Math.max(1, (codeAreaH - 6) / LINE_H);
        if (curRow < scrollY || curRow >= scrollY + visibleRows) {
            return;
        }
        String line = lines.get(curRow);
        int px = codeAreaX + GUTTER_W + 4 - scrollX + this.font.width(line.substring(0, Math.min(completionStart, line.length())));
        int rowBottom = codeAreaY + 4 + (curRow - scrollY) * LINE_H + LINE_H;

        int labelW = 0;
        int detailW = 0;
        for (Completions.Item item : completionItems) {
            labelW = Math.max(labelW, this.font.width(item.label()));
            detailW = Math.max(detailW, Math.round(this.font.width(item.detail()) * 0.75f));
        }
        int width = Math.min(codeAreaW - 12, labelW + detailW + 22);
        int height = completionItems.size() * LINE_H + 4;
        int x1 = Math.max(codeAreaX + GUTTER_W, Math.min(px - 2, codeAreaX + codeAreaW - width - 6));
        int y1 = rowBottom + 1;
        if (y1 + height > bottom) {
            y1 = rowBottom - LINE_H - height - 1;
        }
        int x2 = x1 + width;
        int y2 = y1 + height;
        setRect(popupRect, x1, y1, x2, y2);
        box(g, x1, y1, x2, y2, 0xFF1E272D, 0xFF3A4850);

        for (int i = 0; i < completionItems.size(); i++) {
            Completions.Item item = completionItems.get(i);
            int ry = y1 + 2 + i * LINE_H;
            if (i == completionIndex) {
                g.fill(x1 + 1, ry, x2 - 1, ry + LINE_H, 0xFF234C57);
            }
            g.drawString(this.font, item.label(), x1 + 5, ry + 1, i == completionIndex ? 0xFFFFFFFF : CODE_TEXT, false);

            int detailColor = item.detail().equals("keyword") ? CODE_KEYWORD
                    : (item.detail().equals("function") || item.detail().equals("method") ? CODE_FUNC : GUTTER);
            float scale = 0.75f;
            int dw = Math.round(this.font.width(item.detail()) * scale);
            g.pose().pushPose();
            g.pose().translate(x2 - 5 - dw, ry + 2, 0);
            g.pose().scale(scale, scale, 1f);
            g.drawString(this.font, item.detail(), 0, 0, detailColor, false);
            g.pose().popPose();
        }
    }

    // ----------------------------------------------------------------- helpers

    private static int tagColor(String tag) {
        if (tag.startsWith("screen")) {
            return 0xFF1F5F69;
        }
        if (tag.startsWith("servo")) {
            return 0xFF6B4B1F;
        }
        if (tag.startsWith("speaker")) {
            return 0xFF4B3A73;
        }
        return 0xFF2F5A43;
    }

    private static void box(GuiGraphics g, int x1, int y1, int x2, int y2, int fill, int border) {
        g.fill(x1, y1, x2, y2, border);
        g.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, fill);
    }

    private static void dashedBox(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        for (int px = x1; px < x2; px += 4) {
            int end = Math.min(px + 2, x2);
            g.fill(px, y1, end, y1 + 1, color);
            g.fill(px, y2 - 1, end, y2, color);
        }
        for (int py = y1; py < y2; py += 4) {
            int end = Math.min(py + 2, y2);
            g.fill(x1, py, x1 + 1, end, color);
            g.fill(x2 - 1, py, x2, end, color);
        }
    }
}
