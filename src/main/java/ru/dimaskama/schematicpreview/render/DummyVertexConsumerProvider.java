package ru.dimaskama.schematicpreview.render;

import it.unimi.dsi.fastutil.objects.Object2ObjectSortedMaps;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;

public class DummyVertexConsumerProvider extends VertexConsumerProvider.Immediate {

    public static final VertexConsumer DUMMY_VERTEX_CONSUMER = new VertexConsumer() {
        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }
    };
    public static final BufferAllocator EMPTY_BUFFER_ALLOCATOR = BufferAllocator.fixedSized(8);

    public DummyVertexConsumerProvider() {
        super(EMPTY_BUFFER_ALLOCATOR, Object2ObjectSortedMaps.emptyMap());
    }

    @Override
    public VertexConsumer getBuffer(RenderLayer layer) {
        return DUMMY_VERTEX_CONSUMER;
    }

    @Override
    protected void draw(RenderLayer layer, BufferBuilder builder) {
        currentLayer = null;
    }

}
