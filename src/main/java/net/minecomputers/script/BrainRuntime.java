package net.minecomputers.script;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.logging.LogUtils;
import net.minecomputers.network.ConsoleLine;
import net.minecraft.core.GlobalPos;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.slf4j.Logger;

/**
 * One running script of one Brain, in its own isolated GraalJS context.
 *
 * Sandbox: only the API exposed through {@link ScriptHost} is reachable (HostAccess.EXPLICIT); no Java
 * classes, no file access, no network, no threads, no native code, no processes, no environment.
 * CPU: every call into the script (the first run and each timer callback) has a statement limit and a
 * wall-clock cap. A script that goes over is stopped and the error appears in the laptop's Console.
 * Everything here runs on the server thread, except the watchdog that cancels a script that ran too long.
 */
public final class BrainRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String BRAIN_TAG = "brain:1";

    // Limits. The spec suggests ~2 ms per tick (hard cap ~5 ms); that is too tight for GraalJS in its
    // interpreter-only mode (no JIT here), so these start looser. To be moved into the server config.
    /** Operations a script may run per call (the first run, or one timer callback). */
    static final long STATEMENT_LIMIT = 1_000_000L;
    /** Wall-clock cap for the first run of main.js. */
    static final long START_CAP_MS = 500L;
    /**
     * Cap for setting up the context (loading the JS engine and our own bootstrap code, no player code).
     * The very first context in a game session is slow (cold start, no JIT), so this must be generous.
     */
    static final long SETUP_CAP_MS = 8000L;
    /** Wall-clock cap for one timer callback. */
    static final long EVENT_CAP_MS = 25L;
    /** Wall-clock cap for a touch handler. Looser than a timer: a touch is rare, and the first call is cold and slow. */
    static final long TOUCH_CAP_MS = 100L;
    static final int MAX_LINES = 100;
    static final int MAX_PRINTS_PER_CALL = 50;
    static final int MAX_LINE_CHARS = 300;
    static final int MAX_TIMERS = 64;

    private static final AtomicLong VERSIONS = new AtomicLong();
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "MineComputers-script-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    /** Standard-JavaScript front for the host API: console, get(), os.on(), setTimeout/setInterval. */
    private static final String BOOTSTRAP = """
            (function (host) {
              'use strict';
              const fmt = (v) => {
                if (typeof v === 'string') return v;
                if (v === undefined) return 'undefined';
                if (typeof v === 'function') return String(v);
                try {
                  const s = JSON.stringify(v);
                  return s === undefined ? String(v) : s;
                } catch (e) {
                  return String(v);
                }
              };
              const join = (args) => args.map(fmt).join(' ');
              globalThis.console = {
                log: (...a) => host.print(0, join(a)),
                info: (...a) => host.print(0, join(a)),
                warn: (...a) => host.print(1, join(a)),
                error: (...a) => host.print(2, join(a)),
              };
              globalThis.get = (id) => {
                const key = String(id);
                const type = host.typeOf(key);
                if (type === null || type === undefined) {
                  throw new Error('No device with ID "' + key + '" on this network');
                }
                const device = { id: key, type: String(type) };
                if (type === 'screen') {
                  const s = (op, a, b, c, d, e) => host.screen(key, op, a, b, c, d, e);
                  const n = (v) => Number(v);
                  const px = (c) => (c === undefined || c === null ? -1 : Number(c));
                  device.write = (...a) => s('write', join(a));
                  device.print = (...a) => s('print', join(a));
                  device.log = device.print;
                  device.setCursorPos = (x, y) => s('cursor', n(x), n(y));
                  device.getCursorPos = () => [s('curx'), s('cury')];
                  device.setTextColor = (c) => s('fg', n(c));
                  device.getTextColor = () => s('getfg');
                  device.setBackgroundColor = (c) => s('bg', n(c));
                  device.getBackgroundColor = () => s('getbg');
                  device.clear = () => s('clear');
                  device.clearText = () => s('cleartext');
                  device.clearPixels = () => s('clearpixels');
                  device.clearLine = () => s('clearline');
                  device.scroll = (k) => s('scroll', n(k));
                  device.getSize = () => [s('cols'), s('rows')];
                  device.setTextScale = (v) => s('scale', n(v));
                  device.getTextScale = () => s('getscale');
                  device.setPaletteColor = (i, r, g, b) => (g === undefined
                    ? s('palette', n(i), n(r))
                    : s('palette', n(i), (n(r) << 16) | (n(g) << 8) | n(b)));
                  device.setPixel = (x, y, c) => s('pixel', n(x), n(y), px(c));
                  device.fillRect = (x, y, w, h, c) => s('rect', n(x), n(y), n(w), n(h), px(c));
                  device.drawLine = (x0, y0, x1, y1, c) => s('line', n(x0), n(y0), n(x1), n(y1), px(c));
                  device.getPixelSize = () => [s('pw'), s('ph')];
                } else if (type === 'servo') {
                  const v = (op, a, b) => host.servo(key, op, a, b);
                  const n = (x) => Number(x);
                  const opt = (x) => (x === undefined || x === null ? null : Number(x));
                  device.setAngle = (deg, speed) => v('angle', n(deg), opt(speed));
                  device.rotate = (deg, speed) => v('rotate', n(deg), opt(speed));
                  device.setSpeed = (s) => v('speed', n(s));
                  device.stop = () => v('stop');
                  device.getAngle = () => v('getangle');
                  device.getTarget = () => v('gettarget');
                  device.isMoving = () => v('moving');
                  device.isPowered = () => v('powered');
                }
                return device;
              };
              globalThis.colors = Object.freeze({
                white: 0, orange: 1, magenta: 2, lightBlue: 3, yellow: 4, lime: 5, pink: 6, gray: 7,
                lightGray: 8, cyan: 9, purple: 10, blue: 11, brown: 12, green: 13, red: 14, black: 15,
              });
              globalThis.os = {
                on: (event, fn) => {
                  if (typeof fn !== 'function') throw new TypeError('os.on needs a function');
                  if (event === 'start') {
                    host.onStart(fn);
                  } else if (event === 'touch') {
                    host.onTouch((id, x, y, px, py) => fn({ screen: id, x: x, y: y, pixelX: px, pixelY: py }));
                  } else {
                    throw new Error('Unknown event "' + event + '"');
                  }
                },
              };
              const timer = (repeat) => (fn, ms, ...args) => {
                if (typeof fn !== 'function') throw new TypeError('A function is required');
                return host.setTimer(() => fn(...args), Number(ms) || 0, repeat);
              };
              globalThis.setTimeout = timer(false);
              globalThis.setInterval = timer(true);
              globalThis.clearTimeout = globalThis.clearInterval = (id) => host.clearTimer(Number(id) | 0);
            })
            """;

    private static final class Timer {
        final int id;
        final Value callback;
        final boolean repeat;
        final long intervalTicks;
        long due;

        Timer(int id, Value callback, boolean repeat, long intervalTicks, long due) {
            this.id = id;
            this.callback = callback;
            this.repeat = repeat;
            this.intervalTicks = intervalTicks;
            this.due = due;
        }
    }

    private final Map<String, String> devices;
    /** Device ID to the position of the device's block (for a Screen: its origin block), for the script API. */
    private final Map<String, GlobalPos> positions;
    private final List<Value> touchHandlers = new ArrayList<>();
    private final Deque<ConsoleLine> lines = new ArrayDeque<>();
    private final List<Timer> timers = new ArrayList<>();
    private final List<Value> startHandlers = new ArrayList<>();
    private Context context;
    private boolean running;
    private long tickCount;
    private int nextTimerId = 1;
    private int printsThisCall;
    private volatile boolean timedOut;
    private long version;

    /**
     * @param devices   device ID to device type for everything connected to the Brain (for get())
     * @param positions device ID to the position of its block
     */
    public BrainRuntime(Map<String, String> devices, Map<String, GlobalPos> positions) {
        this.devices = Map.copyOf(devices);
        this.positions = Map.copyOf(positions);
        bump();
    }

    // ------------------------------------------------------------ public API

    public boolean isRunning() {
        return running;
    }

    /** Changes every time the console or the running state changes; unique across all runtimes. */
    public long version() {
        return version;
    }

    public List<ConsoleLine> lines() {
        return new ArrayList<>(lines);
    }

    /** Starts the script: runs main.js once, then the os.on("start") handlers. */
    public void start(Engine engine, Map<String, String> files) {
        lines.clear();
        String code = files.get("main.js");
        if (code == null) {
            error("main.js not found. Create it in the Explorer and press Run.");
            return;
        }
        try {
            context = buildContext(engine);
        } catch (Throwable t) {
            LOGGER.error("MineComputers: the JavaScript engine could not start", t);
            error("The JavaScript engine could not start: " + t);
            return;
        }
        running = true;
        bump();
        // Our own setup code gets its own, generous time, so the player's script is not charged for engine start-up.
        if (!runGuarded(SETUP_CAP_MS, () -> context.eval("js", BOOTSTRAP).execute(new ScriptHost(this)))) {
            return;
        }
        boolean ok = runGuarded(net.minecomputers.MineComputersConfig.startCapMs(), () -> {
            context.eval(Source.newBuilder("js", code, "main.js").buildLiteral());
            for (Value handler : List.copyOf(startHandlers)) {
                handler.execute();
            }
        });
        if (ok) {
            startHandlers.clear();
            finishIfIdle();
        }
    }

    /** Runs the timers that are due. Called once per server tick while the script runs. */
    public void tick() {
        if (!running) {
            return;
        }
        tickCount++;
        List<Timer> due = new ArrayList<>();
        for (Timer timer : timers) {
            if (timer.due <= tickCount) {
                due.add(timer);
            }
        }
        due.sort(Comparator.<Timer>comparingLong(t -> t.due).thenComparingInt(t -> t.id));
        for (Timer timer : due) {
            if (!running) {
                return;
            }
            if (!timers.contains(timer)) {
                continue; // cleared by an earlier callback in this tick
            }
            if (timer.repeat) {
                timer.due = tickCount + timer.intervalTicks;
            } else {
                timers.remove(timer);
            }
            if (!runGuarded(net.minecomputers.MineComputersConfig.eventCapMs(), () -> timer.callback.execute())) {
                return;
            }
        }
        finishIfIdle();
    }

    /**
     * Like a Node.js program: when nothing is left to wait for (no timers), the script is finished and stops
     * by itself. A script with setInterval keeps running until Stop.
     */
    private void finishIfIdle() {
        if (running && timers.isEmpty() && touchHandlers.isEmpty()) {
            push(new ConsoleLine(BRAIN_TAG, "Script finished.", 0));
            stop();
        }
    }

    /**
     * A player touched (right-clicked) the Screen whose origin block is at this position. Runs the script's
     * os.on("touch") handlers. Returns false if this script has no screen there or nobody listens.
     */
    public boolean touch(GlobalPos screen, int x, int y, int px, int py) {
        if (!running || touchHandlers.isEmpty()) {
            return false;
        }
        String id = null;
        for (Map.Entry<String, GlobalPos> entry : positions.entrySet()) {
            if (entry.getValue().equals(screen) && "screen".equals(devices.get(entry.getKey()))) {
                id = entry.getKey();
                break;
            }
        }
        if (id == null) {
            return false;
        }
        final String screenId = id;
        for (Value handler : List.copyOf(touchHandlers)) {
            if (!running) {
                break;
            }
            if (!runGuarded(net.minecomputers.MineComputersConfig.touchCapMs(), () -> handler.execute(screenId, x, y, px, py))) {
                break;
            }
        }
        return true;
    }

    /** Stops the script. The console stays, so the player can still read it. */
    public void stop() {
        boolean wasRunning = running;
        running = false;
        timers.clear();
        startHandlers.clear();
        touchHandlers.clear();
        Context old = context;
        context = null;
        if (old != null) {
            try {
                old.close(true);
            } catch (Throwable ignored) {
                // Already closed (for example by the watchdog).
            }
        }
        if (wasRunning) {
            bump();
        }
    }

    // ------------------------------------------------- used by ScriptHost

    String deviceType(String id) {
        return devices.get(id);
    }

    GlobalPos devicePos(String id) {
        return positions.get(id);
    }

    void addTouchHandler(Value callback) {
        if (!callback.canExecute()) {
            throw new IllegalArgumentException("A function is required");
        }
        if (touchHandlers.size() >= MAX_TIMERS) {
            throw new IllegalStateException("Too many touch handlers");
        }
        touchHandlers.add(callback);
    }

    void addStartHandler(Value callback) {
        if (!callback.canExecute()) {
            throw new IllegalArgumentException("A function is required");
        }
        startHandlers.add(callback);
    }

    int addTimer(Value callback, double milliseconds, boolean repeat) {
        if (!callback.canExecute()) {
            throw new IllegalArgumentException("A function is required");
        }
        if (timers.size() >= MAX_TIMERS) {
            throw new IllegalStateException("Too many timers (the limit is " + MAX_TIMERS + ")");
        }
        double clamped = Math.max(0, Math.min(milliseconds, 1.0e9));
        long ticks = Math.max(1, Math.round(clamped / 50.0));
        Timer timer = new Timer(nextTimerId++, callback, repeat, ticks, tickCount + ticks);
        timers.add(timer);
        return timer.id;
    }

    void removeTimer(int id) {
        timers.removeIf(t -> t.id == id);
    }

    void addLine(String tag, String text, int level) {
        if (printsThisCall >= MAX_PRINTS_PER_CALL) {
            if (printsThisCall == MAX_PRINTS_PER_CALL) {
                printsThisCall++;
                push(new ConsoleLine(BRAIN_TAG, "[too much output, the rest was dropped]", 1));
            }
            return;
        }
        printsThisCall++;
        String shown = text.length() > MAX_LINE_CHARS ? text.substring(0, MAX_LINE_CHARS) + "..." : text;
        push(new ConsoleLine(tag, shown, level));
    }

    // -------------------------------------------------------------- internals

    private void error(String text) {
        push(new ConsoleLine(BRAIN_TAG, text, 2));
    }

    private void push(ConsoleLine line) {
        lines.addLast(line);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
        bump();
    }

    private void bump() {
        version = VERSIONS.incrementAndGet();
    }

    private static Context buildContext(Engine engine) {
        try {
            return baseBuilder(engine)
                    .option("js.load", "false")
                    .option("js.print", "false")
                    .option("js.polyglot-builtin", "false")
                    .build();
        } catch (IllegalArgumentException e) {
            // An optional hardening option is not known to this GraalJS version: run without it.
            LOGGER.warn("MineComputers: a GraalJS option was rejected, running without it: {}", e.getMessage());
            return baseBuilder(engine).build();
        }
    }

    private static Context.Builder baseBuilder(Engine engine) {
        ResourceLimits limits = ResourceLimits.newBuilder().statementLimit(net.minecomputers.MineComputersConfig.statementLimit(), null).build();
        return Context.newBuilder("js")
                .engine(engine)
                .allowHostAccess(HostAccess.EXPLICIT)
                .allowHostClassLookup(name -> false)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowCreateProcess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .resourceLimits(limits);
    }

    /**
     * Runs one call into the script with its limits. Returns false (and stops the script) if it failed,
     * ran too many operations, or ran longer than the cap.
     */
    private boolean runGuarded(long capMillis, Runnable body) {
        timedOut = false;
        printsThisCall = 0;
        Context current = context;
        if (current == null) {
            return false;
        }
        current.resetLimits();
        ScheduledFuture<?> guard = WATCHDOG.schedule(() -> {
            timedOut = true;
            try {
                current.close(true);
            } catch (Throwable ignored) {
                // Nothing more to do.
            }
        }, capMillis, TimeUnit.MILLISECONDS);
        try {
            body.run();
            if (timedOut) {
                error("Stopped: the script ran longer than " + capMillis + " ms at once.");
                stop();
                return false;
            }
            return true;
        } catch (PolyglotException e) {
            reportFailure(e, capMillis);
            stop();
            return false;
        } catch (Throwable t) {
            LOGGER.error("MineComputers: internal script error", t);
            error(timedOut ? "Stopped: the script ran longer than " + capMillis + " ms at once." : "Internal error: " + t);
            stop();
            return false;
        } finally {
            guard.cancel(false);
        }
    }

    private void reportFailure(PolyglotException e, long capMillis) {
        if (timedOut) {
            error("Stopped: the script ran longer than " + capMillis + " ms at once.");
        } else if (e.isResourceExhausted()) {
            error("Stopped: the script ran too many operations at once.");
        } else if (e.isCancelled()) {
            error("Stopped.");
        } else if (e.isHostException() && e.asHostException() instanceof RuntimeException host
                && host.getMessage() != null) {
            // An error our API threw on purpose (bad argument, missing device): show its message.
            SourceSection where = e.getSourceLocation();
            String location = where != null ? " (" + where.getSource().getName() + ":" + where.getStartLine() + ")" : "";
            error(host.getMessage() + location);
        } else if (e.isGuestException()) {
            SourceSection where = e.getSourceLocation();
            String location = where != null ? " (" + where.getSource().getName() + ":" + where.getStartLine() + ")" : "";
            error(e.getMessage() + location);
        } else {
            LOGGER.error("MineComputers: internal script error", e);
            error("Internal error: " + e.getMessage());
        }
    }
}
