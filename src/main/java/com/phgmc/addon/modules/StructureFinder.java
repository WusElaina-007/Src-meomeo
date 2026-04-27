package com.phgmc.addon.modules;

import com.phgmc.addon.BiomeSampler;
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
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;

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
        //                label                          spc sep salt       tri  dim                  knownY
        STRONGHOLD      ("Stronghold (thành ngầm)",      0,  0, 0,         false, Dimension.Overworld,  20, true),
        VILLAGE         ("Làng",                       34,  8, 10387312,  false, Dimension.Overworld,  72),
        PILLAGER_OUTPOST("Đài Pillager",               32,  8, 165745296, false, Dimension.Overworld,  80),
        DESERT_PYRAMID  ("Kim tự tháp sa mạc",         32,  8, 14357617,  false, Dimension.Overworld,  65),
        JUNGLE_PYRAMID  ("Kim tự tháp rừng",           32,  8, 14357619,  false, Dimension.Overworld,  80),
        SWAMP_HUT       ("Nhà phù thuỷ",               32,  8, 14357620,  false, Dimension.Overworld,  62),
        IGLOO           ("Nhà băng (Igloo)",           32,  8, 14357618,  false, Dimension.Overworld,  70),
        OCEAN_MONUMENT  ("Tượng đài đại dương",        32,  5, 10387313,  true,  Dimension.Overworld,  40),
        WOODLAND_MANSION("Biệt thự rừng",              80, 20, 10387319,  true,  Dimension.Overworld,  90),
        SHIPWRECK       ("Tàu đắm",                    24,  4, 165745295, false, Dimension.Overworld,  50),
        RUINED_PORTAL_OW("Cổng đổ nát (Overworld)",    40, 15, 34222645,  false, Dimension.Overworld,  60),
        ANCIENT_CITY    ("Thành phố cổ (Deep Dark)",   24,  8, 20083232,  false, Dimension.Overworld, -51),
        TRIAL_CHAMBERS  ("Buồng thử thách",            34, 12, 94251327,  false, Dimension.Overworld, -20),
        OCEAN_RUIN      ("Tàn tích đại dương",         20,  8, 14357621,  false, Dimension.Overworld,  40),
        TRAIL_RUINS     ("Tàn tích đường mòn",         34,  8, 83469867,  false, Dimension.Overworld,  60),
        BURIED_TREASURE ("Kho báu chôn (Buried Treasure)", 1, 0, 0,       false, Dimension.Overworld,  56,  false, 0.01f, 9, FreqMethod.LEGACY_TYPE_2),
        NETHER_FORTRESS ("Lâu đài Nether",             27,  4, 30084232,  false, Dimension.Nether,     65),
        BASTION_REMNANT ("Bastion Remnant",            27,  4, 30084232,  false, Dimension.Nether,     45),
        NETHER_FOSSIL   ("Hoá thạch Nether",            2,  1, 14357921,  false, Dimension.Nether,     85),
        RUINED_PORTAL_N ("Cổng đổ nát (Nether)",       40, 15, 34222645,  false, Dimension.Nether,     40),
        END_CITY        ("End City",                   20, 11, 10387313,  true,  Dimension.End,        75);

        public final String label;
        public final int spacing, separation, salt;
        public final boolean triangular;
        public final Dimension dimension;
        /** Approximate Y level where this structure typically generates. Used for disc rendering. */
        public final int knownY;
        /** If true this Kind uses ConcentricRings placement instead of RandomSpread (Stronghold). */
        public final boolean concentricRings;
        /** Probability filter applied after start-chunk check (1.0 = always). */
        public final float frequency;
        /** Block offset within a chunk to use for the in-world position (default 8 = chunk center). */
        public final int locateOffset;
        /** Frequency reduction algorithm (only relevant when frequency < 1.0). */
        public final FreqMethod freqMethod;

        Kind(String label, int spacing, int separation, int salt, boolean triangular, Dimension dimension, int knownY) {
            this(label, spacing, separation, salt, triangular, dimension, knownY, false, 1.0f, 8, FreqMethod.DEFAULT);
        }

        Kind(String label, int spacing, int separation, int salt, boolean triangular, Dimension dimension, int knownY, boolean concentricRings) {
            this(label, spacing, separation, salt, triangular, dimension, knownY, concentricRings, 1.0f, 8, FreqMethod.DEFAULT);
        }

        Kind(String label, int spacing, int separation, int salt, boolean triangular, Dimension dimension, int knownY, boolean concentricRings, float frequency, int locateOffset, FreqMethod freqMethod) {
            this.label = label;
            this.spacing = spacing;
            this.separation = separation;
            this.salt = salt;
            this.triangular = triangular;
            this.dimension = dimension;
            this.knownY = knownY;
            this.concentricRings = concentricRings;
            this.frequency = frequency;
            this.locateOffset = locateOffset;
            this.freqMethod = freqMethod;
        }
    }

    /** Mirror of {@code StructurePlacement.FrequencyReductionMethod} so we don't need vanilla class at enum-init time. */
    public enum FreqMethod { DEFAULT, LEGACY_TYPE_1, LEGACY_TYPE_2, LEGACY_TYPE_3 }

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

    private final Setting<Boolean> marker = sgRender.add(new BoolSetting.Builder()
        .name("đánh-dấu")
        .description("Vẽ chấm vằng (ô phẳng) tại vị trí công trình — kể cả dưới lòng đất.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> markerSize = sgRender.add(new IntSetting.Builder()
        .name("kích-thước-chấm")
        .description("Bán kính ô đánh dấu (block). Càng lớn càng dễ thấy từ xa.")
        .defaultValue(8).min(2).sliderMin(2).sliderMax(32)
        .build());

    private final Setting<Boolean> beam = sgRender.add(new BoolSetting.Builder()
        .name("cột-sáng")
        .description("Vẽ cột sáng từ mặt đất lên trời để nhìn từ xa.")
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

    private final Setting<Boolean> chatDump = sgRender.add(new BoolSetting.Builder()
        .name("in-toạ-độ-ra-chat")
        .description("Sau mỗi lần quét, in danh sách công trình gần nhất ra chat để đối chiếu.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> biomeFilter = sgGeneral.add(new BoolSetting.Builder()
        .name("lọc-theo-biome")
        .description("Loại bỏ ngẫu nhiên RNG đúng nhưng biome sai (VD làng giữa biển). Lần quét đầu mất 5-15s để nạp biome noise — chấp nhận được.")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> colorSide = sgRender.add(new ColorSetting.Builder()
        .name("màu-nền")
        .description("Màu nền chấm (có alpha).")
        .defaultValue(new SettingColor(180, 80, 255, 120))
        .build());

    private final Setting<SettingColor> colorLine = sgRender.add(new ColorSetting.Builder()
        .name("màu-viền")
        .description("Màu viền và cột sáng.")
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
            case STRONGHOLD, END_CITY, ANCIENT_CITY, WOODLAND_MANSION,
                 OCEAN_MONUMENT, NETHER_FORTRESS, BASTION_REMNANT,
                 VILLAGE, TRIAL_CHAMBERS, BURIED_TREASURE -> true;
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

        boolean doBiomeCheck = biomeFilter.get();
        if (doBiomeCheck) {
            try {
                BiomeSampler.setSeed(seed);
            } catch (Throwable t) {
                // If biome sampler fails to initialise, scan without biome filter.
                doBiomeCheck = false;
                final Throwable fail = t;
                if (mc != null) mc.execute(() -> ChatUtils.warning(
                    "Tìm-Công-Trình: không nạp được biome filter: %s", fail));
            }
        }

        List<Found> results = new ArrayList<>();

        for (Kind k : Kind.values()) {
            if (!kindEnabled.get(k).get()) continue;

            if (k.concentricRings) {
                for (ChunkPos cp : calculateStrongholds(seed)) {
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
                continue;
            }

            int regionRadius = Math.max(1, (r / 16) / k.spacing + 1);
            int regionCX = Math.floorDiv(centerCX, k.spacing);
            int regionCZ = Math.floorDiv(centerCZ, k.spacing);

            for (int rx = regionCX - regionRadius; rx <= regionCX + regionRadius; rx++) {
                for (int rz = regionCZ - regionRadius; rz <= regionCZ + regionRadius; rz++) {
                    ChunkPos cp = getStartChunk(seed, k, rx, rz);
                    if (k.frequency < 1.0f && !passesFrequency(seed, k, cp.x, cp.z)) continue;
                    int bx = cp.x * 16 + k.locateOffset;
                    int bz = cp.z * 16 + k.locateOffset;
                    double dx = bx - playerX;
                    double dz = bz - playerZ;
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist > r) continue;
                    if (doBiomeCheck && !biomeAllowed(k, bx, bz)) continue;
                    results.add(new Found(k, bx, bz, dist));
                }
                if (epoch != scanEpoch) return;
            }
        }

        results.sort(Comparator.comparingDouble(Found::distance));
        if (epoch != scanEpoch) return;

        found.clear();
        found.addAll(results);

        if (mc != null) mc.execute(() -> {
            applyWaypoints();
            if (chatDump.get()) dumpFoundToChat();
        });
    }

    private void dumpFoundToChat() {
        if (found.isEmpty()) {
            ChatUtils.info("Tìm-Công-Trình: không tìm thấy công trình nào trong bán kính.");
            return;
        }
        int show = Math.min(found.size(), 10);
        ChatUtils.info("Tìm-Công-Trình: tìm thấy %d — %d gần nhất:", found.size(), show);
        for (int i = 0; i < show; i++) {
            Found f = found.get(i);
            ChatUtils.info("  %s §7@§f %d §7/§f %d §7/§f %d  §8(%dm, %s)",
                f.kind.label, f.blockX, f.kind.knownY, f.blockZ,
                (int) f.distance, f.kind.dimension);
        }
    }

    /**
     * Reimplements {@code ConcentricRingsStructurePlacement.calculatePositions}
     * with vanilla stronghold parameters (distance=32, spread=3, count=128).
     * Returns the ~128 nominal stronghold chunks for the given seed; the
     * actual in-world position is snapped to the nearest valid biome chunk,
     * so expect up to a few chunks of deviation from these values.
     */
    private static List<ChunkPos> calculateStrongholds(long seed) {
        final int totalCount = 128;
        final int distance = 32;
        final int initialRingCount = 3;

        ChunkRandom random = new ChunkRandom(new CheckedRandom(0L));
        random.setCarverSeed(seed, 0, 0);
        double angle = random.nextDouble() * Math.PI * 2.0;

        List<ChunkPos> positions = new ArrayList<>(totalCount);
        int inRing = 0;
        int ringIndex = 0;
        int countInRing = initialRingCount;

        for (int k = 0; k < totalCount; k++) {
            double e = (4.0 * distance + distance * ringIndex * 6)
                     + (random.nextDouble() - 0.5) * distance * 2.5;
            int chunkX = (int) Math.round(Math.cos(angle) * e);
            int chunkZ = (int) Math.round(Math.sin(angle) * e);
            positions.add(new ChunkPos(chunkX, chunkZ));

            angle += Math.PI * 2.0 / countInRing;
            inRing++;
            if (inRing == countInRing) {
                ringIndex++;
                inRing = 0;
                countInRing = countInRing + 2 * countInRing / (ringIndex + 1);
                countInRing = Math.min(countInRing, totalCount - (k + 1));
                angle += random.nextDouble() * Math.PI * 2.0;
            }
        }
        return positions;
    }

    private static boolean biomeAllowed(Kind k, int blockX, int blockZ) {
        List<String> allow = switch (k) {
            case VILLAGE          -> BiomeSampler.VILLAGE;
            case PILLAGER_OUTPOST -> BiomeSampler.PILLAGER_OUTPOST;
            case DESERT_PYRAMID   -> BiomeSampler.DESERT_PYRAMID;
            case JUNGLE_PYRAMID   -> BiomeSampler.JUNGLE_TEMPLE;
            case SWAMP_HUT        -> BiomeSampler.SWAMP_HUT;
            case IGLOO            -> BiomeSampler.IGLOO;
            case OCEAN_MONUMENT   -> BiomeSampler.OCEAN_MONUMENT;
            case WOODLAND_MANSION -> BiomeSampler.WOODLAND_MANSION;
            case SHIPWRECK        -> BiomeSampler.SHIPWRECK;
            case ANCIENT_CITY     -> BiomeSampler.ANCIENT_CITY;
            case TRIAL_CHAMBERS   -> BiomeSampler.TRIAL_CHAMBERS;
            case OCEAN_RUIN       -> BiomeSampler.OCEAN_RUIN;
            case TRAIL_RUINS      -> BiomeSampler.TRAIL_RUINS;
            case BURIED_TREASURE  -> BiomeSampler.BURIED_TREASURE;
            case NETHER_FORTRESS  -> BiomeSampler.NETHER_FORTRESS;
            case BASTION_REMNANT  -> BiomeSampler.BASTION_REMNANT;
            case NETHER_FOSSIL    -> BiomeSampler.NETHER_FOSSIL;
            case RUINED_PORTAL_N  -> BiomeSampler.RUINED_PORTAL_N;
            // RUINED_PORTAL_OW spawns in nearly every overworld biome → no useful filter.
            // STRONGHOLD also accepts almost any overworld biome (no useful filter).
            // END_CITY: end biome source not supported here, skip filter.
            default -> null;
        };
        if (allow == null) return true;
        var key = BiomeSampler.sample(k.dimension, blockX, k.knownY, blockZ);
        return BiomeSampler.matchesAny(key, allow);
    }

    /** Calls vanilla {@link StructurePlacement.FrequencyReductionMethod#shouldGenerate} to check rare-spawn structures. */
    private static boolean passesFrequency(long seed, Kind k, int chunkX, int chunkZ) {
        StructurePlacement.FrequencyReductionMethod m = switch (k.freqMethod) {
            case LEGACY_TYPE_1 -> StructurePlacement.FrequencyReductionMethod.LEGACY_TYPE_1;
            case LEGACY_TYPE_2 -> StructurePlacement.FrequencyReductionMethod.LEGACY_TYPE_2;
            case LEGACY_TYPE_3 -> StructurePlacement.FrequencyReductionMethod.LEGACY_TYPE_3;
            default            -> StructurePlacement.FrequencyReductionMethod.DEFAULT;
        };
        return m.shouldGenerate(seed, k.salt, chunkX, chunkZ, k.frequency);
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
                .pos(new BlockPos(fs.blockX, fs.kind.knownY, fs.blockZ))
                .dimension(fs.kind.dimension)
                .build();
            wps.add(wp);
        }
    }

    // ─── Render ─────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (found.isEmpty() || mc.player == null) return;
        if (!marker.get() && !beam.get()) return;

        Color side = colorSide.get();
        Color line = colorLine.get();
        Dimension cur = currentDimension();
        int sz = markerSize.get();

        for (Found fs : found) {
            if (fs.kind.dimension != cur) continue;

            double cx = fs.blockX + 0.5;
            double cz = fs.blockZ + 0.5;
            double y  = fs.kind.knownY;

            if (marker.get()) {
                // Filled horizontal marker disc (actually a square) at structure Y
                event.renderer.sideHorizontal(
                    cx - sz, y, cz - sz,
                    cx + sz, cz + sz,
                    side, line, shape.get());
                // Second, smaller highlight square for a "dot" feel
                int inner = Math.max(1, sz / 2);
                event.renderer.sideHorizontal(
                    cx - inner, y + 0.02, cz - inner,
                    cx + inner, cz + inner,
                    brighter(side), line, shape.get());
            }

            if (beam.get()) {
                // Vertical beam from below-world to sky, visible from afar
                double yLo = -64, yHi = 320;
                event.renderer.line(cx, yLo, cz, cx, yHi, cz, line);
                event.renderer.line(cx + 0.3, yLo, cz, cx + 0.3, yHi, cz, line);
                event.renderer.line(cx - 0.3, yLo, cz, cx - 0.3, yHi, cz, line);
                event.renderer.line(cx, yLo, cz + 0.3, cx, yHi, cz + 0.3, line);
                event.renderer.line(cx, yLo, cz - 0.3, cx, yHi, cz - 0.3, line);
            }
        }
    }

    private static Color brighter(Color c) {
        return new Color(
            Math.min(255, c.r + 60),
            Math.min(255, c.g + 60),
            Math.min(255, c.b + 60),
            Math.min(255, c.a + 40));
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
