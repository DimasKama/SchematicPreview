package ru.dimaskama.schematicpreview.render;

import net.minecraft.client.render.*;
import ru.dimaskama.schematicpreview.extend.MultiPhaseParametersExtend;

public class CustomVertexConsumerProvider extends VertexConsumerProvider.Immediate {

    private final RenderPhase.Target target;

    protected CustomVertexConsumerProvider(VertexConsumerProvider.Immediate delegate, RenderPhase.Target target) {
        super(delegate.allocator, delegate.layerBuffers);
        this.target = target;
    }

    @Override
    protected void draw(RenderLayer layer, BufferBuilder builder) {
        if (layer instanceof RenderLayer.MultiPhase multiPhase) {
            ((MultiPhaseParametersExtend) (Object) multiPhase.phases).schematicpreview_replaceTarget(target);
        }
        super.draw(layer, builder);
        if (layer instanceof RenderLayer.MultiPhase multiPhase) {
            ((MultiPhaseParametersExtend) (Object) multiPhase.phases).schematicpreview_originalTarget();
        }
    }

}
