package net.minecomputers.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecomputers.MineComputers;
import net.minecomputers.block.ScreenBlock;
import net.minecomputers.block.entity.ScreenBlockEntity;
import net.minecomputers.screen.ScreenTerminal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * Draws a Screen on the front face of its group, in the world. Only the origin block (top left) draws, and it
 * draws the whole group. Layers from the back to the front: a black face, the colored character cells, the
 * pixel layer (a texture), and the text. The server sends the content as one packet whenever it changes;
 * this class only draws what the last packet said.
 *
 * Placement works like the vanilla wall sign: rotate to the facing, step out to the front face. The front face
 * is assumed to be a flat full block face (models/block/screen.json); if you model your own screen, change
 * MARGIN to the width of its frame.
 */
public class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity> {
    /** The frame around the drawing area, in blocks. */
    private static final float MARGIN = 0.04f;
    /** The layers stand this far apart (in blocks) so they don't flicker into each other at a distance. */
    private static final float Z_BASE = 0.002f;
    private static final float Z_CELLS = 0.004f;
    private static final float Z_PIXELS = 0.006f;
    private static final float Z_TEXT = 0.008f;
    private static final int FACE_COLOR = 0xFF050505;

    private static int textureCounter;

    private final Font font;

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    /** A screen is not drawn from far away: text this small can't be read anyway. */
    @Override
    public int getViewDistance() {
        return 48;
    }

    /**
     * The whole group is drawn from the origin block, so the box used for "is it on screen" must cover the group.
     * (In NeoForge for 1.21.1 this lives on the renderer, not on the block entity.)
     */
    public AABB getRenderBoundingBox(ScreenBlockEntity screen) {
        BlockPos pos = screen.getBlockPos();
        if (!screen.isOrigin() || screen.isSingle() || !(screen.getBlockState().getBlock() instanceof ScreenBlock)) {
            return new AABB(pos);
        }
        Direction right = ScreenBlockEntity.rightOf(screen.getBlockState().getValue(ScreenBlock.FACING));
        BlockPos far = pos.relative(right, screen.groupWidth() - 1).relative(Direction.DOWN, screen.groupHeight() - 1);
        return new AABB(Math.min(pos.getX(), far.getX()), Math.min(pos.getY(), far.getY()),
                Math.min(pos.getZ(), far.getZ()), Math.max(pos.getX(), far.getX()) + 1,
                Math.max(pos.getY(), far.getY()) + 1, Math.max(pos.getZ(), far.getZ()) + 1);
    }

    /** The texture of the pixel layer of one screen. Closed by the block entity when it goes away. */
    private static final class PixelTexture implements AutoCloseable {
        final ResourceLocation location;
        final NativeImage image;
        final DynamicTexture texture;
        ScreenTerminal source;

        PixelTexture(int width, int height) {
            this.location = ResourceLocation.fromNamespaceAndPath(MineComputers.MOD_ID, "screen_pixels/" + (textureCounter++));
            this.image = new NativeImage(width, height, true);
            this.texture = new DynamicTexture(image);
            Minecraft.getInstance().getTextureManager().register(location, texture);
        }

        /** Copies the terminal's pixel layer into the texture (only when the terminal object is a new one). */
        void update(ScreenTerminal terminal) {
            if (source == terminal) {
                return;
            }
            source = terminal;
            byte[] pixels = terminal.pixels();
            int w = image.getWidth();
            int h = image.getHeight();
            int[] abgr = new int[ScreenTerminal.COLORS];
            for (int i = 0; i < abgr.length; i++) {
                int rgb = terminal.color(i);
                abgr[i] = 0xFF000000 | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
            }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    byte value = pixels == null ? ScreenTerminal.CLEAR : pixels[y * w + x];
                    image.setPixelRGBA(x, y, value < 0 ? 0 : abgr[value & 15]);
                }
            }
            texture.upload();
        }

        @Override
        public void close() {
            Minecraft.getInstance().getTextureManager().release(location);
        }
    }

    private PixelTexture pixelTexture(ScreenBlockEntity screen, ScreenTerminal terminal) {
        PixelTexture current = screen.renderResource() instanceof PixelTexture p ? p : null;
        if (current != null && (current.image.getWidth() != terminal.pixelW() || current.image.getHeight() != terminal.pixelH())) {
            screen.setRenderResource(null);
            current.close();
            current = null;
        }
        if (current == null) {
            current = new PixelTexture(terminal.pixelW(), terminal.pixelH());
            screen.setRenderResource(current);
        }
        current.update(terminal);
        return current;
    }

    @Override
    public void render(ScreenBlockEntity screen, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight, int packedOverlay) {
        ScreenTerminal terminal = screen.clientTerminal();
        BlockState state = screen.getBlockState();
        if (!screen.isOrigin() || terminal == null || !(state.getBlock() instanceof ScreenBlock)) {
            return;
        }
        Direction facing = state.getValue(ScreenBlock.FACING);
        float width = terminal.blocksW();
        float height = terminal.blocksH();
        float areaW = width - 2 * MARGIN;
        float areaH = height - 2 * MARGIN;
        int cols = terminal.cols();
        int rows = terminal.rows();
        float cellW = areaW / cols;
        float cellH = areaH / rows;
        int light = LightTexture.FULL_BRIGHT; // a screen lights itself, whatever the light around it

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        // Now the front face is at z = 0.5. Go to its top left corner of the whole group: x runs right, y runs up.
        poseStack.translate(-0.5, 0.5, 0.5);
        Matrix4f matrix = poseStack.last().pose();

        // 1. The face, black.
        VertexConsumer quads = buffer.getBuffer(RenderType.debugQuads());
        quad(quads, matrix, 0, 0, width, -height, Z_BASE, FACE_COLOR);

        // 2. Cell backgrounds: runs of the same color along a row become one quad.
        for (int r = 0; r < rows; r++) {
            int c = 0;
            while (c < cols) {
                int color = terminal.bgAt(r * cols + c);
                int end = c + 1;
                while (end < cols && terminal.bgAt(r * cols + end) == color) {
                    end++;
                }
                if (terminal.color(color) != 0x050505) {
                    quad(quads, matrix, MARGIN + c * cellW, -(MARGIN + r * cellH),
                            MARGIN + end * cellW, -(MARGIN + (r + 1) * cellH), Z_CELLS, 0xFF000000 | terminal.color(color));
                }
                c = end;
            }
        }

        // 3. The pixel layer.
        if (terminal.pixels() != null) {
            PixelTexture pixels = pixelTexture(screen, terminal);
            VertexConsumer tex = buffer.getBuffer(RenderType.text(pixels.location));
            float x0 = MARGIN;
            float x1 = width - MARGIN;
            float y0 = -MARGIN;
            float y1 = -(height - MARGIN);
            tex.addVertex(matrix, x0, y1, Z_PIXELS).setColor(0xFFFFFFFF).setUv(0f, 1f).setLight(light);
            tex.addVertex(matrix, x1, y1, Z_PIXELS).setColor(0xFFFFFFFF).setUv(1f, 1f).setLight(light);
            tex.addVertex(matrix, x1, y0, Z_PIXELS).setColor(0xFFFFFFFF).setUv(1f, 0f).setLight(light);
            tex.addVertex(matrix, x0, y0, Z_PIXELS).setColor(0xFFFFFFFF).setUv(0f, 0f).setLight(light);
        } else if (screen.renderResource() != null) {
            // The pixels were cleared: let go of the texture.
            try {
                screen.renderResource().close();
            } catch (Exception ignored) {
                // Nothing more to do.
            }
            screen.setRenderResource(null);
        }

        // 4. The text. One font pixel is cellW / 6 blocks wide and cellH / 9 blocks high.
        poseStack.translate(MARGIN, -MARGIN, Z_TEXT);
        poseStack.scale(cellW / ScreenTerminal.CELL_W, -cellH / ScreenTerminal.CELL_H, 1f);
        Matrix4f textMatrix = poseStack.last().pose();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int index = r * cols + c;
                char ch = terminal.charAt(index);
                if (ch == ' ') {
                    continue;
                }
                String text = String.valueOf(ch);
                float offset = (ScreenTerminal.CELL_W - font.width(text)) / 2f;
                font.drawInBatch(text, c * ScreenTerminal.CELL_W + offset, r * ScreenTerminal.CELL_H,
                        0xFF000000 | terminal.color(terminal.fgAt(index)), false, textMatrix, buffer,
                        Font.DisplayMode.NORMAL, 0, light);
            }
        }

        poseStack.popPose();
    }

    /** A flat rectangle facing the viewer: left, top, right, bottom in blocks (y up), at depth z. */
    private static void quad(VertexConsumer consumer, Matrix4f matrix, float x0, float top, float x1, float bottom,
                             float z, int argb) {
        consumer.addVertex(matrix, x0, bottom, z).setColor(argb);
        consumer.addVertex(matrix, x1, bottom, z).setColor(argb);
        consumer.addVertex(matrix, x1, top, z).setColor(argb);
        consumer.addVertex(matrix, x0, top, z).setColor(argb);
    }
}
