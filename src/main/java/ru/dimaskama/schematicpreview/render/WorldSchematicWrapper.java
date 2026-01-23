package ru.dimaskama.schematicpreview.render;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.*;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.TickContainerAccess;
import org.jetbrains.annotations.Nullable;
import ru.dimaskama.schematicpreview.SchematicPreview;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class WorldSchematicWrapper extends Level implements LightChunkGetter {

    private final LevelLightEngine lightingProvider = new FakeLightingProvider(this);
    private final FakeChunkManager fakeChunkManager = new FakeChunkManager();
    private final WorldBorder worldBorder = new WorldBorder();
    private final PalettedContainerFactory palettesFactory;
    private final Map<String, BlockPos> areaPoses = new HashMap<>();
    private final Biome biome;
    private LitematicaSchematic schematic;
    private Vec3i areasOrigin = new Vec3i(0, 0, 0);
    private Vec3i size = new Vec3i(0, 0, 0);
    private BlockState[] blocksData;
    private Map<BlockPos, Supplier<BlockEntity>> blockEntities;

    public WorldSchematicWrapper(Minecraft mc) {
        super(
                new ClientLevel.ClientLevelData(Difficulty.PEACEFUL, false, true),
                Level.OVERWORLD,
                mc.level.registryAccess(),
                mc.level.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD),
                true,
                false,
                0L,
                0
        );
        palettesFactory = mc.level.palettedContainerFactory();
        biome = registryAccess().lookupOrThrow(Registries.BIOME).getValue(Biomes.PLAINS);
    }

    public void setSchematic(LitematicaSchematic schematic) {
        this.schematic = schematic;
        areaPoses.clear();
        schematic.getAreas().forEach((region, box) -> areaPoses.put(region, BlockPos.min(box.getPos1(), box.getPos2())));
        BlockPos.MutableBlockPos areasOrigin = new BlockPos.MutableBlockPos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        BlockPos.MutableBlockPos areasEnd = new BlockPos.MutableBlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
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
            BlockPos shift = areaPos.subtract(areasOrigin);
            schematic.getBlockEntityMapForRegion(region).forEach((relPos, nbtCompound) -> {
                BlockPos pos = relPos.offset(shift);
                blockEntitiesBuilder.put(pos, Suppliers.memoize(() -> {
                    BlockEntity blockEntity = silentCreateTileFromNbt(pos, getBlockState(pos), nbtCompound, registryAccess());
                    if (blockEntity != null) {
                        blockEntity.setLevel(this);
                    }
                    return blockEntity;
                }));
            });
        });
        blockEntities = blockEntitiesBuilder.buildKeepingLast();
    }

    @Nullable
    private static BlockEntity silentCreateTileFromNbt(BlockPos pos, BlockState state, CompoundTag nbt, HolderLookup.Provider registries) {
        String string = nbt.getStringOr("id", "");
        Identifier identifier = Identifier.tryParse(string);
        if (identifier == null) {
            return null;
        } else {
            return BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(identifier).map((type) -> {
                try {
                    return type.create(pos, state);
                } catch (Exception e) {
                    return null;
                }
            }).map((blockEntity) -> {
                try (ProblemReporter.ScopedCollector logging = new ProblemReporter.ScopedCollector(blockEntity.problemPath(), SchematicPreview.LOGGER)) {
                    blockEntity.loadWithComponents(TagValueInput.create(logging, registries, nbt));
                    return blockEntity;
                } catch (Exception e) {
                    return null;
                }
            }).orElse(null);
        }
    }

    public Vec3i getSize() {
        return size;
    }

    @Override
    public float getShade(Direction direction, boolean shaded) {
        return 1.0F;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return lightingProvider;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        return colorResolver.getColor(biome, pos.getX(), pos.getZ());
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int biomeX, int biomeY, int biomeZ) {
        return this.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
    }

    @Override
    public int getSeaLevel() {
        return 0;
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        Supplier<BlockEntity> supplier = blockEntities.get(pos);
        return supplier != null ? supplier.get() : null;
    }

    @Override
    public void setRespawnData(LevelData.RespawnData spawnPoint) {

    }

    @Override
    public LevelData.RespawnData getRespawnData() {
        return null;
    }

    @Nullable
    @Override
    public Entity getEntity(int id) {
        return null;
    }

    @Override
    public Collection<EnderDragonPart> dragonParts() {
        return List.of();
    }

    @Override
    public TickRateManager tickRateManager() {
        return null;
    }

    @Nullable
    @Override
    public MapItemSavedData getMapData(MapId id) {
        return null;
    }

    @Override
    public void destroyBlockProgress(int entityId, BlockPos pos, int progress) {

    }

    @Override
    public Scoreboard getScoreboard() {
        return null;
    }

    @Override
    public RecipeAccess recipeAccess() {
        return null;
    }

    @Override
    protected LevelEntityGetter<Entity> getEntities() {
        return null;
    }

    @Override
    public EnvironmentAttributeSystem environmentAttributes() {
        return EnvironmentAttributeSystem.builder().addDefaultLayers(this).build();
    }

    @Override
    public PotionBrewing potionBrewing() {
        return null;
    }

    @Override
    public FuelValues fuelValues() {
        return null;
    }

    public Map<BlockPos, Supplier<BlockEntity>> getBlockEntities() {
        return blockEntities;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (blocksData == null || isOutOfSize(pos.getX(), pos.getY(), pos.getZ(), size)) {
            return Blocks.AIR.defaultBlockState();
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
        return state != null ? state : Blocks.AIR.defaultBlockState();
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
    public void playSeededSound(@Nullable Entity source, double x, double y, double z, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed) {

    }

    @Override
    public void playSeededSound(@Nullable Entity source, Entity entity, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed) {

    }

    @Override
    public void explode(@Nullable Entity entity, @Nullable DamageSource damageSource, @Nullable ExplosionDamageCalculator behavior, double x, double y, double z, float power, boolean createFire, ExplosionInteraction explosionSourceType, ParticleOptions smallParticle, ParticleOptions largeParticle, WeightedList<ExplosionParticleInfo> blockParticles, Holder<SoundEvent> soundEvent) {

    }

    @Override
    public String gatherChunkSourceStats() {
        return getClass().getSimpleName();
    }

    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public int getMinY() {
        return 0;
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return FeatureFlags.DEFAULT_FLAGS;
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {}

    @Override
    public @org.jspecify.annotations.Nullable LightChunk getChunkForLighting(int i, int j) {
        return null;
    }

    @Override
    public BlockGetter getLevel() {
        return this;
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public ChunkSource getChunkSource() {
        return fakeChunkManager;
    }

    @Override
    public void levelEvent(@Nullable Entity source, int eventId, BlockPos pos, int data) {

    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 emitterPos, GameEvent.Context emitter) {}

    @Override
    public List<? extends Player> players() {
        return List.of();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return worldBorder;
    }

    private static class FakeLightingProvider extends LevelLightEngine {

        private final LayerLightEventListener FULL_BRIGHT_VIEW = new LayerLightEventListener() {
            @Nullable
            @Override
            public DataLayer getDataLayerData(SectionPos pos) {
                return null;
            }
            @Override
            public int getLightValue(BlockPos pos) {
                return 15;
            }
            @Override
            public void checkBlock(BlockPos pos) {}
            @Override
            public boolean hasLightWork() {
                return false;
            }
            @Override
            public int runLightUpdates() {
                return 15;
            }
            @Override
            public void updateSectionStatus(SectionPos pos, boolean notReady) {}
            @Override
            public void setLightEnabled(ChunkPos pos, boolean retainData) {}
            @Override
            public void propagateLightSources(ChunkPos chunkPos) {}
        };

        public FakeLightingProvider(LightChunkGetter chunkProvider) {
            super(chunkProvider, false, false);
        }

        @Override
        public LayerLightEventListener getLayerListener(LightLayer lightType) {
            return FULL_BRIGHT_VIEW;
        }

    }

    private class FakeChunkManager extends ChunkSource {

        private final Map<ChunkPos, FakeChunk> fakeChunks = new HashMap<>();

        @Nullable
        @Override
        public ChunkAccess getChunk(int x, int z, ChunkStatus leastStatus, boolean create) {
            ChunkPos chunkPos = new ChunkPos(x, z);
            return fakeChunks.computeIfAbsent(chunkPos, FakeChunk::new);
        }

        @Override
        public void tick(BooleanSupplier shouldKeepTicking, boolean tickChunks) {

        }

        @Override
        public String gatherStats() {
            return "FakeChunkManager";
        }

        @Override
        public int getLoadedChunksCount() {
            return 0;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return lightingProvider;
        }

        @Override
        public BlockGetter getLevel() {
            return WorldSchematicWrapper.this;
        }

    }

    private class FakeChunk extends ChunkAccess {

        private final BlockPos chunkOrigin;

        public FakeChunk(ChunkPos pos) {
            super(pos, new UpgradeData(new CompoundTag(), WorldSchematicWrapper.this), WorldSchematicWrapper.this, palettesFactory, 0, null, null);
            chunkOrigin = pos.getWorldPosition();
        }

        @Nullable
        @Override
        public BlockState setBlockState(BlockPos pos, BlockState state, int flags) {
            return null;
        }

        @Override
        public void setBlockEntity(BlockEntity blockEntity) {

        }

        @Override
        public void addEntity(Entity entity) {

        }

        @Override
        public ChunkStatus getPersistedStatus() {
            return ChunkStatus.FULL;
        }

        @Override
        public void removeBlockEntity(BlockPos pos) {

        }

        @Nullable
        @Override
        public CompoundTag getBlockEntityNbtForSaving(BlockPos pos, HolderLookup.Provider registries) {
            BlockEntity blockEntity = getBlockEntity(pos);
            if (blockEntity != null) {
                CompoundTag nbtCompound = blockEntity.saveWithFullMetadata(registries);
                nbtCompound.putBoolean("keepPacked", false);
                return nbtCompound;
            }
            return null;
        }

        @Override
        public TickContainerAccess<Block> getBlockTicks() {
            return BlackholeTickAccess.emptyContainer();
        }

        @Override
        public TickContainerAccess<Fluid> getFluidTicks() {
            return BlackholeTickAccess.emptyContainer();
        }

        @Override
        public PackedTicks getTicksForSerialization(long time) {
            return new PackedTicks(List.of(), List.of());
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return WorldSchematicWrapper.this.getBlockEntity(chunkOrigin.offset(pos));
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return WorldSchematicWrapper.this.getBlockState(chunkOrigin.offset(pos));
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return WorldSchematicWrapper.this.getFluidState(chunkOrigin.offset(pos));
        }

    }

}
