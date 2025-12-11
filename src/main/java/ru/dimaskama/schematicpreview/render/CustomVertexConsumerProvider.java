package ru.dimaskama.schematicpreview.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.ScissorState;
import net.minecraft.client.render.*;
import net.minecraft.client.util.BufferAllocator;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.OptionalDouble;
import java.util.OptionalInt;

public class CustomVertexConsumerProvider extends VertexConsumerProvider.Immediate {

    private Framebuffer framebuffer;

    protected CustomVertexConsumerProvider(VertexConsumerProvider.Immediate delegate) {
        super(delegate.allocator, delegate.layerBuffers);
    }

    public void setFramebuffer(Framebuffer framebuffer) {
        this.framebuffer = framebuffer;
    }

    @Override
    protected void draw(RenderLayer layer, BufferBuilder builder) {
        if (framebuffer == null) {
            return;
        }
        BuiltBuffer buffer = builder.endNullable();
        if (buffer != null) {
            if (layer.isTranslucent()) {
                BufferAllocator bufferAllocator = layerBuffers.getOrDefault(layer, this.allocator);
                buffer.sortQuads(bufferAllocator, RenderSystem.getProjectionType().getVertexSorter());
            }

            layer.startDrawing();
            GpuBufferSlice gpuBufferSlice = RenderSystem.getDynamicUniforms()
                    .write(
                            RenderSystem.getModelViewMatrix(),
                            new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                            new Vector3f(),
                            RenderSystem.getTextureMatrix(),
                            RenderSystem.getShaderLineWidth()
                    );
            try {
                GpuBuffer gpuBuffer = layer.getRenderPipeline().getVertexFormat().uploadImmediateVertexBuffer(buffer.getBuffer());
                GpuBuffer gpuBuffer2;
                VertexFormat.IndexType indexType;
                if (buffer.getSortedBuffer() == null) {
                    RenderSystem.ShapeIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(buffer.getDrawParameters().mode());
                    gpuBuffer2 = shapeIndexBuffer.getIndexBuffer(buffer.getDrawParameters().indexCount());
                    indexType = shapeIndexBuffer.getIndexType();
                } else {
                    gpuBuffer2 = layer.getRenderPipeline().getVertexFormat().uploadImmediateIndexBuffer(buffer.getSortedBuffer());
                    indexType = buffer.getDrawParameters().indexType();
                }

                GpuTextureView gpuTextureView = RenderSystem.outputColorTextureOverride != null
                        ? RenderSystem.outputColorTextureOverride
                        : framebuffer.getColorAttachmentView();
                GpuTextureView gpuTextureView2 = framebuffer.useDepthAttachment
                        ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : framebuffer.getDepthAttachmentView())
                        : null;

                try (RenderPass renderPass = RenderSystem.getDevice()
                        .createCommandEncoder()
                        .createRenderPass(() -> "Immediate draw for SchematicPreview", gpuTextureView, OptionalInt.empty(), gpuTextureView2, OptionalDouble.empty())) {
                    renderPass.setPipeline(layer.getRenderPipeline());
                    ScissorState scissorState = RenderSystem.getScissorStateForRenderTypeDraws();
                    if (scissorState.isEnabled()) {
                        renderPass.enableScissor(scissorState.getX(), scissorState.getY(), scissorState.getWidth(), scissorState.getHeight());
                    }

                    RenderSystem.bindDefaultUniforms(renderPass);
                    renderPass.setUniform("DynamicTransforms", gpuBufferSlice);
                    renderPass.setVertexBuffer(0, gpuBuffer);

                    for (int i = 0; i < 12; i++) {
                        GpuTextureView gpuTextureView3 = RenderSystem.getShaderTexture(i);
                        if (gpuTextureView3 != null) {
                            renderPass.bindSampler("Sampler" + i, gpuTextureView3);
                        }
                    }

                    renderPass.setIndexBuffer(gpuBuffer2, indexType);
                    renderPass.drawIndexed(0, 0, buffer.getDrawParameters().indexCount(), 1);
                }
            } catch (Throwable var17) {
                try {
                    buffer.close();
                } catch (Throwable var14) {
                    var17.addSuppressed(var14);
                }

                throw var17;
            }

            buffer.close();

            layer.endDrawing();
        }

        if (layer.equals(currentLayer)) {
            currentLayer = null;
        }
    }

}
