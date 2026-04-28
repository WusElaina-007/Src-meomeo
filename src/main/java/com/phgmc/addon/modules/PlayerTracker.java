package com.phgmc.addon.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Theo-Doi-Player — passive tracker. Logs every player you encounter:
 * name, UUID, last-seen position + dimension + timestamp, main hand item,
 * armor pieces. Saves to {@code config/phgmc/players.json} so the database
 * survives sessions. Pure observation, no packets sent → undetectable.
 */
public class PlayerTracker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgChat    = settings.createGroup("Chat");

    private final Setting<Integer> scanInterval = sgGeneral.add(new IntSetting.Builder()
        .name("scan-mỗi-tick")
        .description("Quét player nearby mỗi N tick (20 = 1s).")
        .defaultValue(40).min(5).sliderMin(5).sliderMax(200)
        .build());

    private final Setting<Integer> saveInterval = sgGeneral.add(new IntSetting.Builder()
        .name("save-mỗi-tick")
        .description("Lưu DB ra file mỗi N tick.")
        .defaultValue(200).min(40).sliderMin(40).sliderMax(2400)
        .build());

    private final Setting<Boolean> recordItems = sgGeneral.add(new BoolSetting.Builder()
        .name("ghi-item")
        .description("Ghi tay chính + giáp player đang cầm/mặc.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> maxEntries = sgGeneral.add(new IntSetting.Builder()
        .name("max-entries")
        .description("Tối đa số player lưu trong DB (xoá cũ nhất khi tràn).")
        .defaultValue(1000).min(50).sliderMin(50).sliderMax(10000)
        .build());

    private final Setting<Boolean> announceFirstSeen = sgChat.add(new BoolSetting.Builder()
        .name("báo-player-mới")
        .description("In chat khi gặp player chưa từng thấy lần nào.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> announceReSeen = sgChat.add(new BoolSetting.Builder()
        .name("báo-gặp-lại")
        .description("In chat khi gặp lại player cũ (vì đã từng track).")
        .defaultValue(false)
        .build());

    public static class Entry {
        public String uuid;
        public String name;
        public double x, y, z;
        public String dimension;
        public long firstSeenMs;
        public long lastSeenMs;
        public int seenCount;
        public String mainHand;
        public String offHand;
        public String[] armor;
        public float lastHealth;
    }

    private final Map<String, Entry> db = new HashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DB_TYPE = new TypeToken<Map<String, Entry>>() {}.getType();
    private int scanCounter, saveCounter;
    private boolean dirty;

    public PlayerTracker() {
        super(PhgMCAddon.PhgMC_Support, "Theo-Doi-Player",
            "Passive tracker: ghi nhận player gặp được vào DB JSON để xem lại.");
    }

    private static Path dbFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("phgmc").resolve("players.json");
    }

    @Override
    public void onActivate() {
        loadDb();
        scanCounter = 0;
        saveCounter = 0;
        dirty = false;
    }

    @Override
    public void onDeactivate() {
        saveDbIfDirty();
    }

    @SuppressWarnings("unchecked")
    private void loadDb() {
        db.clear();
        Path f = dbFile();
        if (!Files.exists(f)) return;
        try (Reader r = Files.newBufferedReader(f)) {
            Map<String, Entry> loaded = GSON.fromJson(r, DB_TYPE);
            if (loaded != null) db.putAll(loaded);
        } catch (Exception e) {
            ChatUtils.warning("Theo-Doi-Player: load DB fail: %s", e.toString());
        }
    }

    private void saveDbIfDirty() {
        if (!dirty) return;
        try {
            Path f = dbFile();
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f)) {
                GSON.toJson(db, DB_TYPE, w);
            }
            dirty = false;
        } catch (IOException e) {
            ChatUtils.warning("Theo-Doi-Player: save DB fail: %s", e.toString());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (mc.world == null || mc.player == null) return;

        if (++scanCounter >= scanInterval.get()) {
            scanCounter = 0;
            scanWorld();
        }
        if (++saveCounter >= saveInterval.get()) {
            saveCounter = 0;
            saveDbIfDirty();
            // Trim if over capacity
            if (db.size() > maxEntries.get()) trimOldest();
        }
    }

    private void scanWorld() {
        long now = System.currentTimeMillis();
        String dim = mc.world.getRegistryKey().getValue().toString();

        for (var p : mc.world.getPlayers()) {
            if (!(p instanceof AbstractClientPlayerEntity acp)) continue;
            if (acp == mc.player) continue;
            UUID uuid = acp.getUuid();
            String key = uuid.toString();
            Entry entry = db.get(key);
            boolean isNew = entry == null;
            if (isNew) {
                entry = new Entry();
                entry.uuid = key;
                entry.firstSeenMs = now;
                entry.seenCount = 0;
            }
            entry.name = acp.getName().getString();
            entry.x = acp.getX();
            entry.y = acp.getY();
            entry.z = acp.getZ();
            entry.dimension = dim;
            entry.lastSeenMs = now;
            entry.seenCount++;
            entry.lastHealth = acp.getHealth();

            if (recordItems.get()) {
                entry.mainHand = stackToId(acp.getMainHandStack());
                entry.offHand  = stackToId(acp.getOffHandStack());
                String[] armor = new String[4];
                armor[0] = stackToId(acp.getEquippedStack(EquipmentSlot.HEAD));
                armor[1] = stackToId(acp.getEquippedStack(EquipmentSlot.CHEST));
                armor[2] = stackToId(acp.getEquippedStack(EquipmentSlot.LEGS));
                armor[3] = stackToId(acp.getEquippedStack(EquipmentSlot.FEET));
                entry.armor = armor;
            }

            db.put(key, entry);
            dirty = true;

            if (isNew && announceFirstSeen.get()) {
                ChatUtils.info("Theo-Doi-Player: §a[mới]§r %s §7(%d, %d, %d)",
                    entry.name, (int) entry.x, (int) entry.y, (int) entry.z);
            } else if (!isNew && announceReSeen.get() && entry.seenCount % 100 == 1) {
                ChatUtils.info("Theo-Doi-Player: §egặp lại§r %s (lần %d)",
                    entry.name, entry.seenCount);
            }
        }
    }

    private static String stackToId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    private void trimOldest() {
        List<Entry> all = new ArrayList<>(db.values());
        all.sort(Comparator.comparingLong(en -> en.lastSeenMs));
        int toRemove = db.size() - maxEntries.get();
        for (int i = 0; i < toRemove && i < all.size(); i++) {
            db.remove(all.get(i).uuid);
        }
        dirty = true;
    }

    /** Returns DB entries sorted newest first. Useful for UI / commands. */
    public List<Entry> entriesByMostRecent() {
        List<Entry> list = new ArrayList<>(db.values());
        list.sort(Comparator.comparingLong((Entry e) -> e.lastSeenMs).reversed());
        return Collections.unmodifiableList(list);
    }

    @Override
    public String getInfoString() {
        return db.size() + " player";
    }
}
