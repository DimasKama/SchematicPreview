package ru.dimaskama.schematicpreview.gui.widget;

public interface SchematicBrowserPatch {

    default SchematicPreviewWidget schematicpreview_getSideWidget() {
        return null;
    }

}
