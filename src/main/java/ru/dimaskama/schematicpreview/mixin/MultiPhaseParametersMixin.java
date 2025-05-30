package ru.dimaskama.schematicpreview.mixin;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import org.spongepowered.asm.mixin.*;
import ru.dimaskama.schematicpreview.extend.MultiPhaseParametersExtend;

@Mixin(RenderLayer.MultiPhaseParameters.class)
abstract class MultiPhaseParametersMixin implements MultiPhaseParametersExtend {

    @Shadow @Final @Mutable
    RenderPhase.Target target;
    @Shadow @Final @Mutable
    ImmutableList<RenderPhase> phases;

    @Unique
    private RenderPhase.Target schematicpreview_originalTarget;
    @Unique
    private ImmutableList<RenderPhase> schematicpreview_originalPhases;

    @Override
    public void schematicpreview_replaceTarget(RenderPhase.Target target) {
        schematicpreview_originalTarget = this.target;
        schematicpreview_originalPhases = phases;
        this.target = target;
        var phasesBuilder = ImmutableList.<RenderPhase>builder();
        for (RenderPhase phase : phases) {
            if (phase instanceof RenderPhase.Target) {
                phasesBuilder.add(target);
            } else {
                phasesBuilder.add(phase);
            }
        }
        phases = phasesBuilder.build();
    }

    @Override
    public void schematicpreview_originalTarget() {
        target = schematicpreview_originalTarget;
        phases = schematicpreview_originalPhases;
    }

}
