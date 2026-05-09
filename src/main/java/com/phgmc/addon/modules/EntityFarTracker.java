package com.phgmc.addon.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityType;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Theo-Doi-Entity-Xa — listens to {@link EntitySpawnS2CPacket} and logs
 * every spawn outside a configured radius. Captures item drops, xp orbs,
 * vehicles, projectiles, hostile mobs, etc.
 *
 * Item-drop / xp-orb spawn = someone died / mined / fought near here.
 * Boat / Minecart spawn = transport hub. Persists to disk.
 */
public class EntityFarTracker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter  = settings.createGroup("Bộ lọc loại entity");
    private final SettingGroup sgRender  = settings.createGroup("Hiển thị");

    private final Setting<Integer> minDistance = sgGeneral.add(new IntSetting.Builder()
        .name("min-distance")
        .description("Bỏ qua entity gần player ≤ N block (tránh log entity quanh bạn).")
        .defaultValue(48).min(0).sliderMax(256).build());

    private final Setting<Integer> ttlSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("ttl-giây").defaultValue(900).min(60).sliderMax(7200).build());

    private final Setting<Integer> maxRecords = sgGeneral.add(new IntSetting.Builder()
        .name("max-record").defaultValue(500).min(50).sliderMax(5000).build());

    private final Setting<Integer> saveEveryTicks = sgGeneral.add(new IntSetting.Builder()
        .name("save-mỗi-tick").defaultValue(400).min(40).sliderMax(2400).build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-chat-quan-trọng")
        .description("In chat khi spawn item/xp/boat/minecart/projectile.")
        .defaultValue(true).build());

    private final Setting<Boolean> fItem = sgFilter.add(new BoolSetting.Builder().name("item-drop").defaultValue(true).build());
    private final Setting<Boolean> fXp   = sgFilter.add(new BoolSetting.Builder().name("xp-orb").defaultValue(true).build());
    private final Setting<Boolean> fBoat = sgFilter.add(new BoolSetting.Builder().name("boat").defaultValue(true).build());
    private final Setting<Boolean> fMinecart = sgFilter.add(new BoolSetting.Builder().name("minecart").defaultValue(true).build());
    private final Setting<Boolean> fProjectile = sgFilter.add(new BoolSetting.Builder().name("projectile (arrow/trident/pearl/snowball)").defaultValue(true).build());
    private final Setting<Boolean> fEnderEye = sgFilter.add(new BoolSetting.Builder().name("eye-of-ender").defaultValue(true).build());
    private final Setting<Boolean> fHostile = sgFilter.add(new BoolSetting.Builder().name("hostile-mob").defaultValue(false).build());
    private final Setting<Boolean> fPassive = sgFilter.add(new BoolSetting.Builder().name("passive-mob").defaultValue(false).build());

    private final Setting<SettingColor> col = sgRender.add(new ColorSetting.Builder()
        .name("màu").defaultValue(new SettingColor(255, 200, 80, 80)).build());
    private final Setting<SettingColor> line = sgRender.add(new ColorSetting.Builder()
        .name("viền").defaultValue(new SettingColor(255, 220, 130, 220)).build());
    private final Setting<ShapeMode> shape = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape").defaultValue(ShapeMode.Both).build());

    public static class Record {
        public String type;
        public double x, y, z;
        public long timeMs;
    }

    private final List<Record> records = new ArrayList<>();
    private int tickCounter = 0;
    private boolean dirty = false;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public EntityFarTracker() {
        super(PhgMCAddon.PhgMC_Support, "Theo-Doi-Entity-Xa",
            "Log entity spawn xa player (item/xp/boat/projectile = hoạt động).");
    }

    @Override public void onActivate() { load(); }
    @Override public void onDeactivate() { save(); }

    @EventHandler
    private void onPacket(PacketEvent.Receive e) {
        if (!(e.packet instanceof EntitySpawnS2CPacket pkt)) return;
        if (mc.player == null) return;

        EntityType<?> type = pkt.getEntityType();
        if (type == null || type == EntityType.LIGHTNING_BOLT) return;

        if (!isAllowedType(type)) return;

        double x = pkt.getX(), y = pkt.getY(), z = pkt.getZ();
        double dx = x - mc.player.getX(), dy = y - mc.player.getY(), dz = z - mc.player.getZ();
        double dsq = dx * dx + dy * dy + dz * dz;
        int min = minDistance.get();
        if (dsq < min * min) return;

        Identifier id = Registries.ENTITY_TYPE.getId(type);
        if (id == null) return;

        Record r = new Record();
        r.type = id.toString();
        r.x = x; r.y = y; r.z = z;
        r.timeMs = System.currentTimeMillis();
        synchronized (records) {
            records.add(r);
            while (records.size() > maxRecords.get()) records.remove(0);
        }
        dirty = true;

        if (announce.get() && isImportant(type)) {
            ChatUtils.info("Theo-Doi-Entity-Xa: §6%s§r tại §e%d, %d, %d§r §7(%.0fm)",
                id.getPath(), (int) x, (int) y, (int) z, Math.sqrt(dsq));
        }
    }

    private boolean isAllowedType(EntityType<?> t) {
        if (t == EntityType.ITEM)             return fItem.get();
        if (t == EntityType.EXPERIENCE_ORB)   return fXp.get();
        if (t == EntityType.OAK_BOAT || t == EntityType.SPRUCE_BOAT || t == EntityType.BIRCH_BOAT
            || t == EntityType.JUNGLE_BOAT || t == EntityType.ACACIA_BOAT || t == EntityType.DARK_OAK_BOAT
            || t == EntityType.MANGROVE_BOAT || t == EntityType.CHERRY_BOAT || t == EntityType.PALE_OAK_BOAT
            || t == EntityType.BAMBOO_RAFT
            || t == EntityType.OAK_CHEST_BOAT || t == EntityType.SPRUCE_CHEST_BOAT
            || t == EntityType.BIRCH_CHEST_BOAT || t == EntityType.JUNGLE_CHEST_BOAT
            || t == EntityType.ACACIA_CHEST_BOAT || t == EntityType.DARK_OAK_CHEST_BOAT
            || t == EntityType.MANGROVE_CHEST_BOAT || t == EntityType.CHERRY_CHEST_BOAT
            || t == EntityType.PALE_OAK_CHEST_BOAT || t == EntityType.BAMBOO_CHEST_RAFT)
            return fBoat.get();
        if (t == EntityType.MINECART || t == EntityType.CHEST_MINECART
            || t == EntityType.HOPPER_MINECART || t == EntityType.TNT_MINECART
            || t == EntityType.FURNACE_MINECART || t == EntityType.SPAWNER_MINECART
            || t == EntityType.COMMAND_BLOCK_MINECART)
            return fMinecart.get();
        if (t == EntityType.ARROW || t == EntityType.SPECTRAL_ARROW
            || t == EntityType.TRIDENT || t == EntityType.ENDER_PEARL
            || t == EntityType.SNOWBALL || t == EntityType.EGG
            || t == EntityType.SPLASH_POTION || t == EntityType.LINGERING_POTION
            || t == EntityType.FIREBALL || t == EntityType.SMALL_FIREBALL
            || t == EntityType.WIND_CHARGE || t == EntityType.BREEZE_WIND_CHARGE
            || t == EntityType.FIREWORK_ROCKET)
            return fProjectile.get();
        if (t == EntityType.EYE_OF_ENDER) return fEnderEye.get();

        // Heuristic: hostile vs passive
        String cat = t.getSpawnGroup().getName();
        if (cat.equals("monster")) return fHostile.get();
        if (cat.equals("creature") || cat.equals("ambient") || cat.equals("water_creature")
            || cat.equals("water_ambient") || cat.equals("axolotls") || cat.equals("underground_water_creature"))
            return fPassive.get();

        return false;
    }

    private boolean isImportant(EntityType<?> t) {
        return t == EntityType.ITEM || t == EntityType.EXPERIENCE_ORB
            || t == EntityType.EYE_OF_ENDER
            || t == EntityType.MINECART || t == EntityType.CHEST_MINECART
            || t == EntityType.TNT_MINECART
            || t == EntityType.TRIDENT || t == EntityType.FIREWORK_ROCKET;
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        long now = System.currentTimeMillis();
        long ttl = ttlSeconds.get() * 1000L;
        synchronized (records) { records.removeIf(r -> now - r.timeMs > ttl); }
        if (++tickCounter >= saveEveryTicks.get()) {
            tickCounter = 0;
            if (dirty) save();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent e) {
        Color s = col.get();
        Color l = line.get();
        ShapeMode sh = shape.get();
        synchronized (records) {
            for (Record r : records) {
                e.renderer.box(r.x - 0.4, r.y - 0.4, r.z - 0.4,
                               r.x + 0.4, r.y + 0.4, r.z + 0.4, s, l, sh, 0);
            }
        }
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir()
            .resolve("phgmc").resolve("entity_far.json");
    }

    private void load() {
        try {
            Path p = file();
            if (!Files.exists(p)) return;
            String json = Files.readString(p);
            List<Record> loaded = gson.fromJson(json,
                new com.google.gson.reflect.TypeToken<ArrayList<Record>>(){}.getType());
            if (loaded != null) {
                synchronized (records) {
                    records.clear();
                    records.addAll(loaded);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void save() {
        try {
            Path p = file();
            Files.createDirectories(p.getParent());
            List<Record> snap;
            synchronized (records) { snap = new ArrayList<>(records); }
            Files.writeString(p, gson.toJson(snap));
            dirty = false;
        } catch (IOException ignored) {}
    }

    public List<Record> snapshot() {
        synchronized (records) { return new ArrayList<>(records); }
    }

    @Override
    public String getInfoString() {
        synchronized (records) { return "§6" + records.size() + "§7 record"; }
    }
}
