package ru.dimaskama.schematicpreview.render;

import fi.dy.masa.litematica.render.schematic.BlockModelRendererSchematic;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Face culling for previews that ignores Litematica render-layer / translucent-inner-side
 * configs, which are meant for in-world schematic placement rather than offline previews.
 */
final class PreviewBlockModelRenderer extends BlockModelRendererSchematic {

    @Override
    public boolean shouldRenderModelSide(
            BlockAndTintGetter worldIn,
            BlockState stateIn,
            BlockPos posIn,
            Direction face,
            BlockPos neighbor
    ) {
        return Block.shouldRenderFace(stateIn, worldIn.getBlockState(neighbor), face);
    }
}
