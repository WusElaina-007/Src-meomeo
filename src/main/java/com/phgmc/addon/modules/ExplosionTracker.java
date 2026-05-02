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
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Theo-Doi-No — listens to {@link ExplosionS2CPacket} which is sent to
 * any player nearby (server-tracked, not render-distance limited).
 * Captures: TNT, end crystal, beds in nether/end, creeper, wither, etc.
 *
 * Pure receive observation. Strong base/PvP indicator on SMP.
 */
public class ExplosionTracker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Hiển thị");

    private final Setting<Double> minRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-radius")
        .description("Bỏ qua nổ nhỏ hơn (creeper ~3, TNT ~4, end crystal ~6, bed ~5).")
        .defaultValue(0.0).min(0.0).sliderMax(10.0)
        .build());

    private final Setting<Integer> ttlSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("ttl-giây")
        .description("Marker bị xóa sau N giây.")
        .defaultValue(600).min(30).sliderMin(30).sliderMax(3600)
        .build());

    private final Setting<Integer> maxMarkers = sgGeneral.add(new IntSetting.Builder()
        .name("max-marker")
        .description("Tối đa marker đồng thời (FIFO).")
        .defaultValue(64).min(8).sliderMin(8).sliderMax(256)
        .build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-chat").defaultValue(true).build());

    private final Setting<Boolean> autoWaypoint = sgGeneral.add(new BoolSetting.Builder()
        .name("tự-tạo-waypoint")
        .description("Tự waypoint cho mỗi nổ ≥ min-radius.")
        .defaultValue(true).build());

    private final Setting<SettingColor> col = sgRender.add(new ColorSetting.Builder()
        .name("màu").defaultValue(new SettingColor(255, 90, 60, 90)).build());
    private final Setting<SettingColor> line = sgRender.add(new ColorSetting.Builder()
        .name("viền").defaultValue(new SettingColor(255, 130, 90, 230)).build());
    private final Setting<ShapeMode> shape = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape").defaultValue(ShapeMode.Both).build());

    public static class Boom {
        public Vec3d pos;
        public float radius;
        public long timeMs;
    }

    private final List<Boom> booms = new ArrayList<>();

    public ExplosionTracker() {
        super(PhgMCAddon.PhgMC_Support, "Theo-Doi-No",
            "Track packet ExplosionS2C. TNT/bed/crystal/creeper = base/pvp activity.");
    }

    @Override
    public void onActivate() {
        synchronized (booms) { booms.clear(); }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive e) {
        if (!(e.packet instanceof ExplosionS2CPacket pkt)) return;
        if (mc.world == null) return;
        Vec3d c = pkt.center();
        float r = pkt.radius();
        if (r < minRadius.get()) return;

        Boom b = new Boom();
        b.pos = c;
        b.radius = r;
        b.timeMs = System.currentTimeMillis();

        synchronized (booms) {
            booms.add(b);
            while (booms.size() > maxMarkers.get()) booms.remove(0);
        }

        if (announce.get()) {
            ChatUtils.info("Theo-Doi-No: §cnổ§r §7r=%.1f§r tại §e%d, %d, %d§r",
                r, (int) c.x, (int) c.y, (int) c.z);
        }
        if (autoWaypoint.get()) {
            try {
                Waypoint wp = new Waypoint.Builder()
                    .name("[Boom] r" + (int) r + " " + (int) c.x + "," + (int) c.z)
                    .icon("circle")
                    .pos(BlockPos.ofFloored(c))
                    .build();
                Waypoints.get().add(wp);
            } catch (Throwable ignored) {}
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        long now = System.currentTimeMillis();
        long ttl = ttlSeconds.get() * 1000L;
        synchronized (booms) {
            Iterator<Boom> it = booms.iterator();
            while (it.hasNext()) if (now - it.next().timeMs > ttl) it.remove();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent e) {
        Color s = col.get();
        Color l = line.get();
        ShapeMode sh = shape.get();
        synchronized (booms) {
            for (Boom b : booms) {
                double r = Math.max(1.0, b.radius);
                e.renderer.box(b.pos.x - r, b.pos.y - r, b.pos.z - r,
                               b.pos.x + r, b.pos.y + r, b.pos.z + r,
                               s, l, sh, 0);
            }
        }
    }

    public List<Boom> snapshot() {
        synchronized (booms) { return new ArrayList<>(booms); }
    }

    @Override
    public String getInfoString() {
        synchronized (booms) { return "§c" + booms.size() + "§7 nổ"; }
    }
}
