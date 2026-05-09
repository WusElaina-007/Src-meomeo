package com.phgmc.addon.modules;

import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;

/**
 * Theo-Doi-Goc-Nhin — first-person spectate of another player by name.
 * Pure client-side: only swaps {@link net.minecraft.client.MinecraftClient#setCameraEntity}
 * so the local body stays in place but the rendered POV comes from target.
 * No packets sent → 100% undetectable by anti-cheat.
 *
 * Limits: target must be currently in render distance (server only sends data
 * for visible entities). Lose them and you fall back to your own view.
 */
public class PlayerCam extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> targetName = sgGeneral.add(new StringSetting.Builder()
        .name("ten-player")
        .description("Tên player muốn theo dõi POV. Phân biệt hoa thường.")
        .defaultValue("")
        .onChanged(v -> rebindTarget())
        .build());

    private final Setting<Boolean> onlyWhenVisible = sgGeneral.add(new BoolSetting.Builder()
        .name("chi-khi-trong-render-distance")
        .description("Chỉ camera khi player target có entity trong client. Nếu họ đi xa, fallback về POV của bạn.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> announceLoss = sgGeneral.add(new BoolSetting.Builder()
        .name("bao-mat-target")
        .description("Báo chat khi mất tín hiệu target (out of render distance).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> announceFound = sgGeneral.add(new BoolSetting.Builder()
        .name("bao-tim-target")
        .description("Báo chat khi tìm thấy target và bắt đầu camera.")
        .defaultValue(true)
        .build());

    private AbstractClientPlayerEntity target;
    private boolean cameraSet;

    public PlayerCam() {
        super(PhgMCAddon.PhgMC_Support, "Theo-Doi-Goc-Nhin",
            "Camera góc nhìn thứ nhất của player khác (offline render, AC không bắt được).");
    }

    @Override
    public void onActivate() {
        target = null;
        cameraSet = false;
        rebindTarget();
    }

    @Override
    public void onDeactivate() {
        restoreCamera();
        target = null;
    }

    private void rebindTarget() {
        target = null;
        cameraSet = false;
        restoreCamera();
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (mc.world == null || mc.player == null) return;
        String name = targetName.get().trim();
        if (name.isEmpty()) {
            if (cameraSet) restoreCamera();
            return;
        }

        // Re-acquire target if lost
        if (target == null || target.isRemoved() || !target.isAlive()
            || !target.getName().getString().equals(name)) {
            AbstractClientPlayerEntity prev = target;
            target = findPlayer(name);
            if (target != null && prev != target && announceFound.get()) {
                ChatUtils.info("Theo-Doi-Goc-Nhin: đang spectate §a%s§r (%.0fm)",
                    target.getName().getString(),
                    mc.player.distanceTo(target));
            }
            if (target == null && cameraSet) {
                restoreCamera();
                if (announceLoss.get()) ChatUtils.warning(
                    "Theo-Doi-Goc-Nhin: mất tín hiệu §c%s§r (out of render distance).", name);
                return;
            }
        }

        if (target == null) return;

        if (onlyWhenVisible.get() && !mc.world.getEntitiesByClass(
                AbstractClientPlayerEntity.class,
                target.getBoundingBox().expand(0.001),
                p -> p == target).contains(target)) {
            // unreachable — if we have target ref it's still valid; this is kept for safety
        }

        if (mc.getCameraEntity() != target) {
            mc.setCameraEntity(target);
            cameraSet = true;
        }
    }

    private void restoreCamera() {
        if (mc.player != null) mc.setCameraEntity(mc.player);
        cameraSet = false;
    }

    private AbstractClientPlayerEntity findPlayer(String name) {
        if (mc.world == null) return null;
        for (Entity ent : mc.world.getEntities()) {
            if (ent instanceof AbstractClientPlayerEntity p
                && p != mc.player
                && p.getName().getString().equals(name)) {
                return p;
            }
        }
        return null;
    }

    @Override
    public String getInfoString() {
        if (target != null && !target.isRemoved()) {
            float dist = mc.player == null ? 0 : mc.player.distanceTo(target);
            return "§a" + target.getName().getString() + "§7 (" + (int) dist + "m)";
        }
        if (!targetName.get().trim().isEmpty()) return "§echờ §7" + targetName.get();
        return "§7chưa nhập tên";
    }
}
