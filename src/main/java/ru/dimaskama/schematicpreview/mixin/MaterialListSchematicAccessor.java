package ru.dimaskama.schematicpreview.mixin;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.litematica.materials.MaterialListSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MaterialListSchematic.class)
public interface MaterialListSchematicAccessor {

    @Accessor(value = "schematic", remap = false)
    LitematicaSchematic schematicpreview_schematic();

    @Accessor(value = "regions", remap = false)
    ImmutableList<String> schematicpreview_regions();

}
