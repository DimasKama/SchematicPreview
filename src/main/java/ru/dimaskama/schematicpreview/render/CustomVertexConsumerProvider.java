package ru.dimaskama.schematicpreview.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.BufferAllocator;

import java.util.HashMap;
import java.util.Map;

public class CustomVertexConsumerProvider implements VertexConsumerProvider {
    private final VertexConsumerProvider.Immediate delegate;
    private final Framebuffer framebuffer;
    private final Map<RenderLayer, BufferBuilder> layerBuffers;
    private final BufferAllocator allocator;

    public CustomVertexConsumerProvider(VertexConsumerProvider.Immediate delegate, Framebuffer framebuffer) {
        this.delegate = delegate;
        this.framebuffer = framebuffer;
        this.layerBuffers = new HashMap<>();
        this.allocator = new BufferAllocator(262144);
    }

    @Override
    public VertexConsumer getBuffer(RenderLayer layer) {
        BufferBuilder builder = layerBuffers.computeIfAbsent(layer, k -> 
            new BufferBuilder(allocator, layer.getDrawMode(), layer.getVertexFormat()));
        return new CustomVertexConsumer(builder);
    }

    public void draw() {
        for (Map.Entry<RenderLayer, BufferBuilder> entry : layerBuffers.entrySet()) {
            RenderLayer layer = entry.getKey();
            BufferBuilder builder = entry.getValue();
            BuiltBuffer builtBuffer = builder.endNullable();
            if (builtBuffer != null) {
                if (layer.isTranslucent()) {
                    builtBuffer.sortQuads(allocator, RenderSystem.getVertexSorting());
                }
                layer.startDrawing();
                framebuffer.beginWrite(true);
                BufferRenderer.drawWithGlobalProgram(builtBuffer);
                layer.endDrawing();
                builtBuffer.close();
            }
        }
        layerBuffers.clear();
        delegate.draw();
    }

    private class CustomVertexConsumer implements VertexConsumer {
        private final BufferBuilder builder;

        public CustomVertexConsumer(BufferBuilder builder) {
            this.builder = builder;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            builder.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            builder.color(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            builder.texture(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            builder.overlay(u, v);
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            builder.light(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            builder.normal(x, y, z);
            return this;
        }
    }
}
