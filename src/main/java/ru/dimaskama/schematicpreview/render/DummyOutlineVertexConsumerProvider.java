package ru.dimaskama.schematicpreview.render;

import net.minecraft.client.render.*;

public class DummyOutlineVertexConsumerProvider extends OutlineVertexConsumerProvider {

    @Override
    public VertexConsumer getBuffer(RenderLayer layer) {
        return DummyVertexConsumerProvider.DUMMY_VERTEX_CONSUMER;
    }

}
