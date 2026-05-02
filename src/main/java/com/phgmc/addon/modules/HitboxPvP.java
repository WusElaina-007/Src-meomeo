package com.phgmc.addon.modules;

import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Hitbox-PvP — expands entity targeting margin client-side so the vanilla
 * crosshair raycast finds the entity from a slightly larger area. The
 * server still validates against the real bbox + reach distance, so we
 * cap reach (3.0 by default) to avoid AC reach checks (Vulcan / Matrix /
 * Spartan / NCP / Verus / AAC).
 *
 * Cannot bypass movement-prediction AC (Grim / Polar / IntaveAddon) — they
 * simulate raycast with vanilla bbox server-side.
 */
public class HitboxPvP extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter  = settings.createGroup("Bộ lọc target");
    private final SettingGroup sgSafety  = settings.createGroup("An toàn AC");

    private final Setting<Double> expansion = sgGeneral.add(new DoubleSetting.Builder()
        .name("expansion")
        .description("Mở rộng bbox bao nhiêu block về mỗi phía. Càng nhỏ càng an toàn AC.")
        .defaultValue(0.10).min(0.01).sliderMin(0.01).sliderMax(0.50)
        .build());

    private final Setting<Double> playerExpansion = sgGeneral.add(new DoubleSetting.Builder()
        .name("expansion-player")
        .description("Riêng cho player target. -1 = dùng giá trị 'expansion'. Để giá trị nhỏ hơn cho an toàn AC.")
        .defaultValue(-1.0).min(-1.0).sliderMin(-1.0).sliderMax(0.50)
        .build());

    private final Setting<Boolean> targetPlayers = sgFilter.add(new BoolSetting.Builder()
        .name("target-player")
        .defaultValue(true).build());

    private final Setting<Boolean> targetHostile = sgFilter.add(new BoolSetting.Builder()
        .name("target-hostile-mob")
        .defaultValue(true).build());

    private final Setting<Boolean> targetPassive = sgFilter.add(new BoolSetting.Builder()
        .name("target-passive-mob")
        .defaultValue(false).build());

    private final Setting<Boolean> onlyWhileAttacking = sgFilter.add(new BoolSetting.Builder()
        .name("chỉ-khi-đang-attack")
        .description("Chỉ kích hoạt expansion khi giữ chuột trái — tránh AC pattern detect khi đứng yên.")
        .defaultValue(false).build());

    private final Setting<Boolean> capReach = sgSafety.add(new BoolSetting.Builder()
        .name("cap-reach")
        .description("Reset crosshair target nếu vượt reach-cap. Cần thiết với Vulcan/Matrix.")
        .defaultValue(true).build());

    private final Setting<Double> reachCap = sgSafety.add(new DoubleSetting.Builder()
        .name("reach-cap")
        .description("Reach tối đa được phép (block). Vanilla = 3.0, AC tolerance thường 3.0-3.15.")
        .defaultValue(3.0).min(2.5).sliderMin(2.5).sliderMax(4.5)
        .visible(capReach::get)
        .build());

    private final Setting<Boolean> debug = sgSafety.add(new BoolSetting.Builder()
        .name("debug-chat")
        .description("In chat khi target bị reset do quá reach (debug).")
        .defaultValue(false).build());

    public HitboxPvP() {
        super(PhgMCAddon.PhgMC_PvP, "Hitbox-PvP",
            "Mở rộng hitbox client-side cho attack picking. Cap reach để né AC.");
    }

    /** Called from {@link com.phgmc.addon.mixin.EntityMixin}. Returns extra margin to add. */
    public float bonusFor(Entity e) {
        if (!isAllowedTarget(e)) return 0f;
        if (onlyWhileAttacking.get() && !isAttacking()) return 0f;
        double exp;
        if (e instanceof PlayerEntity && playerExpansion.get() >= 0) exp = playerExpansion.get();
        else exp = expansion.get();
        return (float) exp;
    }

    private boolean isAllowedTarget(Entity e) {
        if (!(e instanceof LivingEntity le)) return false;
        if (mc.player != null && le == mc.player) return false;
        if (le.isRemoved() || !le.isAlive()) return false;
        if (le instanceof PlayerEntity)        return targetPlayers.get();
        if (le instanceof HostileEntity)       return targetHostile.get();
        if (le instanceof PassiveEntity)       return targetPassive.get();
        // Fallback for things not in the trees (squid, slime, etc.) → treat as hostile/passive heuristic
        return targetPassive.get() || targetHostile.get();
    }

    private boolean isAttacking() {
        if (mc.options == null) return false;
        try {
            return mc.options.attackKey.isPressed();
        } catch (Throwable t) {
            return false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (!capReach.get()) return;
        if (mc.player == null || mc.crosshairTarget == null) return;
        if (mc.crosshairTarget.getType() != HitResult.Type.ENTITY) return;
        EntityHitResult hr = (EntityHitResult) mc.crosshairTarget;
        if (!isAllowedTarget(hr.getEntity())) return;

        double dist = mc.player.getEyePos().distanceTo(hr.getPos());
        if (dist > reachCap.get()) {
            // Reset to miss so vanilla doAttack won't fire an attack-entity packet
            mc.crosshairTarget = null;
            mc.targetedEntity = null;
            if (debug.get()) {
                ChatUtils.warning("Hitbox-PvP: bỏ target (reach %.2f > cap %.2f)", dist, reachCap.get());
            }
        }
    }

    @Override
    public String getInfoString() {
        return String.format("§b+%.2f§7", expansion.get());
    }
}
