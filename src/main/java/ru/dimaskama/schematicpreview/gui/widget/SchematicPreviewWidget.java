package ru.dimaskama.schematicpreview.gui.widget;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3i;
import org.joml.*;
import ru.dimaskama.schematicpreview.SchematicPreview;
import ru.dimaskama.schematicpreview.SchematicPreviewConfigs;
import ru.dimaskama.schematicpreview.gui.GuiSchematicPreviewFullscreen;
import ru.dimaskama.schematicpreview.render.PreviewsCache;
import ru.dimaskama.schematicpreview.render.SchematicPreviewRenderer;

import java.io.File;
import java.lang.Math;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

// Extending malilib widget, but using vanilla inside
public class SchematicPreviewWidget extends WidgetBase {

    private final Renderer renderer = new Renderer(mc);
    private final Runnable tickAction = this::tick;
    private final List<ClickableWidget> buttons;
    private final Vector3f rotOrigin = new Vector3f();
    private final PreviewsCache cache;
    private final boolean nonStatic;
    private File schematicFile;
    private LitematicaSchematic lastSchematic;
    private Framebuffer framebuffer;
    private boolean mouseDragging;
    private int lastMouseX;
    private int lastMouseY;
    private boolean fullscreen;
    private boolean freecam;
    private float distance;
    private float yRot;
    private float xRot;
    private boolean scissor;

    public SchematicPreviewWidget(PreviewsCache cache, boolean nonStatic) {
        super(0, 0, 0, 0);
        this.cache = cache;
        this.nonStatic = nonStatic;
        buttons = nonStatic
                ? List.of(new OnOffButton(
                        6,
                        6,
                        Text.translatable("button.schematicpreview.fullscreen"),
                        this::toggleFullscreen,
                        SchematicPreview.id("fullscreen_on"),
                        SchematicPreview.id("fullscreen_on_focused"),
                        SchematicPreview.id("fullscreen_off"),
                        SchematicPreview.id("fullscreen_off_focused")
                ), new OnOffButton(
                        6,
                        6,
                        Text.translatable("button.schematicpreview.freecam"),
                        this::toggleFreecam,
                        SchematicPreview.id("freecam_on"),
                        SchematicPreview.id("freecam_on_focused"),
                        SchematicPreview.id("freecam_off"),
                        SchematicPreview.id("freecam_off_focused")
                ))
                : List.of();
    }

    public void setSchematic(File schematicFile) {
        this.schematicFile = schematicFile;
    }

    public void renderPreviewAndOverlay(DrawContext context, int x, int y, int width, int height) {
        SchematicPreview.addTickable(tickAction);
        if (x != this.x || y != this.y || width != this.width || height != this.height) {
            setPosition(x, y);
            setWidth(width);
            setHeight(height);
            resized();
        }
        int centerX = x + (width >> 1);
        int centerY = y + ((height - 10) >> 1);
        CompletableFuture<LitematicaSchematic> loadingSchematic = cache.getSchematic(schematicFile);
        if (loadingSchematic.isDone()) {
            LitematicaSchematic schematic;
            if (!loadingSchematic.isCompletedExceptionally() && (schematic = loadingSchematic.join()) != null) {
                int maxVolume = SchematicPreviewConfigs.PREVIEW_MAX_VOLUME.getIntegerValue();
                Vec3i size;
                if (nonStatic || maxVolume == 0 || (size = schematic.getTotalSize()).getX() * size.getY() * size.getZ() <= maxVolume) {
                    if (lastSchematic == null || !(lastSchematic == schematic || Objects.equals(lastSchematic.getFile(), schematic.getFile()))) {
                        lastSchematic = schematic;
                        renderer.newSchematic(schematic);
                        resetCameraPos();
                    }
                    if (!renderer.isBuildingTerrainOrStart()) {
                        renderPreviewAndOverlay(context, mc.getRenderTickCounter().getTickDelta(true));
                    } else {
                        context.drawCenteredTextWithShadow(textRenderer, "Building terrain...", centerX, centerY, 0xFFBBBBBB);
                    }
                } else {
                    context.drawCenteredTextWithShadow(textRenderer, "Too big", centerX, centerY, 0xFFBBBBBB);
                }
            } else {
                context.drawCenteredTextWithShadow(textRenderer, "Preview load failed", centerX, centerY, 0xFFFF5555);
            }
        } else {
            context.drawCenteredTextWithShadow(textRenderer, "Loading preview...", centerX, centerY, 0xFFBBBBBB);
        }
    }

    private void resized() {
        int x = this.x + 1;
        int y = this.y + 1;
        for (ClickableWidget button : buttons) {
            button.setPosition(x, y);
            x += button.getWidth() + 2;
        }
    }

    private void renderPreviewAndOverlay(DrawContext context, float tickDelta) {
        if (fullscreen) {
            context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), 0xFF000000);
        }
        context.draw();
        boolean framebufferUpdated = prepareFramebuffer();
        if (nonStatic || framebufferUpdated || renderer.needsReRender()) {
            if (scissor) {
                GlStateManager._disableScissorTest();
            }
            framebuffer.clear();
            renderer.render(framebuffer, tickDelta);
            mc.getFramebuffer().beginWrite(true);
            if (scissor) {
                GlStateManager._enableScissorTest();
            }
        }
        RenderSystem.backupProjectionMatrix();
        Matrix4f projMat = new Matrix4f().setOrtho(
                0.0F,
                mc.getWindow().getFramebufferWidth(),
                mc.getWindow().getFramebufferHeight(),
                0.0F,
                1000.0F,
                21000.0F
        );
        RenderSystem.setProjectionMatrix(projMat, ProjectionType.ORTHOGRAPHIC);
        RenderSystem.setShaderTexture(0, framebuffer.getColorAttachment());
        RenderSystem.setShader(ShaderProgramKeys.POSITION_TEX);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        Matrix4f matrix4f = context.getMatrices().peek().getPositionMatrix();
        BufferBuilder bufferBuilder = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        float scaleFactor = (float) mc.getWindow().getScaleFactor();
        float x1 = x * scaleFactor;
        float y1 = y * scaleFactor;
        float x2 = (x + width) * scaleFactor;
        float y2 = (y + height) * scaleFactor;
        bufferBuilder.vertex(matrix4f, x1, y1, 0.0F).texture(0.0F, 1.0F);
        bufferBuilder.vertex(matrix4f, x1, y2, 0.0F).texture(0.0F, 0.0F);
        bufferBuilder.vertex(matrix4f, x2, y2, 0.0F).texture(1.0F, 0.0F);
        bufferBuilder.vertex(matrix4f, x2, y1, 0.0F).texture(1.0F, 1.0F);
        BufferRenderer.drawWithGlobalProgram(bufferBuilder.end());
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.restoreProjectionMatrix();
        renderOverlay(context, tickDelta);
    }

    private boolean prepareFramebuffer() {
        double scale = mc.getWindow().getScaleFactor();
        int scaledWidth = (int) (scale * width);
        int scaledHeight = (int) (scale * height);
        if (framebuffer == null) {
            framebuffer = new SimpleFramebuffer(scaledWidth, scaledHeight, true);
            framebuffer.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            return true;
        }
        if (framebuffer.viewportWidth != scaledWidth || framebuffer.viewportHeight != scaledHeight) {
            framebuffer.resize(scaledWidth, scaledHeight);
            return true;
        }
        return false;
    }

    private void renderOverlay(DrawContext context, float tickDelta) {
        int mouseX = getMouseX();
        int mouseY = getMouseY();
        for (ClickableWidget button : buttons) {
            button.render(context, mouseX, mouseY, tickDelta);
        }
    }

    private void toggleFullscreen(boolean fullscreen) {
        if (fullscreen) {
            mc.setScreen(new GuiSchematicPreviewFullscreen(mc.currentScreen, this));
        } else {
            if (mc.currentScreen != null) {
                mc.currentScreen.close();
            }
        }
    }

    public void setFullScreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    private void toggleFreecam(boolean freecam) {
        this.freecam = freecam;
        if (!freecam) {
            resetCameraPos();
        }
    }

    private void resetCameraPos() {
        Vec3i size = lastSchematic.getMetadata().getEnclosingSize();
        rotOrigin.set(size.getX() * 0.5F, size.getY() * 0.3F, size.getZ() * 0.5F);
        yRot = (float) SchematicPreviewConfigs.PREVIEW_ROTATION_Y.getDoubleValue();
        xRot = (float) SchematicPreviewConfigs.PREVIEW_ROTATION_X.getDoubleValue();
        distance = MathHelper.sqrt(MathHelper.magnitude(size.getX() * 0.5F, size.getY() * 0.7F, size.getZ() * 0.5F)) * 2.0F;
        updateRendererCameraPos();
        renderer.resetPosition();
    }

    private void updateRendererCameraPos() {
        renderer.setRotation(-xRot, yRot);
        Vector3f offset = getRotationVec(xRot, 180.0F + yRot).mul(-distance);
        renderer.setPos(rotOrigin.x + offset.x, rotOrigin.y + offset.y, rotOrigin.z + offset.z);
    }

    public void setScissor(boolean scissor) {
        this.scissor = scissor;
    }

    public void tick() {
        if (nonStatic) {
            renderer.tick();
            if (mouseDragging) {
                int x = getMouseX();
                int y = getMouseY();
                int deltaX = x - lastMouseX;
                int deltaY = y - lastMouseY;
                if (deltaX != 0 || deltaY != 0) {
                    mouseDragged(deltaX, deltaY);
                    lastMouseX = x;
                    lastMouseY = y;
                }
            }
        }
    }

    private int getMouseX() {
        return (int) (mc.mouse.getX() * mc.getWindow().getScaledWidth() / mc.getWindow().getWidth());
    }

    private int getMouseY() {
        return (int) (mc.mouse.getY() * mc.getWindow().getScaledHeight() / mc.getWindow().getHeight());
    }

    private void mouseDragged(int deltaX, int deltaY) {
        float dX = ((float) deltaX / width) * 180.0F;
        float dY = ((float) deltaY / height) * 180.0F;
        if (freecam) {
            renderer.setRotation(renderer.getPitch() - dY, renderer.getYaw() - dX);
        } else {
            yRot = MathHelper.wrapDegrees(yRot - dX);
            xRot = MathHelper.clamp(xRot + dY, -90.0F, 90.0F);
            updateRendererCameraPos();
        }
    }

    @Override
    protected boolean onMouseClickedImpl(int mouseX, int mouseY, int mouseButton) {
        if (nonStatic) {
            for (ClickableWidget button : buttons) {
                if (button.mouseClicked(mouseX, mouseY, mouseButton)) {
                    return true;
                }
            }
            if (mouseButton == 0) {
                lastMouseX = mouseX;
                lastMouseY = mouseY;
                mouseDragging = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public void onMouseReleasedImpl(int mouseX, int mouseY, int mouseButton) {
        if (nonStatic && mouseButton == 0) {
            mouseDragging = false;
        }
    }

    @Override
    public boolean onMouseScrolledImpl(int mouseX, int mouseY, double horizontalAmount, double verticalAmount) {
        if (nonStatic) {
            if (verticalAmount != 0.0F) {
                float am = -(float) verticalAmount;
                if (freecam) {
                    am *= 2.0F * (Screen.hasShiftDown() ? 2.0F : 1.0F) * (Screen.hasControlDown() ? 2.0F : 1.0F);
                    Vector3f move = getRotationVec(renderer.getPitch(), renderer.getYaw()).mul(am);
                    renderer.setPos(renderer.getX() + move.x, renderer.getY() + move.y, renderer.getZ() + move.z);
                } else {
                    distance = MathHelper.square(Math.max(0.0F, MathHelper.sqrt(distance) + am * 0.5F));
                    updateRendererCameraPos();
                }
            }
            return true;
        }
        return false;
    }

    public void removed() {
        if (framebuffer != null) {
            framebuffer.delete();
            framebuffer = null;
        }
        renderer.close();
        SchematicPreview.removeTickable(tickAction);
    }

    private static Vector3f getRotationVec(float pitch, float yaw) {
        pitch = pitch * MathHelper.RADIANS_PER_DEGREE;
        yaw = yaw * MathHelper.RADIANS_PER_DEGREE;
        float h = MathHelper.cos(yaw);
        float i = MathHelper.sin(yaw);
        float j = MathHelper.cos(pitch);
        float k = MathHelper.sin(pitch);
        return new Vector3f(i * j, -k, h * j);
    }

    private static class Renderer {

        private final MinecraftClient mc;
        private final Quaternionf rotation = new Quaternionf();
        private final Vector3f lastRenderPos = new Vector3f();
        private final Vector3f prevPos = new Vector3f();
        private final Vector3f pos = new Vector3f(0.0F, 2.0F, 100.0F);
        private final Vector2f lastRenderRot = new Vector2f();
        private final Vector2f prevRot = new Vector2f();
        private final Vector2f rot = new Vector2f();
        private SchematicPreviewRenderer renderer;
        private int lastChunksBuilt;
        private boolean schematicNew;

        private Renderer(MinecraftClient mc) {
            this.mc = mc;
        }

        private void tick() {
            resetPosition();
        }

        private void resetPosition() {
            prevPos.set(pos);
            prevRot.set(rot);
        }

        private float getX() {
            return pos.x;
        }

        private float getY() {
            return pos.y;
        }

        private float getZ() {
            return pos.z;
        }

        private void setPos(float x, float y, float z) {
            pos.set(x, y, z);
        }

        private float getPitch() {
            return rot.x;
        }

        private float getYaw() {
            return rot.y;
        }

        private void setRotation(float pitch, float yaw) {
            rot.set(MathHelper.clamp(pitch, -90.0F, 90.0F), MathHelper.wrapDegrees(yaw));
        }

        private void newSchematic(LitematicaSchematic schematic) {
            if (renderer == null) {
                renderer = new SchematicPreviewRenderer(mc);
            }
            renderer.setup(schematic);
            schematicNew = true;
        }

        private boolean isBuildDone() {
            return renderer.isBuildDone();
        }

        private boolean isBuildingTerrainOrStart() {
            return renderer.isBuildingTerrain();
        }

        private boolean needsReRender() {
            return schematicNew || !pos.equals(lastRenderPos) || !rot.equals(lastRenderRot) || renderer.getBuiltChunksCount() != lastChunksBuilt;
        }

        private void render(Framebuffer framebuffer, float tickDelta) {
            schematicNew = false;

            if (isBuildingTerrainOrStart()) {
                return;
            }

            lastChunksBuilt = renderer.getBuiltChunksCount();

            RenderSystem.backupProjectionMatrix();
            Matrix4f projectionMatrix = new Matrix4f().perspective(
                    (float) SchematicPreviewConfigs.PREVIEW_FOV.getDoubleValue() * MathHelper.RADIANS_PER_DEGREE,
                    (float) framebuffer.viewportWidth / framebuffer.viewportHeight,
                    0.05F,
                    1024.0F
            );
            RenderSystem.setProjectionMatrix(projectionMatrix, ProjectionType.PERSPECTIVE);
            lastRenderRot.set(MathHelper.lerp(tickDelta, prevRot.x, rot.x), MathHelper.lerpAngleDegrees(tickDelta, prevRot.y, rot.y));
            Matrix4f rotationMatrix = new Matrix4f().rotation(rotation.rotationYXZ(
                    lastRenderRot.y * MathHelper.RADIANS_PER_DEGREE,
                    lastRenderRot.x * MathHelper.RADIANS_PER_DEGREE,
                    0.0F
            ).conjugate());
            Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
            modelViewStack.pushMatrix();
            modelViewStack.set(rotationMatrix);

            // Draw layers
            lastRenderPos.set(
                    MathHelper.lerp(tickDelta, prevPos.x, pos.x),
                    MathHelper.lerp(tickDelta, prevPos.y, pos.y),
                    MathHelper.lerp(tickDelta, prevPos.z, pos.z)
            );
            renderer.prepareRender(lastRenderPos.x, lastRenderPos.y, lastRenderPos.z, framebuffer);
            renderer.renderLayer(RenderLayer.getSolid());
            renderer.renderLayer(RenderLayer.getCutoutMipped());
            renderer.renderLayer(RenderLayer.getCutout());
            renderer.renderLayer(RenderLayer.getTranslucent());
            if (SchematicPreviewConfigs.RENDER_TILE.getBooleanValue()) {
                renderer.renderBlockEntities(new MatrixStack(), mc.getBufferBuilders().getEntityVertexConsumers(), tickDelta);
            }

            RenderSystem.restoreProjectionMatrix();
            modelViewStack.popMatrix();
        }

        private void close() {
            if (renderer != null) {
                renderer.close();
                renderer = null;
            }
        }

    }

}
