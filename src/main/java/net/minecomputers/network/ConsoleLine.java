package net.minecomputers.network;

/**
 * One line in the laptop's Console panel.
 *
 * @param tag   who printed it: "brain:1" for console.log, or a device ID such as "screen:1"
 * @param text  the text (already shortened by the server)
 * @param level 0 = normal, 1 = warning, 2 = error
 */
public record ConsoleLine(String tag, String text, int level) {
}
