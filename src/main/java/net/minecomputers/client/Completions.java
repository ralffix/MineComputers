package net.minecomputers.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecomputers.network.LaptopSnapshot;

/**
 * Autocomplete suggestions for the laptop editor. Pure logic, no drawing.
 *
 * What it suggests: the script API (console, get, os, timers), common JavaScript globals and keywords,
 * members after a dot (console.log, Math.floor, and the members of a variable that holds a device, such as
 * screen.log after const screen = get("screen:1")), the IDs of the connected devices inside get("..."),
 * and other words used in the file.
 */
final class Completions {
    /** One suggestion: what is shown, what is inserted, and a short kind label. */
    record Item(String label, String insert, String detail) {
    }

    /** The suggestions and the column where the word being completed starts. */
    record Result(int startCol, List<Item> items) {
    }

    private static final int MAX_ITEMS = 8;

    private static final Pattern GET_STRING = Pattern.compile("get\\(\\s*\"([^\"]*)$");
    private static final Pattern DEVICE_VARIABLE =
            Pattern.compile("(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*get\\(\\s*\"([a-z]+):\\d+\"");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_$][\\w$]*");

    private static final String[][] GLOBALS = {
            {"console", "object"}, {"get", "function"}, {"os", "object"},
            {"setTimeout", "function"}, {"setInterval", "function"},
            {"clearTimeout", "function"}, {"clearInterval", "function"},
            {"JSON", "object"}, {"Math", "object"}, {"Object", "object"}, {"Array", "object"},
            {"Number", "object"}, {"String", "object"}, {"Date", "object"},
            {"parseInt", "function"}, {"parseFloat", "function"}, {"isNaN", "function"}, {"isFinite", "function"},
            {"undefined", "value"}, {"NaN", "value"}, {"Infinity", "value"},
    };

    private static final String[] KEYWORDS = {
            "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "break", "continue",
            "switch", "case", "default", "new", "typeof", "instanceof", "true", "false", "null", "this", "class",
            "try", "catch", "finally", "throw", "of", "in", "delete", "async", "await",
    };

    private static final Map<String, String[][]> MEMBERS = Map.of(
            "console", new String[][] {{"log", "method"}, {"info", "method"}, {"warn", "method"}, {"error", "method"}},
            "os", new String[][] {{"on", "method"}},
            "Math", new String[][] {{"floor", "method"}, {"ceil", "method"}, {"round", "method"}, {"trunc", "method"},
                    {"abs", "method"}, {"min", "method"}, {"max", "method"}, {"pow", "method"}, {"sqrt", "method"},
                    {"sign", "method"}, {"random", "method"}, {"sin", "method"}, {"cos", "method"},
                    {"atan2", "method"}, {"PI", "property"}},
            "JSON", new String[][] {{"stringify", "method"}, {"parse", "method"}},
            "Object", new String[][] {{"keys", "method"}, {"values", "method"}, {"entries", "method"},
                    {"assign", "method"}, {"freeze", "method"}},
            "Array", new String[][] {{"isArray", "method"}, {"from", "method"}},
            "Number", new String[][] {{"isInteger", "method"}, {"isFinite", "method"}, {"parseFloat", "method"},
                    {"parseInt", "method"}, {"MAX_SAFE_INTEGER", "property"}},
            "String", new String[][] {{"fromCharCode", "method"}},
            "Date", new String[][] {{"now", "method"}});

    /**
     * @param lines   the file being edited
     * @param row     cursor row
     * @param col     cursor column
     * @param devices what is connected (for get("..."))
     * @param force   true for Ctrl+Space: also suggest when nothing has been typed yet
     * @return suggestions, or null if there is nothing to suggest here
     */
    static Result compute(List<String> lines, int row, int col, List<LaptopSnapshot.DeviceEntry> devices, boolean force) {
        String line = lines.get(row);
        String before = line.substring(0, Math.min(col, line.length()));

        if (inLineComment(before)) {
            return null;
        }

        // Inside get("...": suggest the device IDs of the network.
        Matcher get = GET_STRING.matcher(before);
        if (get.find()) {
            String prefix = get.group(1);
            List<Item> items = new ArrayList<>();
            for (LaptopSnapshot.DeviceEntry device : devices) {
                if (device.id().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                    items.add(new Item(device.id(), device.id(), device.type()));
                }
            }
            return finish(items, before.length() - prefix.length(), prefix);
        }

        if (inString(before)) {
            return null;
        }

        int start = before.length();
        while (start > 0 && isWordChar(before.charAt(start - 1))) {
            start--;
        }
        String prefix = before.substring(start);
        if (!prefix.isEmpty() && Character.isDigit(prefix.charAt(0))) {
            return null;
        }

        // After a dot: members of the thing in front of it.
        if (start > 0 && before.charAt(start - 1) == '.') {
            int end = start - 1;
            int begin = end;
            while (begin > 0 && isWordChar(before.charAt(begin - 1))) {
                begin--;
            }
            String object = before.substring(begin, end);
            if (object.isEmpty()) {
                return null;
            }
            return finish(filter(membersOf(object, lines), prefix), start, prefix);
        }

        if (prefix.isEmpty() && !force) {
            return null;
        }

        List<Item> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] global : GLOBALS) {
            all.add(new Item(global[0], global[0], global[1]));
            seen.add(global[0]);
        }
        for (String keyword : KEYWORDS) {
            all.add(new Item(keyword, keyword, "keyword"));
            seen.add(keyword);
        }
        Matcher words = WORD.matcher(String.join("\n", lines));
        while (words.find()) {
            String word = words.group();
            if (word.length() >= 3 && !word.equals(prefix) && seen.add(word)) {
                all.add(new Item(word, word, "word"));
            }
        }
        return finish(filter(all, prefix), start, prefix);
    }

    private static List<Item> membersOf(String object, List<String> lines) {
        List<Item> members = new ArrayList<>();
        String[][] known = MEMBERS.get(object);
        if (known != null) {
            for (String[] member : known) {
                members.add(new Item(member[0], member[0], member[1]));
            }
            return members;
        }
        // A variable that holds a device: const screen = get("screen:1")
        Matcher variable = DEVICE_VARIABLE.matcher(String.join("\n", lines));
        String type = null;
        while (variable.find()) {
            if (variable.group(1).equals(object)) {
                type = variable.group(2);
            }
        }
        if (type != null) {
            members.add(new Item("id", "id", "property"));
            members.add(new Item("type", "type", "property"));
            if (type.equals("screen")) {
                members.add(new Item("log", "log", "method"));
            }
        }
        return members;
    }

    private static List<Item> filter(List<Item> items, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<Item> result = new ArrayList<>();
        for (Item item : items) {
            if (item.label().toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(item);
            }
        }
        return result;
    }

    /** Caps the list; nothing to show if it is empty or the only suggestion is exactly what is typed. */
    private static Result finish(List<Item> items, int start, String prefix) {
        if (items.isEmpty() || (items.size() == 1 && items.get(0).label().equals(prefix))) {
            return null;
        }
        return new Result(start, new ArrayList<>(items.subList(0, Math.min(MAX_ITEMS, items.size()))));
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** True if an odd number of quotes of one kind is open at the end of the text. */
    private static boolean inString(String text) {
        return quoteOpen(text, '"') || quoteOpen(text, '\'') || quoteOpen(text, '`');
    }

    private static boolean quoteOpen(String text, char quote) {
        boolean open = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                open = !open;
            }
        }
        return open;
    }

    /** True if a // comment started before the end of the text (outside of any string). */
    private static boolean inLineComment(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'' || c == '`') {
                quote = c;
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                return true;
            }
        }
        return false;
    }

    private Completions() {
    }
}
