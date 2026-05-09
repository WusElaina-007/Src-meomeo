package com.phgmc.addon.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * So-Sanh-Chunk-Hash — fingerprints every received chunk and saves the
 * hash to {@code config/phgmc/chunks.json}, keyed by (dimension, cx, cz).
 * On revisit, a different hash means the chunk's contents changed since
 * last visit → an active base. Persistent across sessions, undetectable.
 *
 * Hash is composed of two parts so that any human-driven change shows up:
 *   feHash  — block-entity positions + types (chest add/remove, sign edits…)
 *   palHash — palette-block-id sum per section (catches plain block place/break)
 */
public class ChunkChangeRecorder extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Hiển thị");

    private final Setting<Integer> minDistChunk = sgGeneral.add(new IntSetting.Builder()
        .name("min-distance-chunk")
        .description("Bỏ qua chunk gần player (tránh log block bạn vừa đặt).")
        .defaultValue(2).min(0).sliderMax(8).build());

    private final Setting<Integer> minSecondsBetween = sgGeneral.add(new IntSetting.Builder()
        .name("min-seconds-giữa-2-lần")
        .description("Chỉ flag thay đổi nếu lần xem trước cách đây N giây trở lên.")
        .defaultValue(120).min(5).sliderMax(86400).build());

    private final Setting<Integer> maxEntries = sgGeneral.add(new IntSetting.Builder()
        .name("max-entries")
        .description("Số chunk tối đa lưu trong DB. FIFO khi vượt.")
        .defaultValue(50000).min(100).sliderMax(500000).build());

    private final Setting<Integer> saveEveryTicks = sgGeneral.add(new IntSetting.Builder()
        .name("save-mỗi-tick")
        .description("Save DB ra file sau mỗi N tick.")
        .defaultValue(400).min(20).sliderMax(2400).build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-chat").defaultValue(true).build());

    private final Setting<Boolean> autoWaypoint = sgGeneral.add(new BoolSetting.Builder()
        .name("tự-tạo-waypoint").defaultValue(true).build());

    private final Setting<Boolean> requireFeChange = sgGeneral.add(new BoolSetting.Builder()
        .name("yêu-cầu-block-entity-đổi")
        .description("Chỉ flag nếu block entity (chest/sign/...) đổi. Tắt để bắt cả block thường.")
        .defaultValue(false).build());

    private final Setting<SettingColor> col = sgRender.add(new ColorSetting.Builder()
        .name("màu")
        .defaultValue(new SettingColor(255, 200, 50, 100)).build());

    private final Setting<SettingColor> line = sgRender.add(new ColorSetting.Builder()
        .name("viền")
        .defaultValue(new SettingColor(255, 220, 100, 220)).build());

    private final Setting<ShapeMode> shape = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("kiểu-hiển-thị").defaultValue(ShapeMode.Lines).build());

    public enum RenderMode { Flat, Top, Box, Off }

    private final Setting<RenderMode> renderMode = sgRender.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode").defaultValue(RenderMode.Flat).build());

    private final Setting<Integer> flatYOffset = sgRender.add(new IntSetting.Builder()
        .name("flat-y-offset").defaultValue(0).min(-64).sliderMin(-32).sliderMax(64).build());

    private final Setting<Integer> topY = sgRender.add(new IntSetting.Builder()
        .name("top-y").defaultValue(120).min(-64).sliderMin(-64).sliderMax(320).build());

    private final Setting<Boolean> drawColumn = sgRender.add(new BoolSetting.Builder()
        .name("vẽ-cột-dọc").defaultValue(false).build());

    private final Setting<Integer> maxRender = sgRender.add(new IntSetting.Builder()
        .name("max-render").defaultValue(48).min(1).sliderMin(8).sliderMax(256).build());

    public static class Snap {
        public long feHash;
        public long palHash;
        public long timestampMs;
    }

    public static class Change {
        public int cx, cz;
        public String dimension;
        public long oldFe, newFe;
        public long oldPal, newPal;
        public long firstSeenMs;
        public long changedAtMs;
    }

    /** Persisted database: dimension|cx|cz → snap. */
    private final Map<String, Snap> db = new ConcurrentHashMap<>();
    /** Visible changed chunks for current world. */
    private final Map<Long, Change> visible = new ConcurrentHashMap<>();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private int tickCounter = 0;
    private boolean dirty = false;

    public ChunkChangeRecorder() {
        super(PhgMCAddon.PhgMC_Support, "So-Sanh-Chunk-Hash",
            "Lưu hash chunk qua các session, flag chunk thay đổi = base active.");
    }

    @Override public void onActivate() { visible.clear(); load(); }
    @Override public void onDeactivate() { if (dirty) save(); }

    private static String keyFor(String dim, int cx, int cz) {
        return dim + "|" + cx + "|" + cz;
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent e) {
        if (mc.world == null || mc.player == null) return;
        WorldChunk c = e.chunk();
        ChunkPos cp = c.getPos();
        int pcx = mc.player.getBlockX() >> 4;
        int pcz = mc.player.getBlockZ() >> 4;
        if (Math.max(Math.abs(cp.x - pcx), Math.abs(cp.z - pcz)) < minDistChunk.get()) return;

        String dim = mc.world.getRegistryKey().getValue().toString();
        long feHash = computeFeHash(c);
        long palHash = computePalHash(c);
        long now = System.currentTimeMillis();
        String k = keyFor(dim, cp.x, cp.z);

        Snap prev = db.get(k);
        if (prev != null) {
            long elapsed = (now - prev.timestampMs) / 1000;
            boolean feChanged = prev.feHash != feHash;
            boolean palChanged = prev.palHash != palHash;
            boolean changed = requireFeChange.get() ? feChanged : (feChanged || palChanged);
            if (changed && elapsed >= minSecondsBetween.get()) {
                Change ch = new Change();
                ch.cx = cp.x; ch.cz = cp.z; ch.dimension = dim;
                ch.oldFe = prev.feHash; ch.newFe = feHash;
                ch.oldPal = prev.palHash; ch.newPal = palHash;
                ch.firstSeenMs = prev.timestampMs;
                ch.changedAtMs = now;
                visible.put(ChunkPos.toLong(cp.x, cp.z), ch);
                if (announce.get()) {
                    ChatUtils.info("So-Sanh-Chunk-Hash: §echunk %d,%d§r §6đổi§r sau §7%s§r§7 %s%s",
                        cp.x, cp.z, fmtElapsed(elapsed),
                        feChanged ? "§a[fe]§r" : "",
                        palChanged ? "§b[pal]§r" : "");
                }
                if (autoWaypoint.get()) {
                    try {
                        Waypoint wp = new Waypoint.Builder()
                            .name("[Đổi] " + cp.x + "," + cp.z)
                            .icon("circle")
                            .pos(new BlockPos(cp.x * 16 + 8, 70, cp.z * 16 + 8))
                            .build();
                        Waypoints.get().add(wp);
                    } catch (Throwable ignored) {}
                }
            }
        }

        Snap s = new Snap();
        s.feHash = feHash; s.palHash = palHash; s.timestampMs = now;
        db.put(k, s);
        dirty = true;
        if (db.size() > maxEntries.get()) {
            // FIFO: drop oldest entries
            db.entrySet().stream()
                .sorted(Comparator.comparingLong(en -> en.getValue().timestampMs))
                .limit(db.size() - maxEntries.get())
                .map(Map.Entry::getKey)
                .forEach(db::remove);
        }
    }

    private static long computeFeHash(WorldChunk c) {
        long h = 1469598103934665603L; // FNV offset basis
        for (Map.Entry<BlockPos, BlockEntity> en : c.getBlockEntities().entrySet()) {
            BlockPos p = en.getKey();
            BlockEntity be = en.getValue();
            long v = ((long) p.getX() & 0xFFFF) << 32 | ((long) p.getY() & 0xFFFF) << 16 | (p.getZ() & 0xFFFF);
            v ^= be.getType().hashCode();
            h ^= v;
            h *= 1099511628211L;
        }
        return h;
    }

    private static long computePalHash(WorldChunk c) {
        long h = 1469598103934665603L;
        ChunkSection[] secs = c.getSectionArray();
        for (int i = 0; i < secs.length; i++) {
            ChunkSection sec = secs[i];
            if (sec == null || sec.isEmpty()) continue;
            long[] sum = {0};
            sec.getBlockStateContainer().count((BlockState st, int n) -> {
                if (st.isAir()) return;
                sum[0] += (long) st.getBlock().hashCode() * n;
            });
            h ^= ((long) i << 32) ^ sum[0];
            h *= 1099511628211L;
        }
        return h;
    }

    private static String fmtElapsed(long s) {
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m" + (s % 60) + "s";
        if (s < 86400) return (s / 3600) + "h" + ((s % 3600) / 60) + "m";
        return (s / 86400) + "d" + ((s % 86400) / 3600) + "h";
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (++tickCounter < saveEveryTicks.get()) return;
        tickCounter = 0;
        if (dirty) save();
    }

    @EventHandler
    private void onRender(Render3DEvent e) {
        if (visible.isEmpty()) return;
        RenderMode rm = renderMode.get();
        if (rm == RenderMode.Off) return;
        Color s = col.get(); Color l = line.get(); ShapeMode sh = shape.get();
        double py = mc.player != null ? mc.player.getY() + flatYOffset.get() : 64;
        int pcx = mc.player == null ? 0 : (mc.player.getBlockX() >> 4);
        int pcz = mc.player == null ? 0 : (mc.player.getBlockZ() >> 4);
        boolean drawCol = drawColumn.get();

        java.util.List<Change> list = visible.values().stream()
            .sorted(java.util.Comparator.comparingInt(ch -> {
                int dx = ch.cx - pcx, dz = ch.cz - pcz; return dx * dx + dz * dz;
            }))
            .limit(maxRender.get())
            .toList();

        for (Change ch : list) {
            double x0 = ch.cx * 16, z0 = ch.cz * 16;
            double x1 = x0 + 16, z1 = z0 + 16;
            switch (rm) {
                case Box  -> e.renderer.box(x0, -64, z0, x1, 320, z1, s, l, sh, 0);
                case Top  -> e.renderer.box(x0, topY.get(), z0, x1, topY.get() + 0.05, z1, s, l, sh, 0);
                case Flat -> e.renderer.box(x0, py, z0, x1, py + 0.05, z1, s, l, sh, 0);
                default   -> {}
            }
            if (drawCol) e.renderer.line(x0 + 8, -64, z0 + 8, x0 + 8, 320, z0 + 8, l);
        }
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("phgmc").resolve("chunks.json");
    }

    private void load() {
        try {
            Path p = file();
            if (!Files.exists(p)) return;
            String json = Files.readString(p);
            Map<String, Snap> loaded = gson.fromJson(json,
                new com.google.gson.reflect.TypeToken<HashMap<String, Snap>>(){}.getType());
            if (loaded != null) {
                db.clear();
                db.putAll(loaded);
            }
        } catch (Throwable ignored) {}
    }

    private void save() {
        try {
            Path p = file();
            Files.createDirectories(p.getParent());
            Files.writeString(p, gson.toJson(db));
            dirty = false;
        } catch (IOException ignored) {}
    }

    public Map<Long, Change> snapshot() {
        return new HashMap<>(visible);
    }

    @Override
    public String getInfoString() {
        return visible.size() + "§7/§7" + db.size();
    }
}
