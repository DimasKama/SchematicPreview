package ru.dimaskama.schematicpreview.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.gui.screens.Screen;
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
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        preview.renderPreviewAndOverlay(drawContext, 0, 0, width, height);
    }

    @Override
    public void onClose() {
        mc.setScreen(getParent());
    }

    @Override
    public void removed() {
        preview.setFullScreen(false);
    }

}
