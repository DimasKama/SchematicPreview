package ru.dimaskama.schematicpreview.render;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.util.FileType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Util;
import ru.dimaskama.schematicpreview.gui.widget.SchematicPreviewWidget;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class PreviewsCache implements AutoCloseable {

    private final Map<File, CompletableFuture<LitematicaSchematic>> schematics = new HashMap<>();
    private final Map<File, SchematicPreviewWidget> smallWidgets = new HashMap<>();
    private boolean closed = false;

    public CompletableFuture<LitematicaSchematic> getSchematic(File file) {
        closed = false;
        return schematics.computeIfAbsent(file, f -> CompletableFuture.supplyAsync(() ->
                LitematicaSchematic.createFromFile(f.getParentFile(), f.getName(), FileType.fromFile(f)),
                Util.getDownloadWorkerExecutor()
        ));
    }

    public SchematicPreviewWidget getSmallWidget(File file) {
        closed = false;
        return smallWidgets.computeIfAbsent(file, f -> {
            SchematicPreviewWidget w = new SchematicPreviewWidget(this, false);
            w.setSchematic(f);
            return w;
        });
    }

    public void tickClose() {
        if (!closed && MinecraftClient.getInstance().currentScreen == null) {
            close();
        }
    }

    @Override
    public void close() {
        schematics.values().forEach(f -> f.cancel(true));
        schematics.clear();
        smallWidgets.values().forEach(SchematicPreviewWidget::removed);
        smallWidgets.clear();
        closed = true;
    }

}
