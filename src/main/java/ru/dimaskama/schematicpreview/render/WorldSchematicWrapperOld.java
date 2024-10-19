package ru.dimaskama.schematicpreview.render;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.*;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.BlockView;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.light.ChunkLightingView;
import net.minecraft.world.chunk.light.LightSourceView;
import net.minecraft.world.chunk.light.LightingProvider;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class WorldSchematicWrapperOld implements BlockRenderView, ChunkProvider {

    private final LightingProvider lightingProvider = new FakeLightingProvider(this);
    private final Map<String, BlockPos> areaPoses = new HashMap<>();
    private final DynamicRegistryManager registryManager;
    private final Biome biome;
    private LitematicaSchematic schematic;
    private Vec3i areasOrigin = new Vec3i(0, 0, 0);
    private Vec3i size = new Vec3i(0, 0, 0);
    private BlockState[] blocksData;
    private Map<BlockPos, Supplier<BlockEntity>> blockEntities;

    public WorldSchematicWrapperOld(MinecraftClient mc) {
        registryManager = mc.world.getRegistryManager();
        biome = registryManager.get(RegistryKeys.BIOME).get(BiomeKeys.PLAINS);
    }

    public void setSchematic(LitematicaSchematic schematic) {
        this.schematic = schematic;
        areaPoses.clear();
        schematic.getAreas().forEach((region, box) -> areaPoses.put(region, BlockPos.min(box.getPos1(), box.getPos2())));
        BlockPos.Mutable areasOrigin = new BlockPos.Mutable(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        BlockPos.Mutable areasEnd = new BlockPos.Mutable(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        areaPoses.forEach((region, areaPos) -> {
            areasOrigin.set(
                    Math.min(areasOrigin.getX(), areaPos.getX()),
                    Math.min(areasOrigin.getY(), areaPos.getY()),
                    Math.min(areasOrigin.getZ(), areaPos.getZ())
            );
            Vec3i areaSize = schematic.getSubRegionContainer(region).getSize();
            areasEnd.set(
                    Math.max(areasEnd.getX(), areaPos.getX() + areaSize.getX()),
                    Math.max(areasEnd.getY(), areaPos.getY() + areaSize.getY()),
                    Math.max(areasEnd.getZ(), areaPos.getZ() + areaSize.getZ())
            );
        });
        this.areasOrigin = areasOrigin;
        size = areasEnd.subtract(areasOrigin);
        blocksData = new BlockState[size.getX() * size.getY() * size.getZ()];
        ImmutableMap.Builder<BlockPos, Supplier<BlockEntity>> blockEntitiesBuilder = ImmutableMap.builder();
        areaPoses.forEach((region, areaPos) -> {
            BlockPos shift = areasOrigin.subtract(areaPos);
            schematic.getBlockEntityMapForRegion(region).forEach((relPos, nbtCompound) -> {
                BlockPos pos = relPos.add(shift);
                blockEntitiesBuilder.put(pos, Suppliers.memoize(() -> BlockEntity.createFromNbt(pos, getBlockState(pos), nbtCompound, registryManager)));
            });
        });
        blockEntities = blockEntitiesBuilder.buildKeepingLast();
    }

    public Vec3i getSize() {
        return size;
    }

    @Override
    public float getBrightness(Direction direction, boolean shaded) {
        return 1.0F;
    }

    @Override
    public LightingProvider getLightingProvider() {
        return lightingProvider;
    }

    @Override
    public int getColor(BlockPos pos, ColorResolver colorResolver) {
        return colorResolver.getColor(biome, pos.getX(), pos.getZ());
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        Supplier<BlockEntity> supplier = blockEntities.get(pos);
        return supplier != null ? supplier.get() : null;
    }

    public Map<BlockPos, Supplier<BlockEntity>> getBlockEntities() {
        return blockEntities;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (blocksData == null || isOutOfSize(pos.getX(), pos.getY(), pos.getZ(), size)) {
            return Blocks.AIR.getDefaultState();
        }
        int index = getBlocksDataIndex(pos);
        BlockState state = blocksData[index];
        if (state == null) {
            for (Map.Entry<String, BlockPos> areaEntry : areaPoses.entrySet()) {
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(areaEntry.getKey());
                int x = pos.getX() - areaEntry.getValue().getX() + areasOrigin.getX();
                int y = pos.getY() - areaEntry.getValue().getY() + areasOrigin.getY();
                int z = pos.getZ() - areaEntry.getValue().getZ() + areasOrigin.getZ();
                if (!isOutOfSize(x, y, z, container.getSize())) {
                    return blocksData[index] = container.get(x, y, z);
                }
            }
        }
        return state != null ? state : Blocks.AIR.getDefaultState();
    }

    private static boolean isOutOfSize(int x, int y, int z, Vec3i size) {
        return x < 0 || x >= size.getX() || y < 0 || y >= size.getY() || z < 0 || z >= size.getZ();
    }

    private int getBlocksDataIndex(BlockPos pos) {
        return pos.getY() * size.getX() * size.getZ() + pos.getZ() * size.getX() + pos.getX();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public int getBottomY() {
        return 0;
    }

    @Nullable
    @Override
    public LightSourceView getChunk(int chunkX, int chunkZ) {
        return null;
    }

    @Override
    public BlockView getWorld() {
        return this;
    }

    private static class FakeLightingProvider extends LightingProvider {

        private final ChunkLightingView FULL_BRIGHT_VIEW = new ChunkLightingView() {
            @Nullable
            @Override
            public ChunkNibbleArray getLightSection(ChunkSectionPos pos) {
                return null;
            }
            @Override
            public int getLightLevel(BlockPos pos) {
                return 15;
            }
            @Override
            public void checkBlock(BlockPos pos) {}
            @Override
            public boolean hasUpdates() {
                return false;
            }
            @Override
            public int doLightUpdates() {
                return 15;
            }
            @Override
            public void setSectionStatus(ChunkSectionPos pos, boolean notReady) {}
            @Override
            public void setColumnEnabled(ChunkPos pos, boolean retainData) {}
            @Override
            public void propagateLight(ChunkPos chunkPos) {}
        };

        public FakeLightingProvider(ChunkProvider chunkProvider) {
            super(chunkProvider, false, false);
        }

        @Override
        public ChunkLightingView get(LightType lightType) {
            return FULL_BRIGHT_VIEW;
        }

    }

}
