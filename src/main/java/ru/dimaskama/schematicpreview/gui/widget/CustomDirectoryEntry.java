package ru.dimaskama.schematicpreview.gui.widget;

import com.google.common.base.Suppliers;
import fi.dy.masa.malilib.gui.interfaces.IDirectoryNavigator;
import fi.dy.masa.malilib.gui.interfaces.IFileBrowserIconProvider;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.gui.widgets.WidgetDirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;
import ru.dimaskama.schematicpreview.ItemIconState;
import ru.dimaskama.schematicpreview.SchematicPreview;
import ru.dimaskama.schematicpreview.SchematicPreviewCache;
import ru.dimaskama.schematicpreview.gui.GuiEntryIconEdit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class CustomDirectoryEntry extends WidgetDirectoryEntry {

    private final SchematicPreviewType previewType;
    private final ScreenRectangle scissor;
    private final Function<Path, SchematicPreviewWidget> widgets;
    private final String name;
    private final Supplier<@Nullable Path> firstSchematicSubFile = Suppliers.memoize(() -> {
        try {
            for (Path file : Files.newDirectoryStream(entry.getFullPath(), CustomSchematicBrowser.getSchematicFileFilter())) {
                return file;
            }
        } catch (Exception e) {
            SchematicPreview.LOGGER.error("Failed to list files in {}", entry.getFullPath(), e);
        }
        return null;
    });
    @Nullable
    private ItemStack iconStack;
    private ItemIconState.Pos iconStatePos;
    @Nullable
    private String trimmedName;
    private ScreenRectangle iconPos;

    public CustomDirectoryEntry(int x, int y, int width, int height, boolean isOdd, WidgetFileBrowserBase.DirectoryEntry entry, int listIndex, IDirectoryNavigator navigator, IFileBrowserIconProvider iconProvider, SchematicPreviewType previewType, ScreenRectangle scissor, Function<Path, SchematicPreviewWidget> widgets) {
        super(x, y, width, height, isOdd, entry, listIndex, navigator, iconProvider);
        this.previewType = previewType;
        this.scissor = scissor;
        this.widgets = widgets;
        name = getDisplayName();
        updateCustomIcon();
    }

    private String getCacheKey() {
        return entry.getFullPath().toString().replace('\\', '/');
    }

    private void updateCustomIcon() {
        iconStack = null;
        iconStatePos = ItemIconState.Pos.DEFAULT;
        String key = getCacheKey();
        ItemIconState state = SchematicPreviewCache.ICONS.get(key);
        if (state != null) {
            if (!state.itemId().isEmpty()) {
                try {
                    Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(state.itemId()));
                    if (item != Items.AIR) {
                        iconStack = item.getDefaultInstance();
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
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_2) {
            if (canEditIcon((int) click.x(), (int) click.y())) {
                String key = getCacheKey();
                Minecraft client = Minecraft.getInstance();
                client.setScreen(new GuiEntryIconEdit(
                        client.screen,
                        SchematicPreviewCache.ICONS.get(key),
                        state -> {
                            String itemId = state.itemId();
                            if (!itemId.isEmpty()) {
                                Optional<Item> opt = Identifier.read(state.itemId()).result().map(BuiltInRegistries.ITEM::getValue).filter(i -> i != Items.AIR);
                                if (opt.isEmpty()) {
                                    return false;
                                }
                                itemId = BuiltInRegistries.ITEM.getKey(opt.get()).toString();
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
        return super.onMouseClickedImpl(click, doubleClick);
    }

    @Override
    public boolean isMouseOver(int mouseX, int mouseY) {
        return super.isMouseOver(mouseX, mouseY) && scissor.containsPoint(mouseX, mouseY);
    }

    @Override
    public void render(GuiContext context, int mouseX, int mouseY, boolean selected) {
        context.enableScissor(scissor.left(), scissor.top(), scissor.right(), scissor.bottom());

        RenderUtils.drawRect(context, x, y, width, height, selected || isMouseOver(mouseX, mouseY) ? 0x70FFFFFF : isOdd ? 0x20FFFFFF : 0x38FFFFFF);

        int xOffset = 2;

        int centerHeight = previewType.isList() ? height - 4 : height - 18;
        int centerWidth = previewType.isList() ? Math.min(width * 2 / 3, (int) (centerHeight * 1.6F)) : width - 4;
        int centerX = x + xOffset;
        int centerY = y + (previewType.isList() ? 2 : 16);

        if (!renderIconAtCenter(context, centerX, centerY, centerWidth, centerHeight)) {
            Path schematicToRender = getSchematicToRender();
            if (schematicToRender != null) {
                context.enableScissor(centerX, centerY, centerX + centerWidth, centerY + centerHeight);
                SchematicPreviewWidget widget = widgets.apply(schematicToRender);
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
            RenderUtils.drawOutline(context, x, y, width, height, 0xEEEEEEEE);
        }

        int yOffset = previewType.isTile() ? 3 : (height - fontHeight >> 1) + 1;
        {
            int textX = x + xOffset + 2;
            int textY = y + yOffset;
            int maxTextWidth = x + width - textX;
            int exceedingWidth = textRenderer.width(name) - maxTextWidth;
            if (exceedingWidth > 0) {
                if (mouseX >= textX && mouseX < textX + maxTextWidth && mouseY >= textY && mouseY < textY + textRenderer.lineHeight) {
                    context.enableScissor(textX, textY, textX + maxTextWidth, textY + textRenderer.lineHeight);
                    drawString(context, textX - Math.round((float) (mouseX - textX) / maxTextWidth * exceedingWidth), textY, 0xFFFFFFFF, name);
                    context.disableScissor();
                } else {
                    if (trimmedName == null) {
                        trimmedName = textRenderer.plainSubstrByWidth(name, maxTextWidth - textRenderer.width("...")) + "...";
                    }
                    drawString(context, textX, textY, 0xFFFFFFFF, trimmedName);
                }
            } else {
                drawString(context, textX, textY, 0xFFFFFFFF, name);
            }
        }

        drawSubWidgets(context, mouseX, mouseY);

        context.disableScissor();

    }

    @Override
    public void postRenderHovered(GuiContext drawContext, int mouseX, int mouseY, boolean selected) {
        if (canEditIcon(mouseX, mouseY)) {
            RenderUtils.drawHoverText(drawContext, mouseX, mouseY, List.of(I18n.get("button.schematicpreview.change_directory_icon")));
        }
        super.postRenderHovered(drawContext, mouseX, mouseY, selected);
    }

    @Nullable
    private Path getSchematicToRender() {
        if (entry.type() == WidgetFileBrowserBase.DirectoryEntryType.FILE) {
            if (previewType.hasPreview()) {
                return entry.getFullPath();
            }
            return null;
        }
        if (entry.type() == WidgetFileBrowserBase.DirectoryEntryType.DIRECTORY) {
            if (previewType.hasPreview() && iconStatePos == ItemIconState.Pos.DEFAULT_WITH_SCHEMATIC) {
                return firstSchematicSubFile.get();
            }
            return null;
        }
        return null;
    }

    private boolean canEditIcon(int mouseX, int mouseY) {
        return entry.type() == WidgetFileBrowserBase.DirectoryEntryType.DIRECTORY && iconPos != null && iconPos.containsPoint(mouseX, mouseY);
    }

    private boolean renderIconAtCorner(GuiContext context, int iconX) {
        if (shouldRenderIconAtCenter()) {
            return false;
        }
        ItemStack customIcon = iconStack;
        if (customIcon != null) {
            int iconY = y + (previewType.isTile() ? 2 : height - 12 >> 1);
            iconPos = new ScreenRectangle(iconX, iconY, 12, 12);
            Matrix3x2fStack matrixStack = context.pose();
            matrixStack.pushMatrix();
            matrixStack.translate(iconX, iconY);
            matrixStack.scale(0.75F, 0.75F); // <- 12/16
            context.renderItem(customIcon, 0, 0);
            matrixStack.popMatrix();
            return true;
        }
        IGuiIcon icon = getDefaultIcon();
        if (icon != null) {
            int iconY = y + (previewType.isTile() ? 2 : height - icon.getHeight() >> 1);
            iconPos = new ScreenRectangle(iconX, iconY, icon.getWidth(), icon.getHeight());
            icon.renderAt(context, iconX, iconY, zLevel + 10, false, false);
            return true;
        }
        return false;
    }

    private boolean renderIconAtCenter(GuiContext context, int centerX, int centerY, int centerWidth, int centerHeight) {
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
        iconPos = new ScreenRectangle(x, y, side, side);
        Matrix3x2fStack matrixStack = context.pose();
        matrixStack.pushMatrix();
        matrixStack.translate(x, y);
        ItemStack customIcon = iconStack;
        if (customIcon != null) {
            float scale = side / 16.0F;
            matrixStack.scale(scale, scale);
            context.renderItem(customIcon, 0, 0);
            matrixStack.popMatrix();
            return true;
        }
        IGuiIcon icon = getDefaultIcon();
        if (icon != null) {
            float scale = (float) side / icon.getWidth();
            matrixStack.scale(scale, scale);
            icon.renderAt(context, 0, 0, zLevel + 10, false, false);
            matrixStack.popMatrix();
            return true;
        }
        matrixStack.popMatrix();
        return false;
    }

    private boolean shouldRenderIconAtCenter() {
        return entry.type() == WidgetFileBrowserBase.DirectoryEntryType.DIRECTORY
                && previewType.hasPreview()
                && iconStatePos == ItemIconState.Pos.CENTER;
    }

    private IGuiIcon getDefaultIcon() {
        return entry.type() == WidgetFileBrowserBase.DirectoryEntryType.DIRECTORY
                ? iconProvider.getIconDirectory()
                : iconProvider.getIconForFile(entry.getFullPath());
    }

}
