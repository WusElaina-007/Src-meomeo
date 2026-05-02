package com.phgmc.addon.modules;

import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.MapColor;
import net.minecraft.network.packet.s2c.play.MapUpdateS2CPacket;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Doc-Map-Vat-Pham — listens to {@link MapUpdateS2CPacket}. When any
 * other player nearby holds an item-frame map or hand-held map, the
 * server syncs the map's color buffer to your client so it renders
 * correctly. We capture that buffer, accumulate per-map state, and
 * dump to a PNG in {@code config/phgmc/maps/}. Effectively a free
 * leak of any explored area someone else has mapped.
 */
public class MapReader extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> dumpEverySeconds = sgGeneral.add(new IntSetting.Builder()
        .name("dump-mỗi-giây")
        .description("Dump PNG khi map có thay đổi mới và đã N giây kể từ dump trước.")
        .defaultValue(15).min(1).sliderMax(120).build());

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-chat")
        .description("In chat khi nhận hoặc dump map mới.")
        .defaultValue(true).build());

    private final Setting<Boolean> announceFirst = sgGeneral.add(new BoolSetting.Builder()
        .name("báo-map-mới")
        .description("Chỉ báo lần đầu map ID xuất hiện (giảm spam).")
        .defaultValue(true).build());

    private static final int MAP_SIZE = 128;

    public static class Buf {
        public byte[] colors = new byte[MAP_SIZE * MAP_SIZE];
        public boolean dirty;
        public long lastUpdateMs;
        public long lastDumpMs;
        public int updates;
    }

    private final Map<Integer, Buf> buffers = new HashMap<>();

    public MapReader() {
        super(PhgMCAddon.PhgMC_Support, "Doc-Map-Vat-Pham",
            "Đọc map item của player khác, dump PNG vào config/phgmc/maps/.");
    }

    @Override
    public void onActivate() {
        synchronized (buffers) { buffers.clear(); }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive e) {
        if (!(e.packet instanceof MapUpdateS2CPacket pkt)) return;
        if (pkt.updateData().isEmpty()) return;
        var ud = pkt.updateData().get();
        int mapId = pkt.mapId().id();

        Buf buf;
        boolean isNew;
        synchronized (buffers) {
            buf = buffers.get(mapId);
            isNew = (buf == null);
            if (isNew) {
                buf = new Buf();
                buffers.put(mapId, buf);
            }
        }

        // Apply update: ud has startX, startZ, width, height, colors[]
        try {
            int sx = ud.startX();
            int sz = ud.startZ();
            int w = ud.width();
            int h = ud.height();
            byte[] src = ud.colors();
            for (int dz = 0; dz < h; dz++) {
                for (int dx = 0; dx < w; dx++) {
                    int x = sx + dx;
                    int z = sz + dz;
                    if (x < 0 || x >= MAP_SIZE || z < 0 || z >= MAP_SIZE) continue;
                    buf.colors[z * MAP_SIZE + x] = src[dz * w + dx];
                }
            }
            buf.dirty = true;
            buf.lastUpdateMs = System.currentTimeMillis();
            buf.updates++;
        } catch (Throwable t) {
            return;
        }

        if (announce.get() && (isNew || !announceFirst.get())) {
            ChatUtils.info("Doc-Map-Vat-Pham: §dmap %d§r %s (%d update)",
                mapId, isNew ? "§a(MỚI)§r" : "cập nhật", buf.updates);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        long now = System.currentTimeMillis();
        long throttleMs = dumpEverySeconds.get() * 1000L;
        synchronized (buffers) {
            for (var entry : buffers.entrySet()) {
                Buf buf = entry.getValue();
                if (!buf.dirty) continue;
                if (now - buf.lastDumpMs < throttleMs) continue;
                if (dumpToPng(entry.getKey(), buf)) {
                    buf.dirty = false;
                    buf.lastDumpMs = now;
                }
            }
        }
    }

    private boolean dumpToPng(int mapId, Buf buf) {
        try {
            BufferedImage img = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int z = 0; z < MAP_SIZE; z++) {
                for (int x = 0; x < MAP_SIZE; x++) {
                    int c = buf.colors[z * MAP_SIZE + x] & 0xFF;
                    int rgb = MapColor.getRenderColor(c);
                    if ((c & 0b11) == 0) {
                        img.setRGB(x, z, 0); // transparent
                    } else {
                        // ABGR → ARGB swizzle for AWT
                        int a = 0xFF;
                        int b = (rgb >> 16) & 0xFF;
                        int g = (rgb >> 8) & 0xFF;
                        int r = rgb & 0xFF;
                        img.setRGB(x, z, (a << 24) | (r << 16) | (g << 8) | b);
                    }
                }
            }
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("phgmc").resolve("maps");
            Files.createDirectories(dir);
            Path file = dir.resolve("map_" + mapId + ".png");
            ImageIO.write(img, "PNG", file.toFile());
            if (announce.get()) {
                ChatUtils.info("Doc-Map-Vat-Pham: §ddumped§r map %d → %s", mapId, file);
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    @Override
    public String getInfoString() {
        synchronized (buffers) { return "§d" + buffers.size() + "§7 map"; }
    }
}
