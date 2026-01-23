package ru.dimaskama.schematicpreview.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Consumer;

public class CustomVertexConsumerProvider extends MultiBufferSource.BufferSource {

    private RenderTarget framebuffer;

    protected CustomVertexConsumerProvider(MultiBufferSource.BufferSource delegate) {
        super(delegate.sharedBuffer, delegate.fixedBuffers);
    }

    public void setFramebuffer(RenderTarget framebuffer) {
        this.framebuffer = framebuffer;
    }

    @Override
    protected void endBatch(RenderType renderType, BufferBuilder builder) {
        if (framebuffer == null) {
            return;
        }
        MeshData meshData = builder.build();
        if (meshData != null) {
            if (renderType.sortOnUpload()) {
                ByteBufferBuilder byteBufferBuilder = fixedBuffers.getOrDefault(renderType, sharedBuffer);
                meshData.sortQuads(byteBufferBuilder, RenderSystem.getProjectionType().vertexSorting());
            }

            Matrix4fStack matrix4fStack = RenderSystem.getModelViewStack();
            Consumer<Matrix4fStack> consumer = renderType.state.layeringTransform.getModifier();
            if (consumer != null) {
                matrix4fStack.pushMatrix();
                consumer.accept(matrix4fStack);
            }

            GpuBufferSlice gpuBufferSlice = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrix(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector3f(), renderType.state.textureTransform.getMatrix());
            Map<String, RenderSetup.TextureAndSampler> map = renderType.state.getTextures();
            MeshData var6 = meshData;

            try {
                GpuBuffer gpuBuffer = renderType.state.pipeline.getVertexFormat().uploadImmediateVertexBuffer(meshData.vertexBuffer());
                GpuBuffer gpuBuffer2;
                VertexFormat.IndexType indexType;
                if (meshData.indexBuffer() == null) {
                    RenderSystem.AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(meshData.drawState().mode());
                    gpuBuffer2 = autoStorageIndexBuffer.getBuffer(meshData.drawState().indexCount());
                    indexType = autoStorageIndexBuffer.type();
                } else {
                    gpuBuffer2 = renderType.state.pipeline.getVertexFormat().uploadImmediateIndexBuffer(meshData.indexBuffer());
                    indexType = meshData.drawState().indexType();
                }

                RenderTarget renderTarget = framebuffer;
                GpuTextureView gpuTextureView = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : renderTarget.getColorTextureView();
                GpuTextureView gpuTextureView2 = renderTarget.useDepth ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : renderTarget.getDepthTextureView()) : null;

                try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Immediate draw for SchematicPreview", gpuTextureView, OptionalInt.empty(), gpuTextureView2, OptionalDouble.empty())) {
                    renderPass.setPipeline(renderType.state.pipeline);
                    ScissorState scissorState = RenderSystem.getScissorStateForRenderTypeDraws();
                    if (scissorState.enabled()) {
                        renderPass.enableScissor(scissorState.x(), scissorState.y(), scissorState.width(), scissorState.height());
                    }

                    RenderSystem.bindDefaultUniforms(renderPass);
                    renderPass.setUniform("DynamicTransforms", gpuBufferSlice);
                    renderPass.setVertexBuffer(0, gpuBuffer);

                    for(Map.Entry<String, RenderSetup.TextureAndSampler> entry : map.entrySet()) {
                        renderPass.bindTexture(entry.getKey(), entry.getValue().textureView(), entry.getValue().sampler());
                    }

                    renderPass.setIndexBuffer(gpuBuffer2, indexType);
                    renderPass.drawIndexed(0, 0, meshData.drawState().indexCount(), 1);
                }
            } catch (Throwable var20) {
                if (meshData != null) {
                    try {
                        var6.close();
                    } catch (Throwable var17) {
                        var20.addSuppressed(var17);
                    }
                }

                throw var20;
            }

            if (meshData != null) {
                meshData.close();
            }

            if (consumer != null) {
                matrix4fStack.popMatrix();
            }
        }

        if (renderType.equals(lastSharedType)) {
            lastSharedType = null;
        }
    }

}
