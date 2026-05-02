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
import net.minecraft.entity.EntityType;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Theo-Doi-Sam-Set — listens to {@link EntitySpawnS2CPacket} for lightning
 * bolts. Server sends lightning to every player within ~512 blocks.
 * Cluster of lightning at a single position = channeling trident farm
 * or lightning rod base.
 */
public class LightningTracker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Hiển thị");

    private final Setting<Integer> clusterRadius = sgGeneral.add(new IntSetting.Builder()
        .name("cụm-bán-kính").defaultValue(8).min(1).sliderMax(32).build());

    private final Setting<Integer> minHits = sgGeneral.add(new IntSetting.Builder()
        .name("ngưỡng-cluster")
        .description("Số sét tối thiểu trong cụm để báo (random storm = 1, farm = nhiều).")
        .defaultValue(2).min(1).sliderMax(20).build());

    private final Setting<Integer> ttlSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("ttl-giây").defaultValue(900).min(60).sliderMax(3600).build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-chat").defaultValue(true).build());

    private final Setting<Boolean> autoWaypoint = sgGeneral.add(new BoolSetting.Builder()
        .name("tự-tạo-waypoint").defaultValue(true).build());

    private final Setting<SettingColor> col = sgRender.add(new ColorSetting.Builder()
        .name("màu").defaultValue(new SettingColor(255, 255, 100, 80)).build());
    private final Setting<SettingColor> line = sgRender.add(new ColorSetting.Builder()
        .name("viền").defaultValue(new SettingColor(255, 255, 200, 230)).build());
    private final Setting<ShapeMode> shape = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape").defaultValue(ShapeMode.Both).build());

    public static class Strike {
        public Vec3d pos;
        public int hits;
        public long lastMs;
        public boolean reported;
    }

    private final List<Strike> strikes = new ArrayList<>();

    public LightningTracker() {
        super(PhgMCAddon.PhgMC_Support, "Theo-Doi-Sam-Set",
            "Track sét. Cluster = lightning rod / channeling trident farm.");
    }

    @Override public void onActivate() { synchronized (strikes) { strikes.clear(); } }

    @EventHandler
    private void onPacket(PacketEvent.Receive e) {
        if (!(e.packet instanceof EntitySpawnS2CPacket pkt)) return;
        if (pkt.getEntityType() != EntityType.LIGHTNING_BOLT) return;
        if (mc.world == null) return;

        addStrike(pkt.getX(), pkt.getY(), pkt.getZ());
    }

    private void addStrike(double x, double y, double z) {
        long now = System.currentTimeMillis();
        int rsq = clusterRadius.get() * clusterRadius.get();
        Strike best = null;
        double bestSq = rsq;
        synchronized (strikes) {
            for (Strike s : strikes) {
                double dx = s.pos.x - x, dz = s.pos.z - z;
                double sq = dx * dx + dz * dz;
                if (sq <= bestSq) { bestSq = sq; best = s; }
            }
            if (best == null) {
                best = new Strike();
                best.pos = new Vec3d(x, y, z);
                strikes.add(best);
            } else {
                best.pos = new Vec3d(
                    (best.pos.x * best.hits + x) / (best.hits + 1),
                    (best.pos.y * best.hits + y) / (best.hits + 1),
                    (best.pos.z * best.hits + z) / (best.hits + 1));
            }
            best.hits++;
            best.lastMs = now;

            if (!best.reported && best.hits >= minHits.get()) {
                best.reported = true;
                if (announce.get()) {
                    ChatUtils.info("Theo-Doi-Sam-Set: §esét cluster§r tại §e%d, %d, %d§r (%d sét)",
                        (int) best.pos.x, (int) best.pos.y, (int) best.pos.z, best.hits);
                }
                if (autoWaypoint.get()) {
                    try {
                        Waypoint wp = new Waypoint.Builder()
                            .name("[Lightning] " + best.hits + "x " + (int) best.pos.x + "," + (int) best.pos.z)
                            .icon("circle")
                            .pos(BlockPos.ofFloored(best.pos))
                            .build();
                        Waypoints.get().add(wp);
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        long now = System.currentTimeMillis();
        long ttl = ttlSeconds.get() * 1000L;
        synchronized (strikes) { strikes.removeIf(s -> now - s.lastMs > ttl); }
    }

    @EventHandler
    private void onRender(Render3DEvent e) {
        Color s = col.get();
        Color l = line.get();
        ShapeMode sh = shape.get();
        int min = minHits.get();
        synchronized (strikes) {
            for (Strike st : strikes) {
                if (st.hits < min) continue;
                double r = 1.0;
                e.renderer.box(st.pos.x - r, st.pos.y - r, st.pos.z - r,
                               st.pos.x + r, st.pos.y + r, st.pos.z + r, s, l, sh, 0);
                e.renderer.line(st.pos.x, -64, st.pos.z, st.pos.x, 320, st.pos.z, l);
            }
        }
    }

    public List<Strike> snapshot() {
        synchronized (strikes) { return new ArrayList<>(strikes); }
    }

    @Override
    public String getInfoString() {
        int min = minHits.get();
        synchronized (strikes) {
            long active = strikes.stream().filter(s -> s.hits >= min).count();
            return "§e" + active + "§7 cluster";
        }
    }
}
