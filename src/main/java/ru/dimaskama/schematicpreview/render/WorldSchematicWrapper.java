package ru.dimaskama.schematicpreview.render;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.FuelRegistry;
import net.minecraft.item.map.MapState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.recipe.BrewingRecipeRegistry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.storage.NbtReadView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.Pool;
import net.minecraft.util.math.*;
import net.minecraft.world.*;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.ChunkManager;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.ChunkLightingView;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.entity.EntityLookup;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.explosion.ExplosionBehavior;
import net.minecraft.world.tick.QueryableTickScheduler;
import net.minecraft.world.tick.TickManager;
import org.jetbrains.annotations.Nullable;
import ru.dimaskama.schematicpreview.SchematicPreview;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class WorldSchematicWrapper extends World implements ChunkProvider {

    private final LightingProvider lightingProvider = new FakeLightingProvider(this);
    private final Map<String, BlockPos> areaPoses = new HashMap<>();
    private final Biome biome;
    private LitematicaSchematic schematic;
    private Vec3i areasOrigin = new Vec3i(0, 0, 0);
    private Vec3i size = new Vec3i(0, 0, 0);
    private BlockState[] blocksData;
    private Map<BlockPos, Supplier<BlockEntity>> blockEntities;

    public WorldSchematicWrapper(MinecraftClient mc) {
        super(
                new ClientWorld.Properties(Difficulty.PEACEFUL, false, true),
                World.OVERWORLD,
                mc.world.getRegistryManager(),
                mc.world.getRegistryManager().getOrThrow(RegistryKeys.DIMENSION_TYPE).getOrThrow(DimensionTypes.OVERWORLD),
                true,
                false,
                0L,
                0
        );
        biome = getRegistryManager().getOrThrow(RegistryKeys.BIOME).get(BiomeKeys.PLAINS);
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
            BlockPos shift = areaPos.subtract(areasOrigin);
            schematic.getBlockEntityMapForRegion(region).forEach((relPos, nbtCompound) -> {
                BlockPos pos = relPos.add(shift);
                blockEntitiesBuilder.put(pos, Suppliers.memoize(() -> {
                    BlockEntity blockEntity = silentCreateTileFromNbt(pos, getBlockState(pos), nbtCompound, getRegistryManager());
                    if (blockEntity != null) {
                        blockEntity.setWorld(this);
                    }
                    return blockEntity;
                }));
            });
        });
        blockEntities = blockEntitiesBuilder.buildKeepingLast();
    }

    @Nullable
    private static BlockEntity silentCreateTileFromNbt(BlockPos pos, BlockState state, NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        String string = nbt.getString("id", "");
        Identifier identifier = Identifier.tryParse(string);
        if (identifier == null) {
            return null;
        } else {
            return Registries.BLOCK_ENTITY_TYPE.getOptionalValue(identifier).map((type) -> {
                try {
                    return type.instantiate(pos, state);
                } catch (Exception e) {
                    return null;
                }
            }).map((blockEntity) -> {
                try (ErrorReporter.Logging logging = new ErrorReporter.Logging(blockEntity.getReporterContext(), SchematicPreview.LOGGER)) {
                    blockEntity.read(NbtReadView.create(logging, registries, nbt));
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

    @Override
    public RegistryEntry<Biome> getGeneratorStoredBiome(int biomeX, int biomeY, int biomeZ) {
        return null;
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
    public void setSpawnPoint(WorldProperties.SpawnPoint spawnPoint) {

    }

    @Override
    public WorldProperties.SpawnPoint getSpawnPoint() {
        return null;
    }

    @Nullable
    @Override
    public Entity getEntityById(int id) {
        return null;
    }

    @Override
    public Collection<EnderDragonPart> getEnderDragonParts() {
        return List.of();
    }

    @Override
    public TickManager getTickManager() {
        return null;
    }

    @Nullable
    @Override
    public MapState getMapState(MapIdComponent id) {
        return null;
    }

    @Override
    public void setBlockBreakingInfo(int entityId, BlockPos pos, int progress) {

    }

    @Override
    public Scoreboard getScoreboard() {
        return null;
    }

    @Override
    public RecipeManager getRecipeManager() {
        return null;
    }

    @Override
    protected EntityLookup<Entity> getEntityLookup() {
        return null;
    }

    @Override
    public BrewingRecipeRegistry getBrewingRecipeRegistry() {
        return null;
    }

    @Override
    public FuelRegistry getFuelRegistry() {
        return null;
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
    public void playSound(@Nullable Entity source, double x, double y, double z, RegistryEntry<SoundEvent> sound, SoundCategory category, float volume, float pitch, long seed) {

    }

    @Override
    public void playSoundFromEntity(@Nullable Entity source, Entity entity, RegistryEntry<SoundEvent> sound, SoundCategory category, float volume, float pitch, long seed) {

    }

    @Override
    public void createExplosion(@Nullable Entity entity, @Nullable DamageSource damageSource, @Nullable ExplosionBehavior behavior, double x, double y, double z, float power, boolean createFire, ExplosionSourceType explosionSourceType, ParticleEffect smallParticle, ParticleEffect largeParticle, Pool<BlockParticleEffect> blockParticles, RegistryEntry<SoundEvent> soundEvent) {

    }

    @Override
    public String asString() {
        return getClass().getSimpleName();
    }

    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public int getBottomY() {
        return 0;
    }

    @Override
    public WorldChunk getChunk(int chunkX, int chunkZ) {
        return null;
    }

    @Override
    public FeatureSet getEnabledFeatures() {
        return FeatureFlags.DEFAULT_ENABLED_FEATURES;
    }

    @Override
    public void updateListeners(BlockPos pos, BlockState oldState, BlockState newState, int flags) {}

    @Override
    public BlockView getWorld() {
        return this;
    }

    @Override
    public QueryableTickScheduler<Block> getBlockTickScheduler() {
        return null;
    }

    @Override
    public QueryableTickScheduler<Fluid> getFluidTickScheduler() {
        return null;
    }

    @Override
    public ChunkManager getChunkManager() {
        return null;
    }

    @Override
    public void syncWorldEvent(@Nullable Entity source, int eventId, BlockPos pos, int data) {

    }

    @Override
    public void emitGameEvent(RegistryEntry<GameEvent> event, Vec3d emitterPos, GameEvent.Emitter emitter) {}

    @Override
    public List<? extends PlayerEntity> getPlayers() {
        return List.of();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return null;
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
