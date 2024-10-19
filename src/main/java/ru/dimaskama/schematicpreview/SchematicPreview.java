package ru.dimaskama.schematicpreview;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InitializationHandler;
import fi.dy.masa.malilib.event.InputEventHandler;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.dimaskama.schematicpreview.gui.GuiSchematicPreviewConfig;
import ru.dimaskama.schematicpreview.render.PreviewsCache;

import java.util.Set;

//todo translations
public class SchematicPreview implements ModInitializer {

    public static final String MOD_ID = "schematicpreview";
    public static final Logger LOGGER = LoggerFactory.getLogger("SchematicPreview");
    public static final ModContainer MOD_CONTAINER = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow();
    public static final PreviewsCache PREVIEWS_CACHE = new PreviewsCache();
    private static final Set<Runnable> tickables = new ObjectArraySet<>();

    @Override
    public void onInitialize() {
        InitializationHandler.getInstance().registerInitializationHandler(() -> {
            ConfigManager.getInstance().registerConfigHandler(MOD_ID, SchematicPreviewConfigs.INSTANCE);
            InputEventHandler.getKeybindManager().registerKeybindProvider(SchematicPreviewInputHandler.getInstance());
            SchematicPreviewConfigs.CONFIG_MENU_HOTKEY.getKeybind().setCallback((action, key) -> {
                MinecraftClient.getInstance().setScreen(new GuiSchematicPreviewConfig(MinecraftClient.getInstance().currentScreen));
                return true;
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            PREVIEWS_CACHE.tickClose();
            if (!tickables.isEmpty()) {
                for (Runnable tick : tickables) {
                    tick.run();
                }
            }
        });
    }

    public static void addTickable(Runnable tickAction) {
        tickables.add(tickAction);
    }

    public static void removeTickable(Runnable tickAction) {
        tickables.remove(tickAction);
    }

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

}
