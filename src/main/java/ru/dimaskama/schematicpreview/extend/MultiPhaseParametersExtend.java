package ru.dimaskama.schematicpreview.extend;

import net.minecraft.client.render.RenderPhase;

public interface MultiPhaseParametersExtend {

    void schematicpreview_replaceTarget(RenderPhase.Target target);

    void schematicpreview_originalTarget();

}
