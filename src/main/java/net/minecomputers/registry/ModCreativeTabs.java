package net.minecomputers.registry;

import net.minecomputers.MineComputers;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** One creative tab that holds everything in the mod. */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MineComputers.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + MineComputers.MOD_ID))
                    .icon(() -> new ItemStack(ModItems.BRAIN.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.LAPTOP.get());
                        output.accept(ModItems.REMOTE_ACCESS.get());
                        output.accept(ModItems.LINK_TRANSMITTER.get());
                        output.accept(ModItems.CIPHER.get());
                        output.accept(ModItems.BRAIN.get());
                        output.accept(ModItems.DATA_CABLE.get());
                        output.accept(ModItems.POWER_CABLE.get());
                        output.accept(ModItems.POWER_DATA_CABLE.get());
                        output.accept(ModItems.POWER_SOURCE.get());
                        output.accept(ModItems.SERVO.get());
                        output.accept(ModItems.SCREEN.get());
                        output.accept(ModItems.SPEAKER.get());
                        output.accept(ModItems.REDSTONE_LINK.get());
                        output.accept(ModItems.REMOTE_GATEWAY.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
