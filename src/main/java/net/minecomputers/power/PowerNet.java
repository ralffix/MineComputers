package net.minecomputers.power;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecomputers.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * The FE network. Power Cables and Power Data Cables carry energy (a Data Cable does not). A device that needs
 * FE (Servo, Speaker) asks here: the network is searched from the device through the cables, every block next to
 * the network or the device that offers energy (a generator, a battery: anything with the standard NeoForge
 * energy capability that allows extracting) is a source, and the energy is taken from them. Server side only.
 */
public final class PowerNet {
    /** Safety cap so a huge cable network can't lag the server. */
    private static final int MAX_BLOCKS = 512;

    private static boolean carriesPower(Block block) {
        return block == ModBlocks.POWER_CABLE.get() || block == ModBlocks.POWER_DATA_CABLE.get();
    }

    /** A block of the network that offers energy, and the side of it that the network touches. */
    private record Source(IEnergyStorage storage) {
    }

    /**
     * Tries to take `amount` FE for the device at `device`. All or nothing: if the sources together can't give the
     * full amount, nothing is taken and false is returned.
     */
    public static boolean pull(Level level, BlockPos device, int amount) {
        if (amount <= 0 || net.minecomputers.MineComputersConfig.freePower()) {
            return true; // nothing to pay, or free power is switched on in the config (testing)
        }
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        List<Source> sources = new ArrayList<>();
        visited.add(device);
        queue.add(device);
        while (!queue.isEmpty() && visited.size() < MAX_BLOCKS) {
            BlockPos current = queue.poll();
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (!level.isLoaded(next) || !visited.add(next)) {
                    continue;
                }
                if (carriesPower(level.getBlockState(next).getBlock())) {
                    queue.add(next);
                    continue;
                }
                IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, next, direction.getOpposite());
                if (storage != null && storage.canExtract()) {
                    sources.add(new Source(storage));
                }
            }
        }
        int available = 0;
        for (Source source : sources) {
            available += Math.max(0, source.storage().extractEnergy(amount, true));
            if (available >= amount) {
                break;
            }
        }
        if (available < amount) {
            return false;
        }
        int remaining = amount;
        for (Source source : sources) {
            if (remaining <= 0) {
                break;
            }
            remaining -= Math.max(0, source.storage().extractEnergy(remaining, false));
        }
        return true;
    }

    private PowerNet() {
    }
}
