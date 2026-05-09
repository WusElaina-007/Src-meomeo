package com.phgmc.addon.modules;

import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heatmap-Hoat-Dong — aggregates output from all observation modules
 * (sound / explosion / particle / lightning / entity / base / stash /
 * hole) into a single per-cell activity score, decayed over time.
 *
 * Cells are 16×16 (chunk-aligned). Render as beacon-style columns
 * coloured blue (low) → yellow → red → magenta (very high). Top-N
 * cells get auto-waypoints.
 */
public class ActivityHeatmap extends Module {

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgWeights  = settings.createGroup("Trọng số nguồn");
    private final SettingGroup sgRender   = settings.createGroup("Hiển thị");

    private final Setting<Integer> rescanTicks = sgGeneral.add(new IntSetting.Builder()
        .name("rescan-mỗi-tick").defaultValue(40).min(5).sliderMax(400).build());

    private final Setting<Double> halfLifeMin = sgGeneral.add(new DoubleSetting.Builder()
        .name("half-life-phút")
        .description("Score của 1 cell giảm 1/2 sau N phút.")
        .defaultValue(15.0).min(0.5).sliderMax(180.0).build());

    private final Setting<Double> minDisplay = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-score-display")
        .description("Cell có score < ngưỡng sẽ không vẽ.")
        .defaultValue(2.0).min(0.0).sliderMax(50.0).build());

    private final Setting<Integer> topN = sgGeneral.add(new IntSetting.Builder()
        .name("top-N-waypoint")
        .description("Tự waypoint cho top N cell.")
        .defaultValue(10).min(0).sliderMax(50).build());

    private final Setting<Boolean> announceTop = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-top-mỗi-rescan").defaultValue(false).build());

    private final Setting<Double> wSound = sgWeights.add(new DoubleSetting.Builder()
        .name("w-sound").defaultValue(1.0).min(0.0).sliderMax(20.0).build());
    private final Setting<Double> wParticle = sgWeights.add(new DoubleSetting.Builder()
        .name("w-particle").defaultValue(0.5).min(0.0).sliderMax(20.0).build());
    private final Setting<Double> wExplosion = sgWeights.add(new DoubleSetting.Builder()
        .name("w-explosion").defaultValue(8.0).min(0.0).sliderMax(50.0).build());
    private final Setting<Double> wLightning = sgWeights.add(new DoubleSetting.Builder()
        .name("w-lightning").defaultValue(0.5).min(0.0).sliderMax(20.0).build());
    private final Setting<Double> wEntity = sgWeights.add(new DoubleSetting.Builder()
        .name("w-entity").defaultValue(0.2).min(0.0).sliderMax(20.0).build());
    private final Setting<Double> wBase = sgWeights.add(new DoubleSetting.Builder()
        .name("w-base").defaultValue(10.0).min(0.0).sliderMax(50.0).build());
    private final Setting<Double> wStash = sgWeights.add(new DoubleSetting.Builder()
        .name("w-stash").defaultValue(6.0).min(0.0).sliderMax(50.0).build());
    private final Setting<Double> wHole = sgWeights.add(new DoubleSetting.Builder()
        .name("w-hole").defaultValue(2.0).min(0.0).sliderMax(20.0).build());
    private final Setting<Double> wBlockUpdate = sgWeights.add(new DoubleSetting.Builder()
        .name("w-block-update").defaultValue(0.8).min(0.0).sliderMax(20.0).build());
    private final Setting<Double> wChunkChange = sgWeights.add(new DoubleSetting.Builder()
        .name("w-chunk-đổi").defaultValue(5.0).min(0.0).sliderMax(50.0).build());

    private final Setting<Double> kSigmoid = sgGeneral.add(new DoubleSetting.Builder()
        .name("k-sigmoid")
        .description("Hệ số trong công thức confidence = 1 - exp(-score/k). k thấp → tier confidence dễ đạt 99%.")
        .defaultValue(8.0).min(1.0).sliderMax(100.0).build());

    private final Setting<Integer> minSourceTypes = sgGeneral.add(new IntSetting.Builder()
        .name("min-loại-source")
        .description("Cell phải có ≥ N loại source khác nhau để confidence áp dụng (lọc fluke).")
        .defaultValue(2).min(1).sliderMax(8).build());

    private final Setting<Boolean> tierMode = sgRender.add(new BoolSetting.Builder()
        .name("vẽ-theo-tier")
        .description("Vẽ màu theo tier (suspect/likely/confirmed) thay vì gradient.")
        .defaultValue(true).build());

    private final Setting<Double> tierConfirmed = sgRender.add(new DoubleSetting.Builder()
        .name("ngưỡng-confirmed").defaultValue(0.99).min(0.5).max(1.0).build());

    private final Setting<Double> tierLikely = sgRender.add(new DoubleSetting.Builder()
        .name("ngưỡng-likely").defaultValue(0.85).min(0.4).max(0.99).build());

    private final Setting<Double> tierSuspect = sgRender.add(new DoubleSetting.Builder()
        .name("ngưỡng-suspect").defaultValue(0.60).min(0.2).max(0.95).build());

    private final Setting<SettingColor> colSuspect = sgRender.add(new ColorSetting.Builder()
        .name("màu-suspect").defaultValue(new SettingColor(255, 230, 60, 200)).build());

    private final Setting<SettingColor> colLikely = sgRender.add(new ColorSetting.Builder()
        .name("màu-likely").defaultValue(new SettingColor(255, 140, 30, 220)).build());

    private final Setting<SettingColor> colConfirmed = sgRender.add(new ColorSetting.Builder()
        .name("màu-confirmed").defaultValue(new SettingColor(255, 50, 50, 240)).build());

    private final Setting<Boolean> renderColumns = sgRender.add(new BoolSetting.Builder()
        .name("vẽ-cột").description("Vẽ cột Y-64→320 cho mỗi cell (gây che mặt).")
        .defaultValue(false).build());
    private final Setting<Boolean> renderTopBox = sgRender.add(new BoolSetting.Builder()
        .name("vẽ-box-top").defaultValue(true).build());

    public enum HMRenderMode { Flat, Top, Box, Off }

    private final Setting<HMRenderMode> hmRenderMode = sgRender.add(new EnumSetting.Builder<HMRenderMode>()
        .name("render-mode").defaultValue(HMRenderMode.Flat).build());

    private final Setting<Integer> flatYOffset = sgRender.add(new IntSetting.Builder()
        .name("flat-y-offset").defaultValue(0).min(-64).sliderMin(-32).sliderMax(64).build());

    private final Setting<Integer> topY = sgRender.add(new IntSetting.Builder()
        .name("top-y").defaultValue(120).min(-64).sliderMin(-64).sliderMax(320).build());
    private final Setting<SettingColor> outline = sgRender.add(new ColorSetting.Builder()
        .name("outline").defaultValue(new SettingColor(255, 255, 255, 200)).build());
    private final Setting<ShapeMode> shape = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape").defaultValue(ShapeMode.Both).build());

    public static class Cell {
        public int cx, cz;
        public double score;
        public long lastUpdateMs;
        public Map<String, Double> sources = new HashMap<>();
    }

    private final Map<Long, Cell> cells = new ConcurrentHashMap<>();
    private int tickCounter = 0;
    private long lastRescanMs = 0;

    public ActivityHeatmap() {
        super(PhgMCAddon.PhgMC_Support, "Heatmap-Hoat-Dong",
            "Tổng hợp sound/particle/explosion/lightning/entity/base/stash/hole → 1 heatmap.");
    }

    @Override public void onActivate() { cells.clear(); tickCounter = 0; lastRescanMs = System.currentTimeMillis(); }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (++tickCounter < rescanTicks.get()) return;
        tickCounter = 0;
        rescan();
    }

    private void rescan() {
        long now = System.currentTimeMillis();
        double dtMin = (now - lastRescanMs) / 60000.0;
        lastRescanMs = now;

        // Decay all
        double halfLife = Math.max(0.001, halfLifeMin.get());
        double decay = Math.pow(0.5, dtMin / halfLife);
        for (Cell c : cells.values()) c.score *= decay;

        // Pull deltas from each source
        addSoundTracker();
        addExplosionTracker();
        addParticleTracker();
        addLightningTracker();
        addEntityFarTracker();
        addBaseFinder();
        addStashFinder();
        addHoleHunter();
        addBlockUpdateTracker();
        addChunkChangeRecorder();

        // Drop tiny cells
        cells.values().removeIf(c -> c.score < 0.01);

        if (announceTop.get()) {
            List<Cell> top = top(5);
            if (!top.isEmpty()) {
                StringBuilder sb = new StringBuilder("Heatmap top: ");
                for (Cell c : top) sb.append(String.format("§e%d,%d§7=§c%.1f§r ", c.cx, c.cz, c.score));
                ChatUtils.info(sb.toString());
            }
        }

        if (topN.get() > 0) {
            List<Cell> top = top(topN.get());
            for (Cell c : top) {
                Tier t = tierOf(confidenceOf(c));
                if (tierMode.get() && t == Tier.NONE) continue;
                try {
                    String tag = switch (t) {
                        case CONFIRMED -> "[Heat!]";
                        case LIKELY    -> "[Heat?]";
                        case SUSPECT   -> "[Heat??]";
                        case NONE      -> "[Heat~]";
                    };
                    String wpName = String.format("%s %.0f %d,%d", tag, c.score, c.cx * 16 + 8, c.cz * 16 + 8);
                    Waypoint wp = new Waypoint.Builder()
                        .name(wpName).icon("circle")
                        .pos(new BlockPos(c.cx * 16 + 8, 70, c.cz * 16 + 8))
                        .build();
                    Waypoints.get().add(wp);
                } catch (Throwable ignored) {}
            }
        }
    }

    private Cell cellFor(double x, double z) {
        int cx = (int) Math.floor(x) >> 4;
        int cz = (int) Math.floor(z) >> 4;
        long k = ChunkPos.toLong(cx, cz);
        return cells.computeIfAbsent(k, kk -> {
            Cell c = new Cell();
            c.cx = cx;
            c.cz = cz;
            return c;
        });
    }

    private void contribute(double x, double z, double weight, String src) {
        if (weight <= 0) return;
        Cell c = cellFor(x, z);
        c.score += weight;
        c.sources.merge(src, weight, Double::sum);
        c.lastUpdateMs = System.currentTimeMillis();
    }

    private void addSoundTracker() {
        SoundTracker m = Modules.get().get(SoundTracker.class);
        if (m == null || !m.isActive()) return;
        double w = wSound.get();
        try {
            for (var s : m.snapshot()) {
                contribute(s.x, s.z, w * Math.min(s.hits, 50) / 10.0, "sound");
            }
        } catch (Throwable ignored) {}
    }

    private void addExplosionTracker() {
        ExplosionTracker m = Modules.get().get(ExplosionTracker.class);
        if (m == null || !m.isActive()) return;
        double w = wExplosion.get();
        for (var b : m.snapshot()) {
            contribute(b.pos.x, b.pos.z, w * Math.max(1.0, b.radius / 4.0), "explosion");
        }
    }

    private void addParticleTracker() {
        ParticleTracker m = Modules.get().get(ParticleTracker.class);
        if (m == null || !m.isActive()) return;
        double w = wParticle.get();
        for (var c : m.snapshot()) {
            contribute(c.x, c.z, w * Math.min(c.hits, 100) / 20.0, "particle");
        }
    }

    private void addLightningTracker() {
        LightningTracker m = Modules.get().get(LightningTracker.class);
        if (m == null || !m.isActive()) return;
        double w = wLightning.get();
        for (var s : m.snapshot()) {
            contribute(s.pos.x, s.pos.z, w * s.hits, "lightning");
        }
    }

    private void addEntityFarTracker() {
        EntityFarTracker m = Modules.get().get(EntityFarTracker.class);
        if (m == null || !m.isActive()) return;
        double w = wEntity.get();
        for (var r : m.snapshot()) {
            contribute(r.x, r.z, w, "entity");
        }
    }

    private void addBaseFinder() {
        BaseFinder m = Modules.get().get(BaseFinder.class);
        if (m == null || !m.isActive()) return;
        double w = wBase.get();
        try {
            for (var f : m.snapshot()) {
                contribute(f.chunkX * 16 + 8, f.chunkZ * 16 + 8, w * Math.min(f.score, 60) / 10.0, "base");
            }
        } catch (Throwable ignored) {}
    }

    private void addStashFinder() {
        StashFinder m = Modules.get().get(StashFinder.class);
        if (m == null || !m.isActive()) return;
        double w = wStash.get();
        try {
            for (var s : m.snapshot()) {
                contribute(s.chunkX * 16 + 8, s.chunkZ * 16 + 8, w, "stash");
            }
        } catch (Throwable ignored) {}
    }

    private void addHoleHunter() {
        HoleHunter m = Modules.get().get(HoleHunter.class);
        if (m == null || !m.isActive()) return;
        double w = wHole.get();
        for (var entry : m.snapshot().entrySet()) {
            for (var s : entry.getValue()) {
                contribute(s.pos.getX(), s.pos.getZ(), w, "hole");
            }
        }
    }

    private void addBlockUpdateTracker() {
        BlockUpdateTracker m = Modules.get().get(BlockUpdateTracker.class);
        if (m == null || !m.isActive()) return;
        double w = wBlockUpdate.get();
        for (var s : m.snapshot()) {
            // density already accumulated; sample as score contribution
            contribute(s.cx * 16 + 8, s.cz * 16 + 8, w * Math.min(s.density, 100) / 10.0, "blockupdate");
        }
    }

    private void addChunkChangeRecorder() {
        ChunkChangeRecorder m = Modules.get().get(ChunkChangeRecorder.class);
        if (m == null || !m.isActive()) return;
        double w = wChunkChange.get();
        for (var ch : m.snapshot().values()) {
            contribute(ch.cx * 16 + 8, ch.cz * 16 + 8, w, "chunkchange");
        }
    }

    private double confidenceOf(Cell c) {
        if (c.sources.size() < minSourceTypes.get()) return 0.0;
        double k = Math.max(0.5, kSigmoid.get());
        return 1.0 - Math.exp(-c.score / k);
    }

    public enum Tier { NONE, SUSPECT, LIKELY, CONFIRMED }

    private Tier tierOf(double conf) {
        if (conf >= tierConfirmed.get()) return Tier.CONFIRMED;
        if (conf >= tierLikely.get())    return Tier.LIKELY;
        if (conf >= tierSuspect.get())   return Tier.SUSPECT;
        return Tier.NONE;
    }

    private List<Cell> top(int n) {
        List<Cell> all = new ArrayList<>(cells.values());
        all.sort((a, b) -> Double.compare(b.score, a.score));
        return all.subList(0, Math.min(n, all.size()));
    }

    @EventHandler
    private void onRender(Render3DEvent e) {
        if (cells.isEmpty()) return;
        double minS = minDisplay.get();
        double maxS = cells.values().stream().mapToDouble(c -> c.score).max().orElse(1.0);
        if (maxS <= 0) return;
        Color o = outline.get();
        ShapeMode sh = shape.get();

        Set<Long> topKeys = new HashSet<>();
        if (renderTopBox.get()) {
            for (Cell c : top(topN.get())) topKeys.add(ChunkPos.toLong(c.cx, c.cz));
        }

        boolean tier = tierMode.get();
        for (Cell c : cells.values()) {
            if (c.score < minS) continue;
            Color col;
            if (tier) {
                Tier t = tierOf(confidenceOf(c));
                if (t == Tier.NONE) continue;
                col = switch (t) {
                    case CONFIRMED -> colConfirmed.get();
                    case LIKELY    -> colLikely.get();
                    case SUSPECT   -> colSuspect.get();
                    case NONE      -> outline.get();
                };
            } else {
                float p = (float) (c.score / maxS);
                col = heat(p);
            }

            double x1 = c.cx * 16, z1 = c.cz * 16;
            double x2 = x1 + 16, z2 = z1 + 16;

            if (renderColumns.get()) {
                e.renderer.line(x1 + 8, -64, z1 + 8, x1 + 8, 320, z1 + 8, col);
            }

            if (renderTopBox.get() && topKeys.contains(ChunkPos.toLong(c.cx, c.cz))) {
                HMRenderMode rm = hmRenderMode.get();
                double py = mc.player != null ? mc.player.getY() + flatYOffset.get() : 64;
                switch (rm) {
                    case Box  -> e.renderer.box(x1, -64, z1, x2, 320, z2, col, o, sh, 0);
                    case Top  -> e.renderer.box(x1, topY.get(), z1, x2, topY.get() + 0.05, z2, col, o, sh, 0);
                    case Flat -> e.renderer.box(x1, py, z1, x2, py + 0.05, z2, col, o, sh, 0);
                    case Off  -> {}
                }
            }
        }
    }

    private static Color heat(float t) {
        // 0 = blue → 0.33 yellow → 0.66 red → 1 magenta
        if (t < 0.33f) {
            float k = t / 0.33f;
            return new Color((int)(50 + 200 * k), (int)(50 + 200 * k), (int)(255 - 200 * k), 200);
        } else if (t < 0.66f) {
            float k = (t - 0.33f) / 0.33f;
            return new Color(255, (int)(255 - 200 * k), 50, 220);
        } else {
            float k = (t - 0.66f) / 0.34f;
            return new Color(255, 55, (int)(50 + 200 * k), 240);
        }
    }

    @Override
    public String getInfoString() {
        if (cells.isEmpty()) return "0";
        long c1 = 0, c2 = 0, c3 = 0;
        for (Cell c : cells.values()) {
            Tier t = tierOf(confidenceOf(c));
            if (t == Tier.CONFIRMED) c3++;
            else if (t == Tier.LIKELY) c2++;
            else if (t == Tier.SUSPECT) c1++;
        }
        return String.format("§c%d§7/§6%d§7/§e%d", c3, c2, c1);
    }
}
