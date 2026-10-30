package net.minecomputers.power;

import net.neoforged.neoforge.energy.IEnergyStorage;

/** An energy source that never runs out: gives whatever is asked, takes nothing. Used by the Power Source block. */
public final class InfiniteEnergy implements IEnergyStorage {
    public static final InfiniteEnergy INSTANCE = new InfiniteEnergy();

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        return 0;
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        return Math.max(0, toExtract);
    }

    @Override
    public int getEnergyStored() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getMaxEnergyStored() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean canExtract() {
        return true;
    }

    @Override
    public boolean canReceive() {
        return false;
    }

    private InfiniteEnergy() {
    }
}
