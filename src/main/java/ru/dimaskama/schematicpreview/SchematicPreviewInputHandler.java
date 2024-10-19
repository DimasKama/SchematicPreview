package ru.dimaskama.schematicpreview;

import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;

public class SchematicPreviewInputHandler implements IKeybindProvider {

    private static final SchematicPreviewInputHandler INSTANCE = new SchematicPreviewInputHandler();

    private SchematicPreviewInputHandler() {}

    public static SchematicPreviewInputHandler getInstance() {
        return INSTANCE;
    }

    @Override
    public void addKeysToMap(IKeybindManager manager) {
        SchematicPreviewConfigs.HOTKEYS.stream().map(ConfigHotkey::getKeybind).forEach(manager::addKeybindToMap);
    }

    @Override
    public void addHotkeys(IKeybindManager manager) {
        manager.addHotkeysForCategory(SchematicPreview.MOD_ID, "schematicpreview.hotkeys.category.generic_hotkeys", SchematicPreviewConfigs.HOTKEYS);
    }

}
