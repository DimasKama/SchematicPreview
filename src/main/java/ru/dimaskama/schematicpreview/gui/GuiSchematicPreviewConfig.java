package ru.dimaskama.schematicpreview.gui;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import ru.dimaskama.schematicpreview.SchematicPreview;
import ru.dimaskama.schematicpreview.SchematicPreviewConfigs;

import java.util.List;
import net.minecraft.client.gui.screens.Screen;

public class GuiSchematicPreviewConfig extends GuiConfigsBase {

    private ConfigGuiTab selectedTab = ConfigGuiTab.GENERIC;

    public GuiSchematicPreviewConfig(Screen parent) {
        super(10, 50, SchematicPreview.MOD_ID, parent, "schematicpreview.config", SchematicPreview.MOD_CONTAINER.getMetadata().getVersion().getFriendlyString());
    }

    @Override
    public void initGui() {
        super.initGui();
        clearOptions();

        int x = 10;
        int y = 26;

        x += createButton(x, y, ConfigGuiTab.GENERIC);
        x += createButton(x, y, ConfigGuiTab.MENU);
        x += createButton(x, y, ConfigGuiTab.PREVIEW);
    }

    private int createButton(int x, int y, ConfigGuiTab tab) {
        String name = tab.getDisplayName();
        ButtonGeneric button = new ButtonGeneric(x, y, font.width(name) + 8, 20, name);
        button.setEnabled(tab != selectedTab);
        addButton(button, ((b, mouseButton) -> {
            selectedTab = tab;
            initGui();
        }));
        return button.getWidth() + 2;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        return ConfigOptionWrapper.createFor(selectedTab.configs);
    }

    private enum ConfigGuiTab {

        GENERIC("schematicpreview.config.tab.generic", SchematicPreviewConfigs.GENERIC),
        MENU("schematicpreview.config.tab.menu", SchematicPreviewConfigs.MENU),
        PREVIEW("schematicpreview.config.tab.preview", SchematicPreviewConfigs.PREVIEW);

        private final String translationKey;
        private final List<IConfigBase> configs;

        ConfigGuiTab(String translationKey, List<IConfigBase> configs) {
            this.translationKey = translationKey;
            this.configs = configs;
        }

        private String getDisplayName() {
            return StringUtils.translate(this.translationKey);
        }

    }

}
