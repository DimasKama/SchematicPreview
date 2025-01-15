package ru.dimaskama.schematicpreview.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import fi.dy.masa.litematica.render.schematic.IBufferBuilderPatch;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
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
                                        BufferBuilder builder = chunk.getBuilderByLayer(layer);
                                        ((IBufferBuilderPatch) builder).litematica$setOffsetY((worldPos.getY() >> 4) << 4);
                                        blockRenderManager.renderFluid(worldPos, world, builder, state, fluid);
                                        ((IBufferBuilderPatch) builder).litematica$setOffsetY(0.0F);
                                    }
                                    if (renderBlock) {
                                        RenderLayer layer = RenderLayers.getBlockLayer(state);
                                        BufferBuilder builder = chunk.getBuilderByLayer(layer);
                                        blockRenderManager.getModelRenderer().renderSmooth(
                                                world,
                                                blockRenderManager.getModel(state),
                                                state,
                                                worldPos,
                                                matrices,
                                                builder,
                                                true,
                                                random,
                                                state.getRenderingSeed(worldPos),
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

    public void prepareRender(float x, float y, float z) {
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
            VertexBuffer buffer = chunk.buffers.get(layer);
            if (buffer == null) {
                return;
            }
            if (updated || uploaded) {
                chunk.resortTransparent(
                        layer,
                        VertexSorter.byDistance(pos.x - chunkEntry.pos.getStartX(), pos.y, pos.z - chunkEntry.pos.getStartZ())
                );
            }
            ShaderProgram shader = RenderSystem.getShader();
            shader.bind();
            if (shader.chunkOffset != null) {
                shader.chunkOffset.set(chunkEntry.pos.getStartX() -pos.x, -pos.y, chunkEntry.pos.getStartZ() - pos.z);
                shader.chunkOffset.upload();
            }
            buffer.bind();
            buffer.draw(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), shader);
        });
        layer.endDrawing();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void renderBlockEntities(MatrixStack stack, VertexConsumerProvider.Immediate vertexConsumers, float tickDelta) {
        if (getBuiltChunksCount() != chunks.size()) {
            return;
        }
        world.getBlockEntities().forEach((pos, blockEntitySupplier) -> {
            BlockEntity blockEntity = blockEntitySupplier.get();
            if (blockEntity != null) {
                BlockEntityRenderer renderer = blockEntityRenderDispatcher.get(blockEntity);
                if (renderer != null) {
                    stack.push();
                    stack.translate(pos.getX() - lastPos.x, pos.getY() - lastPos.y, pos.getZ() - lastPos.z);
                    try {
                        renderer.render(blockEntity, tickDelta, stack, vertexConsumers, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
                    } catch (Exception e) {
                        SchematicPreview.LOGGER.debug("Exception while rendering preview block entity", e);
                    }
                    stack.pop();
                }
            }
        });
        vertexConsumers.draw();
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
    }

    private record ChunkEntry(ChunkPos pos, CompletableFuture<@Nullable BuiltChunk> future) {}

    private record BuiltChunk(
            Map<RenderLayer, BufferBuilder> builderCache,
            Map<RenderLayer, BufferAllocator> allocatorCache,
            Map<RenderLayer, BuiltBuffer> builtBuffers,
            Map<RenderLayer, BuiltBuffer.SortState> sortStates,
            Map<RenderLayer, VertexBuffer> buffers
    ) implements AutoCloseable {

        private BuiltChunk() {
            this(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        }

        private boolean uploadBufferIfNot(RenderLayer layer) {
            VertexBuffer buffer = buffers.get(layer);
            if (buffer != null) {
                return false;
            }
            BufferBuilder builder = builderCache.get(layer);
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
            builtBuffers.put(layer, built);
            if (layer == RenderLayer.getTranslucent()) {
                BuiltBuffer.SortState sortState = built.sortQuads(allocatorCache.get(layer), VertexSorter.BY_DISTANCE);
                if (sortState != null) {
                    sortStates.put(layer, sortState);
                }
            }
            buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(built);
            buffers.put(layer, buffer);
            return true;
        }

        private void resortTransparent(RenderLayer layer, VertexSorter vertexSorter) {
            BuiltBuffer.SortState sortState = sortStates().get(layer);
            if (sortState != null) {
                VertexBuffer buffer = buffers().get(layer);
                if (buffer != null) {
                    BufferAllocator allocator = getAllocatorByLayer(layer);
                    BufferAllocator.CloseableBuffer result = sortState.sortAndStore(allocator, vertexSorter);
                    if (result != null) {
                        buffer.bind();
                        buffer.uploadIndexBuffer(result);
                    }
                }
            }
        }

        private BufferBuilder getBuilderByLayer(RenderLayer layer) {
            return builderCache.computeIfAbsent(layer, l -> new BufferBuilder(getAllocatorByLayer(l), l.getDrawMode(), l.getVertexFormat()));
        }

        private BufferAllocator getAllocatorByLayer(RenderLayer layer) {
            return allocatorCache.computeIfAbsent(layer, l -> new BufferAllocator(l.getExpectedBufferSize()));
        }

        @Override
        public void close() {
            allocatorCache.values().forEach(BufferAllocator::close);
            builtBuffers.values().forEach(BuiltBuffer::close);
            buffers.values().forEach(VertexBuffer::close);
        }

    }

}
