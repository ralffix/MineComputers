package net.minecomputers.script;

/**
 * Where a script's screen output goes. The script runtime only knows device IDs; whoever starts the script
 * (ServerHandlers) provides this and turns an ID such as "screen:1" into the real Screen block.
 */
public interface ScreenSink {
    /** Adds a line (or several, split on newlines) to the screen with this ID. */
    void log(String id, String text);

    /** Clears the screen with this ID. */
    void clear(String id);
}
