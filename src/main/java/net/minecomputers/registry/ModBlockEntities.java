package net.minecomputers.registry;

import net.minecomputers.MineComputers;
import net.minecomputers.block.entity.BrainBlockEntity;
import net.minecomputers.block.entity.ScreenBlockEntity;
import net.minecomputers.block.entity.ServoBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MineComputers.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BrainBlockEntity>> BRAIN =
            BLOCK_ENTITIES.register("brain",
                    () -> BlockEntityType.Builder.of(BrainBlockEntity::new, ModBlocks.BRAIN.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ScreenBlockEntity>> SCREEN =
            BLOCK_ENTITIES.register("screen",
                    () -> BlockEntityType.Builder.of(ScreenBlockEntity::new, ModBlocks.SCREEN.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ServoBlockEntity>> SERVO =
            BLOCK_ENTITIES.register("servo",
                    () -> BlockEntityType.Builder.of(ServoBlockEntity::new, ModBlocks.SERVO.get()).build(null));

    private ModBlockEntities() {
    }
}
