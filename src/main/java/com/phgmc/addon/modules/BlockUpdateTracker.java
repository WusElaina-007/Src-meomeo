package com.phgmc.addon.modules;

import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Theo-Doi-Cap-Nhat-Block — listens to BlockUpdateEvent (server-pushed
 * block state changes) and accumulates per-chunk update density with
 * exponential decay. High density over time = active human-driven base
 * (redstone clock, door open/close, item frame rotate, place/break).
 *
 * Filters out natural noise: fluid spread, leaf decay, fire/lava spread,
 * crops/saplings/kelp/sea-grass growth, falling-block gravity. What
 * remains is overwhelmingly player-driven activity.
 *
 * Pure receive-side observation → no packets sent.
 */
public class BlockUpdateTracker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter  = settings.createGroup("Bộ lọc nhiễu");
    private final SettingGroup sgRender  = settings.createGroup("Hiển thị");

    private final Setting<Double> halfLifeMin = sgGeneral.add(new DoubleSetting.Builder()
        .name("half-life-phút")
        .description("Density giảm còn 1/2 sau N phút.")
        .defaultValue(8.0).min(0.5).sliderMax(60.0)
        .build());

    private final Setting<Double> threshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("ngưỡng-density")
        .description("Density của chunk cần đạt để flag là active.")
        .defaultValue(15.0).min(2.0).sliderMax(200.0)
        .build());

    private final Setting<Integer> minDistChunk = sgGeneral.add(new IntSetting.Builder()
        .name("min-distance-chunk")
        .description("Bỏ qua chunk gần player N chunk (tránh log update do bạn gây ra).")
        .defaultValue(2).min(0).sliderMax(8)
        .build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-chat").defaultValue(true).build());

    private final Setting<Boolean> autoWaypoint = sgGeneral.add(new BoolSetting.Builder()
        .name("tự-tạo-waypoint").defaultValue(true).build());

    private final Setting<Boolean> filterFluid = sgFilter.add(new BoolSetting.Builder()
        .name("bỏ-fluid").description("Bỏ water/lava spread updates.").defaultValue(true).build());

    private final Setting<Boolean> filterLeaves = sgFilter.add(new BoolSetting.Builder()
        .name("bỏ-leaves").description("Bỏ leaf decay updates.").defaultValue(true).build());

    private final Setting<Boolean> filterPlant = sgFilter.add(new BoolSetting.Builder()
        .name("bỏ-plant-grow").description("Bỏ crop/sapling/kelp/seagrass/vine growth.").defaultValue(true).build());

    private final Setting<Boolean> filterFire = sgFilter.add(new BoolSetting.Builder()
        .name("bỏ-fire").description("Bỏ fire/soul-fire spread updates.").defaultValue(true).build());

    private final Setting<Boolean> filterGravity = sgFilter.add(new BoolSetting.Builder()
        .name("bỏ-gravity").description("Bỏ sand/gravel/concrete-powder falling.").defaultValue(true).build());

    private final Setting<Double> redstoneWeight = sgFilter.add(new DoubleSetting.Builder()
        .name("trọng-số-redstone")
        .description("Hệ số nhân cho update của block redstone (đặc trưng farm/clock).")
        .defaultValue(2.0).min(0.1).sliderMax(10.0).build());

    private final Setting<Double> storageWeight = sgFilter.add(new DoubleSetting.Builder()
        .name("trọng-số-storage")
        .description("Hệ số nhân cho update của chest/barrel/shulker (đặc trưng base).")
        .defaultValue(3.0).min(0.1).sliderMax(20.0).build());

    private final Setting<Boolean> renderAll = sgRender.add(new BoolSetting.Builder()
        .name("vẽ-mọi-chunk").description("Vẽ tất cả chunk có update (không chỉ chunk vượt ngưỡng).").defaultValue(false).build());

    private final Setting<SettingColor> colCold = sgRender.add(new ColorSetting.Builder()
        .name("màu-thường")
        .defaultValue(new SettingColor(150, 200, 255, 80)).build());

    private final Setting<SettingColor> colHot = sgRender.add(new ColorSetting.Builder()
        .name("màu-nóng")
        .defaultValue(new SettingColor(255, 80, 80, 200)).build());

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
        .name("vẽ-cột-dọc").description("Vẽ cột Y-64→320 (gây che mặt khi nhiều chunk).")
        .defaultValue(false).build());

    private final Setting<Integer> maxRender = sgRender.add(new IntSetting.Builder()
        .name("max-render").defaultValue(48).min(1).sliderMin(8).sliderMax(256).build());

    private static final Set<Block> GRAVITY_BLOCKS = new HashSet<>();
    static {
        GRAVITY_BLOCKS.add(Blocks.SAND);
        GRAVITY_BLOCKS.add(Blocks.RED_SAND);
        GRAVITY_BLOCKS.add(Blocks.GRAVEL);
        GRAVITY_BLOCKS.add(Blocks.WHITE_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.BLACK_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.RED_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.YELLOW_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.BLUE_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.GREEN_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.LIME_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.ORANGE_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.PINK_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.PURPLE_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.MAGENTA_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.CYAN_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.LIGHT_GRAY_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.GRAY_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.LIGHT_BLUE_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.BROWN_CONCRETE_POWDER);
        GRAVITY_BLOCKS.add(Blocks.ANVIL);
        GRAVITY_BLOCKS.add(Blocks.CHIPPED_ANVIL);
        GRAVITY_BLOCKS.add(Blocks.DAMAGED_ANVIL);
        GRAVITY_BLOCKS.add(Blocks.POINTED_DRIPSTONE);
    }

    private static final Set<Block> REDSTONE_BLOCKS = new HashSet<>();
    static {
        REDSTONE_BLOCKS.add(Blocks.REDSTONE_WIRE);
        REDSTONE_BLOCKS.add(Blocks.REDSTONE_TORCH);
        REDSTONE_BLOCKS.add(Blocks.REDSTONE_WALL_TORCH);
        REDSTONE_BLOCKS.add(Blocks.REPEATER);
        REDSTONE_BLOCKS.add(Blocks.COMPARATOR);
        REDSTONE_BLOCKS.add(Blocks.OBSERVER);
        REDSTONE_BLOCKS.add(Blocks.PISTON);
        REDSTONE_BLOCKS.add(Blocks.STICKY_PISTON);
        REDSTONE_BLOCKS.add(Blocks.PISTON_HEAD);
        REDSTONE_BLOCKS.add(Blocks.MOVING_PISTON);
        REDSTONE_BLOCKS.add(Blocks.DISPENSER);
        REDSTONE_BLOCKS.add(Blocks.DROPPER);
        REDSTONE_BLOCKS.add(Blocks.LEVER);
        REDSTONE_BLOCKS.add(Blocks.STONE_BUTTON);
        REDSTONE_BLOCKS.add(Blocks.OAK_BUTTON);
        REDSTONE_BLOCKS.add(Blocks.STONE_PRESSURE_PLATE);
        REDSTONE_BLOCKS.add(Blocks.OAK_PRESSURE_PLATE);
        REDSTONE_BLOCKS.add(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE);
        REDSTONE_BLOCKS.add(Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE);
        REDSTONE_BLOCKS.add(Blocks.TARGET);
        REDSTONE_BLOCKS.add(Blocks.TRIPWIRE);
        REDSTONE_BLOCKS.add(Blocks.TRIPWIRE_HOOK);
        REDSTONE_BLOCKS.add(Blocks.NOTE_BLOCK);
        REDSTONE_BLOCKS.add(Blocks.TNT);
        REDSTONE_BLOCKS.add(Blocks.REDSTONE_LAMP);
        REDSTONE_BLOCKS.add(Blocks.HOPPER);
        REDSTONE_BLOCKS.add(Blocks.IRON_DOOR);
        REDSTONE_BLOCKS.add(Blocks.IRON_TRAPDOOR);
        REDSTONE_BLOCKS.add(Blocks.DAYLIGHT_DETECTOR);
    }

    public static class ChunkStat {
        public int cx, cz;
        public double density;
        public long lastUpdateMs;
        public boolean reported;
    }

    private final Map<Long, ChunkStat> stats = new ConcurrentHashMap<>();
    private long lastDecayMs = System.currentTimeMillis();

    public BlockUpdateTracker() {
        super(PhgMCAddon.PhgMC_Support, "Theo-Doi-Cap-Nhat-Block",
            "Phát hiện base active qua mật độ block update (door/chest/redstone/place/break).");
    }

    @Override public void onActivate() { stats.clear(); lastDecayMs = System.currentTimeMillis(); }

    private boolean isNoise(BlockState oldS, BlockState newS) {
        Block ob = oldS.getBlock();
        Block nb = newS.getBlock();
        if (filterFluid.get()) {
            FluidState fo = oldS.getFluidState();
            FluidState fn = newS.getFluidState();
            // pure fluid <-> fluid level change with same block
            if (ob == nb && (!fo.isEmpty() || !fn.isEmpty()) && oldS.isAir() == newS.isAir()) {
                if (ob == Blocks.WATER || ob == Blocks.LAVA) return true;
            }
            if (ob == Blocks.WATER && nb == Blocks.AIR) return true;
            if (ob == Blocks.LAVA && nb == Blocks.AIR) return true;
            if (ob == Blocks.AIR && (nb == Blocks.WATER || nb == Blocks.LAVA)) return true;
        }
        if (filterLeaves.get()) {
            String name = nb.toString().toLowerCase();
            if (name.contains("leaves")) return true;
            if (ob.toString().toLowerCase().contains("leaves") && nb == Blocks.AIR) return true;
        }
        if (filterPlant.get()) {
            String n = nb.toString().toLowerCase();
            if (n.contains("crop") || n.contains("kelp") || n.contains("seagrass") || n.contains("vine")
                || n.contains("sapling") || n.contains("bamboo") || n.contains("chorus_flower")
                || n.contains("sweet_berry") || n.contains("cocoa") || n.contains("sugar_cane")
                || n.contains("nether_wart") || n.contains("twisting_vines") || n.contains("weeping_vines")
                || n.contains("cave_vines") || nb == Blocks.WHEAT || nb == Blocks.CARROTS
                || nb == Blocks.POTATOES || nb == Blocks.BEETROOTS || nb == Blocks.MELON_STEM
                || nb == Blocks.PUMPKIN_STEM || nb == Blocks.ATTACHED_MELON_STEM
                || nb == Blocks.ATTACHED_PUMPKIN_STEM) return true;
        }
        if (filterFire.get()) {
            if (nb == Blocks.FIRE || nb == Blocks.SOUL_FIRE) return true;
            if (ob == Blocks.FIRE || ob == Blocks.SOUL_FIRE) return true;
        }
        if (filterGravity.get()) {
            if (GRAVITY_BLOCKS.contains(ob) && nb == Blocks.AIR) return true;
            if (GRAVITY_BLOCKS.contains(nb) && ob == Blocks.AIR) return true;
        }
        return false;
    }

    private double weightFor(BlockState oldS, BlockState newS) {
        Block ob = oldS.getBlock();
        Block nb = newS.getBlock();
        double w = 1.0;
        if (REDSTONE_BLOCKS.contains(ob) || REDSTONE_BLOCKS.contains(nb)) w *= redstoneWeight.get();
        // Check storage (chest/barrel/shulker/hopper) via block-entity types where possible
        if (mc.world != null) {
            try {
                BlockEntity be = mc.world.getBlockEntity(BlockPos.ORIGIN); // placeholder; recheck per pos in handler
                // We'll handle storage weight in onBlockUpdate using pos directly
            } catch (Throwable ignored) {}
        }
        return w;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent e) {
        if (mc.player == null || mc.world == null) return;
        BlockPos pos = e.pos;
        BlockState oldS = e.oldState;
        BlockState newS = e.newState;
        if (oldS == null || newS == null) return;
        if (oldS == newS) return;
        if (isNoise(oldS, newS)) return;

        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int pcx = mc.player.getBlockX() >> 4;
        int pcz = mc.player.getBlockZ() >> 4;
        int dist = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
        if (dist < minDistChunk.get()) return;

        double w = weightFor(oldS, newS);
        // Storage weight check via current block entity at pos
        try {
            BlockEntity be = mc.world.getBlockEntity(pos);
            if (be instanceof ChestBlockEntity || be instanceof ShulkerBoxBlockEntity || be instanceof HopperBlockEntity) {
                w *= storageWeight.get();
            }
        } catch (Throwable ignored) {}

        long key = ChunkPos.toLong(cx, cz);
        ChunkStat st = stats.computeIfAbsent(key, k -> {
            ChunkStat s = new ChunkStat();
            s.cx = cx; s.cz = cz; return s;
        });
        st.density += w;
        st.lastUpdateMs = System.currentTimeMillis();

        if (!st.reported && st.density >= threshold.get()) {
            st.reported = true;
            if (announce.get()) {
                ChatUtils.info("Theo-Doi-Cap-Nhat-Block: §echunk %d,%d§r density §c%.1f§r → có hoạt động",
                    cx, cz, st.density);
            }
            if (autoWaypoint.get()) {
                try {
                    Waypoint wp = new Waypoint.Builder()
                        .name("[Update] " + cx + "," + cz)
                        .icon("circle")
                        .pos(new BlockPos(cx * 16 + 8, 70, cz * 16 + 8))
                        .build();
                    Waypoints.get().add(wp);
                } catch (Throwable ignored) {}
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        long now = System.currentTimeMillis();
        if (now - lastDecayMs < 5_000) return;
        double dtMin = (now - lastDecayMs) / 60000.0;
        lastDecayMs = now;
        double hl = Math.max(0.01, halfLifeMin.get());
        double f = Math.pow(0.5, dtMin / hl);
        for (ChunkStat s : stats.values()) s.density *= f;
        stats.values().removeIf(s -> s.density < 0.05);
    }

    @EventHandler
    private void onRender(Render3DEvent e) {
        if (stats.isEmpty()) return;
        RenderMode rm = renderMode.get();
        if (rm == RenderMode.Off) return;
        double th = threshold.get();
        double max = stats.values().stream().mapToDouble(s -> s.density).max().orElse(th);
        Color cold = colCold.get();
        Color hot = colHot.get();
        ShapeMode sh = shape.get();
        double py = mc.player != null ? mc.player.getY() + flatYOffset.get() : 64;
        int pcx = mc.player == null ? 0 : (mc.player.getBlockX() >> 4);
        int pcz = mc.player == null ? 0 : (mc.player.getBlockZ() >> 4);
        int limit = maxRender.get();
        boolean col = drawColumn.get();

        java.util.List<ChunkStat> visible = stats.values().stream()
            .filter(s -> renderAll.get() || s.density >= th)
            .sorted(java.util.Comparator.comparingInt(s -> {
                int dx = s.cx - pcx, dz = s.cz - pcz; return dx * dx + dz * dz;
            }))
            .limit(limit)
            .toList();

        for (ChunkStat s : visible) {
            float t = (float) Math.min(1.0, s.density / Math.max(1.0, max));
            Color c = lerp(cold, hot, t);
            double x0 = s.cx * 16, z0 = s.cz * 16;
            double x1 = x0 + 16, z1 = z0 + 16;
            switch (rm) {
                case Box  -> e.renderer.box(x0, -64, z0, x1, 320, z1, c, c, sh, 0);
                case Top  -> e.renderer.box(x0, topY.get(), z0, x1, topY.get() + 0.05, z1, c, c, sh, 0);
                case Flat -> e.renderer.box(x0, py, z0, x1, py + 0.05, z1, c, c, sh, 0);
                default   -> {}
            }
            if (col) e.renderer.line(x0 + 8, -64, z0 + 8, x0 + 8, 320, z0 + 8, c);
        }
    }

    private static Color lerp(Color a, Color b, float t) {
        return new Color(
            (int) (a.r + (b.r - a.r) * t),
            (int) (a.g + (b.g - a.g) * t),
            (int) (a.b + (b.b - a.b) * t),
            (int) (a.a + (b.a - a.a) * t));
    }

    public java.util.Collection<ChunkStat> snapshot() {
        return new java.util.ArrayList<>(stats.values());
    }

    @Override
    public String getInfoString() {
        long active = stats.values().stream().filter(s -> s.density >= threshold.get()).count();
        return "§c" + active + "§7/§7" + stats.size();
    }
}
