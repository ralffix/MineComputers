package net.minecomputers.block;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecomputers.block.entity.ServoBlockEntity;
import net.minecomputers.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Servo: a Create kinetic block. Its shaft is on the face it points to (FACING); it turns whatever shaft,
 * cogwheel or machine is attached there to the programmed angle and holds it (see ServoBlockEntity). Create
 * supplies the placement rules (FACING, like the Creative Motor), the rotation network and the ticking.
 */
public class ServoBlock extends DirectionalKineticBlock implements IBE<ServoBlockEntity> {
    public ServoBlock(Properties properties) {
        super(properties);
    }

    /** The shaft sticks out of the front only. */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    /** The Servo delivers a fixed budget instead of a stress impact, so Create's "stress impact" tooltip is hidden. */
    @Override
    public boolean hideStressImpact() {
        return true;
    }

    @Override
    public Class<ServoBlockEntity> getBlockEntityClass() {
        return ServoBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ServoBlockEntity> getBlockEntityType() {
        return ModBlockEntities.SERVO.get();
    }
}
