package ru.dimaskama.schematicpreview.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import fi.dy.masa.litematica.render.schematic.BlockModelRendererSchematic;
import fi.dy.masa.litematica.render.schematic.IBlockOutputSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import ru.dimaskama.schematicpreview.SchematicPreview;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public class SchematicPreviewRenderer implements AutoCloseable {

    private final WorldSchematicWrapper world;
    private final FluidRenderer fluidRenderer;
    private final ModelManager modelManager;
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
        modelManager = mc.getModelManager();
        fluidRenderer = new FluidRenderer(modelManager.getFluidStateModelSet());
        blockEntityRenderManager = mc.getBlockEntityRenderDispatcher();
        orderedRenderCommandQueue = new SubmitNodeStorage();
        customVertexConsumerProvider = new CustomVertexConsumerProvider(mc.renderBuffers().bufferSource());
        renderDispatcher = new FeatureRenderDispatcher(
                orderedRenderCommandQueue,
                modelManager,
                customVertexConsumerProvider,
                mc.getAtlasManager(),
                new DummyOutlineVertexConsumerProvider(),
                new DummyVertexConsumerProvider(),
                mc.font,
                new GameRenderState()
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
                    BlockModelRendererSchematic blockModelRenderer = new BlockModelRendererSchematic();
                    blockModelRenderer.enableCache();
                    BlockPos.MutableBlockPos worldPos = new BlockPos.MutableBlockPos();
                    int chunkStartX = chunkPos.getMinBlockX();
                    int chunkStartZ = chunkPos.getMinBlockZ();
                    int chunkEndX = chunkStartX + 16;
                    int chunkEndZ = chunkStartZ + 16;

                    IBlockOutputSchematic blockOutput = (bx, by, bz, quad, inst) -> {
                        ChunkSectionLayer layer = quad.materialInfo().layer();
                        BufferBuilder builder = chunk.getBuilderByLayer(layer);
                        builder.putBlockBakedQuad(bx, by, bz, quad, inst);
                    };

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
                                Vec3 offset = new Vec3(x & 0xF, y, z & 0xF);

                                if (renderFluid) {
                                    FluidRenderer.Output fluidOutput = chunk::getBuilderByLayer;
                                    fluidRenderer.tesselate(world, worldPos, fluidOutput, state, fluid);
                                }
                                if (renderBlock) {
                                    BlockStateModel model = modelManager.getBlockStateModelSet().get(state);
                                    blockModelRenderer.tessellateBlock(
                                            world,
                                            state,
                                            worldPos,
                                            offset,
                                            model,
                                            state.getSeed(worldPos),
                                            blockOutput
                                    );
                                }
                            }
                        }
                    }
                    blockModelRenderer.disableCache();
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
            ChunkPos newChunkPos = new ChunkPos(Mth.floor(cameraRenderState.pos.x) >> 4, Mth.floor(cameraRenderState.pos.z) >> 4);
            if (!this.chunkPos.equals(newChunkPos)) {
                this.chunkPos = newChunkPos;
                chunks.sort(Comparator.comparingInt(chunk -> -(Math.abs(chunk.pos().x() - newChunkPos.x()) + Math.abs(chunk.pos().z() - newChunkPos.z()))));
            }
        }
    }

    private ChunkSectionsToRender prepareChunks() {
        Iterator<ChunkEntry> chunkIterator = chunks.iterator();
        EnumMap<ChunkSectionLayer, Int2ObjectOpenHashMap<List<com.mojang.blaze3d.systems.RenderPass.Draw<GpuBufferSlice[]>>>> enumMap = new EnumMap<>(ChunkSectionLayer.class);
        int maxIndices = 0;

        for (ChunkSectionLayer chunkSectionLayer : ChunkSectionLayer.values()) {
            enumMap.put(chunkSectionLayer, new Int2ObjectOpenHashMap<>());
        }

        List<DynamicUniforms.ChunkSectionInfo> chunkSectionInfos = new ArrayList<>();
        GpuTextureView blockAtlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        int atlasWidth = blockAtlas.getWidth(0);
        int atlasHeight = blockAtlas.getHeight(0);

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
            int uniformIndex = -1;

            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                built.uploadBuffer(layer, vertexSorter);
                if (updated) {
                    built.resortTransparent(layer, vertexSorter);
                }
                SectionBuffers sectionBuffers = built.buffers.get(layer);
                if (sectionBuffers == null) {
                    continue;
                }
                if (uniformIndex == -1) {
                    uniformIndex = chunkSectionInfos.size();
                    chunkSectionInfos.add(new DynamicUniforms.ChunkSectionInfo(
                            new Matrix4f(RenderSystem.getModelViewMatrix()),
                            chunk.pos().getMinBlockX(),
                            0,
                            chunk.pos().getMinBlockZ(),
                            1.0F,
                            atlasWidth,
                            atlasHeight
                    ));
                }

                GpuBuffer indexBuffer;
                VertexFormat.IndexType indexType;
                if (sectionBuffers.indexBuffer() == null) {
                    if (sectionBuffers.indexCount() > maxIndices) {
                        maxIndices = sectionBuffers.indexCount();
                    }
                    indexBuffer = null;
                    indexType = null;
                } else {
                    indexBuffer = sectionBuffers.indexBuffer();
                    indexType = sectionBuffers.indexType();
                }

                int finalUniformIndex = uniformIndex;
                enumMap.get(layer)
                        .computeIfAbsent(0, ignored -> new ArrayList<>())
                        .add(new com.mojang.blaze3d.systems.RenderPass.Draw<>(
                                0,
                                sectionBuffers.vertexBuffer(),
                                indexBuffer,
                                indexType,
                                0,
                                sectionBuffers.indexCount(),
                                0,
                                (gpuBufferSlices, uniformUploader) -> uniformUploader.upload("ChunkSection", gpuBufferSlices[finalUniformIndex])
                        ));
            }
        }

        GpuBufferSlice[] gpuBufferSlices = RenderSystem.getDynamicUniforms().writeChunkSections(
                chunkSectionInfos.toArray(new DynamicUniforms.ChunkSectionInfo[0])
        );
        return new ChunkSectionsToRender(blockAtlas, enumMap, maxIndices, gpuBufferSlices);
    }

    public void renderBlocks() {
        if (target == null) {
            return;
        }
        ChunkSectionsToRender chunkSectionsToRender = prepareChunks();
        renderChunkSectionsLayer(chunkSectionsToRender, ChunkSectionLayerGroup.OPAQUE);
        renderChunkSectionsLayer(chunkSectionsToRender, ChunkSectionLayerGroup.TRANSLUCENT);
    }

    private void renderChunkSectionsLayer(ChunkSectionsToRender chunks, ChunkSectionLayerGroup group) {
        RenderSystem.AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer sharedIndexBuffer = chunks.maxIndicesRequired() == 0 ? null : autoStorageIndexBuffer.getBuffer(chunks.maxIndicesRequired());
        VertexFormat.IndexType sharedIndexType = chunks.maxIndicesRequired() == 0 ? null : autoStorageIndexBuffer.type();
        Minecraft minecraft = Minecraft.getInstance();
        GpuSampler blockSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

        try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "SchematicPreview " + group.label(),
                target.getColorTextureView(),
                OptionalInt.empty(),
                target.getDepthTextureView(),
                OptionalDouble.empty()
        )) {
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindTexture(
                    "Sampler2",
                    minecraft.gameRenderer.lightmap(),
                    blockSampler
            );

            for (ChunkSectionLayer layer : group.layers()) {
                Int2ObjectOpenHashMap<List<RenderPass.Draw<GpuBufferSlice[]>>> drawGroups = chunks.drawGroupsPerLayer().get(layer);
                if (drawGroups == null) {
                    continue;
                }
                renderPass.setPipeline(layer.pipeline());
                renderPass.bindTexture("Sampler0", chunks.textureView(), blockSampler);
                for (List<RenderPass.Draw<GpuBufferSlice[]>> draws : drawGroups.values()) {
                    if (draws.isEmpty()) {
                        continue;
                    }
                    List<RenderPass.Draw<GpuBufferSlice[]>> drawList = layer == ChunkSectionLayer.TRANSLUCENT ? draws.reversed() : draws;
                    renderPass.drawMultipleIndexed(drawList, sharedIndexBuffer, sharedIndexType, List.of("ChunkSection"), chunks.chunkSectionInfos());
                }
            }
        }
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

    private record SectionBuffers(
            GpuBuffer vertexBuffer,
            GpuBuffer indexBuffer,
            int indexCount,
            VertexFormat.IndexType indexType
    ) implements AutoCloseable {

        @Override
        public void close() {
            vertexBuffer.close();
            if (indexBuffer != null) {
                indexBuffer.close();
            }
        }
    }

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
            if (buffers.containsKey(layer)) {
                return;
            }
            BufferBuilder builder = builderCache.get(layer);
            if (builder == null) {
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
            buffers.put(layer, new SectionBuffers(vertexBuffer, indexBuffer, built.drawState().indexCount(), built.drawState().indexType()));
        }

        private void resortTransparent(ChunkSectionLayer layer, VertexSorting vertexSorter) {
            MeshData.SortState sortState = sortStates.get(layer);
            if (sortState == null) {
                return;
            }
            SectionBuffers sectionBuffers = buffers.get(layer);
            if (sectionBuffers == null || sectionBuffers.indexBuffer() == null || sectionBuffers.indexBuffer().isClosed()) {
                return;
            }
            ByteBufferBuilder allocator = getAllocatorByLayer(layer);
            try (ByteBufferBuilder.Result result = sortState.buildSortedIndexBuffer(allocator, vertexSorter)) {
                if (result != null) {
                    RenderSystem.getDevice().createCommandEncoder()
                            .writeToBuffer(sectionBuffers.indexBuffer().slice(), result.byteBuffer());
                }
            }
        }

        private BufferBuilder getBuilderByLayer(ChunkSectionLayer layer) {
            return builderCache.computeIfAbsent(layer, ignored -> new BufferBuilder(
                    getAllocatorByLayer(layer),
                    layer.pipeline().getVertexFormatMode(),
                    layer.pipeline().getVertexFormat()
            ));
        }

        private ByteBufferBuilder getAllocatorByLayer(ChunkSectionLayer layer) {
            return allocatorCache.computeIfAbsent(layer, ignored -> new ByteBufferBuilder(1536));
        }

        @Override
        public void close() {
            allocatorCache.values().forEach(ByteBufferBuilder::close);
            builtBuffers.values().forEach(MeshData::close);
            buffers.values().forEach(SectionBuffers::close);
        }
    }

}
