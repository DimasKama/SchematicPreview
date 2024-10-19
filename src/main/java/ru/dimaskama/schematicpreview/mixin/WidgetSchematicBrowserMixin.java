package ru.dimaskama.schematicpreview.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import fi.dy.masa.litematica.gui.GuiSchematicBrowserBase;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicBrowser;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase;
import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.dimaskama.schematicpreview.gui.widget.SchematicBrowserPatch;
import ru.dimaskama.schematicpreview.gui.widget.SchematicPreviewWidget;

@Mixin(WidgetSchematicBrowser.class)
abstract class WidgetSchematicBrowserMixin extends WidgetFileBrowserBase implements SchematicBrowserPatch {

    @Shadow(remap = false) @Final protected int infoHeight;
    @Shadow(remap = false) @Final protected GuiSchematicBrowserBase parent;
    @Shadow(remap = false) @Final protected int infoWidth;

    private WidgetSchematicBrowserMixin() {
        super(0, 0, 0, 0, null, null, null, null, null);
        throw new AssertionError();
    }

    @ModifyExpressionValue(
            method = "drawSelectedSchematicInfo",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"
            ),
            remap = false
    )
    private Object modifyIcon(Object original) {
        return schematicpreview_getSideWidget() != null ? null : original;
    }

    @Inject(method = "drawSelectedSchematicInfo", at = @At("TAIL"), remap = false)
    private void drawPreview(@Nullable DirectoryEntry entry, DrawContext context, CallbackInfo ci, @Local(ordinal = 1) int currentY) {
        SchematicPreviewWidget widget = schematicpreview_getSideWidget();
        if (widget != null && entry != null) {
            int x = posX + totalWidth - infoWidth;
            int height = Math.min(infoHeight, parent.getMaxInfoHeight());
            int allowedHeight = height - (currentY - posY);
            widget.setSchematic(entry.getFullPath());
            widget.renderPreviewAndOverlay(context, x, currentY, infoWidth, allowedHeight);
        }
    }

}
