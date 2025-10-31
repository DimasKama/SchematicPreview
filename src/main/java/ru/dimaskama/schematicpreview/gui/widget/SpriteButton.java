package ru.dimaskama.schematicpreview.gui.widget;

import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

public class SpriteButton extends ButtonGeneric {

    private final Identifier sprite;

    public SpriteButton(int x, int y, int width, int height, Identifier sprite) {
        super(x, y, width, height, "");
        this.sprite = sprite;
        renderDefaultBackground = true;
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, boolean selected) {
        super.render(drawContext, mouseX, mouseY, selected);
        if (hovered) {
            RenderUtils.drawOutline(drawContext, x - 1, y - 1, width + 2, height + 2, 0xFFFFFFFF);
        }
    }

    @Override
    protected Identifier getTexture(boolean isMouseOver) {
        return sprite;
    }

}
