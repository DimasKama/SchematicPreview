package ru.dimaskama.schematicpreview.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import ru.dimaskama.schematicpreview.gui.widget.SchematicPreviewWidget;

public class GuiSchematicPreviewFullscreen extends GuiBase {

    private final SchematicPreviewWidget preview;

    public GuiSchematicPreviewFullscreen(Screen parent, SchematicPreviewWidget preview) {
        setParent(parent);
        this.preview = preview;
    }

    @Override
    public void initGui() {
        super.initGui();
        addWidget(preview);
        preview.setFullScreen(true);
    }

    @Override
    protected void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        drawContext.getMatrices().push();
        drawContext.getMatrices().translate(0.0F, 0.0F, 1000.0F);
        preview.renderPreviewAndOverlay(drawContext, 0, 0, width, height);
        drawContext.getMatrices().pop();
    }

    @Override
    public void close() {
        mc.setScreen(getParent());
    }

    @Override
    public void removed() {
        preview.setFullScreen(false);
    }

}
