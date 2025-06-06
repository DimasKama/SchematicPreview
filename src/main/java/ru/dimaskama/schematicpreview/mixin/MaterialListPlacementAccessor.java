package ru.dimaskama.schematicpreview.mixin;

import fi.dy.masa.litematica.materials.MaterialListPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MaterialListPlacement.class)
public interface MaterialListPlacementAccessor {

    @Accessor(value = "placement", remap = false)
    SchematicPlacement schematicpreview_placement();

}
