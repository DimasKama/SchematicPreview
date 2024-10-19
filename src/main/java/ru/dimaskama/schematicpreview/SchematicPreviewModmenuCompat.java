package ru.dimaskama.schematicpreview;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import ru.dimaskama.schematicpreview.gui.GuiSchematicPreviewConfig;

public class SchematicPreviewModmenuCompat implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return GuiSchematicPreviewConfig::new;
    }

}
