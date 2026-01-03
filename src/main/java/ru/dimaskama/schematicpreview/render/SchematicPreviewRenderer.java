package ru.dimaskama.schematicpreview.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import fi.dy.masa.litematica.render.schematic.IBufferBuilderPatch;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import ru.dimaskama.schematicpreview.SchematicPreview;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SchematicPreviewRenderer implements AutoCloseable {

    private final WorldSchematicWrapper world;
    private final BlockRenderDispatcher blockRenderManager;
    private final BlockEntityRenderDispatcher blockEntityRenderManager;
    private final SubmitNodeStorage orderedRenderCommandQueue;
    private final CustomVertexConsumerProvider customVertexConsumerProvider;
    private final FeatureRenderDispatcher renderDispatcher;
    private final List<ChunkEntry> chunks = new ArrayList<>();
    private final Vector3f pos = new Vector3f(Float.MIN_VALUE, Float.MIN_VALUE, Float.MIN_VALUE);
    private ChunkPos chunkPos = new ChunkPos(Integer.MIN_VALUE, Integer.MIN_VALUE);
    private CameraRenderState cameraRenderState;
    private boolean updated;
    private boolean canceled;
    private RenderTarget target;

    public SchematicPreviewRenderer(Minecraft mc) {
        world = new WorldSchematicWrapper(mc);
        blockRenderManager = mc.getBlockRenderer();
        blockEntityRenderManager = mc.getBlockEntityRenderDispatcher();
        orderedRenderCommandQueue = new SubmitNodeStorage();
        customVertexConsumerProvider = new CustomVertexConsumerProvider(mc.renderBuffers().bufferSource());
        renderDispatcher = new FeatureRenderDispatcher(
                orderedRenderCommandQueue,
                blockRenderManager,
                customVertexConsumerProvider,
                mc.getAtlasManager(),
                new DummyOutlineVertexConsumerProvider(),
                new DummyVertexConsumerProvider(),
                mc.font
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
                    RandomSource random = new SingleThreadedRandomSource(0);
                    BuiltChunk chunk = new BuiltChunk();
                    PoseStack matrices = new PoseStack();
                    BlockPos.MutableBlockPos worldPos = new BlockPos.MutableBlockPos();
                    int chunkStartX = chunkPos.getMinBlockX();
                    int chunkStartZ = chunkPos.getMinBlockZ();
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
                                boolean renderBlock = state.getRenderShape() == RenderShape.MODEL;
                                if (renderFluid || renderBlock) {
                                    matrices.pushPose();
                                    matrices.translate(worldPos.getX() & 0xF, worldPos.getY(), worldPos.getZ() & 0xF);
                                    if (renderFluid) {
                                        ChunkSectionLayer layer = ItemBlockRenderTypes.getRenderLayer(fluid);
                                        BufferBuilder builder = chunk.getBuilderByLayer(layer);
                                        ((IBufferBuilderPatch) builder).litematica$setOffsetY((worldPos.getY() >> 4) << 4);
                                        blockRenderManager.renderLiquid(worldPos, world, builder, state, fluid);
                                        ((IBufferBuilderPatch) builder).litematica$setOffsetY(0.0F);
                                    }
                                    if (renderBlock) {
                                        ChunkSectionLayer layer = ItemBlockRenderTypes.getChunkRenderType(state);
                                        BufferBuilder builder = chunk.getBuilderByLayer(layer);
                                        blockRenderManager.getModelRenderer().tesselateWithAO(
                                                world,
                                                blockRenderManager.getBlockModel(state).collectParts(random),
                                                state,
                                                worldPos,
                                                matrices,
                                                builder,
                                                true,
                                                OverlayTexture.NO_OVERLAY
                                        );
                                    }
                                    matrices.popPose();
                                }
                            }
                        }
                    }
                    return chunk;
                })));
            }
        }
    }

    public void prepareRender(CameraRenderState cameraRenderState, RenderTarget target) {
        this.cameraRenderState = cameraRenderState;
        this.target = target;
        updated = !pos.equals((float) cameraRenderState.pos.x, (float) cameraRenderState.pos.y, (float) cameraRenderState.pos.z);
        if (updated) {
            pos.set(cameraRenderState.pos.x, cameraRenderState.pos.y, cameraRenderState.pos.z);
            ChunkPos chunkPos = new ChunkPos(Mth.floor(cameraRenderState.pos.x) >> 4, Mth.floor(cameraRenderState.pos.z) >> 4);
            if (!this.chunkPos.equals(chunkPos)) {
                this.chunkPos = chunkPos;
                chunks.sort(Comparator.comparingInt(chunk -> -(Math.abs(chunk.pos.x - chunkPos.x) + Math.abs(chunk.pos.z - chunkPos.z))));
            }
        }
    }

    @SuppressWarnings("deprecation")
    private ChunkSectionsToRender prepareChunks() {
        Iterator<ChunkEntry> chunkIterator = chunks.iterator();
        EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> enumMap = new EnumMap<>(ChunkSectionLayer.class);
        int i = 0;

        for(ChunkSectionLayer chunkSectionLayer : ChunkSectionLayer.values()) {
            enumMap.put(chunkSectionLayer, new ArrayList<>());
        }

        List<DynamicUniforms.ChunkSectionInfo> list = new ArrayList<>();
        GpuTextureView gpuTextureView = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        int j = gpuTextureView.getWidth(0);
        int k = gpuTextureView.getHeight(0);

        while (chunkIterator.hasNext()) {
            ChunkEntry chunk = chunkIterator.next();
            if (!chunk.future.isDone()) {
                continue;
            }
            BuiltChunk built = chunk.future.join();
            if (built == null) {
                continue;
            }
            VertexSorting vertexSorter = VertexSorting.byDistance(pos.x - chunk.pos().getMinBlockX(), pos.y, pos.z - chunk.pos().getMinBlockZ());

            int m = -1;

            for (ChunkSectionLayer chunkSectionLayer2 : ChunkSectionLayer.values()) {
                built.uploadBuffer(chunkSectionLayer2, vertexSorter);
                if (updated) {
                    built.resortTransparent(chunkSectionLayer2, vertexSorter);
                }
                SectionBuffers sectionBuffers = built.buffers.get(chunkSectionLayer2);
                if (sectionBuffers != null) {
                    if (m == -1) {
                        m = list.size();
                        list.add(new DynamicUniforms.ChunkSectionInfo(new Matrix4f(RenderSystem.getModelViewMatrix()), chunk.pos().getMinBlockX(), 0, chunk.pos().getMinBlockZ(), 1.0F, j, k));
                    }

                    GpuBuffer gpuBuffer;
                    VertexFormat.IndexType indexType;
                    if (sectionBuffers.getIndexBuffer() == null) {
                        if (sectionBuffers.getIndexCount() > i) {
                            i = sectionBuffers.getIndexCount();
                        }

                        gpuBuffer = null;
                        indexType = null;
                    } else {
                        gpuBuffer = sectionBuffers.getIndexBuffer();
                        indexType = sectionBuffers.getIndexType();
                    }

                    int finalM = m;
                    enumMap.get(chunkSectionLayer2).add(new RenderPass.Draw<>(0, sectionBuffers.getVertexBuffer(), gpuBuffer, indexType, 0, sectionBuffers.getIndexCount(), (gpuBufferSlicesx, uniformUploader) -> uniformUploader.upload("ChunkSection", gpuBufferSlicesx[finalM])));
                }
            }
        }

        GpuBufferSlice[] gpuBufferSlices = RenderSystem.getDynamicUniforms().writeChunkSections(list.toArray(new DynamicUniforms.ChunkSectionInfo[0]));
        return new ChunkSectionsToRender(gpuTextureView, enumMap, i, gpuBufferSlices);
    }

    private void renderChunkSectionsLayer(ChunkSectionsToRender chunks, ChunkSectionLayerGroup group) {
        RenderSystem.AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer gpuBuffer = chunks.maxIndicesRequired() == 0 ? null : autoStorageIndexBuffer.getBuffer(chunks.maxIndicesRequired());
        VertexFormat.IndexType indexType = chunks.maxIndicesRequired() == 0 ? null : autoStorageIndexBuffer.type();
        ChunkSectionLayer[] chunkSectionLayers = group.layers();
        Minecraft minecraft = Minecraft.getInstance();

        try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Section layers for " + group.label(), target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture("Sampler2", minecraft.gameRenderer.lightTexture().getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));

            for(ChunkSectionLayer chunkSectionLayer : chunkSectionLayers) {
                List<RenderPass.Draw<GpuBufferSlice[]>> list = chunks.drawsPerLayer().get(chunkSectionLayer);
                if (!list.isEmpty()) {
                    if (chunkSectionLayer == ChunkSectionLayer.TRANSLUCENT) {
                        list = list.reversed();
                    }

                    renderPass.setPipeline(chunkSectionLayer.pipeline());
                    renderPass.bindTexture("Sampler0", chunks.textureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                    renderPass.drawMultipleIndexed(list, gpuBuffer, indexType, List.of("ChunkSection"), chunks.chunkSectionInfos());
                }
            }
        }
    }

    public void renderBlocks() {
        ChunkSectionsToRender chunkSectionsToRender = prepareChunks();
        renderChunkSectionsLayer(chunkSectionsToRender, ChunkSectionLayerGroup.OPAQUE);
        renderChunkSectionsLayer(chunkSectionsToRender, ChunkSectionLayerGroup.TRANSLUCENT);
        renderChunkSectionsLayer(chunkSectionsToRender, ChunkSectionLayerGroup.TRIPWIRE);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void renderBlockEntities(PoseStack stack, float tickDelta) {
        if (getBuiltChunksCount() != chunks.size()) {
            return;
        }
        customVertexConsumerProvider.setFramebuffer(target);
        world.getBlockEntities().forEach((pos, blockEntitySupplier) -> {
            BlockEntity blockEntity = blockEntitySupplier.get();
            if (blockEntity != null) {
                BlockEntityRenderer renderer = blockEntityRenderManager.getRenderer(blockEntity);
                if (renderer != null) {
                    BlockEntityRenderState renderState = renderer.createRenderState();
                    stack.pushPose();
                    stack.translate(pos.getX() - this.pos.x, pos.getY() - this.pos.y, pos.getZ() - this.pos.z);
                    try {
                        renderer.extractRenderState(blockEntity, renderState, tickDelta, cameraRenderState.pos, null);
                        renderer.submit(renderState, stack, orderedRenderCommandQueue, cameraRenderState);
                    } catch (Exception e) {
                        SchematicPreview.LOGGER.debug("Exception while rendering preview block entity", e);
                    }
                    stack.popPose();
                }
            }
        });
        renderDispatcher.renderAllFeatures();
        customVertexConsumerProvider.endBatch();
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
        pos.set(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        chunkPos = new ChunkPos(Integer.MIN_VALUE, Integer.MIN_VALUE);
        renderDispatcher.close();
    }

    private record ChunkEntry(ChunkPos pos, CompletableFuture<@Nullable BuiltChunk> future) {}

    private record BuiltChunk(
            Map<ChunkSectionLayer, BufferBuilder> builderCache,
            Map<ChunkSectionLayer, ByteBufferBuilder> allocatorCache,
            Map<ChunkSectionLayer, MeshData> builtBuffers,
            Map<ChunkSectionLayer, MeshData.SortState> sortStates,
            Map<ChunkSectionLayer, SectionBuffers> buffers
    ) implements AutoCloseable {

        private BuiltChunk() {
            this(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        }

        private void uploadBuffer(ChunkSectionLayer layer, VertexSorting vertexSorter) {
            SectionBuffers buffers = this.buffers.get(layer);
            if (buffers != null) {
                return;
            }
            BufferBuilder builder = builderCache.get(layer);
            if (builder == null || !builder.building) {
                return;
            }
            MeshData built = builder.build();
            if (built == null) {
                return;
            }
            builtBuffers.put(layer, built);
            if (layer == ChunkSectionLayer.TRANSLUCENT) {
                MeshData.SortState sortState = built.sortQuads(allocatorCache.get(layer), vertexSorter);
                if (sortState != null) {
                    sortStates.put(layer, sortState);
                }
            }
            GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "SchematicPreview vertex buffer",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    built.vertexBuffer()
            );
            GpuBuffer indexBuffer = built.indexBuffer() != null
                    ? RenderSystem.getDevice().createBuffer(
                    () -> "SchematicPreview index buffer",
                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
                    built.indexBuffer()
            )
                    : null;
            buffers = new SectionBuffers(vertexBuffer, indexBuffer, built.drawState().indexCount(), built.drawState().indexType());
            this.buffers.put(layer, buffers);
        }

        private void resortTransparent(ChunkSectionLayer layer, VertexSorting vertexSorter) {
            MeshData.SortState sortState = sortStates.get(layer);
            if (sortState != null) {
                SectionBuffers buffers = this.buffers.get(layer);
                if (buffers != null) {
                    GpuBuffer indexBuffer = buffers.getIndexBuffer();
                    if (indexBuffer != null && !indexBuffer.isClosed()) {
                        ByteBufferBuilder allocator = getAllocatorByLayer(layer);
                        try (ByteBufferBuilder.Result result = sortState.buildSortedIndexBuffer(allocator, vertexSorter)) {
                            if (result != null) {
                                RenderSystem.getDevice().createCommandEncoder()
                                        .writeToBuffer(indexBuffer.slice(), result.byteBuffer());
                            }
                        }
                    }
                }
            }
        }

        private BufferBuilder getBuilderByLayer(ChunkSectionLayer layer) {
            return builderCache.computeIfAbsent(layer, l -> new BufferBuilder(getAllocatorByLayer(l), l.pipeline().getVertexFormatMode(), l.pipeline().getVertexFormat()));
        }

        private ByteBufferBuilder getAllocatorByLayer(ChunkSectionLayer layer) {
            return allocatorCache.computeIfAbsent(layer, l -> new ByteBufferBuilder(1536));
        }

        @Override
        public void close() {
            allocatorCache.values().forEach(ByteBufferBuilder::close);
            builtBuffers.values().forEach(MeshData::close);
            buffers.values().forEach(SectionBuffers::close);
        }

    }

}
