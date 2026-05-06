package com.phgmc.addon.modules;

import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
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
 * (block-entities + artificial blocks via palette scan) and combines
 * structural score with cross-confirmation signals from other observation
 * modules (Sound, Stash, BlockUpdate, ChunkChange) into a Bayesian-style
 * confidence value.
 *
 * Three tiers:
 *  - SUSPECT  ≥ 60 %  (yellow)
 *  - LIKELY   ≥ 85 %  (orange)
 *  - CONFIRMED ≥ 99 % (red)  ← this is what user wants
 *
 * Pure observation on incoming ChunkData → no packets sent.
 */
public class BaseFinder extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgScore   = settings.createGroup("Score weights");
    private final SettingGroup sgConfirm = settings.createGroup("Cross-confirm");
    private final SettingGroup sgRender  = settings.createGroup("Hiển thị");

    private final Setting<Integer> threshold = sgGeneral.add(new IntSetting.Builder()
        .name("ngưỡng-điểm")
        .description("Tổng score raw 1 chunk cần để raw-score = 100%.")
        .defaultValue(12).min(3).sliderMin(3).sliderMax(60)
        .build());

    private final Setting<Integer> minDistanceFromPlayer = sgGeneral.add(new IntSetting.Builder()
        .name("min-distance-chunk")
        .description("Bỏ qua chunk gần player (lọc bỏ base của chính bạn).")
        .defaultValue(0).min(0).sliderMin(0).sliderMax(8)
        .build());

    private final Setting<Double> minConfidence = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-confidence-báo")
        .description("Confidence tối thiểu để in chat / waypoint. 0.6 = báo cả SUSPECT, 0.99 = chỉ báo CONFIRMED.")
        .defaultValue(0.85).min(0.3).max(1.0).sliderMin(0.3).sliderMax(1.0).build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-chat").defaultValue(true).build());

    private final Setting<Boolean> autoWaypoint = sgGeneral.add(new BoolSetting.Builder()
        .name("tự-tạo-waypoint").defaultValue(true).build());

    private final Setting<Boolean> scanArtificialBlocks = sgScore.add(new BoolSetting.Builder()
        .name("quét-block-nhân-tạo")
        .description("Quét palette section (cobble/glass/glowstone/rails/redstone/wool…). Tốn CPU hơn nhưng phát hiện base build chưa có chest.")
        .defaultValue(true).build());

    private final Setting<Integer> blocksPerPoint = sgScore.add(new IntSetting.Builder()
        .name("block-mỗi-điểm")
        .description("Cứ N block nhân tạo trong chunk = 1 score. Càng cao càng yêu cầu base lớn.")
        .defaultValue(64).min(8).sliderMin(8).sliderMax(512).build());

    private final Setting<Boolean> needsStorage = sgScore.add(new BoolSetting.Builder()
        .name("cần-có-storage")
        .description("Yêu cầu chunk phải có ít nhất 1 chest/barrel/shulker/hopper. Tránh false positive ở terrain tự nhiên có cobble.")
        .defaultValue(true).build());

    private final Setting<Double> wOwn = sgConfirm.add(new DoubleSetting.Builder()
        .name("trọng-số-own-score")
        .description("Phần raw-score đóng góp vào confidence (max).")
        .defaultValue(0.55).min(0.0).max(1.0).build());

    private final Setting<Double> wSound = sgConfirm.add(new DoubleSetting.Builder()
        .name("trọng-số-sound").defaultValue(0.15).min(0.0).max(1.0).build());

    private final Setting<Double> wStash = sgConfirm.add(new DoubleSetting.Builder()
        .name("trọng-số-stash").defaultValue(0.12).min(0.0).max(1.0).build());

    private final Setting<Double> wBlockUpdate = sgConfirm.add(new DoubleSetting.Builder()
        .name("trọng-số-block-update").defaultValue(0.18).min(0.0).max(1.0).build());

    private final Setting<Double> wChunkChange = sgConfirm.add(new DoubleSetting.Builder()
        .name("trọng-số-chunk-đổi").defaultValue(0.15).min(0.0).max(1.0).build());

    private final Setting<Double> wExplosion = sgConfirm.add(new DoubleSetting.Builder()
        .name("trọng-số-no").defaultValue(0.10).min(0.0).max(1.0).build());

    private final Setting<Double> wHole = sgConfirm.add(new DoubleSetting.Builder()
        .name("trọng-số-hole").defaultValue(0.05).min(0.0).max(1.0).build());

    private final Setting<Integer> recheckTicks = sgConfirm.add(new IntSetting.Builder()
        .name("recheck-mỗi-tick")
        .description("Re-evaluate confidence cho các chunk đã thấy mỗi N tick (cập nhật khi có signal mới).")
        .defaultValue(40).min(10).sliderMax(400).build());

    // Tier thresholds
    private final Setting<Double> tierLikely = sgRender.add(new DoubleSetting.Builder()
        .name("ngưỡng-likely").defaultValue(0.85).min(0.4).max(0.99).build());

    private final Setting<Double> tierConfirmed = sgRender.add(new DoubleSetting.Builder()
        .name("ngưỡng-confirmed").defaultValue(0.99).min(0.6).max(1.0).build());

    private final Setting<SettingColor> colSuspect = sgRender.add(new ColorSetting.Builder()
        .name("màu-suspect")
        .defaultValue(new SettingColor(255, 230, 60, 60)).build());

    private final Setting<SettingColor> colLikely = sgRender.add(new ColorSetting.Builder()
        .name("màu-likely")
        .defaultValue(new SettingColor(255, 140, 30, 100)).build());

    private final Setting<SettingColor> colConfirmed = sgRender.add(new ColorSetting.Builder()
        .name("màu-confirmed")
        .defaultValue(new SettingColor(255, 50, 50, 140)).build());

    private final Setting<ShapeMode> shape = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("kiểu-hiển-thị").defaultValue(ShapeMode.Lines).build());

    public enum RenderMode { Flat, Top, Box, Off }

    private final Setting<RenderMode> renderMode = sgRender.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode")
        .description("Flat = ô vuông tại Y player; Top = Y cố định; Box = cột Y-64→320; Off = không vẽ.")
        .defaultValue(RenderMode.Flat).build());

    private final Setting<Integer> flatYOffset = sgRender.add(new IntSetting.Builder()
        .name("flat-y-offset").defaultValue(0).min(-64).sliderMin(-32).sliderMax(64).build());

    private final Setting<Integer> topY = sgRender.add(new IntSetting.Builder()
        .name("top-y").defaultValue(120).min(-64).sliderMin(-64).sliderMax(320).build());

    private final Setting<Integer> maxRender = sgRender.add(new IntSetting.Builder()
        .name("max-render")
        .description("Chỉ vẽ N chunk gần player nhất.")
        .defaultValue(48).min(1).sliderMin(8).sliderMax(256).build());

    private static final String WP_PREFIX = "[Base] ";

    public enum Tier { SUSPECT, LIKELY, CONFIRMED }

    public static class Base {
        public int chunkX;
        public int chunkZ;
        public int score;             // raw structural score
        public double confidence;     // 0..1 with cross-confirm
        public Tier tier;
        public boolean hasStorage;
        public long firstSeenMs;
        public long lastEvalMs;
        public String dimension;
        public boolean announcedTier;
    }

    private final Map<Long, Base> bases = new ConcurrentHashMap<>();
    private int recheckCounter = 0;

    /** Static set of blocks that strongly indicate a player-built area. */
    private static final Set<Block> ARTIFICIAL_BLOCKS = new HashSet<>();
    static {
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
        ARTIFICIAL_BLOCKS.add(Blocks.WHITE_WOOL);
        ARTIFICIAL_BLOCKS.add(Blocks.BLACK_WOOL);
        ARTIFICIAL_BLOCKS.add(Blocks.RED_WOOL);
        ARTIFICIAL_BLOCKS.add(Blocks.BLUE_WOOL);
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
            "Phát hiện base người chơi qua phân tích chunk + cross-confirm với module khác. 3-tier confidence.");
    }

    @Override
    public void onActivate() {
        bases.clear();
        recheckCounter = 0;
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
        Base existing = bases.get(key);
        if (existing != null) {
            // re-evaluate confidence (cross-confirm sources may have updated)
            existing.confidence = computeConfidence(existing.score, cp.x, cp.z);
            updateTier(existing);
            return;
        }

        int score = 0;
        boolean hasStorage = false;

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

        if (score < 1) return; // nothing interesting

        double conf = computeConfidence(score, cp.x, cp.z);
        if (conf < 0.30) return; // ignore truly low confidence

        Base b = new Base();
        b.chunkX = cp.x;
        b.chunkZ = cp.z;
        b.score = score;
        b.hasStorage = hasStorage;
        b.confidence = conf;
        b.firstSeenMs = System.currentTimeMillis();
        b.lastEvalMs = b.firstSeenMs;
        b.dimension = mc.world.getRegistryKey().getValue().toString();
        bases.put(key, b);
        updateTier(b);
    }

    private double computeConfidence(int rawScore, int cx, int cz) {
        double conf = 0;
        // own score capped at threshold → max wOwn
        double th = Math.max(1, threshold.get());
        double own = Math.min(1.0, rawScore / th);
        conf += wOwn.get() * own;
        if (confirmsSound(cx, cz))         conf += wSound.get();
        if (confirmsStash(cx, cz))         conf += wStash.get();
        if (confirmsBlockUpdate(cx, cz))   conf += wBlockUpdate.get();
        if (confirmsChunkChange(cx, cz))   conf += wChunkChange.get();
        if (confirmsExplosion(cx, cz))     conf += wExplosion.get();
        if (confirmsHole(cx, cz))          conf += wHole.get();
        return Math.min(1.0, conf);
    }

    private boolean inSameChunk(double x, double z, int cx, int cz) {
        return ((int) Math.floor(x) >> 4) == cx && ((int) Math.floor(z) >> 4) == cz;
    }

    private boolean confirmsSound(int cx, int cz) {
        try {
            SoundTracker m = Modules.get().get(SoundTracker.class);
            if (m == null || !m.isActive()) return false;
            for (var c : m.snapshot()) {
                if (c.hits >= 2 && inSameChunk(c.x, c.z, cx, cz)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean confirmsStash(int cx, int cz) {
        try {
            StashFinder m = Modules.get().get(StashFinder.class);
            if (m == null || !m.isActive()) return false;
            for (var s : m.snapshot()) {
                if (s.chunkX == cx && s.chunkZ == cz) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean confirmsBlockUpdate(int cx, int cz) {
        try {
            BlockUpdateTracker m = Modules.get().get(BlockUpdateTracker.class);
            if (m == null || !m.isActive()) return false;
            for (var s : m.snapshot()) {
                if (s.cx == cx && s.cz == cz && s.density >= 5.0) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean confirmsChunkChange(int cx, int cz) {
        try {
            ChunkChangeRecorder m = Modules.get().get(ChunkChangeRecorder.class);
            if (m == null || !m.isActive()) return false;
            for (var ch : m.snapshot().values()) {
                if (ch.cx == cx && ch.cz == cz) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean confirmsExplosion(int cx, int cz) {
        try {
            ExplosionTracker m = Modules.get().get(ExplosionTracker.class);
            if (m == null || !m.isActive()) return false;
            for (var b : m.snapshot()) {
                if (((int) b.pos.x >> 4) == cx && ((int) b.pos.z >> 4) == cz) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean confirmsHole(int cx, int cz) {
        try {
            HoleHunter m = Modules.get().get(HoleHunter.class);
            if (m == null || !m.isActive()) return false;
            for (var entry : m.snapshot().values()) {
                for (var s : entry) {
                    if ((s.pos.getX() >> 4) == cx && (s.pos.getZ() >> 4) == cz) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void updateTier(Base b) {
        Tier old = b.tier;
        Tier now;
        if (b.confidence >= tierConfirmed.get()) now = Tier.CONFIRMED;
        else if (b.confidence >= tierLikely.get()) now = Tier.LIKELY;
        else now = Tier.SUSPECT;
        b.tier = now;
        b.lastEvalMs = System.currentTimeMillis();

        if (b.confidence < minConfidence.get()) return;
        if (old != now || !b.announcedTier) {
            b.announcedTier = true;
            if (announce.get()) {
                String tag = switch (now) {
                    case CONFIRMED -> "§c§l[CONFIRMED]§r";
                    case LIKELY    -> "§6[LIKELY]§r";
                    case SUSPECT   -> "§e[suspect]§r";
                };
                ChatUtils.info("Tim-Base: %s base tại §echunk %d, %d§r §7(score %d, conf §a%.0f%%§r§7, %s)",
                    tag, b.chunkX, b.chunkZ, b.score, b.confidence * 100,
                    b.hasStorage ? "có storage" : "no storage");
            }
            if (autoWaypoint.get()) {
                try {
                    String tagW = switch (now) {
                        case CONFIRMED -> "[Base!]";
                        case LIKELY    -> "[Base?]";
                        case SUSPECT   -> "[Base??]";
                    };
                    Waypoint wp = new Waypoint.Builder()
                        .name(tagW + " " + (int) (b.confidence * 100) + "% " + b.chunkX + "," + b.chunkZ)
                        .icon("diamond")
                        .pos(new BlockPos(b.chunkX * 16 + 8, 64, b.chunkZ * 16 + 8))
                        .build();
                    Waypoints.get().add(wp);
                } catch (Throwable ignored) {}
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (++recheckCounter < recheckTicks.get()) return;
        recheckCounter = 0;
        for (Base b : bases.values()) {
            double newConf = computeConfidence(b.score, b.chunkX, b.chunkZ);
            if (Math.abs(newConf - b.confidence) > 0.01) {
                b.confidence = newConf;
                updateTier(b);
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent e) {
        if (bases.isEmpty()) return;
        RenderMode rm = renderMode.get();
        if (rm == RenderMode.Off) return;
        ShapeMode sh = shape.get();
        double py = mc.player != null ? mc.player.getY() + flatYOffset.get() : 64;
        int max = maxRender.get();
        int pcx = mc.player == null ? 0 : (mc.player.getBlockX() >> 4);
        int pcz = mc.player == null ? 0 : (mc.player.getBlockZ() >> 4);

        java.util.List<Base> visible = bases.values().stream()
            .sorted(java.util.Comparator.comparingInt(b -> {
                int dx = b.chunkX - pcx, dz = b.chunkZ - pcz;
                return dx * dx + dz * dz;
            }))
            .limit(max)
            .toList();

        for (Base b : visible) {
            Color c = switch (b.tier) {
                case CONFIRMED -> colConfirmed.get();
                case LIKELY    -> colLikely.get();
                case SUSPECT   -> colSuspect.get();
            };
            double x0 = b.chunkX * 16, z0 = b.chunkZ * 16;
            double x1 = x0 + 16, z1 = z0 + 16;
            switch (rm) {
                case Box  -> e.renderer.box(x0, -64, z0, x1, 320, z1, c, c, sh, 0);
                case Top  -> e.renderer.box(x0, topY.get(), z0, x1, topY.get() + 0.05, z1, c, c, sh, 0);
                case Flat -> e.renderer.box(x0, py, z0, x1, py + 0.05, z1, c, c, sh, 0);
                default   -> {}
            }
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
        long conf = bases.values().stream().filter(b -> b.tier == Tier.CONFIRMED).count();
        long lik  = bases.values().stream().filter(b -> b.tier == Tier.LIKELY).count();
        return "§c" + conf + "§7/§6" + lik + "§7/§e" + (bases.size() - conf - lik);
    }
}
