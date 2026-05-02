package com.phgmc.addon.modules;

import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Tim-Ho-Cu — scans every loaded chunk for signs of player-dug terrain:
 *   - light sources (torch / lantern / glowstone / sea-lantern / etc.)
 *     placed below Y=50 (caves don't naturally place these)
 *   - cobblestone clusters in cave / deepslate area (player walling)
 *   - 1×1 vertical mining shafts (a column of air ≥5 deep through stone)
 *
 * Each finding becomes a "spot" with classification + position. Renders
 * boxes + auto-waypoints. Pure observation on chunks already streamed to
 * client. No packets sent.
 */
public class HoleHunter extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Hiển thị");

    private final Setting<Boolean> scanLights = sgGeneral.add(new BoolSetting.Builder()
        .name("scan-light-source")
        .description("Tìm torch / lantern / glowstone dưới Y=50 (= base lighting).")
        .defaultValue(true).build());

    private final Setting<Integer> lightYMax = sgGeneral.add(new IntSetting.Builder()
        .name("light-y-max")
        .description("Y tối đa khi scan light. Trên giá trị này coi là surface, không log.")
        .defaultValue(50).min(-32).sliderMin(-32).sliderMax(80).build());

    private final Setting<Boolean> scanShafts = sgGeneral.add(new BoolSetting.Builder()
        .name("scan-shaft")
        .description("Tìm 1×1 mining shaft (cột air đào xuống).")
        .defaultValue(true).build());

    private final Setting<Integer> shaftMinDepth = sgGeneral.add(new IntSetting.Builder()
        .name("shaft-min-depth")
        .description("Độ sâu tối thiểu của shaft để báo (block).")
        .defaultValue(8).min(3).sliderMax(64).build());

    private final Setting<Boolean> scanCobble = sgGeneral.add(new BoolSetting.Builder()
        .name("scan-cobble-deep")
        .description("Tìm cobble/stone-bricks Y < 30 (player walling). Hơi tốn CPU.")
        .defaultValue(false).build());

    private final Setting<Integer> cobbleClusterMin = sgGeneral.add(new IntSetting.Builder()
        .name("cobble-cluster-min")
        .description("Số cobble/stone-bricks tối thiểu trong 1 chunk Y<30 để báo.")
        .defaultValue(20).min(5).sliderMax(200).build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-chat").defaultValue(true).build());

    private final Setting<Boolean> autoWaypoint = sgGeneral.add(new BoolSetting.Builder()
        .name("tự-tạo-waypoint").defaultValue(true).build());

    private final Setting<SettingColor> colLight = sgRender.add(new ColorSetting.Builder()
        .name("màu-light").defaultValue(new SettingColor(255, 220, 80, 100)).build());
    private final Setting<SettingColor> colShaft = sgRender.add(new ColorSetting.Builder()
        .name("màu-shaft").defaultValue(new SettingColor(255, 100, 100, 90)).build());
    private final Setting<SettingColor> colCobble = sgRender.add(new ColorSetting.Builder()
        .name("màu-cobble").defaultValue(new SettingColor(180, 180, 180, 90)).build());
    private final Setting<SettingColor> outline = sgRender.add(new ColorSetting.Builder()
        .name("outline").defaultValue(new SettingColor(255, 255, 255, 220)).build());
    private final Setting<ShapeMode> shape = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape").defaultValue(ShapeMode.Both).build());

    private static final Set<Block> LIGHT_SOURCES = new HashSet<>();
    static {
        LIGHT_SOURCES.add(Blocks.TORCH);
        LIGHT_SOURCES.add(Blocks.WALL_TORCH);
        LIGHT_SOURCES.add(Blocks.SOUL_TORCH);
        LIGHT_SOURCES.add(Blocks.SOUL_WALL_TORCH);
        LIGHT_SOURCES.add(Blocks.LANTERN);
        LIGHT_SOURCES.add(Blocks.SOUL_LANTERN);
        LIGHT_SOURCES.add(Blocks.GLOWSTONE);
        LIGHT_SOURCES.add(Blocks.SEA_LANTERN);
        LIGHT_SOURCES.add(Blocks.JACK_O_LANTERN);
        LIGHT_SOURCES.add(Blocks.REDSTONE_LAMP);
        LIGHT_SOURCES.add(Blocks.SHROOMLIGHT);
        LIGHT_SOURCES.add(Blocks.OCHRE_FROGLIGHT);
        LIGHT_SOURCES.add(Blocks.VERDANT_FROGLIGHT);
        LIGHT_SOURCES.add(Blocks.PEARLESCENT_FROGLIGHT);
        LIGHT_SOURCES.add(Blocks.BEACON);
        LIGHT_SOURCES.add(Blocks.CONDUIT);
        LIGHT_SOURCES.add(Blocks.END_ROD);
    }

    private static final Set<Block> COBBLE_LIKE = new HashSet<>();
    static {
        COBBLE_LIKE.add(Blocks.COBBLESTONE);
        COBBLE_LIKE.add(Blocks.MOSSY_COBBLESTONE);
        COBBLE_LIKE.add(Blocks.STONE_BRICKS);
        COBBLE_LIKE.add(Blocks.CRACKED_STONE_BRICKS);
        COBBLE_LIKE.add(Blocks.CHISELED_STONE_BRICKS);
        COBBLE_LIKE.add(Blocks.MOSSY_STONE_BRICKS);
        COBBLE_LIKE.add(Blocks.COBBLED_DEEPSLATE);
        COBBLE_LIKE.add(Blocks.DEEPSLATE_BRICKS);
        COBBLE_LIKE.add(Blocks.CRACKED_DEEPSLATE_BRICKS);
        COBBLE_LIKE.add(Blocks.DEEPSLATE_TILES);
    }

    public enum SpotKind { LIGHT, SHAFT, COBBLE }

    public static class Spot {
        public SpotKind kind;
        public BlockPos pos;
        public int extra; // depth / count
    }

    private final Map<Long, List<Spot>> chunkSpots = new ConcurrentHashMap<>();

    public HoleHunter() {
        super(PhgMCAddon.PhgMC_Support, "Tim-Ho-Cu",
            "Tìm dấu vết người chơi cũ: light source dưới đất, mining shaft, cobble cluster.");
    }

    @Override public void onActivate() { chunkSpots.clear(); }

    @EventHandler
    private void onChunkData(ChunkDataEvent e) {
        WorldChunk c = e.chunk();
        if (c == null || mc.world == null) return;
        long key = c.getPos().toLong();
        if (chunkSpots.containsKey(key)) return;

        List<Spot> spots = new ArrayList<>();
        int cobbleCount = 0;
        BlockPos cobbleCenter = null;

        int worldBottom = mc.world.getBottomY();
        int worldTop = worldBottom + mc.world.getHeight();
        int yMaxLight = Math.min(worldTop - 1, lightYMax.get());

        BlockPos.Mutable mp = new BlockPos.Mutable();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int worldX = c.getPos().getStartX() + lx;
                int worldZ = c.getPos().getStartZ() + lz;

                if (scanShafts.get()) {
                    int shaftDepth = scanShaft(c, lx, lz, worldX, worldZ, worldBottom, worldTop);
                    if (shaftDepth >= shaftMinDepth.get()) {
                        Spot s = new Spot();
                        s.kind = SpotKind.SHAFT;
                        s.pos = new BlockPos(worldX, Math.max(worldBottom, 60 - shaftDepth / 2), worldZ);
                        s.extra = shaftDepth;
                        spots.add(s);
                    }
                }

                for (int y = worldBottom; y <= yMaxLight; y++) {
                    mp.set(worldX, y, worldZ);
                    BlockState bs = c.getBlockState(mp);
                    Block b = bs.getBlock();
                    if (scanLights.get() && LIGHT_SOURCES.contains(b)) {
                        Spot s = new Spot();
                        s.kind = SpotKind.LIGHT;
                        s.pos = mp.toImmutable();
                        spots.add(s);
                    }
                    if (scanCobble.get() && y < 30 && COBBLE_LIKE.contains(b)) {
                        cobbleCount++;
                        if (cobbleCenter == null) cobbleCenter = mp.toImmutable();
                    }
                }
            }
        }

        if (scanCobble.get() && cobbleCount >= cobbleClusterMin.get() && cobbleCenter != null) {
            Spot s = new Spot();
            s.kind = SpotKind.COBBLE;
            s.pos = cobbleCenter;
            s.extra = cobbleCount;
            spots.add(s);
        }

        if (spots.isEmpty()) return;
        chunkSpots.put(key, spots);

        // Coalesce announce: 1 chat per chunk listing counts
        int lights = 0, shafts = 0, cobbles = 0;
        for (Spot s : spots) {
            switch (s.kind) {
                case LIGHT  -> lights++;
                case SHAFT  -> shafts++;
                case COBBLE -> cobbles++;
            }
        }

        if (announce.get()) {
            ChatUtils.info("Tim-Ho-Cu: §echunk %d,%d§r §a%d§7 light §c%d§7 shaft §f%d§7 cobble",
                c.getPos().x, c.getPos().z, lights, shafts, cobbles);
        }

        if (autoWaypoint.get()) {
            BlockPos wpPos;
            String wpName;
            if (lights > 0) {
                wpPos = spots.stream().filter(s -> s.kind == SpotKind.LIGHT)
                    .findFirst().map(s -> s.pos).orElse(spots.get(0).pos);
                wpName = "[Hole] " + lights + "L " + c.getPos().x + "," + c.getPos().z;
            } else if (shafts > 0) {
                Spot deepest = spots.stream().filter(s -> s.kind == SpotKind.SHAFT)
                    .reduce((a, b) -> a.extra > b.extra ? a : b).orElse(spots.get(0));
                wpPos = deepest.pos;
                wpName = "[Hole] shaft d" + deepest.extra + " " + c.getPos().x + "," + c.getPos().z;
            } else {
                Spot cob = spots.stream().filter(s -> s.kind == SpotKind.COBBLE)
                    .findFirst().orElse(spots.get(0));
                wpPos = cob.pos;
                wpName = "[Hole] cobble " + cob.extra + " " + c.getPos().x + "," + c.getPos().z;
            }
            try {
                Waypoint wp = new Waypoint.Builder()
                    .name(wpName).icon("square").pos(wpPos).build();
                Waypoints.get().add(wp);
            } catch (Throwable ignored) {}
        }
    }

    /** Scan a 1×1 vertical air column starting from highest stone-like block. */
    private int scanShaft(WorldChunk c, int lx, int lz, int wx, int wz, int wb, int wt) {
        BlockPos.Mutable mp = new BlockPos.Mutable();
        // Find first non-air block from top → that's potential entrance
        int topAir = wt;
        for (int y = wt - 1; y > wb; y--) {
            mp.set(wx, y, wz);
            if (!c.getBlockState(mp).isAir()) {
                topAir = y + 1;
                break;
            }
        }
        // Walk down, count contiguous air through stone-like
        int depth = 0;
        for (int y = topAir - 1; y > wb; y--) {
            mp.set(wx, y, wz);
            BlockState bs = c.getBlockState(mp);
            if (!bs.isAir()) {
                if (isStoneLike(bs.getBlock())) {
                    return depth;
                }
                return 0; // hit non-stone block, not a clean shaft
            }
            depth++;
            if (depth > wt - wb) break;
        }
        return 0;
    }

    private static boolean isStoneLike(Block b) {
        return b == Blocks.STONE || b == Blocks.DEEPSLATE
            || b == Blocks.TUFF  || b == Blocks.GRANITE
            || b == Blocks.DIORITE || b == Blocks.ANDESITE
            || b == Blocks.DIRT  || b == Blocks.GRAVEL
            || b == Blocks.COBBLESTONE || b == Blocks.COBBLED_DEEPSLATE
            || b == Blocks.NETHERRACK || b == Blocks.END_STONE;
    }

    @EventHandler
    private void onRender(Render3DEvent e) {
        if (chunkSpots.isEmpty()) return;
        Color cL = colLight.get();
        Color cS = colShaft.get();
        Color cC = colCobble.get();
        Color o  = outline.get();
        ShapeMode sh = shape.get();

        for (List<Spot> list : chunkSpots.values()) {
            for (Spot s : list) {
                BlockPos p = s.pos;
                Color c;
                double sz;
                switch (s.kind) {
                    case LIGHT  -> { c = cL; sz = 0.5; }
                    case SHAFT  -> { c = cS; sz = 0.5; }
                    case COBBLE -> { c = cC; sz = 1.0; }
                    default     -> { continue; }
                }
                e.renderer.box(p.getX() + 0.5 - sz, p.getY() + 0.5 - sz, p.getZ() + 0.5 - sz,
                               p.getX() + 0.5 + sz, p.getY() + 0.5 + sz, p.getZ() + 0.5 + sz,
                               c, o, sh, 0);
                if (s.kind == SpotKind.SHAFT) {
                    // Vertical line through shaft
                    e.renderer.line(p.getX() + 0.5, p.getY() - s.extra, p.getZ() + 0.5,
                                    p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, o);
                }
            }
        }
    }

    public Map<Long, List<Spot>> snapshot() {
        return new java.util.HashMap<>(chunkSpots);
    }

    @Override
    public String getInfoString() {
        int total = chunkSpots.values().stream().mapToInt(List::size).sum();
        return chunkSpots.size() + "ch §7" + total + " spot";
    }
}
