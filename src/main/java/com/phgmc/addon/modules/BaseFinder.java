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
import net.minecraft.block.entity.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tim-Base — analyses every loaded chunk for "human activity" markers
 * (block-entities + artificial blocks via palette scan) and flags candidates
 * as a base. Pure observation on incoming ChunkData → no packets sent,
 * undetectable. Works on SMP servers with reduced render distance because
 * the analysis only uses chunks the server has already given the client.
 */
public class BaseFinder extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScore   = settings.createGroup("Score weights");
    private final SettingGroup sgRender  = settings.createGroup("Hiển thị");

    private final Setting<Integer> threshold = sgGeneral.add(new IntSetting.Builder()
        .name("ngưỡng-điểm")
        .description("Tổng score 1 chunk cần đạt để coi là base.")
        .defaultValue(12).min(3).sliderMin(3).sliderMax(60)
        .build());

    private final Setting<Integer> minDistanceFromPlayer = sgGeneral.add(new IntSetting.Builder()
        .name("min-distance-chunk")
        .description("Bỏ qua chunk gần player (lọc bỏ base của chính bạn).")
        .defaultValue(0).min(0).sliderMin(0).sliderMax(8)
        .build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-chat")
        .description("In chat khi tìm thấy base mới.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoWaypoint = sgGeneral.add(new BoolSetting.Builder()
        .name("tự-tạo-waypoint")
        .description("Tự thêm Waypoint cho mỗi chunk base.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> scanArtificialBlocks = sgScore.add(new BoolSetting.Builder()
        .name("quét-block-nhân-tạo")
        .description("Quét palette section cho cobble/glass/glowstone/rails/redstone/wool. Tốn CPU hơn nhưng phát hiện base build chưa có chest.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> blocksPerPoint = sgScore.add(new IntSetting.Builder()
        .name("block-mỗi-điểm")
        .description("Cứ mỗi N block nhân tạo trong chunk = 1 score. Càng cao càng yêu cầu base lớn.")
        .defaultValue(64).min(8).sliderMin(8).sliderMax(512)
        .build());

    private final Setting<Boolean> needsStorage = sgScore.add(new BoolSetting.Builder()
        .name("cần-có-storage")
        .description("Yêu cầu chunk phải có ít nhất 1 chest/barrel/shulker/hopper. Tránh false positive ở các vùng terrain có cobble tự nhiên.")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> col = sgRender.add(new ColorSetting.Builder()
        .name("màu-base")
        .defaultValue(new SettingColor(60, 200, 255, 80))
        .build());

    private final Setting<SettingColor> line = sgRender.add(new ColorSetting.Builder()
        .name("viền-base")
        .defaultValue(new SettingColor(120, 220, 255, 220))
        .build());

    private final Setting<ShapeMode> shape = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("kiểu-hiển-thị")
        .defaultValue(ShapeMode.Both)
        .build());

    private static final String WP_PREFIX = "[Base] ";

    public static class Base {
        public int chunkX;
        public int chunkZ;
        public int score;
        public boolean hasStorage;
        public long firstSeenMs;
        public String dimension;
    }

    private final Map<Long, Base> bases = new ConcurrentHashMap<>();

    /** Static set of blocks that strongly indicate a player-built area. */
    private static final Set<Block> ARTIFICIAL_BLOCKS = new HashSet<>();
    static {
        // Building / decoration
        ARTIFICIAL_BLOCKS.add(Blocks.COBBLESTONE);
        ARTIFICIAL_BLOCKS.add(Blocks.MOSSY_COBBLESTONE);
        ARTIFICIAL_BLOCKS.add(Blocks.STONE_BRICKS);
        ARTIFICIAL_BLOCKS.add(Blocks.MOSSY_STONE_BRICKS);
        ARTIFICIAL_BLOCKS.add(Blocks.CRACKED_STONE_BRICKS);
        ARTIFICIAL_BLOCKS.add(Blocks.CHISELED_STONE_BRICKS);
        ARTIFICIAL_BLOCKS.add(Blocks.SMOOTH_STONE);
        ARTIFICIAL_BLOCKS.add(Blocks.POLISHED_GRANITE);
        ARTIFICIAL_BLOCKS.add(Blocks.POLISHED_DIORITE);
        ARTIFICIAL_BLOCKS.add(Blocks.POLISHED_ANDESITE);
        ARTIFICIAL_BLOCKS.add(Blocks.POLISHED_DEEPSLATE);
        ARTIFICIAL_BLOCKS.add(Blocks.GLASS);
        ARTIFICIAL_BLOCKS.add(Blocks.TINTED_GLASS);
        ARTIFICIAL_BLOCKS.add(Blocks.GLOWSTONE);
        ARTIFICIAL_BLOCKS.add(Blocks.SEA_LANTERN);
        ARTIFICIAL_BLOCKS.add(Blocks.JACK_O_LANTERN);
        ARTIFICIAL_BLOCKS.add(Blocks.REDSTONE_LAMP);
        ARTIFICIAL_BLOCKS.add(Blocks.SHROOMLIGHT);
        ARTIFICIAL_BLOCKS.add(Blocks.OCHRE_FROGLIGHT);
        ARTIFICIAL_BLOCKS.add(Blocks.VERDANT_FROGLIGHT);
        ARTIFICIAL_BLOCKS.add(Blocks.PEARLESCENT_FROGLIGHT);
        // Tech / redstone
        ARTIFICIAL_BLOCKS.add(Blocks.RAIL);
        ARTIFICIAL_BLOCKS.add(Blocks.POWERED_RAIL);
        ARTIFICIAL_BLOCKS.add(Blocks.DETECTOR_RAIL);
        ARTIFICIAL_BLOCKS.add(Blocks.ACTIVATOR_RAIL);
        ARTIFICIAL_BLOCKS.add(Blocks.REDSTONE_WIRE);
        ARTIFICIAL_BLOCKS.add(Blocks.REPEATER);
        ARTIFICIAL_BLOCKS.add(Blocks.COMPARATOR);
        ARTIFICIAL_BLOCKS.add(Blocks.OBSERVER);
        ARTIFICIAL_BLOCKS.add(Blocks.PISTON);
        ARTIFICIAL_BLOCKS.add(Blocks.STICKY_PISTON);
        ARTIFICIAL_BLOCKS.add(Blocks.DISPENSER);
        ARTIFICIAL_BLOCKS.add(Blocks.DROPPER);
        ARTIFICIAL_BLOCKS.add(Blocks.LECTERN);
        ARTIFICIAL_BLOCKS.add(Blocks.NOTE_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.TARGET);
        // Storage / kitchen (also covered by block-entity scan but useful here too)
        ARTIFICIAL_BLOCKS.add(Blocks.CRAFTING_TABLE);
        ARTIFICIAL_BLOCKS.add(Blocks.SMITHING_TABLE);
        ARTIFICIAL_BLOCKS.add(Blocks.STONECUTTER);
        ARTIFICIAL_BLOCKS.add(Blocks.GRINDSTONE);
        ARTIFICIAL_BLOCKS.add(Blocks.LOOM);
        ARTIFICIAL_BLOCKS.add(Blocks.CARTOGRAPHY_TABLE);
        ARTIFICIAL_BLOCKS.add(Blocks.FLETCHING_TABLE);
        ARTIFICIAL_BLOCKS.add(Blocks.COMPOSTER);
        ARTIFICIAL_BLOCKS.add(Blocks.CAULDRON);
        ARTIFICIAL_BLOCKS.add(Blocks.BREWING_STAND);
        // Wool (often used in builds)
        ARTIFICIAL_BLOCKS.add(Blocks.WHITE_WOOL);
        ARTIFICIAL_BLOCKS.add(Blocks.BLACK_WOOL);
        ARTIFICIAL_BLOCKS.add(Blocks.RED_WOOL);
        ARTIFICIAL_BLOCKS.add(Blocks.BLUE_WOOL);
        // End-game / valuable indicators
        ARTIFICIAL_BLOCKS.add(Blocks.IRON_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.GOLD_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.DIAMOND_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.NETHERITE_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.EMERALD_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.LAPIS_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.AMETHYST_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.HAY_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.SLIME_BLOCK);
        ARTIFICIAL_BLOCKS.add(Blocks.HONEY_BLOCK);
    }

    public BaseFinder() {
        super(PhgMCAddon.PhgMC_Support, "Tim-Base",
            "Phát hiện base người chơi qua phân tích chunk: block entities + block nhân tạo.");
    }

    @Override
    public void onActivate() {
        bases.clear();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent e) {
        if (mc.world == null || mc.player == null) return;
        WorldChunk c = e.chunk();
        ChunkPos cp = c.getPos();

        int pcx = mc.player.getBlockX() >> 4;
        int pcz = mc.player.getBlockZ() >> 4;
        int dx = Math.abs(cp.x - pcx);
        int dz = Math.abs(cp.z - pcz);
        int minDist = minDistanceFromPlayer.get();
        if (Math.max(dx, dz) < minDist) return;

        long key = ChunkPos.toLong(cp.x, cp.z);
        if (bases.containsKey(key)) return; // already flagged

        int score = 0;
        boolean hasStorage = false;

        // Block-entity scan (cheap, already in memory)
        for (BlockEntity be : c.getBlockEntities().values()) {
            if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
                || be instanceof ShulkerBoxBlockEntity || be instanceof HopperBlockEntity) {
                score += 1;
                hasStorage = true;
            } else if (be instanceof FurnaceBlockEntity || be instanceof BlastFurnaceBlockEntity
                       || be instanceof SmokerBlockEntity) {
                score += 2;
            } else if (be instanceof BedBlockEntity || be instanceof BeaconBlockEntity
                       || be instanceof EnchantingTableBlockEntity || be instanceof BrewingStandBlockEntity
                       || be instanceof ConduitBlockEntity || be instanceof EndPortalBlockEntity) {
                score += 3;
            } else if (be instanceof JukeboxBlockEntity || be instanceof DispenserBlockEntity
                       || be instanceof LecternBlockEntity || be instanceof BellBlockEntity
                       || be instanceof BeehiveBlockEntity || be instanceof CampfireBlockEntity) {
                score += 1;
            }
        }

        if (needsStorage.get() && !hasStorage) return;

        // Artificial-block scan (palette of each section)
        if (scanArtificialBlocks.get()) {
            int artificial = 0;
            for (ChunkSection sec : c.getSectionArray()) {
                if (sec == null || sec.isEmpty()) continue;
                int[] cnt = {0};
                sec.getBlockStateContainer().count((BlockState st, int n) -> {
                    if (ARTIFICIAL_BLOCKS.contains(st.getBlock())) cnt[0] += n;
                });
                artificial += cnt[0];
            }
            score += artificial / Math.max(1, blocksPerPoint.get());
        }

        if (score < threshold.get()) return;

        Base b = new Base();
        b.chunkX = cp.x;
        b.chunkZ = cp.z;
        b.score = score;
        b.hasStorage = hasStorage;
        b.firstSeenMs = System.currentTimeMillis();
        b.dimension = mc.world.getRegistryKey().getValue().toString();
        bases.put(key, b);

        if (announce.get()) {
            ChatUtils.info("Tim-Base: §bbase§r tại chunk §e%d, %d§r §7(score %d, %s)",
                b.chunkX, b.chunkZ, b.score,
                b.hasStorage ? "có storage" : "chỉ có block");
        }
        if (autoWaypoint.get()) {
            try {
                Waypoint wp = new Waypoint.Builder()
                    .name(WP_PREFIX + "score" + b.score + " " + b.chunkX + "," + b.chunkZ)
                    .icon("diamond")
                    .pos(new BlockPos(b.chunkX * 16 + 8, 64, b.chunkZ * 16 + 8))
                    .build();
                Waypoints.get().add(wp);
            } catch (Throwable ignored) {}
        }
    }

    @EventHandler
    private void onRender(Render3DEvent e) {
        if (bases.isEmpty()) return;
        Color s = col.get();
        Color l = line.get();
        ShapeMode sh = shape.get();
        for (Base b : bases.values()) {
            double x0 = b.chunkX * 16, z0 = b.chunkZ * 16;
            double x1 = x0 + 16, z1 = z0 + 16;
            e.renderer.box(x0, -64, z0, x1, 320, z1, s, l, sh, 0);
        }
    }

    public Map<Long, Base> bases() {
        return new HashMap<>(bases);
    }

    public java.util.Collection<Base> snapshot() {
        return new java.util.ArrayList<>(bases.values());
    }

    @Override
    public String getInfoString() {
        return "§b" + bases.size() + "§7 base";
    }
}
