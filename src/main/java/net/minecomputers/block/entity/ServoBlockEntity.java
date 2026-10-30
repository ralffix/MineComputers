package net.minecomputers.block.entity;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import net.minecomputers.block.ServoBlock;
import net.minecomputers.power.PowerNet;
import net.minecomputers.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Servo: turns a Create shaft to a programmed angle and holds it until the code sets a new one. Degrees are
 * unlimited (setAngle(3600) is ten full turns).
 *
 * How it turns: the Servo is a Create rotation SOURCE. While it has to move it generates a speed (RPM = degrees
 * per second / 6), when it has arrived it generates 0 and the shaft stands still, which is how it holds the angle.
 * The angle is counted from the real speed the block gets from Create every tick, so if the load stalls it
 * (overstressed network, speed 0) the angle does not advance either. In the last tick before the target the
 * speed is lowered so the shaft arrives on the exact angle.
 *
 * Power: FE only (PowerNet). 1 FE every 3 seconds while idle; while turning, faster and longer cost more
 * (about 0.2 FE per degree plus 1 FE per 900 degrees/second of speed, per degree). No FE: it does not move.
 *
 * Load: it can drive at most 2000 stress units (SU) in total, at any speed: the capacity it reports to Create is
 * 2000 / RPM per RPM, so capacity times speed is always 2000. More load than that overstresses the network.
 */
public class ServoBlockEntity extends GeneratingKineticBlockEntity {
    public static final double MAX_DEGREES = 1.0e7;
    public static final double MIN_SPEED = 1.0;
    /** Degrees per second: 720 = 120 RPM. */
    public static final double MAX_SPEED = 720.0;
    public static final double DEFAULT_SPEED = 90.0;
    /** The most stress (SU) the Servo can turn. */
    public static final float MAX_LOAD_SU = 2000f;
    /** One idle FE every 3 seconds. */
    private static final int IDLE_INTERVAL_TICKS = 60;

    private double angle;
    private double target;
    private double speed = DEFAULT_SPEED;
    private boolean powered;
    private int tickCount;
    /** The RPM we asked Create for (before the direction conversion). */
    private float desiredRpm;

    public ServoBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SERVO.get(), pos, state);
    }

    public double angle() {
        return angle;
    }

    public double target() {
        return target;
    }

    public double speed() {
        return speed;
    }

    public boolean isMoving() {
        return Math.abs(target - angle) > 1.0e-6;
    }

    public boolean isPowered() {
        return powered;
    }

    private static double clampSpeed(double value) {
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, value));
    }

    private static double clampDegrees(double value) {
        return Math.max(-MAX_DEGREES, Math.min(MAX_DEGREES, value));
    }

    /** Turn to this angle (degrees, any number). A speed (degrees per second) is optional. */
    public void setTarget(double degrees, Double newSpeed) {
        target = clampDegrees(degrees);
        if (newSpeed != null) {
            speed = clampSpeed(newSpeed);
        }
        setChanged();
    }

    /** Turn by this many degrees from the current target. */
    public void rotateBy(double degrees, Double newSpeed) {
        setTarget(target + degrees, newSpeed);
    }

    public void setSpeed(double newSpeed) {
        speed = clampSpeed(newSpeed);
        setChanged();
    }

    /** Stops where it is and holds that angle. */
    public void stopNow() {
        target = angle;
        setChanged();
    }

    // ------------------------------------------------------------- Create

    /** The speed (RPM) this source generates, in the direction Create expects for the way the block faces. */
    @Override
    public float getGeneratedSpeed() {
        if (!(getBlockState().getBlock() instanceof ServoBlock)) {
            return 0;
        }
        return convertToDirection(desiredRpm, getBlockState().getValue(ServoBlock.FACING));
    }

    /** The capacity per RPM, chosen so that capacity times speed is MAX_LOAD_SU at any speed. */
    @Override
    public float calculateAddedStressCapacity() {
        float rpm = Math.abs(desiredRpm);
        return rpm < 1.0e-3f ? 0f : MAX_LOAD_SU / rpm;
    }

    /** Every server tick (Create ticks its block entities itself). */
    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) {
            return;
        }
        tickCount++;

        // 1. Count the turning that really happened during the last tick (Create's speed for this block).
        float realRpm = convertToDirection(getSpeed(), getBlockState().getValue(ServoBlock.FACING));
        if (Math.abs(realRpm) > 1.0e-4f) {
            double before = target - angle;
            angle += realRpm * 6.0 / 20.0;
            double after = target - angle;
            if (before * after <= 0 || Math.abs(after) < 1.0e-3) {
                angle = target; // arrived (or passed it by a rounding hair)
            }
            setChanged();
        }

        // 2. Decide the speed for the next tick, and pay for it.
        float next = 0f;
        if (isMoving()) {
            double remaining = target - angle;
            double stepDegrees = Math.min(speed / 20.0, Math.abs(remaining)); // degrees during the next tick
            int cost = Math.max(1, (int) Math.ceil(stepDegrees * (0.2 + speed / 900.0)));
            if (PowerNet.pull(level, worldPosition, cost)) {
                powered = true;
                next = (float) (Math.signum(remaining) * stepDegrees * 20.0 / 6.0); // RPM that gives stepDegrees in a tick
            } else {
                powered = false; // no FE: the shaft stops until power comes back
            }
        } else if (tickCount % IDLE_INTERVAL_TICKS == 0) {
            powered = PowerNet.pull(level, worldPosition, 1);
        }
        if (Math.abs(next - desiredRpm) > 1.0e-4f) {
            desiredRpm = next;
            updateGeneratedRotation();
        }
    }

    // --------------------------------------------------------------- save

    @Override
    public void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putDouble("angle", angle);
        compound.putDouble("target", target);
        compound.putDouble("speed", speed);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        angle = clampDegrees(compound.getDouble("angle"));
        target = clampDegrees(compound.getDouble("target"));
        speed = compound.contains("speed") ? clampSpeed(compound.getDouble("speed")) : DEFAULT_SPEED;
    }
}
