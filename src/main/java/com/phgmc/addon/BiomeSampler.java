package com.phgmc.addon;

import com.mojang.logging.LogUtils;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryLoader;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.DefaultResourcePack;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.biome.source.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.noise.NoiseConfig;
import meteordevelopment.meteorclient.utils.world.Dimension;
import org.slf4j.Logger;

import java.util.List;

/**
 * Loads Minecraft's vanilla data pack off the classpath and builds a
 * {@link NoiseConfig} + {@link MultiNoiseBiomeSource} that can answer
 * "what biome is at block (x, y, z) for seed S?" entirely offline.
 *
 * <p>Used by {@code StructureFinder} to discard false-positive candidate
 * chunks whose biome does not match the structure's allowed biome set.</p>
 */
public final class BiomeSampler {

    private static final Logger LOG = LogUtils.getLogger();

    /** Lazily initialised shared registries. {@code null} until first call. */
    private static volatile DynamicRegistryManager.Immutable REGISTRIES;
    private static final Object LOCK = new Object();

    private static volatile long cachedSeed = 0;
    private static volatile boolean cachedSeedSet = false;
    private static volatile NoiseConfig cachedOverworldConfig;
    private static volatile NoiseConfig cachedNetherConfig;
    private static volatile MultiNoiseBiomeSource cachedOverworldSource;
    private static volatile MultiNoiseBiomeSource cachedNetherSource;

    private BiomeSampler() {}

    /** Lazily load vanilla registries from the Minecraft jar on classpath. */
    public static DynamicRegistryManager.Immutable registries() {
        DynamicRegistryManager.Immutable r = REGISTRIES;
        if (r != null) return r;
        synchronized (LOCK) {
            if (REGISTRIES != null) return REGISTRIES;
            long t0 = System.currentTimeMillis();
            DefaultResourcePack pack = VanillaDataPackProvider.createDefaultPack();
            List<ResourcePack> packs = List.of(pack);
            LifecycledResourceManagerImpl rm = new LifecycledResourceManagerImpl(
                ResourceType.SERVER_DATA, packs);
            REGISTRIES = RegistryLoader.loadFromResource(
                rm, List.of(), RegistryLoader.DYNAMIC_REGISTRIES);
            LOG.info("[PhgMC] BiomeSampler: loaded vanilla registries in {} ms",
                System.currentTimeMillis() - t0);
            return REGISTRIES;
        }
    }

    /** Rebuild noise configs + biome sources for the given seed (cached). */
    public static void setSeed(long seed) {
        if (cachedSeedSet && seed == cachedSeed) return;
        synchronized (LOCK) {
            if (cachedSeedSet && seed == cachedSeed) return;
            DynamicRegistryManager.Immutable regs = registries();

            RegistryWrapper.Impl<ChunkGeneratorSettings> gen = regs
                .getOrThrow(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
            RegistryWrapper.Impl<net.minecraft.util.math.noise.DoublePerlinNoiseSampler.NoiseParameters> np = regs
                .getOrThrow(RegistryKeys.NOISE_PARAMETERS);
            RegistryWrapper.Impl<MultiNoiseBiomeSourceParameterList> bsPL = regs
                .getOrThrow(RegistryKeys.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);

            ChunkGeneratorSettings ow = gen.getOrThrow(ChunkGeneratorSettings.OVERWORLD).value();
            ChunkGeneratorSettings ne = gen.getOrThrow(ChunkGeneratorSettings.NETHER).value();

            cachedOverworldConfig = NoiseConfig.create(ow, np, seed);
            cachedNetherConfig    = NoiseConfig.create(ne, np, seed);

            cachedOverworldSource = MultiNoiseBiomeSource.create(
                bsPL.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
            cachedNetherSource = MultiNoiseBiomeSource.create(
                bsPL.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));

            cachedSeed = seed;
            cachedSeedSet = true;
            LOG.info("[PhgMC] BiomeSampler: noise configs built for seed {}", seed);
        }
    }

    /** Sample the biome registry key at the given block position and dimension. */
    public static RegistryKey<Biome> sample(Dimension dim, int blockX, int blockY, int blockZ) {
        MultiNoiseBiomeSource src;
        MultiNoiseUtil.MultiNoiseSampler sampler;
        switch (dim) {
            case Overworld -> { src = cachedOverworldSource; sampler = cachedOverworldConfig.getMultiNoiseSampler(); }
            case Nether    -> { src = cachedNetherSource;    sampler = cachedNetherConfig.getMultiNoiseSampler(); }
            default -> { return null; }  // End not supported (uses TheEndBiomeSource, no multinoise)
        }
        if (src == null || sampler == null) return null;
        RegistryEntry<Biome> entry = src.getBiome(blockX >> 2, blockY >> 2, blockZ >> 2, sampler);
        return entry.getKey().orElse(null);
    }

    /** Test whether a biome id (e.g. {@code minecraft:plains}) is in the given name set. */
    public static boolean matchesAny(RegistryKey<Biome> key, List<String> allowedIds) {
        if (key == null) return true;  // if we couldn't sample, don't filter
        Identifier id = key.getValue();
        String s = id.toString();
        for (String allowed : allowedIds) {
            if (s.equals(allowed)) return true;
        }
        return false;
    }

    // ─── Biome allow-lists extracted from vanilla 1.21.11 data pack ─────────
    // (data/minecraft/tags/worldgen/biome/has_structure/*.json)

    public static final List<String> OCEANS = List.of(
        "minecraft:ocean", "minecraft:deep_ocean",
        "minecraft:cold_ocean", "minecraft:deep_cold_ocean",
        "minecraft:frozen_ocean", "minecraft:deep_frozen_ocean",
        "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean",
        "minecraft:warm_ocean");

    public static final List<String> DEEP_OCEANS = List.of(
        "minecraft:deep_ocean", "minecraft:deep_cold_ocean",
        "minecraft:deep_frozen_ocean", "minecraft:deep_lukewarm_ocean");

    public static final List<String> NETHER = List.of(
        "minecraft:nether_wastes", "minecraft:soul_sand_valley",
        "minecraft:crimson_forest", "minecraft:warped_forest",
        "minecraft:basalt_deltas");

    public static final List<String> VILLAGE = List.of(
        "minecraft:plains", "minecraft:meadow",
        "minecraft:desert", "minecraft:savanna",
        "minecraft:snowy_plains", "minecraft:taiga");

    public static final List<String> PILLAGER_OUTPOST = List.of(
        "minecraft:desert", "minecraft:plains", "minecraft:savanna",
        "minecraft:snowy_plains", "minecraft:taiga", "minecraft:grove",
        "minecraft:jagged_peaks", "minecraft:frozen_peaks",
        "minecraft:stony_peaks", "minecraft:snowy_slopes");

    public static final List<String> DESERT_PYRAMID = List.of("minecraft:desert");
    public static final List<String> JUNGLE_TEMPLE = List.of(
        "minecraft:jungle", "minecraft:bamboo_jungle");
    public static final List<String> SWAMP_HUT = List.of("minecraft:swamp");
    public static final List<String> IGLOO = List.of(
        "minecraft:snowy_taiga", "minecraft:snowy_plains", "minecraft:snowy_slopes");
    public static final List<String> WOODLAND_MANSION = List.of(
        "minecraft:dark_forest", "minecraft:pale_garden");
    public static final List<String> ANCIENT_CITY = List.of("minecraft:deep_dark");
    public static final List<String> BASTION_REMNANT = NETHER;

    public static final List<String> TRIAL_CHAMBERS = List.of(
        "minecraft:mushroom_fields",
        "minecraft:deep_frozen_ocean", "minecraft:frozen_ocean",
        "minecraft:deep_cold_ocean", "minecraft:cold_ocean",
        "minecraft:deep_ocean", "minecraft:ocean",
        "minecraft:deep_lukewarm_ocean", "minecraft:lukewarm_ocean",
        "minecraft:warm_ocean", "minecraft:stony_shore",
        "minecraft:swamp", "minecraft:mangrove_swamp",
        "minecraft:snowy_slopes", "minecraft:snowy_plains", "minecraft:snowy_beach",
        "minecraft:windswept_gravelly_hills", "minecraft:grove",
        "minecraft:windswept_hills", "minecraft:snowy_taiga",
        "minecraft:windswept_forest", "minecraft:taiga",
        "minecraft:plains", "minecraft:meadow", "minecraft:beach",
        "minecraft:forest", "minecraft:old_growth_spruce_taiga",
        "minecraft:flower_forest", "minecraft:birch_forest",
        "minecraft:dark_forest", "minecraft:pale_garden",
        "minecraft:savanna_plateau", "minecraft:savanna",
        "minecraft:jungle", "minecraft:badlands", "minecraft:desert",
        "minecraft:wooded_badlands", "minecraft:jagged_peaks",
        "minecraft:stony_peaks", "minecraft:frozen_river", "minecraft:river",
        "minecraft:ice_spikes", "minecraft:old_growth_pine_taiga",
        "minecraft:sunflower_plains", "minecraft:old_growth_birch_forest",
        "minecraft:sparse_jungle", "minecraft:bamboo_jungle",
        "minecraft:eroded_badlands", "minecraft:windswept_savanna",
        "minecraft:cherry_grove", "minecraft:frozen_peaks",
        "minecraft:dripstone_caves", "minecraft:lush_caves");

    public static final List<String> SHIPWRECK = OCEANS;
    public static final List<String> OCEAN_MONUMENT = DEEP_OCEANS;
    public static final List<String> NETHER_FORTRESS = NETHER;
    public static final List<String> RUINED_PORTAL_N = NETHER;

    public static final List<String> OCEAN_RUIN = List.of(
        "minecraft:frozen_ocean", "minecraft:cold_ocean",
        "minecraft:ocean", "minecraft:deep_frozen_ocean",
        "minecraft:deep_cold_ocean", "minecraft:deep_ocean",
        "minecraft:lukewarm_ocean", "minecraft:warm_ocean",
        "minecraft:deep_lukewarm_ocean");

    public static final List<String> TRAIL_RUINS = List.of(
        "minecraft:taiga", "minecraft:snowy_taiga",
        "minecraft:old_growth_pine_taiga", "minecraft:old_growth_spruce_taiga",
        "minecraft:old_growth_birch_forest", "minecraft:jungle");

    public static final List<String> BURIED_TREASURE = List.of(
        "minecraft:beach", "minecraft:snowy_beach");

    public static final List<String> NETHER_FOSSIL = List.of("minecraft:soul_sand_valley");

    /** Any overworld biome except end/nether. For overworld ruined portal + stronghold. */
    public static final List<String> OVERWORLD_ANY = null; // null = accept any non-null biome
}
