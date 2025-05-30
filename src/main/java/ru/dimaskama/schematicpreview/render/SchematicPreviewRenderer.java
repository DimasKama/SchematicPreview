package ru.dimaskama.schematicpreview.render;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import fi.dy.masa.litematica.render.schematic.IBufferBuilderPatch;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.LocalRandom;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ru.dimaskama.schematicpreview.SchematicPreview;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SchematicPreviewRenderer implements AutoCloseable {

    private final WorldSchematicWrapper world;
    private final BlockRenderManager blockRenderManager;
    private final BlockEntityRenderDispatcher blockEntityRenderDispatcher;
    private final List<ChunkEntry> chunks = new ArrayList<>();
    private final Vector3f pos = new Vector3f(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE);
    private final Vector3f lastPos = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
    private ChunkPos lastChunkPos = new ChunkPos(Integer.MIN_VALUE, Integer.MIN_VALUE);
    private boolean updated;
    private boolean canceled;
    private RenderPhase.Target target;

    public SchematicPreviewRenderer(MinecraftClient mc) {
        world = new WorldSchematicWrapper(mc);
        blockRenderManager = mc.getBlockRenderManager();
        blockEntityRenderDispatcher = mc.getBlockEntityRenderDispatcher();
    }

    public void setup(LitematicaSchematic schematic) {
        close();
        world.setSchematic(schematic);

        int height = world.getSize().getY();
        int chunksX = world.getSize().getX() >>> 4;
        int chunksZ = world.getSize().getZ() >>> 4;

        for (int chunkX = 0; chunkX <= chunksX; chunkX++) {
            for (int chunkZ = 0; chunkZ <= chunksZ; chunkZ++) {
                ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
                chunks.add(new ChunkEntry(chunkPos, CompletableFuture.supplyAsync(() -> {
                    Random random = new LocalRandom(0);
                    BuiltChunk chunk = new BuiltChunk();
                    MatrixStack matrices = new MatrixStack();
                    BlockPos.Mutable worldPos = new BlockPos.Mutable();
                    int chunkStartX = chunkPos.getStartX();
                    int chunkStartZ = chunkPos.getStartZ();
                    int chunkEndX = chunkStartX + 16;
                    int chunkEndZ = chunkStartZ + 16;
                    for (int y = 0; y < height; y++) {
                        for (int z = chunkStartZ; z < chunkEndZ; z++) {
                            for (int x = chunkStartX; x < chunkEndX; x++) {
                                if (canceled) {
                                    chunk.close();
                                    return null;
                                }
                                worldPos.set(x, y, z);
                                BlockState state = world.getBlockState(worldPos);
                                FluidState fluid = state.getFluidState();
                                boolean renderFluid = !fluid.isEmpty();
                                boolean renderBlock = state.getRenderType() == BlockRenderType.MODEL;
                                if (renderFluid || renderBlock) {
                                    matrices.push();
                                    matrices.translate(worldPos.getX() & 0xF, worldPos.getY(), worldPos.getZ() & 0xF);
                                    if (renderFluid) {
                                        RenderLayer layer = RenderLayers.getFluidLayer(fluid);
                                        BufferBuilder builder = chunk.getOrCreateBuffers(layer).getOrCreateBuilder();
                                        ((IBufferBuilderPatch) builder).litematica$setOffsetY((worldPos.getY() >> 4) << 4);
                                        blockRenderManager.renderFluid(worldPos, world, builder, state, fluid);
                                        ((IBufferBuilderPatch) builder).litematica$setOffsetY(0.0F);
                                    }
                                    if (renderBlock) {
                                        RenderLayer layer = RenderLayers.getBlockLayer(state);
                                        BufferBuilder builder = chunk.getOrCreateBuffers(layer).getOrCreateBuilder();
                                        blockRenderManager.getModelRenderer().renderSmooth(
                                                world,
                                                blockRenderManager.getModel(state).getParts(random),
                                                state,
                                                worldPos,
                                                matrices,
                                                builder,
                                                true,
                                                OverlayTexture.DEFAULT_UV
                                        );
                                    }
                                    matrices.pop();
                                }
                            }
                        }
                    }
                    return chunk;
                })));
            }
        }
    }

    public void prepareRender(float x, float y, float z, Framebuffer target) {
        this.target = new RenderPhase.Target("SchematicPreview", () -> target);
        pos.set(x, y, z);
        updated = !lastPos.equals(pos);
        if (updated) {
            lastPos.set(x, y, z);
            ChunkPos chunkPos = new ChunkPos(MathHelper.floor(x) >> 4, MathHelper.floor(z) >> 4);
            if (!lastChunkPos.equals(chunkPos)) {
                lastChunkPos = chunkPos;
                chunks.sort(Comparator.comparingInt(chunk -> -(Math.abs(chunk.pos.x - chunkPos.x) + Math.abs(chunk.pos.z - chunkPos.z))));
            }
        }
    }

    public void renderLayer(RenderLayer layer) {
        layer.startDrawing();
        chunks.forEach(chunkEntry -> {
            if (!chunkEntry.future.isDone()) {
                return;
            }
            BuiltChunk chunk = chunkEntry.future.join();
            if (chunk == null) {
                return;
            }
            boolean uploaded = chunk.uploadBufferIfNot(layer);
            Buffers buffers = chunk.buffersMap.get(layer);
            if (buffers == null || buffers.buffer == null) {
                return;
            }
            if (updated || uploaded) {
                chunk.resortTransparentAndUpload(
                        layer,
                        VertexSorter.byDistance(pos.x - chunkEntry.pos.getStartX(), pos.y, pos.z - chunkEntry.pos.getStartZ())
                );
            }
            Framebuffer framebuffer = target.get();
            GpuBuffer indexBuffer;
            VertexFormat.IndexType indexType;
            if (buffers.indexBuffer != null) {
                indexBuffer = buffers.indexBuffer;
                indexType = buffers.built.getDrawParameters().indexType();
            } else {
                RenderSystem.ShapeIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(buffers.built.getDrawParameters().mode());
                indexBuffer = shapeIndexBuffer.getIndexBuffer(buffers.built.getDrawParameters().indexCount());
                indexType = shapeIndexBuffer.getIndexType();
            }
            RenderSystem.setModelOffset(chunkEntry.pos.getStartX() -pos.x, -pos.y, chunkEntry.pos.getStartZ() - pos.z);
            try (RenderPass renderPass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(
                            framebuffer.getColorAttachment(), OptionalInt.empty(), framebuffer.useDepthAttachment ? framebuffer.getDepthAttachment() : null, OptionalDouble.empty()
                    )) {
                renderPass.setPipeline(layer.getPipeline());
                renderPass.setVertexBuffer(0, buffers.buffer);
                if (RenderSystem.SCISSOR_STATE.isEnabled()) {
                    renderPass.enableScissor(RenderSystem.SCISSOR_STATE);
                }

                for (int i = 0; i < 12; i++) {
                    GpuTexture gpuTexture = RenderSystem.getShaderTexture(i);
                    if (gpuTexture != null) {
                        renderPass.bindSampler("Sampler" + i, gpuTexture);
                    }
                }

                renderPass.setIndexBuffer(indexBuffer, indexType);
                renderPass.drawIndexed(0, buffers.built.getDrawParameters().indexCount());
            }
            RenderSystem.setModelOffset(0.0F, 0.0F, 0.0F);
        });
        layer.endDrawing();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void renderBlockEntities(MatrixStack stack, VertexConsumerProvider.Immediate vertexConsumers, float tickDelta) {
        if (getBuiltChunksCount() != chunks.size()) {
            return;
        }
        CustomVertexConsumerProvider customVertexConsumers = new CustomVertexConsumerProvider(vertexConsumers, target);
        Vec3d cameraPos = new Vec3d(pos);
        world.getBlockEntities().forEach((pos, blockEntitySupplier) -> {
            BlockEntity blockEntity = blockEntitySupplier.get();
            if (blockEntity != null) {
                BlockEntityRenderer renderer = blockEntityRenderDispatcher.get(blockEntity);
                if (renderer != null) {
                    stack.push();
                    stack.translate(pos.getX() - lastPos.x, pos.getY() - lastPos.y, pos.getZ() - lastPos.z);
                    try {
                        renderer.render(blockEntity, tickDelta, stack, customVertexConsumers, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, cameraPos);
                    } catch (Exception e) {
                        SchematicPreview.LOGGER.debug("Exception while rendering preview block entity", e);
                    }
                    stack.pop();
                }
            }
        });
        customVertexConsumers.draw();
    }

    public int getBuiltChunksCount() {
        return (int) chunks.stream().map(ChunkEntry::future).filter(CompletableFuture::isDone).count();
    }

    public boolean isBuildingTerrain() {
        return !chunks.isEmpty() && chunks.stream().map(ChunkEntry::future).noneMatch(CompletableFuture::isDone);
    }

    public boolean isBuildDone() {
        return chunks.stream().map(ChunkEntry::future).allMatch(CompletableFuture::isDone);
    }

    @Override
    public void close() {
        canceled = true;
        chunks.stream().map(ChunkEntry::future).map(CompletableFuture::join).filter(Objects::nonNull).forEach(BuiltChunk::close);
        canceled = false;
        chunks.clear();
        lastPos.set(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE);
        pos.set(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        lastChunkPos = new ChunkPos(Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    private record ChunkEntry(ChunkPos pos, CompletableFuture<@Nullable BuiltChunk> future) {}

    private static class BuiltChunk implements AutoCloseable {

        private final Map<RenderLayer, Buffers> buffersMap = new HashMap<>();

        private boolean uploadBufferIfNot(RenderLayer layer) {
            Buffers buffers = buffersMap.get(layer);
            if (buffers == null) {
                return false;
            }
            GpuBuffer buffer = buffers.buffer;
            if (buffer != null) {
                return false;
            }
            BufferBuilder builder = buffers.builder;
            if (builder == null) {
                return false;
            }
            BuiltBuffer built;
            try {
                built = builder.endNullable();
            } catch (Exception e) {
                return false;
            }
            if (built == null) {
                return false;
            }
            buffers.built = built;
            if (layer == RenderLayer.getTranslucent()) {
                buffers.sortState = built.sortQuads(buffers.allocator, VertexSorter.BY_DISTANCE);
            }

            buffer = RenderSystem.getDevice().createBuffer(() -> "SchematicPreview Chunk", BufferType.VERTICES, BufferUsage.STATIC_WRITE, built.getBuffer());
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer, built.getBuffer(), 0);
            buffers.buffer = buffer;
            return true;
        }

        private void resortTransparentAndUpload(RenderLayer layer, VertexSorter vertexSorter) {
            Buffers buffers = buffersMap.get(layer);
            if (buffers != null) {
                BuiltBuffer built = buffers.built;
                BuiltBuffer.SortState sortState = buffers.sortState;
                if (built != null && sortState != null) {
                    GpuBuffer indexBuffer = buffers.indexBuffer;
                    if (indexBuffer == null) {
                        indexBuffer = RenderSystem.getDevice().createBuffer(() -> "SchematicPreview Indices", BufferType.INDICES, BufferUsage.DYNAMIC_WRITE, built.getSortedBuffer());
                        buffers.indexBuffer = indexBuffer;
                    }
                    try (BufferAllocator.CloseableBuffer closeableBuffer = sortState.sortAndStore(buffers.allocator, vertexSorter)) {
                        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(indexBuffer, closeableBuffer.getBuffer(), 0);
                    }
                }
            }
        }

        private Buffers getOrCreateBuffers(RenderLayer layer) {
            return buffersMap.computeIfAbsent(layer, Buffers::new);
        }

        @Override
        public void close() {
            buffersMap.values().forEach(Buffers::close);
            buffersMap.clear();
        }

    }

    private static class Buffers implements AutoCloseable {

        private final RenderLayer layer;
        private BufferAllocator allocator;
        private BufferBuilder builder;
        private BuiltBuffer built;
        private BuiltBuffer.SortState sortState;
        private GpuBuffer buffer;
        private GpuBuffer indexBuffer;

        public Buffers(RenderLayer layer) {
            this.layer = layer;
            allocator = new BufferAllocator(layer.getExpectedBufferSize());
        }

        public BufferBuilder getOrCreateBuilder() {
            if (builder == null)  {
                builder = new BufferBuilder(allocator, layer.getDrawMode(), layer.getVertexFormat());
            }
            return builder;
        }

        @Override
        public void close() {
            if (allocator != null) {
                allocator.close();
                allocator = null;
            }
            if (built != null) {
                built.close();
                built = null;
            }
            sortState = null;
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
            if (indexBuffer != null) {
                indexBuffer.close();
                indexBuffer = null;
            }
        }

    }

}
