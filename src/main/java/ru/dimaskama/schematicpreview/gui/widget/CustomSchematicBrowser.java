package ru.dimaskama.schematicpreview.gui.widget;

import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicBrowser;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetDirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetDirectoryNavigation;
import fi.dy.masa.malilib.render.GuiContext;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import ru.dimaskama.schematicpreview.SchematicPreview;
import ru.dimaskama.schematicpreview.SchematicPreviewConfigs;

public class CustomSchematicBrowser extends WidgetSchematicBrowser implements SchematicBrowserPatch {

    private static final Identifier PREVIEW_SELECT_BUTTON_TEXTURE = SchematicPreview.id("preview_select_button");
    protected SchematicPreviewWidget sidePreviewWidget;
    protected SpriteButton previewSelectButton;
    protected int customEntryWidth;
    protected int customEntryHeight;
    protected ScreenRectangle scissor;

    public CustomSchematicBrowser(int x, int y, int width, int height, GuiSchematicBrowserBase parent, ISelectionListener<DirectoryEntry> selectionListener) {
        super(x, y, width, height, parent, selectionListener);
    }

    public static FileFilter getSchematicFileFilter() {
        return SCHEMATIC_FILTER;
    }

    @Override
    public SchematicPreviewWidget schematicpreview_getSideWidget() {
        return sidePreviewWidget;
    }

    @Override
    public void initGui() {
        super.initGui();
        if (sidePreviewWidget == null) {
            sidePreviewWidget = new SchematicPreviewWidget(SchematicPreview.PREVIEWS_CACHE, true);
        }
        addWidget(sidePreviewWidget);
        previewSelectButton = new SpriteButton(posX + 3, posY + 5, 12, 12, PREVIEW_SELECT_BUTTON_TEXTURE);
        previewSelectButton.setActionListener((button, mouse) -> {
	        SchematicPreviewConfigs.PREVIEW_TYPE.setOptionListValue(SchematicPreviewConfigs.PREVIEW_TYPE.getOptionListValue().cycle(mouse == 0));
	        reCreateListEntryWidgets();
        });
        addWidget(previewSelectButton);
    }

    @Override
    protected void updateDirectoryNavigationWidget() {
        int x = posX + 2;
        int y = posY + 4;

        directoryNavigationWidget = new WidgetDirectoryNavigation(x + 15, y, browserEntryWidth - 15, 14,
                currentDirectory, getRootDirectory(), this, iconProvider);
        browserEntriesOffsetY = directoryNavigationWidget.getHeight() + 3;
        widgetSearchBar = directoryNavigationWidget;
    }

    @Override
    protected void drawAdditionalContents(GuiContext context, int mouseX, int mouseY) {
        previewSelectButton.render(context, mouseX, mouseY, hoveredWidget == previewSelectButton);
        super.drawAdditionalContents(context, mouseX, mouseY);
    }

    @Override
    protected void reCreateListEntryWidgets() {
        int gapX = SchematicPreviewConfigs.ENTRY_GAP_X.getIntegerValue();
        int gapY = SchematicPreviewConfigs.ENTRY_GAP_Y.getIntegerValue();

        SchematicPreviewType previewType = getPreviewType();
        int columns = previewType.getColumns();
        customEntryWidth = (browserEntryWidth - gapX * (columns - 1)) / columns;
        customEntryHeight = previewType.getHeight(customEntryWidth);

        listWidgets.clear();
        maxVisibleBrowserEntries = 0;

        int numEntries = listContents.size();
        int usableHeight = browserHeight - browserPaddingY - browserEntriesOffsetY;
        int leftX = posX + 2;
        int topY = posY + 4 + browserEntriesOffsetY;
        scissor = new ScreenRectangle(leftX, topY, browserEntryWidth, usableHeight);
        int viewIndex = 0;

        for (int i = scrollBar.getValue() / columns * columns; i < numEntries; ++i) {
            int y = viewIndex / columns * (customEntryHeight + gapY);
            if (y > usableHeight) {
                break;
            }
            int x = viewIndex % columns * (customEntryWidth + gapX);
            listWidgets.add(createListEntryWidget(leftX + x, topY + y, i, ((i % columns) & 1) + ((i / columns) & 1) == 1, listContents.get(i)));
            ++maxVisibleBrowserEntries;
            ++viewIndex;
        }

        scrollBar.setMaxValue(listContents.size() - columns * (Mth.floorDiv(usableHeight, customEntryHeight) - 1));
    }

    @Override
    protected WidgetDirectoryEntry createListEntryWidget(int x, int y, int i, boolean isOdd, DirectoryEntry entry) {
        SchematicPreviewType previewType = getPreviewType();
        return new CustomDirectoryEntry(x, y, customEntryWidth, customEntryHeight, isOdd, entry, i, this, iconProvider, previewType, scissor, SchematicPreview.PREVIEWS_CACHE::getSmallWidget);
    }

    protected SchematicPreviewType getPreviewType() {
        return (SchematicPreviewType) SchematicPreviewConfigs.PREVIEW_TYPE.getOptionListValue();
    }

}
