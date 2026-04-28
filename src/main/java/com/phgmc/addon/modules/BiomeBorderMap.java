package com.phgmc.addon.modules;

import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hien-Bien-Tau-Hoa — render lines along chunk edges where biome changes.
 * Uses already-loaded biome data from the client world (no seed needed).
 * Pure render, no packets → 100% undetectable.
 *
 * Useful for finding rare biomes (dark forest for mansion, cherry grove,
 * deep dark, mushroom fields, mangrove swamp, etc.) by walking the borders.
 */
public class BiomeBorderMap extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Hiển thị");

    private final Setting<Integer> radiusChunks = sgGeneral.add(new IntSetting.Builder()
        .name("phạm-vi-quét")
        .description("Bán kính sample biome quanh player (chunk).")
        .defaultValue(12).min(2).sliderMin(2).sliderMax(32)
        .build());

    private final Setting<Integer> rescanEveryTicks = sgGeneral.add(new IntSetting.Builder()
        .name("rescan-tick")
        .description("Sample lại biome mỗi N tick (20 = 1s). Càng lớn càng nhẹ máy.")
        .defaultValue(40).min(5).sliderMin(5).sliderMax(200)
        .build());

    private final Setting<Boolean> highlightRare = sgGeneral.add(new BoolSetting.Builder()
        .name("tô-biome-hiếm")
        .description("Tô viền đậm hơn cho các biome hiếm: dark_forest, cherry_grove, deep_dark, mushroom_fields, mangrove_swamp, ice_spikes, sunflower_plains, eroded_badlands, pale_garden.")
        .defaultValue(true)
        .build());

    private final Setting<Double> yOffset = sgRender.add(new DoubleSetting.Builder()
        .name("y-offset")
        .description("Cộng vào Y player để vẽ đường viền (0 = ngang chân).")
        .defaultValue(0.05).min(-2).sliderMin(-2).sliderMax(2)
        .build());

    private final Setting<SettingColor> normalColor = sgRender.add(new ColorSetting.Builder()
        .name("màu-thường")
        .defaultValue(new SettingColor(255, 220, 100, 200))
        .build());

    private final Setting<SettingColor> rareColor = sgRender.add(new ColorSetting.Builder()
        .name("màu-biome-hiếm")
        .defaultValue(new SettingColor(255, 80, 220, 255))
        .build());

    private final Setting<Boolean> hudCount = sgRender.add(new BoolSetting.Builder()
        .name("hud-đếm-biome")
        .description("Hiện số biome khác nhau đang nhìn thấy trên HUD module.")
        .defaultValue(true)
        .build());

    /** Border segment: from (x1, z1) to (x2, z2). Color resolved from rare flag. */
    private record Segment(double x1, double z1, double x2, double z2, boolean rare) {}

    private static final List<String> RARE_BIOMES = List.of(
        "minecraft:dark_forest", "minecraft:cherry_grove",
        "minecraft:deep_dark", "minecraft:mushroom_fields",
        "minecraft:mangrove_swamp", "minecraft:ice_spikes",
        "minecraft:sunflower_plains", "minecraft:eroded_badlands",
        "minecraft:pale_garden", "minecraft:bamboo_jungle",
        "minecraft:old_growth_birch_forest");

    private final List<Segment> segments = new ArrayList<>();
    private final Map<Long, String> biomeCache = new HashMap<>();
    private int distinctCount;
    private int tickCounter;
    private int lastPCX = Integer.MIN_VALUE, lastPCZ = Integer.MIN_VALUE;

    public BiomeBorderMap() {
        super(PhgMCAddon.PhgMC_Support, "Hien-Bien-Tau-Hoa",
            "Vẽ ranh giới biome — tìm biome hiếm (dark forest, cherry, deep dark...).");
    }

    @Override
    public void onActivate() {
        segments.clear();
        biomeCache.clear();
        distinctCount = 0;
        tickCounter = 0;
        lastPCX = lastPCZ = Integer.MIN_VALUE;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        int pcx = mc.player.getBlockX() >> 4;
        int pcz = mc.player.getBlockZ() >> 4;
        if (++tickCounter >= rescanEveryTicks.get() || pcx != lastPCX || pcz != lastPCZ) {
            tickCounter = 0;
            lastPCX = pcx;
            lastPCZ = pcz;
            rebuildSegments(pcx, pcz);
        }
    }

    private String biomeAt(int chunkX, int chunkZ, int sampleY) {
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        String cached = biomeCache.get(key);
        if (cached != null) return cached;
        BlockPos pos = new BlockPos(chunkX * 16 + 8, sampleY, chunkZ * 16 + 8);
        RegistryEntry<Biome> entry = mc.world.getBiome(pos);
        String id = entry.getKey().map(RegistryKey::getValue).map(Identifier::toString).orElse("");
        biomeCache.put(key, id);
        return id;
    }

    private void rebuildSegments(int pcx, int pcz) {
        segments.clear();
        biomeCache.clear();
        int r = radiusChunks.get();
        int sampleY = mc.player.getBlockY();
        var distinct = new java.util.HashSet<String>();

        for (int cx = pcx - r; cx <= pcx + r; cx++) {
            for (int cz = pcz - r; cz <= pcz + r; cz++) {
                String here = biomeAt(cx, cz, sampleY);
                if (here.isEmpty()) continue;
                distinct.add(here);

                // Compare with east neighbor (cx+1, cz)
                String east = biomeAt(cx + 1, cz, sampleY);
                if (!east.isEmpty() && !east.equals(here)) {
                    double xEdge = (cx + 1) * 16.0;
                    boolean rare = isRare(here) || isRare(east);
                    segments.add(new Segment(xEdge, cz * 16.0, xEdge, (cz + 1) * 16.0, rare));
                }
                // Compare with south neighbor (cx, cz+1)
                String south = biomeAt(cx, cz + 1, sampleY);
                if (!south.isEmpty() && !south.equals(here)) {
                    double zEdge = (cz + 1) * 16.0;
                    boolean rare = isRare(here) || isRare(south);
                    segments.add(new Segment(cx * 16.0, zEdge, (cx + 1) * 16.0, zEdge, rare));
                }
            }
        }
        distinctCount = distinct.size();
    }

    private static boolean isRare(String biomeId) {
        for (String r : RARE_BIOMES) if (r.equals(biomeId)) return true;
        return false;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (segments.isEmpty() || mc.player == null) return;
        double y = mc.player.getY() + yOffset.get();
        Color cn = normalColor.get();
        Color cr = rareColor.get();
        boolean hl = highlightRare.get();
        for (Segment s : segments) {
            Color c = (hl && s.rare) ? cr : cn;
            event.renderer.line(s.x1, y, s.z1, s.x2, y, s.z2, c);
        }
    }

    @Override
    public String getInfoString() {
        if (!hudCount.get()) return null;
        return distinctCount + " biome / " + segments.size() + " viền";
    }
}
