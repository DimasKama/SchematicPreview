package ru.dimaskama.schematicpreview.gui.widget;

import com.google.common.base.Suppliers;
import fi.dy.masa.malilib.gui.GuiTextInputFeedback;
import fi.dy.masa.malilib.gui.interfaces.IDirectoryNavigator;
import fi.dy.masa.malilib.gui.interfaces.IFileBrowserIconProvider;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.gui.widgets.WidgetDirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import ru.dimaskama.schematicpreview.SchematicPreview;
import ru.dimaskama.schematicpreview.SchematicPreviewCache;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public class CustomDirectoryEntry extends WidgetDirectoryEntry {

    private final SchematicPreviewType previewType;
    private final ScreenRect scissor;
    private final Function<File, SchematicPreviewWidget> widgets;
    private final String name;
    private Supplier<@Nullable ItemStack> iconSupplier;
    @Nullable
    private String trimmedName;
    private ScreenRect iconPos;

    public CustomDirectoryEntry(int x, int y, int width, int height, boolean isOdd, WidgetFileBrowserBase.DirectoryEntry entry, int listIndex, IDirectoryNavigator navigator, IFileBrowserIconProvider iconProvider, SchematicPreviewType previewType, ScreenRect scissor, Function<File, SchematicPreviewWidget> widgets) {
        super(x, y, width, height, isOdd, entry, listIndex, navigator, iconProvider);
        this.previewType = previewType;
        this.scissor = scissor;
        this.widgets = widgets;
        name = getDisplayName();
        updateCustomIcon();
    }

    private String getCacheKey() {
        return entry.getFullPath().toPath().toString().replace('\\', '/');
    }

    private void updateCustomIcon() {
        iconSupplier = Suppliers.memoize(() -> {
            String key = getCacheKey();
            String itemId = SchematicPreviewCache.ICONS.get(key);
            if (itemId != null) {
                try {
                    Item item = Registries.ITEM.get(Identifier.of(itemId));
                    if (item != Items.AIR) {
                        return item.getDefaultStack();
                    }
                } catch (Exception e) {
                    SchematicPreviewCache.ICONS.remove(key);
                    SchematicPreview.LOGGER.warn("Invalid icon for schematic browser entry: {}", itemId);
                }
            }
            return null;
        });
    }

    @Override
    protected boolean onMouseClickedImpl(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton == GLFW.GLFW_MOUSE_BUTTON_2 && isOnIcon(mouseX, mouseY)) {
            String key = getCacheKey();
            MinecraftClient client = MinecraftClient.getInstance();
            client.setScreen(new GuiTextInputFeedback(64, "gui.schematicpreview.change_directory_icon", SchematicPreviewCache.ICONS.get(key), client.currentScreen, str -> {
                updateCustomIcon();
                if (str.isEmpty()) {
                    SchematicPreviewCache.ICONS.remove(key);
                    SchematicPreviewCache.markDirty();
                    return true;
                }
                Optional<Item> opt = Identifier.validate(str).result().map(Registries.ITEM::get).filter(i -> i != Items.AIR);
                if (opt.isPresent()) {
                    SchematicPreviewCache.ICONS.put(key, Registries.ITEM.getId(opt.get()).toString());
                    SchematicPreviewCache.markDirty();
                    return true;
                }
                return false;
            }));
            return true;
        }
        return super.onMouseClickedImpl(mouseX, mouseY, mouseButton);
    }

    @Override
    public boolean isMouseOver(int mouseX, int mouseY) {
        return super.isMouseOver(mouseX, mouseY) && scissor.contains(mouseX, mouseY);
    }

    @Override
    public void render(int mouseX, int mouseY, boolean selected, DrawContext context) {
        context.enableScissor(scissor.getLeft(), scissor.getTop(), scissor.getRight(), scissor.getBottom());

        RenderUtils.drawRect(x, y, width, height, selected || isMouseOver(mouseX, mouseY) ? 0x70FFFFFF : isOdd ? 0x20FFFFFF : 0x38FFFFFF);

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

        if (renderIcon(context, x + xOffset)) {
            xOffset += iconPos.width() + 2;
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

        if (isOnIcon(mouseX, mouseY)) {
            RenderUtils.drawHoverText(mouseX, mouseY, List.of(I18n.translate("button.schematicpreview.change_directory_icon")), context);
        }
    }

    private boolean isOnIcon(int mouseX, int mouseY) {
        return iconPos != null && iconPos.contains(mouseX, mouseY);
    }

    private boolean renderIcon(DrawContext context, int iconX) {
        ItemStack customIcon = iconSupplier.get();
        if (customIcon != null) {
            int iconY = y + (previewType.isTile() ? 2 : height - 12 >> 1);
            iconPos = new ScreenRect(iconX, iconY, 12, 12);
            MatrixStack matrixStack = context.getMatrices();
            matrixStack.push();
            matrixStack.translate(iconX, iconY, 0.0F);
            matrixStack.scale(0.75F, 0.75F, 1.0F); // <- 12/16
            context.drawItem(customIcon, 0, 0);
            matrixStack.pop();
            return true;
        }
        IGuiIcon icon = entry.getType() == WidgetFileBrowserBase.DirectoryEntryType.DIRECTORY
                ? iconProvider.getIconDirectory()
                : iconProvider.getIconForFile(entry.getFullPath());
        if (icon != null) {
            RenderUtils.color(1.0F, 1.0F, 1.0F, 1.0F);
            int iconY = y + (previewType.isTile() ? 2 : height - icon.getHeight() >> 1);
            iconPos = new ScreenRect(iconX, iconY, icon.getWidth(), icon.getHeight());
            bindTexture(icon.getTexture());
            icon.renderAt(iconX, iconY, zLevel + 10, false, false);
            return true;
        }
        return false;
    }

}
