package ru.dimaskama.schematicpreview.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;

public class DummyOutlineVertexConsumerProvider extends OutlineBufferSource {

    @Override
    public VertexConsumer getBuffer(RenderType layer) {
        return DummyVertexConsumerProvider.DUMMY_VERTEX_CONSUMER;
    }

}
