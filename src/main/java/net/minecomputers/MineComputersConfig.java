package net.minecomputers;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The mod's config file (config/minecomputers-common.toml). Script limits live here so a server owner can tune
 * them (SPEC section 10). Every getter falls back to the default if the config is not loaded yet, so reading a
 * limit can never crash.
 */
public final class MineComputersConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.IntValue STATEMENT_LIMIT;
    private static final ModConfigSpec.IntValue START_CAP_MS;
    private static final ModConfigSpec.IntValue EVENT_CAP_MS;
    private static final ModConfigSpec.IntValue TOUCH_CAP_MS;
    private static final ModConfigSpec.IntValue GLOBAL_BUDGET_MS;
    private static final ModConfigSpec.BooleanValue FREE_POWER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Limits for the JavaScript that runs on Brains").push("scripts");
        STATEMENT_LIMIT = builder
                .comment("Most operations a script may run per call (the first run, or one timer/touch callback)")
                .defineInRange("statementLimit", 1_000_000, 1_000, 100_000_000);
        START_CAP_MS = builder
                .comment("Most milliseconds the first run of main.js may take")
                .defineInRange("startCapMs", 500, 10, 10_000);
        EVENT_CAP_MS = builder
                .comment("Most milliseconds one timer callback may take")
                .defineInRange("eventCapMs", 25, 1, 1_000);
        TOUCH_CAP_MS = builder
                .comment("Most milliseconds one Screen touch callback may take")
                .defineInRange("touchCapMs", 100, 1, 1_000);
        GLOBAL_BUDGET_MS = builder
                .comment("Total milliseconds all scripts together may use per server tick")
                .defineInRange("globalBudgetMs", 15, 1, 45);
        builder.pop();
        builder.comment("Testing").push("debug");
        FREE_POWER = builder
                .comment("If true, Servos and other FE devices work without any FE source (for testing only)")
                .define("freePower", false);
        builder.pop();
        SPEC = builder.build();
    }

    private static int read(ModConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (Throwable t) {
            return fallback; // config not loaded yet
        }
    }

    public static long statementLimit() {
        return read(STATEMENT_LIMIT, 1_000_000);
    }

    public static long startCapMs() {
        return read(START_CAP_MS, 500);
    }

    public static long eventCapMs() {
        return read(EVENT_CAP_MS, 25);
    }

    public static long touchCapMs() {
        return read(TOUCH_CAP_MS, 100);
    }

    public static boolean freePower() {
        try {
            return FREE_POWER.get();
        } catch (Throwable t) {
            return false; // config not loaded yet
        }
    }

    public static long globalBudgetMs() {
        return read(GLOBAL_BUDGET_MS, 15);
    }

    private MineComputersConfig() {
    }
}
