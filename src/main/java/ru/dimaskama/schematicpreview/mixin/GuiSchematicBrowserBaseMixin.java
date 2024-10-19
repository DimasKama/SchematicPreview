package ru.dimaskama.schematicpreview.mixin;

import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicBrowser;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.dimaskama.schematicpreview.SchematicPreviewConfigs;
import ru.dimaskama.schematicpreview.gui.widget.CustomSchematicBrowser;

@Mixin(GuiSchematicBrowserBase.class)
abstract class GuiSchematicBrowserBaseMixin {

    @Shadow(remap = false) protected abstract ISelectionListener<WidgetFileBrowserBase.DirectoryEntry> getSelectionListener();

    @Inject(method = "createListWidget(II)Lfi/dy/masa/litematica/gui/widgets/WidgetSchematicBrowser;", remap = false, at = @At("HEAD"), cancellable = true)
    private void modifyWidget(int listX, int listY, CallbackInfoReturnable<WidgetSchematicBrowser> cir) {
        if (SchematicPreviewConfigs.ENABLED.getBooleanValue()) {
            cir.setReturnValue(new CustomSchematicBrowser(listX, listY, 100, 100, (GuiSchematicBrowserBase) (Object) this, getSelectionListener()));
        }
    }

}
