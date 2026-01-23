package ru.dimaskama.schematicpreview.render;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.util.FileType;
import ru.dimaskama.schematicpreview.gui.widget.SchematicPreviewWidget;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;

public class PreviewsCache implements AutoCloseable {

    private final Map<Path, CompletableFuture<LitematicaSchematic>> schematics = new HashMap<>();
    private final Map<Path, SchematicPreviewWidget> smallWidgets = new HashMap<>();
    private boolean closed = false;

    public CompletableFuture<LitematicaSchematic> getSchematic(Path file) {
        closed = false;
        return schematics.computeIfAbsent(file, f -> CompletableFuture.supplyAsync(() ->
                LitematicaSchematic.createFromFile(f.getParent(), f.getFileName().toString(), FileType.fromFile(f)),
                Util.nonCriticalIoPool()
        ));
    }

    public SchematicPreviewWidget getSmallWidget(Path file) {
        closed = false;
        return smallWidgets.computeIfAbsent(file, f -> {
            SchematicPreviewWidget w = new SchematicPreviewWidget(this, false);
            w.setSchematic(f);
            return w;
        });
    }

    public void tickClose() {
        if (!closed && Minecraft.getInstance().screen == null) {
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
