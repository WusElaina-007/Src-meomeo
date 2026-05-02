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
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Theo-Doi-Am-Thanh — listens for {@link PlaySoundS2CPacket} containing
 * sounds that indicate player activity (anvil, beacon, brewing, jukebox,
 * note block, totem, TNT, beehive work, conduit, respawn anchor, ...).
 * Sound packets carry world coordinates that the server sends regardless
 * of render distance — so this works on SMP servers with reduced view.
 *
 * Pure receive-side observation → no packets sent, undetectable.
 */
public class SoundTracker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter  = settings.createGroup("Bộ lọc sound");
    private final SettingGroup sgRender  = settings.createGroup("Hiển thị");

    private final Setting<Integer> clusterRadius = sgGeneral.add(new IntSetting.Builder()
        .name("cụm-bán-kính")
        .description("Bán kính (block) để gộp nhiều sound thành 1 cluster.")
        .defaultValue(24).min(4).sliderMin(4).sliderMax(64)
        .build());

    private final Setting<Integer> minHits = sgGeneral.add(new IntSetting.Builder()
        .name("ngưỡng-cluster")
        .description("Số sound tối thiểu trong 1 cluster để báo + waypoint.")
        .defaultValue(3).min(1).sliderMin(1).sliderMax(20)
        .build());

    private final Setting<Integer> ttlSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("ttl-giây")
        .description("Cluster bị xoá sau N giây không có sound mới.")
        .defaultValue(180).min(10).sliderMin(10).sliderMax(1800)
        .build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-chat")
        .description("Báo khi cluster đạt ngưỡng.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoWaypoint = sgGeneral.add(new BoolSetting.Builder()
        .name("tự-tạo-waypoint")
        .description("Tự thêm waypoint cho mỗi cluster đạt ngưỡng.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> ignoreCloseToPlayer = sgGeneral.add(new BoolSetting.Builder()
        .name("bỏ-qua-gần-player")
        .description("Không track sound trong vòng 32 block của bạn (tránh log sound do chính bạn gây ra).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> filterAnvil = sgFilter.add(new BoolSetting.Builder().name("anvil").defaultValue(true).build());
    private final Setting<Boolean> filterBeacon = sgFilter.add(new BoolSetting.Builder().name("beacon").defaultValue(true).build());
    private final Setting<Boolean> filterBrewing = sgFilter.add(new BoolSetting.Builder().name("brewing").defaultValue(true).build());
    private final Setting<Boolean> filterJukebox = sgFilter.add(new BoolSetting.Builder().name("jukebox").defaultValue(true).build());
    private final Setting<Boolean> filterNote = sgFilter.add(new BoolSetting.Builder().name("note-block").defaultValue(true).build());
    private final Setting<Boolean> filterTotem = sgFilter.add(new BoolSetting.Builder().name("totem").defaultValue(true).build());
    private final Setting<Boolean> filterTnt = sgFilter.add(new BoolSetting.Builder().name("tnt").defaultValue(true).build());
    private final Setting<Boolean> filterBee = sgFilter.add(new BoolSetting.Builder().name("beehive").defaultValue(true).build());
    private final Setting<Boolean> filterRespawnAnchor = sgFilter.add(new BoolSetting.Builder().name("respawn-anchor").defaultValue(true).build());
    private final Setting<Boolean> filterConduit = sgFilter.add(new BoolSetting.Builder().name("conduit").defaultValue(true).build());
    private final Setting<Boolean> filterFireworks = sgFilter.add(new BoolSetting.Builder().name("firework").defaultValue(true).build());
    private final Setting<Boolean> filterMinecart = sgFilter.add(new BoolSetting.Builder().name("minecart").defaultValue(true).build());
    private final Setting<Boolean> filterRedstone = sgFilter.add(new BoolSetting.Builder().name("redstone").defaultValue(true).build());
    private final Setting<Boolean> filterSmithing = sgFilter.add(new BoolSetting.Builder().name("smithing").defaultValue(true).build());
    private final Setting<Boolean> filterSheep = sgFilter.add(new BoolSetting.Builder().name("animal-farm").defaultValue(false)
        .description("Tiếng cừu/bò/lợn — chỉ ra animal farm. Tắt mặc định vì sẽ trigger ở mọi nơi có động vật.").build());

    private final Setting<SettingColor> col = sgRender.add(new ColorSetting.Builder()
        .name("màu")
        .defaultValue(new SettingColor(255, 100, 200, 100))
        .build());

    private final Setting<SettingColor> line = sgRender.add(new ColorSetting.Builder()
        .name("viền")
        .defaultValue(new SettingColor(255, 140, 220, 220))
        .build());

    private final Setting<ShapeMode> shape = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("kiểu-hiển-thị")
        .defaultValue(ShapeMode.Both)
        .build());

    private static final String WP_PREFIX = "[Sound] ";

    public static class Cluster {
        public double x, y, z;
        public int hits;
        public long lastHitMs;
        public boolean reported;
        public final Map<String, Integer> sounds = new HashMap<>();
        public String dimension;
    }

    private final List<Cluster> clusters = new ArrayList<>();

    public SoundTracker() {
        super(PhgMCAddon.PhgMC_Support, "Theo-Doi-Am-Thanh",
            "Phát hiện base / hoạt động qua sound packet (anvil/beacon/jukebox/totem/tnt...).");
    }

    @Override
    public void onActivate() {
        clusters.clear();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        Identifier soundId;
        double x, y, z;
        if (event.packet instanceof PlaySoundS2CPacket pkt) {
            soundId = idOf(pkt.getSound());
            x = pkt.getX(); y = pkt.getY(); z = pkt.getZ();
        } else if (event.packet instanceof PlaySoundFromEntityS2CPacket pkt) {
            soundId = idOf(pkt.getSound());
            if (mc.world == null) return;
            var ent = mc.world.getEntityById(pkt.getEntityId());
            if (ent == null) return;
            x = ent.getX(); y = ent.getY(); z = ent.getZ();
        } else {
            return;
        }
        if (soundId == null) return;
        if (!isInteresting(soundId)) return;
        if (mc.player == null || mc.world == null) return;

        if (ignoreCloseToPlayer.get()) {
            double dx = x - mc.player.getX(), dy = y - mc.player.getY(), dz = z - mc.player.getZ();
            if (dx * dx + dy * dy + dz * dz < 32 * 32) return;
        }

        addToCluster(soundId.toString(), x, y, z);
    }

    private static Identifier idOf(RegistryEntry<SoundEvent> entry) {
        if (entry == null) return null;
        var key = entry.getKey().orElse(null);
        if (key != null) return key.getValue();
        try { return entry.value().id(); } catch (Throwable t) { return null; }
    }

    private boolean isInteresting(Identifier id) {
        String s = id.toString();
        if (filterAnvil.get() && s.startsWith("minecraft:block.anvil.")) return true;
        if (filterBeacon.get() && s.startsWith("minecraft:block.beacon.")) return true;
        if (filterBrewing.get() && s.equals("minecraft:block.brewing_stand.brew")) return true;
        if (filterJukebox.get() && s.startsWith("minecraft:block.jukebox.")) return true;
        if (filterJukebox.get() && s.startsWith("minecraft:music_disc.")) return true;
        if (filterNote.get() && s.startsWith("minecraft:block.note_block.")) return true;
        if (filterTotem.get() && s.equals("minecraft:item.totem.use")) return true;
        if (filterTnt.get() && s.equals("minecraft:entity.tnt.primed")) return true;
        if (filterTnt.get() && s.equals("minecraft:entity.generic.explode")) return true;
        if (filterBee.get() && s.startsWith("minecraft:block.beehive.")) return true;
        if (filterRespawnAnchor.get() && s.startsWith("minecraft:block.respawn_anchor.")) return true;
        if (filterConduit.get() && s.startsWith("minecraft:block.conduit.")) return true;
        if (filterFireworks.get() && s.startsWith("minecraft:entity.firework_rocket.")) return true;
        if (filterMinecart.get() && s.startsWith("minecraft:entity.minecart.")) return true;
        if (filterRedstone.get() && (s.equals("minecraft:block.dispenser.dispense")
            || s.equals("minecraft:block.dispenser.fail")
            || s.equals("minecraft:block.piston.extend")
            || s.equals("minecraft:block.piston.contract")
            || s.equals("minecraft:block.lever.click")
            || s.equals("minecraft:block.note_block.basedrum"))) return true;
        if (filterSmithing.get() && (s.equals("minecraft:block.smithing_table.use")
            || s.equals("minecraft:block.grindstone.use")
            || s.equals("minecraft:ui.stonecutter.take_result"))) return true;
        if (filterSheep.get() && (s.startsWith("minecraft:entity.cow.")
            || s.startsWith("minecraft:entity.sheep.")
            || s.startsWith("minecraft:entity.pig.")
            || s.startsWith("minecraft:entity.chicken.")
            || s.startsWith("minecraft:entity.villager."))) return true;
        return false;
    }

    private void addToCluster(String soundId, double x, double y, double z) {
        long now = System.currentTimeMillis();
        int rsq = clusterRadius.get() * clusterRadius.get();
        Cluster best = null;
        double bestSq = rsq;
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
            best.dimension = mc.world.getRegistryKey().getValue().toString();
            clusters.add(best);
        } else {
            // moving average to centre on hot spot
            best.x = (best.x * best.hits + x) / (best.hits + 1);
            best.y = (best.y * best.hits + y) / (best.hits + 1);
            best.z = (best.z * best.hits + z) / (best.hits + 1);
        }
        best.hits++;
        best.lastHitMs = now;
        best.sounds.merge(soundId, 1, Integer::sum);

        if (!best.reported && best.hits >= minHits.get()) {
            best.reported = true;
            if (announce.get()) {
                ChatUtils.info("Theo-Doi-Am-Thanh: §dcluster§r tại §e%d, %d, %d§r (%d sound, top: %s)",
                    (int) best.x, (int) best.y, (int) best.z, best.hits, topSound(best));
            }
            if (autoWaypoint.get()) {
                try {
                    Waypoint wp = new Waypoint.Builder()
                        .name(WP_PREFIX + topSound(best) + " " + (int) best.x + "," + (int) best.z)
                        .icon("diamond")
                        .pos(BlockPos.ofFloored(best.x, best.y, best.z))
                        .build();
                    Waypoints.get().add(wp);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static String topSound(Cluster c) {
        String top = "";
        int best = 0;
        for (var entry : c.sounds.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                top = entry.getKey();
            }
        }
        if (top.startsWith("minecraft:")) top = top.substring("minecraft:".length());
        return top;
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        long now = System.currentTimeMillis();
        long ttlMs = ttlSeconds.get() * 1000L;
        clusters.removeIf(c -> now - c.lastHitMs > ttlMs);
    }

    @EventHandler
    private void onRender(Render3DEvent e) {
        if (clusters.isEmpty()) return;
        Color s = col.get();
        Color l = line.get();
        ShapeMode sh = shape.get();
        int min = minHits.get();
        for (Cluster c : clusters) {
            if (c.hits < min) continue;
            double r = 1.5;
            e.renderer.box(c.x - r, c.y - r, c.z - r,
                           c.x + r, c.y + r, c.z + r, s, l, sh, 0);
            // beam
            e.renderer.line(c.x, -64, c.z, c.x, 320, c.z, l);
        }
    }

    @Override
    public String getInfoString() {
        long active = clusters.stream().filter(c -> c.hits >= minHits.get()).count();
        return "§d" + active + "§7 cluster";
    }
}
