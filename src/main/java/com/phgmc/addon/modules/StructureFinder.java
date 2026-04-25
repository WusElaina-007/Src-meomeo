package com.phgmc.addon.modules;

import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tìm-Công-Trình — dự đoán vị trí các công trình vanilla từ seed thế giới.
 *
 * <p>Nhập seed (từ SeedCracker), bật các loại công trình muốn tìm, module sẽ
 * quét offline bằng chính thuật toán {@code RandomSpreadStructurePlacement}
 * của Minecraft (không cần chunk đã load). Kết quả hiện dưới dạng ESP 3D
 * và/hoặc Waypoint.</p>
 */
public class StructureFinder extends Module {

    // ─── Danh sách công trình ────────────────────────────────────────────────

    public enum Kind {
        VILLAGE         ("Làng",                       34, 8,  10387312,  false, Dimension.Overworld),
        PILLAGER_OUTPOST("Đài Pillager",               32, 8,  165745296, false, Dimension.Overworld),
        DESERT_PYRAMID  ("Kim tự tháp sa mạc",         32, 8,  14357617,  false, Dimension.Overworld),
        JUNGLE_PYRAMID  ("Kim tự tháp rừng",           32, 8,  14357619,  false, Dimension.Overworld),
        SWAMP_HUT       ("Nhà phù thuỷ",               32, 8,  14357620,  false, Dimension.Overworld),
        IGLOO           ("Nhà băng (Igloo)",           32, 8,  14357618,  false, Dimension.Overworld),
        OCEAN_MONUMENT  ("Tượng đài đại dương",        32, 5,  10387313,  false, Dimension.Overworld),
        WOODLAND_MANSION("Biệt thự rừng",              80, 20, 10387319,  false, Dimension.Overworld),
        SHIPWRECK       ("Tàu đắm",                    24, 4,  165745295, false, Dimension.Overworld),
        RUINED_PORTAL_OW("Cổng đổ nát (Overworld)",    40, 15, 34222645,  false, Dimension.Overworld),
        ANCIENT_CITY    ("Thành phố cổ (Deep Dark)",   24, 8,  20083232,  true,  Dimension.Overworld),
        TRIAL_CHAMBERS  ("Buồng thử thách",            34, 8,  94251327,  true,  Dimension.Overworld),
        NETHER_FORTRESS ("Lâu đài Nether",             27, 4,  30084232,  false, Dimension.Nether),
        BASTION_REMNANT ("Bastion Remnant",            27, 4,  30084232,  false, Dimension.Nether),
        RUINED_PORTAL_N ("Cổng đổ nát (Nether)",       25, 10, 34222647,  false, Dimension.Nether),
        END_CITY        ("End City",                   20, 11, 10387313,  true,  Dimension.End);

        public final String label;
        public final int spacing, separation, salt;
        public final boolean triangular;
        public final Dimension dimension;

        Kind(String label, int spacing, int separation, int salt, boolean triangular, Dimension dimension) {
            this.label = label;
            this.spacing = spacing;
            this.separation = separation;
            this.salt = salt;
            this.triangular = triangular;
            this.dimension = dimension;
        }
    }

    private record Found(Kind kind, int blockX, int blockZ, double distance) {}

    // ─── Settings ───────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgOverworld = settings.createGroup("Overworld");
    private final SettingGroup sgNether    = settings.createGroup("Nether");
    private final SettingGroup sgEnd       = settings.createGroup("End");
    private final SettingGroup sgRender    = settings.createGroup("Hiển thị");

    private final Setting<String> seedStr = sgGeneral.add(new StringSetting.Builder()
        .name("seed")
        .description("Seed thế giới (paste từ SeedCracker hoặc /seed). Để trống = không quét.")
        .defaultValue("")
        .onChanged(v -> triggerScan())
        .build());

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("phạm-vi-quét")
        .description("Bán kính quét quanh player tính bằng block.")
        .defaultValue(5000).min(500).sliderMin(500).sliderMax(20000)
        .onChanged(v -> triggerScan())
        .build());

    // Per-structure toggles, grouped per dimension
    private final Map<Kind, Setting<Boolean>> kindEnabled = new EnumMap<>(Kind.class);

    // ─── Render settings ────────────────────────────────────────────────────

    private final Setting<Boolean> esp = sgRender.add(new BoolSetting.Builder()
        .name("esp-3d")
        .description("Vẽ box ESP 3D tại mỗi công trình tìm được.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> waypoints = sgRender.add(new BoolSetting.Builder()
        .name("tạo-waypoint")
        .description("Thêm vào hệ thống Waypoints của Meteor (giống Xaero Minimap).")
        .defaultValue(true)
        .onChanged(v -> applyWaypoints())
        .build());

    private final Setting<Boolean> hud = sgRender.add(new BoolSetting.Builder()
        .name("hud-gần-nhất")
        .description("Hiển thị công trình gần nhất + khoảng cách trên HUD của module.")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> colorSide = sgRender.add(new ColorSetting.Builder()
        .name("màu-nền")
        .description("Màu nền box ESP.")
        .defaultValue(new SettingColor(180, 80, 255, 60))
        .build());

    private final Setting<SettingColor> colorLine = sgRender.add(new ColorSetting.Builder()
        .name("màu-viền")
        .description("Màu viền box ESP.")
        .defaultValue(new SettingColor(220, 120, 255, 255))
        .build());

    private final Setting<ShapeMode> shape = sgRender.add(new meteordevelopment.meteorclient.settings.EnumSetting.Builder<ShapeMode>()
        .name("kiểu-hiển-thị")
        .description("Sides / Lines / Both.")
        .defaultValue(ShapeMode.Both)
        .build());

    // ─── State ──────────────────────────────────────────────────────────────

    private final List<Found> found = new CopyOnWriteArrayList<>();
    private final ExecutorService scanExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "StudentStructureFinder");
        t.setDaemon(true);
        return t;
    });
    private volatile long scanEpoch = 0;
    private static final String WP_PREFIX = "[TimCT] ";

    public StructureFinder() {
        super(PhgMCAddon.PhgMC_Support, "Tim-Cong-Trinh",
            "Dự đoán vị trí công trình (Village/Temple/Stronghold/End City…) từ seed.");

        // Register per-structure toggles, grouped by dimension
        for (Kind k : Kind.values()) {
            SettingGroup g = switch (k.dimension) {
                case Nether    -> sgNether;
                case End       -> sgEnd;
                default        -> sgOverworld;
            };
            kindEnabled.put(k, g.add(new BoolSetting.Builder()
                .name(k.name().toLowerCase().replace('_', '-'))
                .description(k.label)
                .defaultValue(defaultEnabled(k))
                .onChanged(v -> triggerScan())
                .build()));
        }
    }

    private static boolean defaultEnabled(Kind k) {
        return switch (k) {
            case END_CITY, ANCIENT_CITY, WOODLAND_MANSION,
                 OCEAN_MONUMENT, NETHER_FORTRESS, BASTION_REMNANT,
                 VILLAGE, TRIAL_CHAMBERS -> true;
            default -> false;
        };
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        triggerScan();
    }

    @Override
    public void onDeactivate() {
        found.clear();
        removeOurWaypoints();
    }

    // ─── Scan ───────────────────────────────────────────────────────────────

    private void triggerScan() {
        if (!isActive()) return;
        if (mc.player == null) return;
        long epoch = ++scanEpoch;
        final int px = mc.player.getBlockX();
        final int pz = mc.player.getBlockZ();
        scanExec.submit(() -> doScan(epoch, px, pz));
    }

    private void doScan(long epoch, int playerX, int playerZ) {
        long seed;
        try {
            seed = Long.parseLong(seedStr.get().trim());
        } catch (NumberFormatException e) {
            return;
        }

        int r = radius.get();
        int centerCX = playerX >> 4;
        int centerCZ = playerZ >> 4;

        List<Found> results = new ArrayList<>();

        for (Kind k : Kind.values()) {
            if (!kindEnabled.get(k).get()) continue;
            int regionRadius = Math.max(1, (r / 16) / k.spacing + 1);
            int regionCX = Math.floorDiv(centerCX, k.spacing);
            int regionCZ = Math.floorDiv(centerCZ, k.spacing);

            for (int rx = regionCX - regionRadius; rx <= regionCX + regionRadius; rx++) {
                for (int rz = regionCZ - regionRadius; rz <= regionCZ + regionRadius; rz++) {
                    ChunkPos cp = getStartChunk(seed, k, rx, rz);
                    int bx = cp.x * 16 + 8;
                    int bz = cp.z * 16 + 8;
                    double dx = bx - playerX;
                    double dz = bz - playerZ;
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist <= r) {
                        results.add(new Found(k, bx, bz, dist));
                    }
                }
                if (epoch != scanEpoch) return;
            }
        }

        results.sort(Comparator.comparingDouble(Found::distance));
        if (epoch != scanEpoch) return;

        found.clear();
        found.addAll(results);

        if (mc != null) mc.execute(this::applyWaypoints);
    }

    private static ChunkPos getStartChunk(long seed, Kind k, int regionX, int regionZ) {
        ChunkRandom random = new ChunkRandom(new CheckedRandom(0));
        random.setRegionSeed(seed, regionX, regionZ, k.salt);
        int range = k.spacing - k.separation;
        int ox, oz;
        if (k.triangular) {
            ox = (random.nextInt(range) + random.nextInt(range)) / 2;
            oz = (random.nextInt(range) + random.nextInt(range)) / 2;
        } else {
            ox = random.nextInt(range);
            oz = random.nextInt(range);
        }
        return new ChunkPos(regionX * k.spacing + ox, regionZ * k.spacing + oz);
    }

    // ─── Waypoints ──────────────────────────────────────────────────────────

    private void removeOurWaypoints() {
        Waypoints wps = Waypoints.get();
        List<Waypoint> toRemove = new ArrayList<>();
        for (Waypoint w : wps) {
            if (w.name.get().startsWith(WP_PREFIX)) toRemove.add(w);
        }
        if (!toRemove.isEmpty()) wps.removeAll(toRemove);
    }

    private void applyWaypoints() {
        removeOurWaypoints();
        if (!waypoints.get() || !isActive()) return;

        Waypoints wps = Waypoints.get();
        int n = Math.min(found.size(), 64); // safety cap
        for (int i = 0; i < n; i++) {
            Found fs = found.get(i);
            Waypoint wp = new Waypoint.Builder()
                .name(WP_PREFIX + fs.kind.label + " (" + (int) fs.distance + "m)")
                .icon("diamond")
                .pos(new BlockPos(fs.blockX, 64, fs.blockZ))
                .dimension(fs.kind.dimension)
                .build();
            wps.add(wp);
        }
    }

    // ─── Render ─────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!esp.get() || found.isEmpty() || mc.player == null) return;

        Color side = colorSide.get();
        Color line = colorLine.get();

        // Only render structures in current dimension
        Dimension cur = currentDimension();
        double py = mc.player.getY();

        for (Found fs : found) {
            if (fs.kind.dimension != cur) continue;
            double yLo = py - 3;
            double yHi = py + 30;
            event.renderer.box(
                fs.blockX - 8, yLo, fs.blockZ - 8,
                fs.blockX + 8, yHi, fs.blockZ + 8,
                side, line, shape.get(), 0);
        }
    }

    private Dimension currentDimension() {
        if (mc.world == null) return Dimension.Overworld;
        String id = mc.world.getRegistryKey().getValue().toString();
        if (id.contains("the_nether")) return Dimension.Nether;
        if (id.contains("the_end"))    return Dimension.End;
        return Dimension.Overworld;
    }

    // ─── HUD ────────────────────────────────────────────────────────────────

    @Override
    public String getInfoString() {
        if (!hud.get()) return null;
        if (found.isEmpty()) {
            if (seedStr.get().isBlank()) return "§e[nhập seed]";
            return "§7(0)";
        }
        Found nearest = null;
        Dimension cur = currentDimension();
        for (Found f : found) {
            if (f.kind.dimension == cur) { nearest = f; break; }
        }
        if (nearest == null) nearest = found.get(0);
        return String.format("%s §7(%dm)", nearest.kind.label, (int) nearest.distance);
    }
}
