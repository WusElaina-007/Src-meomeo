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

    private final Setting<Boolean> renderColumns = sgRender.add(new BoolSetting.Builder()
        .name("vẽ-cột").defaultValue(true).build());
    private final Setting<Boolean> renderTopBox = sgRender.add(new BoolSetting.Builder()
        .name("vẽ-box-top").defaultValue(true).build());
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
                try {
                    String wpName = String.format("[Heat] %.0f %d,%d", c.score, c.cx * 16 + 8, c.cz * 16 + 8);
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

        for (Cell c : cells.values()) {
            if (c.score < minS) continue;
            float t = (float) (c.score / maxS);
            Color col = heat(t);

            double x1 = c.cx * 16, z1 = c.cz * 16;
            double x2 = x1 + 16, z2 = z1 + 16;

            if (renderColumns.get()) {
                e.renderer.line(x1 + 8, -64, z1 + 8, x1 + 8, 320, z1 + 8, col);
            }

            if (topKeys.contains(ChunkPos.toLong(c.cx, c.cz))) {
                e.renderer.box(x1, 60, z1, x2, 70, z2, col, o, sh, 0);
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
        double max = cells.values().stream().mapToDouble(c -> c.score).max().orElse(0);
        return String.format("%d c §c%.1f", cells.size(), max);
    }
}
