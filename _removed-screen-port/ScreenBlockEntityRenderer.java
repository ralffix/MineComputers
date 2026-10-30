package net.minecomputers.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecomputers.block.ScreenBlock;
import net.minecomputers.block.entity.ScreenBlockEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws a Screen's text on its front face, in the world.
 *
 * Ported from the first version's Monitor renderer, with the placement worked out the way the vanilla sign
 * does it (rotate to the facing, step out to the front face, scale the font with a negative Y) instead of
 * the old numbers. The numbers below match the inset of models/block/screen.json (a 10x10 pixel panel, its
 * outer face 0.525 blocks from the center). If you replace the model with your own, adjust FACE_OFFSET and
 * BOX_PIXELS to fit your screen area.
 */
public class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity> {
    /** How far from the block's center the text plane sits: the inset's outer face (0.525) plus a hair. */
    private static final float FACE_OFFSET = 0.53f;
    /** One font pixel in blocks. */
    private static final float TEXT_SCALE = 0.0065f;
    /** The text area, in font pixels, centered on the face: 96 px = 0.624 blocks, the width of the inset. */
    private static final int BOX_PIXELS = 96;
    private static final int LINE_PIXELS = 9;
    private static final int TEXT_COLOR = 0xFF55FF55;

    private final Font font;

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    public void render(ScreenBlockEntity screen, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight, int packedOverlay) {
        List<String> lines = screen.lines();
        BlockState state = screen.getBlockState();
        if (lines.isEmpty() || !(state.getBlock() instanceof ScreenBlock)) {
            return;
        }
        Direction facing = state.getValue(ScreenBlock.FACING);

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        poseStack.translate(0, 0, FACE_OFFSET);
        poseStack.scale(TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);

        int maxLines = BOX_PIXELS / LINE_PIXELS;
        int first = Math.max(0, lines.size() - maxLines);
        float x = -BOX_PIXELS / 2f;
        float y = -BOX_PIXELS / 2f;
        int fullBright = LightTexture.pack(15, 15); // a screen lights itself, whatever the light around it
        for (int i = first; i < lines.size(); i++) {
            String line = font.plainSubstrByWidth(lines.get(i), BOX_PIXELS);
            font.drawInBatch(line, x, y, TEXT_COLOR, false, poseStack.last().pose(), buffer,
                    Font.DisplayMode.NORMAL, 0, fullBright);
            y += LINE_PIXELS;
        }

        poseStack.popPose();
    }
}
