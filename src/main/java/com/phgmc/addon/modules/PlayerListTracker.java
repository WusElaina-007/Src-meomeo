package com.phgmc.addon.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Theo-Doi-Tab-List — listens to {@link PlayerListS2CPacket} and
 * {@link PlayerRemoveS2CPacket}. Records every join/leave with a
 * timestamp. Persists to {@code config/phgmc/player_list.json}.
 *
 * Use to build an "online schedule" for individual players — when they
 * usually log in. Useful before a raid (catch them online or offline).
 */
public class PlayerListTracker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> announceJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-join").defaultValue(true).build());

    private final Setting<Boolean> announceLeave = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-leave").defaultValue(true).build());

    private final Setting<Integer> saveEveryTicks = sgGeneral.add(new IntSetting.Builder()
        .name("save-mỗi-tick").defaultValue(200).min(20).sliderMax(1200).build());

    private final Setting<Integer> maxSessionsPerPlayer = sgGeneral.add(new IntSetting.Builder()
        .name("max-session-mỗi-player")
        .description("FIFO trim — chỉ giữ N session gần nhất.")
        .defaultValue(50).min(5).sliderMax(500).build());

    public static class Session {
        public long joinMs;
        public long leaveMs;
        public String name;
    }

    public static class Entry {
        public String uuid;
        public String name;
        public long firstSeenMs;
        public long lastSeenMs;
        public int joinCount;
        public final List<Session> sessions = new ArrayList<>();
        public boolean online;
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String, Long> currentJoinMs = new HashMap<>();
    private int tickCounter = 0;
    private boolean dirty = false;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public PlayerListTracker() {
        super(PhgMCAddon.PhgMC_Support, "Theo-Doi-Tab-List",
            "Track join/leave từ tab list. Lưu DB schedule online theo player.");
    }

    @Override public void onActivate() { load(); }
    @Override public void onDeactivate() { save(); }

    @EventHandler
    private void onPacket(PacketEvent.Receive e) {
        if (e.packet instanceof PlayerListS2CPacket pkt) {
            if (!pkt.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) return;
            for (PlayerListS2CPacket.Entry entry : pkt.getPlayerAdditionEntries()) {
                if (entry.profile() == null) continue;
                String name = entry.profile().name();
                String uuid = entry.profileId().toString();
                if (name == null) continue;
                onJoin(uuid, name);
            }
        } else if (e.packet instanceof PlayerRemoveS2CPacket pkt) {
            for (UUID uuid : pkt.profileIds()) onLeave(uuid.toString());
        }
    }

    private void onJoin(String uuid, String name) {
        long now = System.currentTimeMillis();
        Entry en = entries.computeIfAbsent(uuid, k -> {
            Entry x = new Entry();
            x.uuid = k;
            x.firstSeenMs = now;
            return x;
        });
        if (en.online) return; // duplicate add
        en.online = true;
        en.name = name;
        en.lastSeenMs = now;
        en.joinCount++;
        currentJoinMs.put(uuid, now);
        dirty = true;
        if (announceJoin.get()) {
            ChatUtils.info("Theo-Doi-Tab-List: §a+ %s§r §7(lần thứ %d)", name, en.joinCount);
        }
    }

    private void onLeave(String uuid) {
        long now = System.currentTimeMillis();
        Entry en = entries.get(uuid);
        if (en == null || !en.online) return;
        en.online = false;
        en.lastSeenMs = now;
        Long jms = currentJoinMs.remove(uuid);
        if (jms != null) {
            Session s = new Session();
            s.joinMs = jms;
            s.leaveMs = now;
            s.name = en.name;
            en.sessions.add(s);
            int max = maxSessionsPerPlayer.get();
            while (en.sessions.size() > max) en.sessions.remove(0);
        }
        dirty = true;
        if (announceLeave.get()) {
            ChatUtils.info("Theo-Doi-Tab-List: §c- %s§r §7(%s online)", en.name, jms == null ? "?" : human(now - jms));
        }
    }

    private static String human(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (++tickCounter < saveEveryTicks.get()) return;
        tickCounter = 0;
        if (dirty) save();
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir()
            .resolve("phgmc").resolve("player_list.json");
    }

    private void load() {
        try {
            Path p = file();
            if (!Files.exists(p)) return;
            String json = Files.readString(p);
            Map<String, Entry> loaded = gson.fromJson(json,
                new com.google.gson.reflect.TypeToken<HashMap<String, Entry>>(){}.getType());
            if (loaded != null) {
                entries.clear();
                entries.putAll(loaded);
                // Reset online flags (we just connected)
                for (Entry en : entries.values()) en.online = false;
                currentJoinMs.clear();
            }
        } catch (Throwable ignored) {}
    }

    private void save() {
        try {
            Path p = file();
            Files.createDirectories(p.getParent());
            Files.writeString(p, gson.toJson(entries));
            dirty = false;
        } catch (IOException ignored) {}
    }

    public Map<String, Entry> entries() {
        return new HashMap<>(entries);
    }

    @Override
    public String getInfoString() {
        long online = entries.values().stream().filter(e -> e.online).count();
        return online + "/" + entries.size();
    }
}
