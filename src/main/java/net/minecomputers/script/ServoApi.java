package net.minecomputers.script;

import net.minecomputers.block.entity.ServoBlockEntity;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * What scripts can do with a Servo: get("servo:1").setAngle(90), .rotate(360, 180), .setSpeed(45), .stop(),
 * .getAngle(), .getTarget(), .isMoving(), .isPowered(). Runs on the server thread. Angles are degrees, speed is
 * degrees per second (1 to 720, default 90).
 */
final class ServoApi {
    static Object call(GlobalPos device, String op, Object a, Object b) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = server == null ? null : server.getLevel(device.dimension());
        if (level == null || !level.isLoaded(device.pos())) {
            throw new IllegalStateException("The servo is not loaded");
        }
        if (!(level.getBlockEntity(device.pos()) instanceof ServoBlockEntity servo)) {
            throw new IllegalStateException("This servo is gone: run the script again");
        }
        switch (op) {
            case "angle" -> servo.setTarget(number(a), optional(b));
            case "rotate" -> servo.rotateBy(number(a), optional(b));
            case "speed" -> servo.setSpeed(number(a));
            case "stop" -> servo.stopNow();
            case "getangle" -> {
                return servo.angle();
            }
            case "gettarget" -> {
                return servo.target();
            }
            case "moving" -> {
                return servo.isMoving();
            }
            case "powered" -> {
                return servo.isPowered();
            }
            default -> throw new IllegalArgumentException("Unknown servo operation: " + op);
        }
        return null;
    }

    private static double number(Object o) {
        if (o instanceof Number n && !Double.isNaN(n.doubleValue())) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException("A number is required");
    }

    private static Double optional(Object o) {
        return o == null ? null : number(o);
    }

    private ServoApi() {
    }
}
