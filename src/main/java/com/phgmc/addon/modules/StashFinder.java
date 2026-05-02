package com.phgmc.addon.modules;

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
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Stash-Finder — smart filter on incoming chunks. Flags chunks that look like
 * a stash / chunk loader: many storage block entities OR persistently loaded
 * outside the player's normal render area. Pure observation → no packets,
 * undetectable.
 *
 * Two heuristics combined:
 *  1. Storage density   — number of chests/shulker/hopper per chunk ≥ threshold.
 *  2. Persistent loading — chunk has been loaded for ≥ {@code chunk-loader-age}
 *                          seconds while distance from player ≥ {@code max-dist}
 *                          chunks (i.e. not kept loaded by you walking nearby).
 */
public class StashFinder extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Hiển thị");

    private final Setting<Integer> minStorage = sgGeneral.add(new IntSetting.Builder()
        .name("min-storage")
        .description("Số chest+shulker+hopper tối thiểu trong 1 chunk để coi là stash.")
        .defaultValue(4).min(1).sliderMin(1).sliderMax(20)
        .build());

    private final Setting<Integer> chunkLoaderAge = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-loader-age")
        .description("Số giây 1 chunk được giữ load liên tục → coi là chunk loader.")
        .defaultValue(45).min(10).sliderMin(10).sliderMax(600)
        .build());

    private final Setting<Integer> chunkLoaderMaxDist = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-loader-max-dist")
        .description("Khoảng cách (chunk) tối thiểu để chunk không phải do bạn load.")
        .defaultValue(10).min(4).sliderMin(4).sliderMax(32)
        .build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-chat")
        .description("Báo chat khi phát hiện stash mới.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoWaypoint = sgGeneral.add(new BoolSetting.Builder()
        .name("tự-tạo-waypoint")
        .description("Tự thêm waypoint ([Stash]) cho mỗi chunk khả nghi.")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> stashCol = sgRender.add(new ColorSetting.Builder()
        .name("màu-stash")
        .defaultValue(new SettingColor(255, 80, 60, 100))
        .build());

    private final Setting<SettingColor> stashLine = sgRender.add(new ColorSetting.Builder()
        .name("viền-stash")
        .defaultValue(new SettingColor(255, 120, 80, 220))
        .build());

    private final Setting<SettingColor> loaderCol = sgRender.add(new ColorSetting.Builder()
        .name("màu-chunk-loader")
        .defaultValue(new SettingColor(255, 220, 60, 80))
        .build());

    private final Setting<SettingColor> loaderLine = sgRender.add(new ColorSetting.Builder()
        .name("viền-chunk-loader")
        .defaultValue(new SettingColor(255, 240, 80, 220))
        .build());

    private final Setting<ShapeMode> shape = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("kiểu-hiển-thị")
        .defaultValue(ShapeMode.Both)
        .build());

    private static final String WP_PREFIX = "[Stash] ";

    public static class Stash {
        public long chunkKey;
        public int chunkX;
        public int chunkZ;
        public int storageCount;
        public long firstLoadedMs;
        public long lastSeenMs;
        public boolean reported;
        public boolean reportedAsLoader;
        public String dimension;
    }

    private final Map<Long, Stash> stashes = new ConcurrentHashMap<>();
    private final Map<Long, Long> loadedAt = new ConcurrentHashMap<>();
    private int tickCounter;

    public StashFinder() {
        super(PhgMCAddon.PhgMC_Support, "Stash-Finder",
            "Phát hiện stash + chunk loader thông qua phân tích chunk vào client.");
    }

    @Override
    public void onActivate() {
        stashes.clear();
        loadedAt.clear();
        tickCounter = 0;
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent e) {
        if (mc.world == null) return;
        WorldChunk c = e.chunk();
        long key = ChunkPos.toLong(c.getPos().x, c.getPos().z);
        long now = System.currentTimeMillis();
        loadedAt.putIfAbsent(key, now);

        int count = 0;
        for (BlockEntity be : c.getBlockEntities().values()) {
            if (be instanceof ChestBlockEntity
                || be instanceof ShulkerBoxBlockEntity
                || be instanceof HopperBlockEntity) {
                count++;
            }
        }

        if (count >= minStorage.get()) {
            Stash s = stashes.computeIfAbsent(key, k -> {
                Stash ns = new Stash();
                ns.chunkKey = k;
                ns.chunkX = c.getPos().x;
                ns.chunkZ = c.getPos().z;
                ns.firstLoadedMs = now;
                ns.dimension = mc.world.getRegistryKey().getValue().toString();
                return ns;
            });
            s.storageCount = Math.max(s.storageCount, count);
            s.lastSeenMs = now;

            if (!s.reported) {
                s.reported = true;
                if (announce.get()) {
                    ChatUtils.info("Stash-Finder: §c%d storage§r in chunk §e%d, %d§r (%s)",
                        s.storageCount, s.chunkX, s.chunkZ, s.dimension);
                }
                if (autoWaypoint.get()) addWaypoint(s, "stash");
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre ev) {
        if (mc.world == null || mc.player == null) return;
        if (++tickCounter < 20) return;
        tickCounter = 0;

        long now = System.currentTimeMillis();
        long ageMs = chunkLoaderAge.get() * 1000L;
        int maxDist = chunkLoaderMaxDist.get();
        int pcx = mc.player.getBlockX() >> 4;
        int pcz = mc.player.getBlockZ() >> 4;

        for (Map.Entry<Long, Long> entry : loadedAt.entrySet()) {
            long key = entry.getKey();
            long firstLoad = entry.getValue();
            if (now - firstLoad < ageMs) continue;
            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            int dx = Math.abs(cx - pcx);
            int dz = Math.abs(cz - pcz);
            if (Math.max(dx, dz) < maxDist) continue;

            Stash s = stashes.get(key);
            if (s == null) {
                s = new Stash();
                s.chunkKey = key;
                s.chunkX = cx;
                s.chunkZ = cz;
                s.firstLoadedMs = firstLoad;
                s.dimension = mc.world.getRegistryKey().getValue().toString();
                stashes.put(key, s);
            }
            s.lastSeenMs = now;

            if (!s.reportedAsLoader) {
                s.reportedAsLoader = true;
                if (announce.get()) {
                    ChatUtils.info("Stash-Finder: §6chunk loader§r ở chunk §e%d, %d§r (%ds)",
                        cx, cz, (int) ((now - firstLoad) / 1000));
                }
                if (autoWaypoint.get()) addWaypoint(s, "loader");
            }
        }
    }

    private void addWaypoint(Stash s, String tag) {
        try {
            Waypoint wp = new Waypoint.Builder()
                .name(WP_PREFIX + tag + " " + s.chunkX + "," + s.chunkZ)
                .icon("diamond")
                .pos(new BlockPos(s.chunkX * 16 + 8, 64, s.chunkZ * 16 + 8))
                .build();
            Waypoints.get().add(wp);
        } catch (Throwable ignored) {}
    }

    @EventHandler
    private void onRender(Render3DEvent e) {
        if (stashes.isEmpty()) return;
        Color sc = stashCol.get();
        Color sl = stashLine.get();
        Color lc = loaderCol.get();
        Color ll = loaderLine.get();
        ShapeMode sh = shape.get();
        for (Stash s : stashes.values()) {
            double x0 = s.chunkX * 16, z0 = s.chunkZ * 16;
            double x1 = x0 + 16, z1 = z0 + 16;
            if (s.reported) {
                e.renderer.box(x0, -64, z0, x1, 320, z1, sc, sl, sh, 0);
            } else if (s.reportedAsLoader) {
                e.renderer.box(x0, -64, z0, x1, 320, z1, lc, ll, sh, 0);
            }
        }
    }

    public java.util.Collection<Stash> snapshot() {
        return new java.util.ArrayList<>(stashes.values());
    }

    /** Returns top N most recent stashes. */
    public List<Stash> top(int n) {
        return stashes.values().stream()
            .sorted(Comparator.comparingLong((Stash s) -> s.lastSeenMs).reversed())
            .limit(n)
            .collect(Collectors.toList());
    }

    @Override
    public String getInfoString() {
        long stashCount = stashes.values().stream().filter(s -> s.reported).count();
        long loaderCount = stashes.values().stream().filter(s -> s.reportedAsLoader).count();
        return "§c" + stashCount + "§7 stash §6" + loaderCount + "§7 loader";
    }
}
