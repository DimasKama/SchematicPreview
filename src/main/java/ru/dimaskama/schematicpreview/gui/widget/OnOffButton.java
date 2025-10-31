package ru.dimaskama.schematicpreview.gui.widget;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class OnOffButton extends ClickableWidget {

    private final Text name;
    private final BooleanConsumer listener;
    private final Identifier onSprite;
    private final Identifier onSpriteFocused;
    private final Identifier offSprite;
    private final Identifier offSpriteFocused;
    private boolean on;

    public OnOffButton(int width, int height, Text name, BooleanConsumer listener, Identifier onSprite, Identifier onSpriteFocused, Identifier offSprite, Identifier offSpriteFocused) {
        super(0, 0, width, height, ScreenTexts.EMPTY);
        this.name = name;
        this.listener = listener;
        this.onSprite = onSprite;
        this.onSpriteFocused = onSpriteFocused;
        this.offSprite = offSprite;
        this.offSpriteFocused = offSpriteFocused;
        updateTooltip();
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawGuiTexture(
                RenderPipelines.GUI_TEXTURED,
                on ? (isHovered() ? offSpriteFocused : offSprite) : (isHovered() ? onSpriteFocused : onSprite),
                getX(), getY(),
                getWidth(), getHeight()
        );
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }

    @Override
    public void onClick(Click click, boolean doubled) {
        on = !on;
        listener.accept(on);
        updateTooltip();
    }

    private void updateTooltip() {
        setTooltip(Tooltip.of(Text.translatable(on ? "button.schematicpreview.disable" : "button.schematicpreview.enable", name)));
    }

}
