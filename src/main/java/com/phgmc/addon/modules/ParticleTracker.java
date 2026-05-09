package com.phgmc.addon.modules;

import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
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
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Theo-Doi-Hat-Ben — listens to {@link ParticleS2CPacket}. Vanilla server
 * sends "long-distance" particles (explosion, dragon breath, end portal,
 * conduit, witch hut spawn, ender dragon death...) regardless of render
 * distance, plus normal particles within tracking range. Cluster by
 * sending position + identifier.
 */
public class ParticleTracker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter  = settings.createGroup("Bộ lọc particle");
    private final SettingGroup sgRender  = settings.createGroup("Hiển thị");

    private final Setting<Integer> clusterRadius = sgGeneral.add(new IntSetting.Builder()
        .name("cụm-bán-kính").defaultValue(16).min(2).sliderMax(64).build());

    private final Setting<Integer> minHits = sgGeneral.add(new IntSetting.Builder()
        .name("ngưỡng-cluster").defaultValue(8).min(1).sliderMax(50).build());

    private final Setting<Integer> ttlSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("ttl-giây").defaultValue(120).min(10).sliderMax(600).build());

    private final Setting<Boolean> onlyLongDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("chỉ-long-distance")
        .description("Chỉ track packet có flag long-distance (đi xa hơn render distance).")
        .defaultValue(false).build());

    private final Setting<Integer> minDistanceFromPlayer = sgGeneral.add(new IntSetting.Builder()
        .name("bỏ-qua-block")
        .description("Bỏ qua particle gần player ≤ N block.")
        .defaultValue(24).min(0).sliderMax(64).build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-chat").defaultValue(true).build());
    private final Setting<Boolean> autoWaypoint = sgGeneral.add(new BoolSetting.Builder()
        .name("tự-tạo-waypoint").defaultValue(true).build());

    // Whitelist (substring match against particle id)
    private final Setting<Boolean> fSmoke   = sgFilter.add(new BoolSetting.Builder().name("smoke (campfire)").defaultValue(true).build());
    private final Setting<Boolean> fSoul    = sgFilter.add(new BoolSetting.Builder().name("soul-fire/lantern").defaultValue(true).build());
    private final Setting<Boolean> fTotem   = sgFilter.add(new BoolSetting.Builder().name("totem").defaultValue(true).build());
    private final Setting<Boolean> fDragon  = sgFilter.add(new BoolSetting.Builder().name("dragon-breath").defaultValue(true).build());
    private final Setting<Boolean> fEndPort = sgFilter.add(new BoolSetting.Builder().name("end-portal").defaultValue(true).build());
    private final Setting<Boolean> fConduit = sgFilter.add(new BoolSetting.Builder().name("conduit/nautilus").defaultValue(true).build());
    private final Setting<Boolean> fWitch   = sgFilter.add(new BoolSetting.Builder().name("witch").defaultValue(true).build());
    private final Setting<Boolean> fEnchant = sgFilter.add(new BoolSetting.Builder().name("enchant-table").defaultValue(true).build());
    private final Setting<Boolean> fHappy   = sgFilter.add(new BoolSetting.Builder().name("happy-villager (bonemeal)").defaultValue(true).build());
    private final Setting<Boolean> fSculk   = sgFilter.add(new BoolSetting.Builder().name("sculk-charge").defaultValue(true).build());
    private final Setting<Boolean> fComp    = sgFilter.add(new BoolSetting.Builder().name("composter").defaultValue(false).build());
    private final Setting<Boolean> fPortal  = sgFilter.add(new BoolSetting.Builder().name("nether-portal").defaultValue(true).build());
    private final Setting<Boolean> fHeart   = sgFilter.add(new BoolSetting.Builder().name("heart (breeding)").defaultValue(true).build());
    private final Setting<Boolean> fBigSplsh = sgFilter.add(new BoolSetting.Builder().name("splash/effect").defaultValue(false).build());

    private final Setting<SettingColor> col = sgRender.add(new ColorSetting.Builder()
        .name("màu").defaultValue(new SettingColor(150, 255, 100, 80)).build());
    private final Setting<SettingColor> line = sgRender.add(new ColorSetting.Builder()
        .name("viền").defaultValue(new SettingColor(200, 255, 150, 220)).build());
    private final Setting<ShapeMode> shape = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape").defaultValue(ShapeMode.Both).build());

    public static class Cluster {
        public double x, y, z;
        public int hits;
        public long lastMs;
        public boolean reported;
        public final Map<String, Integer> kinds = new HashMap<>();
    }

    private final List<Cluster> clusters = new ArrayList<>();

    public ParticleTracker() {
        super(PhgMCAddon.PhgMC_Support, "Theo-Doi-Hat-Ben",
            "Cluster particle (campfire/totem/dragon/conduit/witch/enchant) → base.");
    }

    @Override public void onActivate() { synchronized (clusters) { clusters.clear(); } }

    @EventHandler
    private void onPacket(PacketEvent.Receive e) {
        if (!(e.packet instanceof ParticleS2CPacket pkt)) return;
        if (mc.world == null || mc.player == null) return;
        if (onlyLongDistance.get() && !pkt.shouldForceSpawn() && !pkt.isImportant()) return;

        ParticleEffect eff = pkt.getParameters();
        if (eff == null) return;
        ParticleType<?> type = eff.getType();
        Identifier id = Registries.PARTICLE_TYPE.getId(type);
        if (id == null) return;
        if (!isInteresting(id.getPath())) return;

        double x = pkt.getX(), y = pkt.getY(), z = pkt.getZ();
        double dx = x - mc.player.getX(), dy = y - mc.player.getY(), dz = z - mc.player.getZ();
        double dsq = dx * dx + dy * dy + dz * dz;
        int min = minDistanceFromPlayer.get();
        if (dsq < min * min) return;

        addToCluster(id.toString(), x, y, z);
    }

    private boolean isInteresting(String p) {
        return switch (p) {
            case "campfire_cosy_smoke", "campfire_signal_smoke", "smoke" -> fSmoke.get();
            case "soul_fire_flame", "soul" -> fSoul.get();
            case "totem_of_undying" -> fTotem.get();
            case "dragon_breath" -> fDragon.get();
            case "end_rod" -> fEndPort.get();
            case "nautilus" -> fConduit.get();
            case "witch" -> fWitch.get();
            case "enchant", "enchanted_hit" -> fEnchant.get();
            case "happy_villager" -> fHappy.get();
            case "sculk_soul", "sculk_charge", "sculk_charge_pop" -> fSculk.get();
            case "composter" -> fComp.get();
            case "portal", "reverse_portal" -> fPortal.get();
            case "heart" -> fHeart.get();
            case "effect", "instant_effect", "splash" -> fBigSplsh.get();
            default -> false;
        };
    }

    private void addToCluster(String kind, double x, double y, double z) {
        long now = System.currentTimeMillis();
        int rsq = clusterRadius.get() * clusterRadius.get();
        Cluster best = null;
        double bestSq = rsq;
        synchronized (clusters) {
            for (Cluster c : clusters) {
                double dx = c.x - x, dz = c.z - z;
                double sq = dx * dx + dz * dz;
                if (sq <= bestSq) {
                    bestSq = sq;
                    best = c;
                }
            }
            if (best == null) {
                best = new Cluster();
                best.x = x; best.y = y; best.z = z;
                clusters.add(best);
            } else {
                best.x = (best.x * best.hits + x) / (best.hits + 1);
                best.y = (best.y * best.hits + y) / (best.hits + 1);
                best.z = (best.z * best.hits + z) / (best.hits + 1);
            }
            best.hits++;
            best.lastMs = now;
            best.kinds.merge(kind, 1, Integer::sum);

            if (!best.reported && best.hits >= minHits.get()) {
                best.reported = true;
                String topKind = topKind(best);
                if (announce.get()) {
                    ChatUtils.info("Theo-Doi-Hat-Ben: §acluster§r tại §e%d, %d, %d§r (%d hat, top: %s)",
                        (int) best.x, (int) best.y, (int) best.z, best.hits, topKind);
                }
                if (autoWaypoint.get()) {
                    try {
                        Waypoint wp = new Waypoint.Builder()
                            .name("[Particle] " + topKind + " " + (int) best.x + "," + (int) best.z)
                            .icon("square")
                            .pos(BlockPos.ofFloored(best.x, best.y, best.z))
                            .build();
                        Waypoints.get().add(wp);
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private static String topKind(Cluster c) {
        String top = "";
        int best = 0;
        for (var e : c.kinds.entrySet()) if (e.getValue() > best) { best = e.getValue(); top = e.getKey(); }
        if (top.startsWith("minecraft:")) top = top.substring("minecraft:".length());
        return top;
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        long now = System.currentTimeMillis();
        long ttl = ttlSeconds.get() * 1000L;
        synchronized (clusters) { clusters.removeIf(c -> now - c.lastMs > ttl); }
    }

    @EventHandler
    private void onRender(Render3DEvent e) {
        Color s = col.get();
        Color l = line.get();
        ShapeMode sh = shape.get();
        int min = minHits.get();
        synchronized (clusters) {
            for (Cluster c : clusters) {
                if (c.hits < min) continue;
                double r = 1.5;
                e.renderer.box(c.x - r, c.y - r, c.z - r,
                               c.x + r, c.y + r, c.z + r, s, l, sh, 0);
                e.renderer.line(c.x, -64, c.z, c.x, 320, c.z, l);
            }
        }
    }

    public List<Cluster> snapshot() {
        synchronized (clusters) { return new ArrayList<>(clusters); }
    }

    @Override
    public String getInfoString() {
        int min = minHits.get();
        synchronized (clusters) {
            long active = clusters.stream().filter(c -> c.hits >= min).count();
            return "§a" + active + "§7 cluster";
        }
    }
}
