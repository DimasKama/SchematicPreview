package ru.dimaskama.schematicpreview.gui.widget;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.*;
import org.lwjgl.system.MemoryStack;
import ru.dimaskama.schematicpreview.SchematicPreview;
import ru.dimaskama.schematicpreview.SchematicPreviewConfigs;
import ru.dimaskama.schematicpreview.gui.GuiSchematicPreviewFullscreen;
import ru.dimaskama.schematicpreview.render.PreviewsCache;
import ru.dimaskama.schematicpreview.render.SchematicPreviewRenderer;

import java.lang.Math;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

// Extending malilib widget, but using vanilla inside
public class SchematicPreviewWidget extends WidgetBase {

    private final Renderer renderer = new Renderer(mc);
    private final Runnable tickAction = this::tick;
    private final List<AbstractWidget> buttons;
    private final Vector3f rotOrigin = new Vector3f();
    private final PreviewsCache cache;
    private final boolean nonStatic;
    private Path schematicFile;
    private LitematicaSchematic lastSchematic;
    private RenderTarget framebuffer;
    private boolean mouseDragging;
    private int lastMouseX;
    private int lastMouseY;
    private boolean fullscreen;
    private boolean freecam;
    private float distance;
    private float yRot;
    private float xRot;

    public SchematicPreviewWidget(PreviewsCache cache, boolean nonStatic) {
        super(0, 0, 0, 0);
        this.cache = cache;
        this.nonStatic = nonStatic;
        buttons = nonStatic
                ? List.of(new OnOffButton(
                        6,
                        6,
                        Component.translatable("button.schematicpreview.fullscreen"),
                        this::toggleFullscreen,
                        SchematicPreview.id("fullscreen_on"),
                        SchematicPreview.id("fullscreen_on_focused"),
                        SchematicPreview.id("fullscreen_off"),
                        SchematicPreview.id("fullscreen_off_focused")
                ), new OnOffButton(
                        6,
                        6,
                        Component.translatable("button.schematicpreview.freecam"),
                        this::toggleFreecam,
                        SchematicPreview.id("freecam_on"),
                        SchematicPreview.id("freecam_on_focused"),
                        SchematicPreview.id("freecam_off"),
                        SchematicPreview.id("freecam_off_focused")
                ))
                : List.of();
    }

    public void setSchematic(Path schematicFile) {
        this.schematicFile = schematicFile;
    }

    public void renderPreviewAndOverlay(GuiGraphics context, int x, int y, int width, int height) {
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
                        renderPreviewAndOverlay(context, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
                    } else {
                        context.drawCenteredString(textRenderer, "Building terrain...", centerX, centerY, 0xFFBBBBBB);
                    }
                } else {
                    context.drawCenteredString(textRenderer, "Too big", centerX, centerY, 0xFFBBBBBB);
                }
            } else {
                context.drawCenteredString(textRenderer, "Preview load failed", centerX, centerY, 0xFFFF5555);
            }
        } else {
            context.drawCenteredString(textRenderer, "Loading preview...", centerX, centerY, 0xFFBBBBBB);
        }
    }

    private void resized() {
        int x = this.x + 1;
        int y = this.y + 1;
        for (AbstractWidget button : buttons) {
            button.setPosition(x, y);
            x += button.getWidth() + 2;
        }
    }

    private void renderPreviewAndOverlay(GuiGraphics context, float tickDelta) {
        if (fullscreen) {
            context.fill(0, 0, context.guiWidth(), context.guiHeight(), 0xFF000000);
        }
        boolean framebufferUpdated = prepareFramebuffer();
        if (nonStatic || framebufferUpdated || renderer.needsReRender()) {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    framebuffer.getColorTexture(),
                    0,
                    framebuffer.getDepthTexture(),
                    1.0
            );
            renderer.render(framebuffer, tickDelta);
        }
        context.guiRenderState.submitGuiElement(new BlitRenderState(
                RenderPipelines.GUI_TEXTURED,
                TextureSetup.singleTexture(framebuffer.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)),
                new Matrix3x2f(context.pose()),
                x,
                y,
                x + width,
                y + height,
                0.0F,
                1.0F,
                1.0F,
                0.0F,
                0xFFFFFFFF,
                context.scissorStack.peek()
        ));
        renderOverlay(context, tickDelta);
    }

    private boolean prepareFramebuffer() {
        double scale = mc.getWindow().getGuiScale();
        int scaledWidth = (int) (scale * width);
        int scaledHeight = (int) (scale * height);
        if (framebuffer == null) {
            framebuffer = new TextureTarget("SchematicPreview", scaledWidth, scaledHeight, true);
            return true;
        }
        if (framebuffer.width != scaledWidth || framebuffer.height != scaledHeight) {
            framebuffer.resize(scaledWidth, scaledHeight);
            return true;
        }
        return false;
    }

    private void renderOverlay(GuiGraphics context, float tickDelta) {
        int mouseX = getMouseX();
        int mouseY = getMouseY();
        for (AbstractWidget button : buttons) {
            button.render(context, mouseX, mouseY, tickDelta);
        }
    }

    private void toggleFullscreen(boolean fullscreen) {
        if (fullscreen) {
            mc.setScreen(new GuiSchematicPreviewFullscreen(mc.screen, this));
        } else {
            if (mc.screen != null) {
                mc.screen.onClose();
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
        distance = Mth.sqrt(Mth.lengthSquared(size.getX() * 0.5F, size.getY() * 0.7F, size.getZ() * 0.5F)) * 2.0F;
        updateRendererCameraPos();
        renderer.resetPosition();
    }

    private void updateRendererCameraPos() {
        renderer.setRotation(-xRot, yRot);
        Vector3f offset = getRotationVec(xRot, 180.0F + yRot).mul(-distance);
        renderer.setPos(rotOrigin.x + offset.x, rotOrigin.y + offset.y, rotOrigin.z + offset.z);
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
        return (int) (mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth());
    }

    private int getMouseY() {
        return (int) (mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight());
    }

    private void mouseDragged(int deltaX, int deltaY) {
        float dX = ((float) deltaX / width) * 180.0F;
        float dY = ((float) deltaY / height) * 180.0F;
        if (freecam) {
            renderer.setRotation(renderer.getPitch() - dY, renderer.getYaw() - dX);
        } else {
            yRot = Mth.wrapDegrees(yRot - dX);
            xRot = Mth.clamp(xRot + dY, -90.0F, 90.0F);
            updateRendererCameraPos();
        }
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick) {
        if (nonStatic) {
            for (AbstractWidget button : buttons) {
                if (button.mouseClicked(click, doubleClick)) {
                    return true;
                }
            }
            if (click.button() == 0) {
                lastMouseX = (int) click.x();
                lastMouseY = (int) click.y();
                mouseDragging = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public void onMouseReleasedImpl(MouseButtonEvent click) {
        if (nonStatic && click.button() == 0) {
            mouseDragging = false;
        }
    }

    @Override
    public boolean onMouseScrolledImpl(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (nonStatic) {
            if (verticalAmount != 0.0F) {
                float am = -(float) verticalAmount;
                if (freecam) {
                    am *= 2.0F * (Minecraft.getInstance().hasShiftDown() ? 2.0F : 1.0F) * (Minecraft.getInstance().hasControlDown() ? 2.0F : 1.0F);
                    Vector3f move = getRotationVec(renderer.getPitch(), renderer.getYaw()).mul(am);
                    renderer.setPos(renderer.getX() + move.x, renderer.getY() + move.y, renderer.getZ() + move.z);
                } else {
                    distance = Mth.square(Math.max(0.0F, Mth.sqrt(distance) + am * 0.5F));
                    updateRendererCameraPos();
                }
            }
            return true;
        }
        return false;
    }

    public void removed() {
        if (framebuffer != null) {
            framebuffer.destroyBuffers();
            framebuffer = null;
        }
        renderer.close();
        SchematicPreview.removeTickable(tickAction);
    }

    private static Vector3f getRotationVec(float pitch, float yaw) {
        pitch = pitch * Mth.DEG_TO_RAD;
        yaw = yaw * Mth.DEG_TO_RAD;
        float h = Mth.cos(yaw);
        float i = Mth.sin(yaw);
        float j = Mth.cos(pitch);
        float k = Mth.sin(pitch);
        return new Vector3f(i * j, -k, h * j);
    }

    private static class Renderer {

        private final Minecraft mc;
        private final Quaternionf rotation = new Quaternionf();
        private final Vector3f lastRenderPos = new Vector3f();
        private final Vector3f prevPos = new Vector3f();
        private final Vector3f pos = new Vector3f(0.0F, 2.0F, 100.0F);
        private final Vector2f lastRenderRot = new Vector2f();
        private final Vector2f prevRot = new Vector2f();
        private final Vector2f rot = new Vector2f();
        private final CameraRenderState cameraRenderState = new CameraRenderState();
        private SchematicPreviewRenderer renderer;
        private int lastChunksBuilt;
        private boolean schematicNew;
        @Nullable
        private PerspectiveProjectionMatrixBuffer projectionMatrix;
        @Nullable
        private GpuBuffer globalUniform;

        private Renderer(Minecraft mc) {
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
            rot.set(Mth.clamp(pitch, -90.0F, 90.0F), Mth.wrapDegrees(yaw));
        }

        private void newSchematic(LitematicaSchematic schematic) {
            if (renderer == null) {
                renderer = new SchematicPreviewRenderer(mc);
            }
            renderer.setup(schematic);
            schematicNew = true;
        }

        private boolean isBuildingTerrainOrStart() {
            return renderer.isBuildingTerrain();
        }

        private boolean needsReRender() {
            return schematicNew || !pos.equals(lastRenderPos) || !rot.equals(lastRenderRot) || renderer.getBuiltChunksCount() != lastChunksBuilt;
        }

        private void render(RenderTarget framebuffer, float tickDelta) {
            schematicNew = false;

            if (isBuildingTerrainOrStart()) {
                return;
            }

            lastChunksBuilt = renderer.getBuiltChunksCount();
            lastRenderRot.set(Mth.lerp(tickDelta, prevRot.x, rot.x), Mth.rotLerp(tickDelta, prevRot.y, rot.y));
            lastRenderPos.set(
                    Mth.lerp(tickDelta, prevPos.x, pos.x),
                    Mth.lerp(tickDelta, prevPos.y, pos.y),
                    Mth.lerp(tickDelta, prevPos.z, pos.z)
            );

            Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
            modelViewStack.pushMatrix();
            modelViewStack.set(new Matrix4f().rotation(rotation.rotationYXZ(
                    lastRenderRot.y * Mth.DEG_TO_RAD,
                    lastRenderRot.x * Mth.DEG_TO_RAD,
                    0.0F
            ).conjugate()));
            RenderSystem.backupProjectionMatrix();
            if (projectionMatrix == null) {
                projectionMatrix = new PerspectiveProjectionMatrixBuffer("SchematicPreview");
            }
            RenderSystem.setProjectionMatrix(projectionMatrix.getBuffer(new Matrix4f().perspective(
                    (float) SchematicPreviewConfigs.PREVIEW_FOV.getDoubleValue() * Mth.DEG_TO_RAD,
                    (float) framebuffer.width / framebuffer.height,
                    0.05F,
                    4096.0F
            )), ProjectionType.PERSPECTIVE);
            GpuBuffer previousGlobalUniform = RenderSystem.getGlobalSettingsUniform();
            if (globalUniform == null) {
                globalUniform = RenderSystem.getDevice().createBuffer(() -> "SchematicPreview Global Settings UBO", 136, GlobalSettingsUniform.UBO_SIZE);
            }
            try (MemoryStack memoryStack = MemoryStack.stackPush()) {
                int floorX = Mth.floor(lastRenderPos.x);
                int floorY = Mth.floor(lastRenderPos.y);
                int floorZ = Mth.floor(lastRenderPos.z);
                ByteBuffer byteBuffer = Std140Builder.onStack(memoryStack, GlobalSettingsUniform.UBO_SIZE)
                        .putIVec3(floorX, floorY, floorZ)
                        .putVec3(floorX - lastRenderPos.x, floorY - lastRenderPos.y, floorZ - lastRenderPos.z)
                        .putVec2(Minecraft.getInstance().getWindow().getWidth(), Minecraft.getInstance().getWindow().getHeight())
                        .putFloat(1.0F)
                        .putFloat(0.0F)
                        .putInt(0)
                        .putInt(0)
                        .get();
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(globalUniform.slice(), byteBuffer);
            }
            RenderSystem.setGlobalSettingsUniform(globalUniform);

            // Draw layers
            cameraRenderState.initialized = true;
            cameraRenderState.orientation = rotation;
            cameraRenderState.pos = new Vec3(lastRenderPos.x, lastRenderPos.y, lastRenderPos.z);
            cameraRenderState.entityPos = cameraRenderState.pos;
            cameraRenderState.blockPos = BlockPos.containing(cameraRenderState.pos);
            mc.gameRenderer.getLighting().setupFor(Lighting.Entry.LEVEL);
            renderer.prepareRender(cameraRenderState, framebuffer);
            renderer.renderBlocks();
            if (SchematicPreviewConfigs.RENDER_TILE.getBooleanValue()) {
                renderer.renderBlockEntities(new PoseStack(), tickDelta);
            }

            RenderSystem.setGlobalSettingsUniform(previousGlobalUniform);
            RenderSystem.restoreProjectionMatrix();
            modelViewStack.popMatrix();
        }

        private void close() {
            if (renderer != null) {
                renderer.close();
                renderer = null;
            }
            if (projectionMatrix != null) {
                projectionMatrix.close();
                projectionMatrix = null;
            }
            if (globalUniform != null) {
                globalUniform.close();
                globalUniform = null;
            }
        }

    }

}
