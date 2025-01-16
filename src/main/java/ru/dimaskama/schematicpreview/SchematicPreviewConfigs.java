package ru.dimaskama.schematicpreview;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.*;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;
import net.minecraft.util.math.MathHelper;
import ru.dimaskama.schematicpreview.gui.widget.SchematicPreviewType;

import java.io.File;
import java.util.List;

public class SchematicPreviewConfigs implements IConfigHandler {

    private static final String CONFIG_FILE_NAME = SchematicPreview.MOD_ID + ".json";
    public static final SchematicPreviewConfigs INSTANCE = new SchematicPreviewConfigs();

    public static final ConfigBoolean ENABLED = new ConfigBoolean("schematicpreviewEnabled", true, "Toggle mod enabled");
    public static final ConfigHotkey CONFIG_MENU_HOTKEY = new ConfigHotkey("schematicpreviewConfigMenuHotkey", "RIGHT_SHIFT,F8", "Hotkey to open this menu");

    public static final ConfigOptionList PREVIEW_TYPE = new ConfigOptionList("schematicpreviewType", SchematicPreviewType.LIST, "Selected browser entry type");
    public static final ConfigInteger ENTRY_GAP_X = new ConfigInteger("schematicpreviewEntryGapX", 2, 0, 10, "Browser entry X axis gap");
    public static final ConfigInteger ENTRY_GAP_Y = new ConfigInteger("schematicpreviewEntryGapY", 2, 0, 10, "Browser entry Y axis gap");
    public static final ConfigInteger LIST_ENTRY_HEIGHT = new ConfigInteger("schematicpreviewListEntryHeight", 15, 10, 80, "Height of entry for browser type \"List\"");
    public static final ConfigInteger LIST_PREVIEW_ENTRY_HEIGHT = new ConfigInteger("schematicpreviewListPreviewEntryHeight", 35, 15, 80, "Height of entry for browser type \"List Preview\"");
    public static final ConfigDouble TILE_HEIGHT_RATIO = new ConfigDouble("schematicpreviewTileHeightRatio", 1.0, 0.3, 5.0, "Width/Height ratio of entries for Tile browser types");

    public static final ConfigInteger PREVIEW_MAX_VOLUME = new ConfigInteger("schematicpreviewMaxVolume", 125000, 0, Integer.MAX_VALUE, "Max. schem volume in list preview");
    public static final ConfigBoolean RENDER_TILE = new ConfigBoolean("schematicpreviewRenderTile", true, "Toggle render tile entities");
    public static final ConfigDouble PREVIEW_FOV = new ConfigDouble("schematicpreviewFov", 50.0, 30.0, 100.0, "3D preview FOV");
    public static final ConfigDouble PREVIEW_ROTATION_Y = new ConfigDouble("schematicpreviewRotationY", -45.0, -180.0, 180.0 - MathHelper.EPSILON, "3D preview Y Rotation");
    public static final ConfigDouble PREVIEW_ROTATION_X = new ConfigDouble("schematicpreviewRotationX", 30.0, -90.0, 90.0 - MathHelper.EPSILON, "3D preview X Rotation");

    public static final List<IConfigBase> GENERIC = List.of(
            ENABLED,
            CONFIG_MENU_HOTKEY
    );
    public static final List<IConfigBase> MENU = List.of(
            PREVIEW_TYPE,
            ENTRY_GAP_X,
            ENTRY_GAP_Y,
            LIST_ENTRY_HEIGHT,
            LIST_PREVIEW_ENTRY_HEIGHT,
            TILE_HEIGHT_RATIO
    );
    public static final List<IConfigBase> PREVIEW = List.of(
            PREVIEW_MAX_VOLUME,
            RENDER_TILE,
            PREVIEW_FOV,
            PREVIEW_ROTATION_Y,
            PREVIEW_ROTATION_X
    );
    public static final List<ConfigHotkey> HOTKEYS = List.of(
            CONFIG_MENU_HOTKEY
    );

    private SchematicPreviewConfigs() {}

    @Override
    public void load() {
        File configFile = new File(FileUtils.getConfigDirectory(), CONFIG_FILE_NAME);
        if (configFile.exists() && configFile.isFile() && configFile.canRead()) {
            JsonElement json = JsonUtils.parseJsonFile(configFile);
            if (json != null && json.isJsonObject()) {
                ConfigUtils.readConfigBase(json.getAsJsonObject(), "Generic", GENERIC);
                ConfigUtils.readConfigBase(json.getAsJsonObject(), "Menu", MENU);
                ConfigUtils.readConfigBase(json.getAsJsonObject(), "Preview", PREVIEW);
            }
        }
    }

    @Override
    public void save() {
        File dir = FileUtils.getConfigDirectory();
        if ((dir.exists() && dir.isDirectory()) || dir.mkdirs()) {
            JsonObject json = new JsonObject();
            ConfigUtils.writeConfigBase(json, "Generic", GENERIC);
            ConfigUtils.writeConfigBase(json, "Menu", MENU);
            ConfigUtils.writeConfigBase(json, "Preview", PREVIEW);
            JsonUtils.writeJsonToFile(json, new File(dir, CONFIG_FILE_NAME));
        }
    }

}
