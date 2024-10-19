package ru.dimaskama.schematicpreview.gui.widget;

import fi.dy.masa.malilib.gui.interfaces.IDirectoryNavigator;
import fi.dy.masa.malilib.gui.interfaces.IFileBrowserIconProvider;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.gui.widgets.WidgetDirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.function.Function;

public class CustomDirectoryEntry extends WidgetDirectoryEntry {

    private final SchematicPreviewType previewType;
    private final ScreenRect scissor;
    private final Function<File, SchematicPreviewWidget> widgets;
    private final String name;
    @Nullable
    private String trimmedName;

    public CustomDirectoryEntry(int x, int y, int width, int height, boolean isOdd, WidgetFileBrowserBase.DirectoryEntry entry, int listIndex, IDirectoryNavigator navigator, IFileBrowserIconProvider iconProvider, SchematicPreviewType previewType, ScreenRect scissor, Function<File, SchematicPreviewWidget> widgets) {
        super(x, y, width, height, isOdd, entry, listIndex, navigator, iconProvider);
        this.previewType = previewType;
        this.scissor = scissor;
        this.widgets = widgets;
        name = getDisplayName();
    }

    @Override
    public boolean isMouseOver(int mouseX, int mouseY) {
        return super.isMouseOver(mouseX, mouseY) && scissor.contains(mouseX, mouseY);
    }

    @Override
    public void render(int mouseX, int mouseY, boolean selected, DrawContext context) {
        context.enableScissor(scissor.getLeft(), scissor.getTop(), scissor.getRight(), scissor.getBottom());

        RenderUtils.drawRect(x, y, width, height, selected || isMouseOver(mouseX, mouseY) ? 0x70FFFFFF : isOdd ? 0x20FFFFFF : 0x38FFFFFF);

        IGuiIcon icon = entry.getType() == WidgetFileBrowserBase.DirectoryEntryType.DIRECTORY
                ? iconProvider.getIconDirectory()
                : iconProvider.getIconForFile(entry.getFullPath());

        int xOffset = 2;

        if (entry.getType() == WidgetFileBrowserBase.DirectoryEntryType.FILE && previewType.hasPreview()) {

            int previewHeight = previewType.isList() ? height - 4 : height - 18;
            int previewWidth = previewType.isList() ? Math.min(width * 2 / 3, (int) (previewHeight * 1.6F)) : width - 4;
            int previewX = x + xOffset;
            int previewY = y + (previewType.isList() ? 2 : 16);

            context.enableScissor(previewX, previewY, previewX + previewWidth, previewY + previewHeight);
            SchematicPreviewWidget widget = widgets.apply(entry.getFullPath());
            widget.setScissor(true);
            widget.renderPreviewAndOverlay(context, x + xOffset, previewY, previewWidth, previewHeight);
            context.disableScissor();

            if (previewType.isList()) {
                xOffset += previewWidth + 2;
            }
        }

        if (icon != null) {
            RenderUtils.color(1f, 1f, 1f, 1f);
            bindTexture(icon.getTexture());
            icon.renderAt(x + xOffset, y + (previewType.isTile() ? 2 : height - icon.getHeight() >> 1), zLevel + 10, false, false);
            xOffset += icon.getWidth() + 2;
        }

        if (selected) {
            RenderUtils.drawOutline(x, y, width, height, 0xEEEEEEEE);
        }

        int yOffset = previewType.isTile() ? 3 : (height - fontHeight >> 1) + 1;
        {
            int textX = x + xOffset + 2;
            int textY = y + yOffset;
            int maxTextWidth = x + width - textX;
            int exceedingWidth = textRenderer.getWidth(name) - maxTextWidth;
            if (exceedingWidth > 0) {
                if (mouseX >= textX && mouseX < textX + maxTextWidth && mouseY >= textY && mouseY < textY + textRenderer.fontHeight) {
                    context.enableScissor(textX, textY, textX + maxTextWidth, textY + textRenderer.fontHeight);
                    drawString(textX - Math.round((float) (mouseX - textX) / maxTextWidth * exceedingWidth), textY, 0xFFFFFFFF, name, context);
                    context.disableScissor();
                } else {
                    if (trimmedName == null) {
                        trimmedName = textRenderer.trimToWidth(name, maxTextWidth - textRenderer.getWidth("...")) + "...";
                    }
                    drawString(textX, textY, 0xFFFFFFFF, trimmedName, context);
                }
            } else {
                drawString(textX, textY, 0xFFFFFFFF, name, context);
            }
        }

        drawSubWidgets(mouseX, mouseY, context);

        context.disableScissor();
    }

}
