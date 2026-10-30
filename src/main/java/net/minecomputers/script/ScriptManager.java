package net.minecomputers.script;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.logging.LogUtils;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.graalvm.polyglot.Engine;
import org.slf4j.Logger;

/**
 * Owns all running Brain scripts of the server and ticks them. Server thread only.
 * A global per-tick time budget stops many Brains from adding up to lag: Brains that don't fit in
 * this tick simply run their timers in the next one.
 */
public final class ScriptManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Total time all scripts together may use per server tick. */
    private static final long GLOBAL_BUDGET_NANOS = 15_000_000L;
    /** A Brain can be started again at most once per second. */
    private static final long MIN_RERUN_NANOS = 1_000_000_000L;

    private static final Map<GlobalPos, BrainRuntime> RUNTIMES = new LinkedHashMap<>();
    private static final Map<GlobalPos, Long> LAST_START = new HashMap<>();
    private static Engine engine;
    private static int rotation;

    /** The runtime of this Brain (running, or stopped with its console kept), or null. */
    public static BrainRuntime get(GlobalPos brain) {
        return RUNTIMES.get(brain);
    }

    /**
     * Starts (or restarts) the script of a Brain.
     *
     * @return the runtime, or null if the JavaScript engine could not be loaded at all
     */
    public static BrainRuntime run(GlobalPos brain, Map<String, String> files, Map<String, String> devices,
                                   Map<String, GlobalPos> positions) {
        BrainRuntime existing = RUNTIMES.get(brain);
        long now = System.nanoTime();
        Long last = LAST_START.get(brain);
        if (existing != null && last != null && now - last < MIN_RERUN_NANOS) {
            return existing; // too soon after the last start
        }
        LAST_START.put(brain, now);
        if (existing != null) {
            existing.stop();
        }
        try {
            BrainRuntime runtime = new BrainRuntime(devices, positions);
            RUNTIMES.put(brain, runtime);
            runtime.start(engine(), files);
            return runtime;
        } catch (Throwable t) {
            LOGGER.error("MineComputers: the JavaScript engine failed to load", t);
            RUNTIMES.remove(brain);
            return null;
        }
    }

    /**
     * A player touched the Screen whose origin block is at this position. Gives the touch to every running
     * script that has this screen and listens for it. Returns true if one did.
     */
    public static boolean screenTouched(GlobalPos screen, int x, int y, int px, int py) {
        boolean handled = false;
        for (BrainRuntime runtime : new ArrayList<>(RUNTIMES.values())) {
            if (runtime.touch(screen, x, y, px, py)) {
                handled = true;
            }
        }
        return handled;
    }

    public static void stop(GlobalPos brain) {
        BrainRuntime runtime = RUNTIMES.get(brain);
        if (runtime != null) {
            runtime.stop();
        }
    }

    /** The Brain was broken or its chunk unloaded: stop its script and forget it. */
    public static void discard(GlobalPos brain) {
        BrainRuntime runtime = RUNTIMES.remove(brain);
        LAST_START.remove(brain);
        if (runtime != null) {
            runtime.stop();
        }
    }

    private static Engine engine() {
        if (engine == null) {
            try {
                engine = Engine.newBuilder()
                        .option("engine.WarnInterpreterOnly", "false")
                        .out(OutputStream.nullOutputStream())
                        .err(OutputStream.nullOutputStream())
                        .build();
            } catch (IllegalArgumentException e) {
                LOGGER.warn("MineComputers: a GraalJS engine option was rejected: {}", e.getMessage());
                engine = Engine.newBuilder()
                        .out(OutputStream.nullOutputStream())
                        .err(OutputStream.nullOutputStream())
                        .build();
            }
        }
        return engine;
    }

    public static void onTick(ServerTickEvent.Post event) {
        if (RUNTIMES.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        List<Map.Entry<GlobalPos, BrainRuntime>> entries = new ArrayList<>(RUNTIMES.entrySet());
        int count = entries.size();
        for (int i = 0; i < count; i++) {
            Map.Entry<GlobalPos, BrainRuntime> entry = entries.get(Math.floorMod(rotation + i, count));
            BrainRuntime runtime = entry.getValue();
            if (!runtime.isRunning()) {
                continue;
            }
            ServerLevel level = event.getServer().getLevel(entry.getKey().dimension());
            if (level == null || !level.isLoaded(entry.getKey().pos())) {
                continue;
            }
            runtime.tick();
            if (System.nanoTime() - start > net.minecomputers.MineComputersConfig.globalBudgetMs() * 1_000_000L) {
                break;
            }
        }
        rotation++;
    }

    public static void onStopping(ServerStoppingEvent event) {
        for (BrainRuntime runtime : RUNTIMES.values()) {
            runtime.stop();
        }
        RUNTIMES.clear();
        LAST_START.clear();
        if (engine != null) {
            try {
                engine.close(true);
            } catch (Throwable ignored) {
                // Shutting down anyway.
            }
            engine = null;
        }
    }

    private ScriptManager() {
    }
}
