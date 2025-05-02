package ru.dimaskama.schematicpreview.gui.widget;

import com.google.common.base.Suppliers;
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
import ru.dimaskama.schematicpreview.ItemIconState;
import ru.dimaskama.schematicpreview.SchematicPreview;
import ru.dimaskama.schematicpreview.SchematicPreviewCache;
import ru.dimaskama.schematicpreview.gui.GuiEntryIconEdit;

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
    private final Supplier<@Nullable File> firstSchematicSubFile = Suppliers.memoize(() -> {
        File[] files = entry.getFullPath().listFiles(CustomSchematicBrowser.getSchematicFileFilter());
        return files != null && files.length != 0 ? files[0] : null;
    });
    @Nullable
    private ItemStack iconStack;
    private ItemIconState.Pos iconStatePos;
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
        iconStack = null;
        iconStatePos = ItemIconState.Pos.DEFAULT;
        String key = getCacheKey();
        ItemIconState state = SchematicPreviewCache.ICONS.get(key);
        if (state != null) {
            if (!state.itemId().isEmpty()) {
                try {
                    Item item = Registries.ITEM.get(Identifier.of(state.itemId()));
                    if (item != Items.AIR) {
                        iconStack = item.getDefaultStack();
                    }
                } catch (Exception e) {
                    SchematicPreviewCache.ICONS.remove(key);
                    SchematicPreviewCache.markDirty();
                    SchematicPreview.LOGGER.warn("Invalid icon for schematic browser entry: {}", state.itemId());
                }
            }
            iconStatePos = state.pos();
        }
    }

    @Override
    protected boolean onMouseClickedImpl(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton == GLFW.GLFW_MOUSE_BUTTON_2) {
            if (canEditIcon(mouseX, mouseY)) {
                String key = getCacheKey();
                MinecraftClient client = MinecraftClient.getInstance();
                client.setScreen(new GuiEntryIconEdit(
                        client.currentScreen,
                        SchematicPreviewCache.ICONS.get(key),
                        state -> {
                            String itemId = state.itemId();
                            if (!itemId.isEmpty()) {
                                Optional<Item> opt = Identifier.validate(state.itemId()).result().map(Registries.ITEM::get).filter(i -> i != Items.AIR);
                                if (opt.isEmpty()) {
                                    return false;
                                }
                                itemId = Registries.ITEM.getId(opt.get()).toString();
                            }
                            SchematicPreviewCache.ICONS.put(key, new ItemIconState(itemId, state.pos()));
                            SchematicPreviewCache.markDirty();
                            updateCustomIcon();
                            return true;
                        }
                ));
            }
            return false;
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

        int centerHeight = previewType.isList() ? height - 4 : height - 18;
        int centerWidth = previewType.isList() ? Math.min(width * 2 / 3, (int) (centerHeight * 1.6F)) : width - 4;
        int centerX = x + xOffset;
        int centerY = y + (previewType.isList() ? 2 : 16);

        if (!renderIconAtCenter(context, centerX, centerY, centerWidth, centerHeight)) {
            File schematicToRender = getSchematicToRender();
            if (schematicToRender != null) {
                context.enableScissor(centerX, centerY, centerX + centerWidth, centerY + centerHeight);
                SchematicPreviewWidget widget = widgets.apply(schematicToRender);
                widget.setScissor(true);
                widget.renderPreviewAndOverlay(context, x + xOffset, centerY, centerWidth, centerHeight);
                context.disableScissor();
            }
        }
        if (previewType.isList()) {
            xOffset += centerWidth + 2;
        }

        if (renderIconAtCorner(context, x + xOffset)) {
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

    }

    @Override
    public void postRenderHovered(int mouseX, int mouseY, boolean selected, DrawContext context) {
        if (canEditIcon(mouseX, mouseY)) {
            RenderUtils.drawHoverText(mouseX, mouseY, List.of(I18n.translate("button.schematicpreview.change_directory_icon")), context);
        }
        super.postRenderHovered(mouseX, mouseY, selected, context);
    }

    @Nullable
    private File getSchematicToRender() {
        if (entry.getType() == WidgetFileBrowserBase.DirectoryEntryType.FILE) {
            if (previewType.hasPreview()) {
                return entry.getFullPath();
            }
            return null;
        }
        if (entry.getType() == WidgetFileBrowserBase.DirectoryEntryType.DIRECTORY) {
            if (previewType.hasPreview() && iconStatePos == ItemIconState.Pos.DEFAULT_WITH_SCHEMATIC) {
                return firstSchematicSubFile.get();
            }
            return null;
        }
        return null;
    }

    private boolean canEditIcon(int mouseX, int mouseY) {
        return entry.getType() == WidgetFileBrowserBase.DirectoryEntryType.DIRECTORY && iconPos != null && iconPos.contains(mouseX, mouseY);
    }

    private boolean renderIconAtCorner(DrawContext context, int iconX) {
        if (shouldRenderIconAtCenter()) {
            return false;
        }
        ItemStack customIcon = iconStack;
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
        IGuiIcon icon = getDefaultIcon();
        if (icon != null) {
            RenderUtils.color(1.0F, 1.0F, 1.0F, 1.0F);
            int iconY = y + (previewType.isTile() ? 2 : height - icon.getHeight() >> 1);
            iconPos = new ScreenRect(iconX, iconY, icon.getWidth(), icon.getHeight());
            bindTexture(icon.getTexture());
            icon.renderAt(iconX, iconY, zLevel + 10, false, false, context);
            return true;
        }
        return false;
    }

    private boolean renderIconAtCenter(DrawContext context, int centerX, int centerY, int centerWidth, int centerHeight) {
        if (!shouldRenderIconAtCenter()) {
            return false;
        }
        int side = Math.min(centerWidth, centerHeight);
        if (previewType.isList()) {
            side -= 2;
        } else {
            side = (int) (side * 0.8F);
        }
        int x = centerX + ((centerWidth - side) >> 1);
        int y = centerY + ((centerHeight - side) >> 1);
        iconPos = new ScreenRect(x, y, side, side);
        MatrixStack matrixStack = context.getMatrices();
        matrixStack.push();
        matrixStack.translate(x, y, 0.0F);
        ItemStack customIcon = iconStack;
        if (customIcon != null) {
            float scale = side / 16.0F;
            matrixStack.scale(scale, scale, 1.0F);
            context.drawItem(customIcon, 0, 0);
            matrixStack.pop();
            return true;
        }
        IGuiIcon icon = getDefaultIcon();
        if (icon != null) {
            RenderUtils.color(1.0F, 1.0F, 1.0F, 1.0F);
            bindTexture(icon.getTexture());
            float scale = (float) side / icon.getWidth();
            matrixStack.scale(scale, scale, 1.0F);
            icon.renderAt(0, 0, zLevel + 10, false, false, context);
            matrixStack.pop();
            return true;
        }
        matrixStack.pop();
        return false;
    }

    private boolean shouldRenderIconAtCenter() {
        return entry.getType() == WidgetFileBrowserBase.DirectoryEntryType.DIRECTORY
                && previewType.hasPreview()
                && iconStatePos == ItemIconState.Pos.CENTER;
    }

    private IGuiIcon getDefaultIcon() {
        return entry.getType() == WidgetFileBrowserBase.DirectoryEntryType.DIRECTORY
                ? iconProvider.getIconDirectory()
                : iconProvider.getIconForFile(entry.getFullPath());
    }

}
