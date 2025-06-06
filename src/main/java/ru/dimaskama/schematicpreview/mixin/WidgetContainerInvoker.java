package ru.dimaskama.schematicpreview.mixin;

import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(WidgetContainer.class)
public interface WidgetContainerInvoker {

    @Invoker(value = "addWidget", remap = false)
    <T extends WidgetBase> T schematicpreview_addWidget(T widget);

}
