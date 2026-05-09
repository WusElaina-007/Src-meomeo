package com.phgmc.addon.modules;

import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import java.util.Random;

/**
 * Tim-Slime — slime chunk overlay. Pure offline math from world seed.
 * No packets sent → 100% undetectable by anti-cheat.
 *
 * Algorithm: vanilla slime chunk RNG used since pre-1.0.
 */
public class SlimeChunk extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Hiển thị");

    private final Setting<String> seedStr = sgGeneral.add(new StringSetting.Builder()
        .name("seed")
        .description("Seed thế giới (paste từ SeedCracker hoặc /seed).")
        .defaultValue("")
        .build());

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("phạm-vi-quét")
        .description("Bán kính vẽ overlay quanh player (chunk).")
        .defaultValue(8).min(1).sliderMin(1).sliderMax(32)
        .build());

    private final Setting<Integer> minY = sgRender.add(new IntSetting.Builder()
        .name("y-bottom")
        .description("Y dưới của ô slime chunk.")
        .defaultValue(-64).sliderMin(-64).sliderMax(320)
        .build());

    private final Setting<Integer> maxY = sgRender.add(new IntSetting.Builder()
        .name("y-top")
        .description("Y trên của ô slime chunk. Slime chỉ spawn dưới Y=40 nên mặc định 40.")
        .defaultValue(40).sliderMin(-64).sliderMax(320)
        .build());

    private final Setting<Boolean> vertical = sgRender.add(new BoolSetting.Builder()
        .name("ô-dọc")
        .description("Vẽ ô đứng từ y-bottom đến y-top (giúp nhìn từ xa).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> floorMarker = sgRender.add(new BoolSetting.Builder()
        .name("đánh-dấu-mặt-đất")
        .description("Vẽ ô phẳng tại y-top (dễ nhìn khi đứng trên mặt đất).")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> sideCol = sgRender.add(new ColorSetting.Builder()
        .name("màu-nền")
        .defaultValue(new SettingColor(64, 200, 64, 80))
        .build());

    private final Setting<SettingColor> lineCol = sgRender.add(new ColorSetting.Builder()
        .name("màu-viền")
        .defaultValue(new SettingColor(64, 255, 64, 200))
        .build());

    private final Setting<ShapeMode> shape = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("kiểu-hiển-thị")
        .defaultValue(ShapeMode.Both)
        .build());

    private Long parsedSeed;

    public SlimeChunk() {
        super(PhgMCAddon.PhgMC_Support, "Tim-Slime",
            "Hiện slime chunk dựa trên seed (offline, không packet).");
    }

    @Override
    public void onActivate() {
        parsedSeed = parseSeed();
        if (parsedSeed == null) {
            ChatUtils.warning("Tim-Slime: seed trống hoặc không phải số. Mở settings và paste seed vào.");
        }
    }

    private Long parseSeed() {
        try {
            return Long.parseLong(seedStr.get().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Vanilla slime-chunk RNG. Same formula since 1.0; verified against MC source. */
    public static boolean isSlimeChunk(long worldSeed, int chunkX, int chunkZ) {
        Random r = new Random(
            worldSeed
                + (long) (chunkX * chunkX * 0x4c1906)
                + (long) (chunkX * 0x5ac0db)
                + (long) (chunkZ * chunkZ) * 0x4307a7L
                + (long) (chunkZ * 0x5f24f) ^ 0x3ad8025fL);
        return r.nextInt(10) == 0;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (parsedSeed == null) {
            parsedSeed = parseSeed();
            if (parsedSeed == null) return;
        }

        int pcx = mc.player.getBlockX() >> 4;
        int pcz = mc.player.getBlockZ() >> 4;
        int r = radius.get();
        int yBot = minY.get();
        int yTop = maxY.get();
        Color s = sideCol.get();
        Color l = lineCol.get();
        ShapeMode mode = shape.get();
        boolean drawSides = vertical.get();
        boolean drawFloor = floorMarker.get();
        long seed = parsedSeed;

        for (int cx = pcx - r; cx <= pcx + r; cx++) {
            for (int cz = pcz - r; cz <= pcz + r; cz++) {
                if (!isSlimeChunk(seed, cx, cz)) continue;
                double x0 = cx * 16, z0 = cz * 16;
                double x1 = x0 + 16, z1 = z0 + 16;

                if (drawSides) {
                    event.renderer.box(x0, yBot, z0, x1, yTop, z1, s, l, mode, 0);
                }
                if (drawFloor) {
                    event.renderer.sideHorizontal(x0, yTop, z0, x1, z1, s, l, mode);
                }
            }
        }
    }
}
