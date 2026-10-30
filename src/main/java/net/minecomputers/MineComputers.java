package net.minecomputers;

import net.minecomputers.network.ModNetwork;
import net.minecomputers.network.ServerHandlers;
import net.minecomputers.power.InfiniteEnergy;
import net.minecomputers.registry.ModBlockEntities;
import net.minecomputers.registry.ModBlocks;
import net.minecomputers.registry.ModCreativeTabs;
import net.minecomputers.registry.ModItems;
import net.minecomputers.screen.ScreenSync;
import net.minecomputers.script.ScriptManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * MineComputers: programmable computers. You write JavaScript on a laptop,
 * the code runs on a brain, and the brain controls screens, speakers, servos and redstone.
 * See docs/SPEC.md for the full design.
 */
@Mod(MineComputers.MOD_ID)
public class MineComputers {
    public static final String MOD_ID = "minecomputers";

    /** The Power Source block offers unlimited FE to its neighbours (and to cables, see PowerNet). */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.EnergyStorage.BLOCK, (level, pos, state, blockEntity, side) -> InfiniteEnergy.INSTANCE,
                ModBlocks.POWER_SOURCE.get());
    }

    public MineComputers(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.COMMON, MineComputersConfig.SPEC);
        modEventBus.addListener(ModNetwork::register);
        modEventBus.addListener(MineComputers::registerCapabilities);
        NeoForge.EVENT_BUS.addListener(ScriptManager::onTick);
        NeoForge.EVENT_BUS.addListener(ServerHandlers::onLogout);
        NeoForge.EVENT_BUS.addListener(ScriptManager::onStopping);
        NeoForge.EVENT_BUS.addListener(ScreenSync::onChunkSent);
    }
}
