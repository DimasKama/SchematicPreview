package ru.dimaskama.schematicpreview.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.BufferAllocator;

public class CustomVertexConsumerProvider extends VertexConsumerProvider.Immediate {

    private final Framebuffer framebuffer;

    protected CustomVertexConsumerProvider(VertexConsumerProvider.Immediate delegate, Framebuffer framebuffer) {
        super(delegate.allocator, delegate.layerBuffers);
        this.framebuffer = framebuffer;
    }

    @Override
    protected void draw(RenderLayer layer, BufferBuilder builder) {
        BuiltBuffer builtBuffer = builder.endNullable();
        if (builtBuffer != null) {
            if (layer.isTranslucent()) {
                BufferAllocator bufferAllocator = layerBuffers.getOrDefault(layer, this.allocator);
                builtBuffer.sortQuads(bufferAllocator, RenderSystem.getProjectionType().getVertexSorter());
            }

            layer.startDrawing();
            framebuffer.beginWrite(true);
            BufferRenderer.drawWithGlobalProgram(builtBuffer);
            layer.endDrawing();
        }

        if (layer.equals(currentLayer)) {
            currentLayer = null;
        }
    }

}
