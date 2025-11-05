package ru.dimaskama.schematicpreview.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import com.mojang.blaze3d.vertex.VertexFormat;
import fi.dy.masa.litematica.render.schematic.IBufferBuilderPatch;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.DynamicUniforms;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.client.render.chunk.Buffers;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import net.minecraft.client.render.command.RenderDispatcher;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.LocalRandom;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import ru.dimaskama.schematicpreview.SchematicPreview;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SchematicPreviewRenderer implements AutoCloseable {

    private static final BlockRenderLayer[] BLOCK_RENDER_LAYER_ORDER = new BlockRenderLayer[] {
            BlockRenderLayer.SOLID,
            BlockRenderLayer.CUTOUT,
            BlockRenderLayer.CUTOUT_MIPPED,
            BlockRenderLayer.TRANSLUCENT,
            BlockRenderLayer.TRIPWIRE
    };
    private final WorldSchematicWrapper world;
    private final BlockRenderManager blockRenderManager;
    private final BlockEntityRenderManager blockEntityRenderManager;
    private final OrderedRenderCommandQueueImpl orderedRenderCommandQueue;
    private final CustomVertexConsumerProvider customVertexConsumerProvider;
    private final RenderDispatcher renderDispatcher;
    private final List<ChunkEntry> chunks = new ArrayList<>();
    private final Vector3f pos = new Vector3f(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE);
    private final Vector3f lastPos = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
    private ChunkPos lastChunkPos = new ChunkPos(Integer.MIN_VALUE, Integer.MIN_VALUE);
    private CameraRenderState cameraRenderState;
    private boolean updated;
    private boolean canceled;
    private Framebuffer target;

    public SchematicPreviewRenderer(MinecraftClient mc) {
        world = new WorldSchematicWrapper(mc);
        blockRenderManager = mc.getBlockRenderManager();
        blockEntityRenderManager = mc.getBlockEntityRenderDispatcher();
        orderedRenderCommandQueue = new OrderedRenderCommandQueueImpl();
        customVertexConsumerProvider = new CustomVertexConsumerProvider(mc.getBufferBuilders().getEntityVertexConsumers());
        renderDispatcher = new RenderDispatcher(
                orderedRenderCommandQueue,
                blockRenderManager,
                customVertexConsumerProvider,
                mc.getAtlasManager(),
                new DummyOutlineVertexConsumerProvider(),
                new DummyVertexConsumerProvider(),
                mc.textRenderer
        );
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
                                        BlockRenderLayer layer = RenderLayers.getFluidLayer(fluid);
                                        BufferBuilder builder = chunk.getBuilderByLayer(layer);
                                        ((IBufferBuilderPatch) builder).litematica$setOffsetY((worldPos.getY() >> 4) << 4);
                                        blockRenderManager.renderFluid(worldPos, world, builder, state, fluid);
                                        ((IBufferBuilderPatch) builder).litematica$setOffsetY(0.0F);
                                    }
                                    if (renderBlock) {
                                        BlockRenderLayer layer = RenderLayers.getBlockLayer(state);
                                        BufferBuilder builder = chunk.getBuilderByLayer(layer);
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

    public void prepareRender(CameraRenderState cameraRenderState, Framebuffer target) {
        this.cameraRenderState = cameraRenderState;
        this.target = target;
        pos.set(cameraRenderState.pos.x, cameraRenderState.pos.y, cameraRenderState.pos.z);
        updated = !lastPos.equals(pos);
        if (updated) {
            lastPos.set(cameraRenderState.pos.x, cameraRenderState.pos.y, cameraRenderState.pos.z);
            ChunkPos chunkPos = new ChunkPos(MathHelper.floor(cameraRenderState.pos.x) >> 4, MathHelper.floor(cameraRenderState.pos.z) >> 4);
            if (!lastChunkPos.equals(chunkPos)) {
                lastChunkPos = chunkPos;
                chunks.sort(Comparator.comparingInt(chunk -> -(Math.abs(chunk.pos.x - chunkPos.x) + Math.abs(chunk.pos.z - chunkPos.z))));
            }
        }
    }

    public void renderBlocks() {
        EnumMap<BlockRenderLayer, List<RenderPass.RenderObject<GpuBufferSlice[]>>> drawsPerLayer = new EnumMap<>(BlockRenderLayer.class);
        int maxIndicesRequired = 0;
        List<DynamicUniforms.UniformValue> dynamicTransforms = new ArrayList<>();

        VertexSorter vertexSorter = VertexSorter.byDistance(pos.x, pos.y, pos.z);
        for (BlockRenderLayer layer : BLOCK_RENDER_LAYER_ORDER) {
            drawsPerLayer.put(layer, new ArrayList<>());

            for (ChunkEntry chunk : chunks) {
                if (chunk.future.isDone()) {
                    BuiltChunk built = chunk.future.join();
                    built.uploadBuffer(layer, vertexSorter);
                    Buffers buffers = built.buffers.get(layer);
                    if (buffers != null) {
                        if (updated) {
                            built.resortTransparent(layer, vertexSorter);
                        }

                        GpuBuffer gpuBuffer;
                        VertexFormat.IndexType indexType;
                        if (buffers.getIndexBuffer() == null) {
                            maxIndicesRequired = Math.max(maxIndicesRequired, buffers.getIndexCount());

                            gpuBuffer = null;
                            indexType = null;
                        } else {
                            gpuBuffer = buffers.getIndexBuffer();
                            indexType = buffers.getIndexType();
                        }

                        int j = dynamicTransforms.size();
                        dynamicTransforms.add(
                                new DynamicUniforms.UniformValue(
                                        RenderSystem.getModelViewMatrix(),
                                        new Vector4f(1.0F),
                                        new Vector3f(
                                                chunk.pos.getStartX() - pos.x,
                                                -pos.y,
                                                chunk.pos.getStartZ() - pos.z
                                        ),
                                        new Matrix4f(),
                                        1.0F
                                )
                        );
                        drawsPerLayer.get(layer).add(
                                new RenderPass.RenderObject<>(
                                        0,
                                        buffers.getVertexBuffer(),
                                        gpuBuffer,
                                        indexType,
                                        0,
                                        buffers.getIndexCount(),
                                        (gpuBufferSlicesx, uniformUploader) -> uniformUploader.upload("DynamicTransforms", gpuBufferSlicesx[j])
                                )
                        );
                    }
                }
            }
        }

        RenderSystem.ShapeIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS);
        GpuBuffer gpuBuffer = maxIndicesRequired == 0 ? null : shapeIndexBuffer.getIndexBuffer(maxIndicesRequired);
        VertexFormat.IndexType indexType = maxIndicesRequired == 0 ? null : shapeIndexBuffer.getIndexType();

        GpuBufferSlice[] dynamicTransformsGpuBuffers = RenderSystem.getDynamicUniforms().writeAll(dynamicTransforms.toArray(DynamicUniforms.UniformValue[]::new));

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "SchematicPreview",
                        target.getColorAttachmentView(),
                        OptionalInt.empty(),
                        target.getDepthAttachmentView(),
                        OptionalDouble.empty()
                )) {
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindSampler("Sampler2", MinecraftClient.getInstance().gameRenderer.getLightmapTextureManager().getGlTextureView());

            for (BlockRenderLayer layer : BLOCK_RENDER_LAYER_ORDER) {
                List<RenderPass.RenderObject<GpuBufferSlice[]>> draws = drawsPerLayer.get(layer);
                if (!draws.isEmpty()) {
                    if (layer == BlockRenderLayer.TRANSLUCENT) {
                        draws = draws.reversed();
                    }

                    renderPass.setPipeline(layer.getPipeline());
                    renderPass.bindSampler("Sampler0", layer.getTextureView());
                    renderPass.drawMultipleIndexed(draws, gpuBuffer, indexType, List.of("DynamicTransforms"), dynamicTransformsGpuBuffers);
                }
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void renderBlockEntities(MatrixStack stack, float tickDelta) {
        if (getBuiltChunksCount() != chunks.size()) {
            return;
        }
        customVertexConsumerProvider.setFramebuffer(target);
        world.getBlockEntities().forEach((pos, blockEntitySupplier) -> {
            BlockEntity blockEntity = blockEntitySupplier.get();
            if (blockEntity != null) {
                BlockEntityRenderer renderer = blockEntityRenderManager.get(blockEntity);
                if (renderer != null) {
                    BlockEntityRenderState renderState = renderer.createRenderState();
                    stack.push();
                    stack.translate(pos.getX() - lastPos.x, pos.getY() - lastPos.y, pos.getZ() - lastPos.z);
                    try {
                        renderer.updateRenderState(blockEntity, renderState, tickDelta, cameraRenderState.pos, null);
                        renderer.render(renderState, stack, orderedRenderCommandQueue, cameraRenderState);
                    } catch (Exception e) {
                        SchematicPreview.LOGGER.debug("Exception while rendering preview block entity", e);
                    }
                    stack.pop();
                }
            }
        });
        renderDispatcher.render();
        customVertexConsumerProvider.draw();
    }

    public int getBuiltChunksCount() {
        return (int) chunks.stream().map(ChunkEntry::future).filter(CompletableFuture::isDone).count();
    }

    public boolean isBuildingTerrain() {
        return !chunks.isEmpty() && chunks.stream().map(ChunkEntry::future).noneMatch(CompletableFuture::isDone);
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
        renderDispatcher.close();
    }

    private record ChunkEntry(ChunkPos pos, CompletableFuture<@Nullable BuiltChunk> future) {}

    private record BuiltChunk(
            Map<BlockRenderLayer, BufferBuilder> builderCache,
            Map<BlockRenderLayer, BufferAllocator> allocatorCache,
            Map<BlockRenderLayer, BuiltBuffer> builtBuffers,
            Map<BlockRenderLayer, BuiltBuffer.SortState> sortStates,
            Map<BlockRenderLayer, Buffers> buffers
    ) implements AutoCloseable {

        private BuiltChunk() {
            this(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        }

        private void uploadBuffer(BlockRenderLayer layer, VertexSorter vertexSorter) {
            Buffers buffers = this.buffers.get(layer);
            if (buffers != null) {
                return;
            }
            BufferBuilder builder = builderCache.get(layer);
            if (builder == null || !builder.building) {
                return;
            }
            BuiltBuffer built = builder.endNullable();
            if (built == null) {
                return;
            }
            builtBuffers.put(layer, built);
            if (layer == BlockRenderLayer.TRANSLUCENT) {
                BuiltBuffer.SortState sortState = built.sortQuads(allocatorCache.get(layer), vertexSorter);
                if (sortState != null) {
                    sortStates.put(layer, sortState);
                }
            }
            GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "SchematicPreview vertex buffer",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    built.getBuffer()
            );
            GpuBuffer indexBuffer = built.getSortedBuffer() != null
                    ? RenderSystem.getDevice().createBuffer(
                    () -> "SchematicPreview index buffer",
                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
                    built.getSortedBuffer()
            )
                    : null;
            buffers = new Buffers(vertexBuffer, indexBuffer, built.getDrawParameters().indexCount(), built.getDrawParameters().indexType());
            this.buffers.put(layer, buffers);
        }

        private void resortTransparent(BlockRenderLayer layer, VertexSorter vertexSorter) {
            BuiltBuffer.SortState sortState = sortStates.get(layer);
            if (sortState != null) {
                Buffers buffers = this.buffers.get(layer);
                if (buffers != null) {
                    GpuBuffer indexBuffer = buffers.getIndexBuffer();
                    if (indexBuffer != null && !indexBuffer.isClosed()) {
                        BufferAllocator allocator = getAllocatorByLayer(layer);
                        try (BufferAllocator.CloseableBuffer result = sortState.sortAndStore(allocator, vertexSorter)) {
                            if (result != null) {
                                RenderSystem.getDevice().createCommandEncoder()
                                        .writeToBuffer(indexBuffer.slice(), result.getBuffer());
                            }
                        }
                    }
                }
            }
        }

        private BufferBuilder getBuilderByLayer(BlockRenderLayer layer) {
            return builderCache.computeIfAbsent(layer, l -> new BufferBuilder(getAllocatorByLayer(l), l.getPipeline().getVertexFormatMode(), l.getPipeline().getVertexFormat()));
        }

        private BufferAllocator getAllocatorByLayer(BlockRenderLayer layer) {
            return allocatorCache.computeIfAbsent(layer, l -> new BufferAllocator(1536));
        }

        @Override
        public void close() {
            allocatorCache.values().forEach(BufferAllocator::close);
            builtBuffers.values().forEach(BuiltBuffer::close);
            buffers.values().forEach(Buffers::close);
        }

    }

}
