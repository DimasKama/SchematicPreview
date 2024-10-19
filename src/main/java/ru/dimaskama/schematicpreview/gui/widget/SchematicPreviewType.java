package ru.dimaskama.schematicpreview.gui.widget;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import ru.dimaskama.schematicpreview.SchematicPreviewConfigs;

import java.util.Locale;
import java.util.function.IntUnaryOperator;

public enum SchematicPreviewType implements IConfigOptionListEntry {

    LIST(1, w -> SchematicPreviewConfigs.LIST_ENTRY_HEIGHT.getIntegerValue()),
    LIST_PREVIEW(1, w -> SchematicPreviewConfigs.LIST_PREVIEW_ENTRY_HEIGHT.getIntegerValue()),
    TILE_5(5, SchematicPreviewType::tileWidthToHeight),
    TILE_4(4, SchematicPreviewType::tileWidthToHeight),
    TILE_3(3, SchematicPreviewType::tileWidthToHeight);

    private final int columns;
    private final IntUnaryOperator widthToHeight;

    SchematicPreviewType(int columns, IntUnaryOperator widthToHeight) {
        this.columns = columns;
        this.widthToHeight = widthToHeight;
    }

    public int getColumns() {
        return columns;
    }

    public int getHeight(int width) {
        return widthToHeight.applyAsInt(width);
    }

    public boolean isList() {
        return this == LIST || this == LIST_PREVIEW;
    }

    public boolean isTile() {
        return this == TILE_5 || this == TILE_4 || this == TILE_3;
    }

    public boolean hasPreview() {
        return this == LIST_PREVIEW || isTile();
    }

    @Override
    public String getStringValue() {
        return toString().toLowerCase(Locale.ROOT);
    }

    @Override
    public String getDisplayName() {
        return toString();
    }

    @Override
    public IConfigOptionListEntry cycle(boolean forward) {
        return values()[(ordinal() + (forward ? 1 : -1)) % values().length];
    }

    @Override
    public IConfigOptionListEntry fromString(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return LIST;
        }
    }

    private static int tileWidthToHeight(int width) {
        return (int) (width * SchematicPreviewConfigs.TILE_HEIGHT_RATIO.getDoubleValue());
    }

}
