package ru.dimaskama.schematicpreview.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.objects.Object2ObjectSortedMaps;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;

public class DummyVertexConsumerProvider extends MultiBufferSource.BufferSource {

    public static final VertexConsumer DUMMY_VERTEX_CONSUMER = new VertexConsumer() {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int i) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float f) {
            return this;
        }
    };
    public static final ByteBufferBuilder EMPTY_BUFFER_ALLOCATOR = ByteBufferBuilder.exactlySized(8);

    public DummyVertexConsumerProvider() {
        super(EMPTY_BUFFER_ALLOCATOR, Object2ObjectSortedMaps.emptyMap());
    }

    @Override
    public VertexConsumer getBuffer(RenderType layer) {
        return DUMMY_VERTEX_CONSUMER;
    }

    @Override
    protected void endBatch(RenderType layer, BufferBuilder builder) {
        lastSharedType = null;
    }

}
