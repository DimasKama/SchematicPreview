package ru.dimaskama.schematicpreview;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class SchematicPreviewCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final Map<String, ItemIconState> ICONS = new HashMap<>();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve(SchematicPreview.MOD_ID + "_cache.json");
    private static boolean dirty;

    public static void markDirty() {
        dirty = true;
    }

    public static void loadOrCreate() {
        if (Files.exists(PATH)) {
            load();
        } else {
            forceSave();
        }
    }

    public static void load() {
        if (Files.exists(PATH)) {
            try (Reader reader = new InputStreamReader(Files.newInputStream(PATH))) {
                decode(JsonParser.parseReader(reader).getAsJsonObject());
            } catch (Exception e) {
                SchematicPreview.LOGGER.warn("Cache load exception {}", PATH, e);
            }
        }
    }

    public static void save() {
        if (dirty) {
            forceSave();
        }
    }

    public static void forceSave() {
        dirty = false;
        try {
            JsonObject json = new JsonObject();
            encode(json);
            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(PATH))) {
                GSON.toJson(json, writer);
            }
        } catch (Exception e) {
            SchematicPreview.LOGGER.warn("Cache save exception {}", PATH, e);
        }
    }

    private static void decode(JsonObject json) {
        ICONS.clear();
        JsonObject icons = json.getAsJsonObject("icons");
        if (icons != null) {
            icons.asMap().forEach((k, v) -> ItemIconState.CODEC.decode(JsonOps.INSTANCE, v).ifSuccess(p -> ICONS.put(k, p.getFirst())));
        }
    }

    private static void encode(JsonObject json) {
        JsonObject icons = new JsonObject();
        ICONS.forEach((k, v) -> ItemIconState.CODEC.encodeStart(JsonOps.INSTANCE, v).ifSuccess(j -> icons.add(k, j)));
        json.add("icons", icons);
    }

}
