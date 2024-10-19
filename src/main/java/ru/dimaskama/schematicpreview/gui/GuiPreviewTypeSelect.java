package ru.dimaskama.schematicpreview.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiDialogBase;
import fi.dy.masa.malilib.gui.button.ConfigButtonOptionList;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.gui.DrawContext;
import ru.dimaskama.schematicpreview.SchematicPreviewConfigs;

public class GuiPreviewTypeSelect extends GuiDialogBase {

    private final Runnable reloader;
    private ConfigButtonOptionList button;
    private boolean dirty;

    public GuiPreviewTypeSelect(GuiBase parent, int x, int y, Runnable reloader) {
        this.reloader = reloader;
        setParent(parent);
        setPosition(x, y);
        setWidthAndHeight(120, 20);
    }

    @Override
    public void initGui() {
        super.initGui();
        button = new ConfigButtonOptionList(dialogLeft, dialogTop, dialogWidth, dialogHeight, SchematicPreviewConfigs.PREVIEW_TYPE);
        button.setActionListener((b, m) -> {
            dirty = true;
            reloader.run();
        });
        addWidget(button);
    }

    @Override
    protected void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks) {
        if (getParent() != null) {
            getParent().render(drawContext, Integer.MIN_VALUE, Integer.MIN_VALUE, partialTicks);
        }
        RenderUtils.drawRect(0, 0, width, height, 0x77000000);
        button.render(mouseX, mouseY, false, drawContext);
    }

    @Override
    public boolean onMouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (!super.onMouseClicked(mouseX, mouseY, mouseButton)) {
            closeGui(true);
        }
        return true;
    }

    @Override
    public void removed() {
        if (dirty) {
            SchematicPreviewConfigs.INSTANCE.save();
        }
    }

}
