package net.minecomputers.script;

import net.minecraft.core.GlobalPos;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

/**
 * The only Java object scripts can reach, and only through the small JavaScript wrapper in
 * {@link BrainRuntime} (console, get, os, timers). Every method scripts may call is marked
 * {@code @HostAccess.Export}; the context uses HostAccess.EXPLICIT, so nothing else is visible.
 */
public final class ScriptHost {
    private final BrainRuntime runtime;

    ScriptHost(BrainRuntime runtime) {
        this.runtime = runtime;
    }

    /** console.log / info / warn / error: prints in the laptop's Console panel. */
    @HostAccess.Export
    public void print(int level, String text) {
        runtime.addLine(BrainRuntime.BRAIN_TAG, text, level);
    }

    /** The type of the device with this ID ("screen", "servo", ...), or null if there is none. */
    @HostAccess.Export
    public String typeOf(String id) {
        return runtime.deviceType(id);
    }

    /**
     * One call on a Screen: get("screen:1").write(...) and the other Screen methods end up here, with a short
     * operation name and up to five arguments (see {@link ScreenApi}).
     */
    @HostAccess.Export
    public Object screen(String id, String op, Object a, Object b, Object c, Object d, Object e) {
        GlobalPos pos = runtime.devicePos(id);
        if (pos == null || !"screen".equals(runtime.deviceType(id))) {
            throw new IllegalArgumentException("No screen with ID \"" + id + "\" on this network");
        }
        return ScreenApi.call(pos, op, a, b, c, d, e);
    }

    /** One call on a Servo: get("servo:1").setAngle(90) and the other Servo methods end up here (see {@link ServoApi}). */
    @HostAccess.Export
    public Object servo(String id, String op, Object a, Object b) {
        GlobalPos pos = runtime.devicePos(id);
        if (pos == null || !"servo".equals(runtime.deviceType(id))) {
            throw new IllegalArgumentException("No servo with ID \"" + id + "\" on this network");
        }
        return ServoApi.call(pos, op, a, b);
    }

    @HostAccess.Export
    public void onStart(Value callback) {
        runtime.addStartHandler(callback);
    }

    /** os.on("touch", fn): fn gets the screen ID and the cell and pixel that were touched. */
    @HostAccess.Export
    public void onTouch(Value callback) {
        runtime.addTouchHandler(callback);
    }

    @HostAccess.Export
    public int setTimer(Value callback, double milliseconds, boolean repeat) {
        return runtime.addTimer(callback, milliseconds, repeat);
    }

    @HostAccess.Export
    public void clearTimer(int id) {
        runtime.removeTimer(id);
    }
}
