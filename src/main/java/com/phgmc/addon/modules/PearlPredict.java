package com.phgmc.addon.modules;

import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

/**
 * PearlPredict
 *
 * Khi cầm ender pearl, vẽ arc trajectory và hiện landing point.
 * Simulation physics đúng với server: gravity 0.03, drag 0.99, speed 1.5.
 * Collision detection dùng world raycast từng bước.
 */
public class PearlPredict extends Module {

    private Vec3d playerPos() {
        return new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }


    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgRender   = settings.createGroup("Render");

    // ─── General ─────────────────────────────────────────────────────────────

    private final Setting<Boolean> onlyWhenHolding = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-holding")
        .description("Chỉ hiện khi đang cầm ender pearl trên tay.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> maxTicks = sgGeneral.add(new IntSetting.Builder()
        .name("max-ticks")
        .description("Số tick tối đa simulate trajectory (20 tick = 1 giây).")
        .defaultValue(240).min(40).sliderMax(400)
        .build());

    private final Setting<Boolean> showLandingBox = sgGeneral.add(new BoolSetting.Builder()
        .name("show-landing-box")
        .description("Hiện box tại điểm pearl sẽ chạm đất.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> showDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("show-distance")
        .description("Hiện khoảng cách đến landing point trong HUD info.")
        .defaultValue(true)
        .build());

    // ─── Render ──────────────────────────────────────────────────────────────

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Màu đường arc.")
        .defaultValue(new SettingColor(180, 80, 255, 200))
        .build());

    private final Setting<SettingColor> landingColor = sgRender.add(new ColorSetting.Builder()
        .name("landing-color")
        .description("Màu box landing point.")
        .defaultValue(new SettingColor(180, 80, 255, 80))
        .build());

    private final Setting<SettingColor> landingLineColor = sgRender.add(new ColorSetting.Builder()
        .name("landing-line-color")
        .description("Màu outline box landing point.")
        .defaultValue(new SettingColor(220, 120, 255, 255))
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Cách render landing box.")
        .defaultValue(ShapeMode.Both)
        .build());

    // ─── State ────────────────────────────────────────────────────────────────

    // Danh sách điểm trên trajectory (tính ở onTick, render ở onRender)
    private final List<Vec3d> trajectoryPoints = new ArrayList<>();

    // Landing position (null nếu chưa tính được)
    private Vec3d landingPos = null;

    // Khoảng cách thực tế đến landing
    private double landingDistance = 0;

    // ─── Constructor ─────────────────────────────────────────────────────────

    public PearlPredict() {
        super(PhgMCAddon.PhgMC_PvP, "Pearl-Predict", "Hiện trajectory và landing point của ender pearl.");
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onDeactivate() {
        trajectoryPoints.clear();
        landingPos = null;
    }

    // ─── Tick: tính trajectory ────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        trajectoryPoints.clear();
        landingPos = null;

        if (mc.player == null || mc.world == null) return;

        // Chỉ hiện khi cầm pearl (nếu setting bật)
        if (onlyWhenHolding.get()) {
            boolean holdingPearl =
                mc.player.getMainHandStack().isOf(Items.ENDER_PEARL) ||
                mc.player.getOffHandStack().isOf(Items.ENDER_PEARL);
            if (!holdingPearl) return;
        }

        simulateTrajectory();
    }

    /**
     * Simulate pearl trajectory theo đúng physics Minecraft:
     *
     *   - Speed khi throw: 1.5 (EnderPearlItem hardcoded)
     *   - Gravity per tick: 0.03 (ThrownItemEntity)
     *   - Drag per tick: velocity *= 0.99 (ThrownItemEntity)
     *   - Spawn position: eye pos dịch xuống 0.1 (xấp xỉ spawn thật)
     *
     * Dừng khi:
     *   - Hit solid block (raycast từ pos trước đến pos tiếp theo)
     *   - Hết maxTicks
     */
    private void simulateTrajectory() {
        float pitch = mc.player.getPitch();
        float yaw   = mc.player.getYaw();

        // Direction unit vector (vanilla setVelocity computation)
        double dx = -MathHelper.sin(yaw * MathHelper.RADIANS_PER_DEGREE)
                  *  MathHelper.cos(pitch * MathHelper.RADIANS_PER_DEGREE);
        double dy = -MathHelper.sin(pitch * MathHelper.RADIANS_PER_DEGREE);
        double dz =  MathHelper.cos(yaw * MathHelper.RADIANS_PER_DEGREE)
                  *  MathHelper.cos(pitch * MathHelper.RADIANS_PER_DEGREE);
        double dlen = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dlen == 0) return;
        dx /= dlen; dy /= dlen; dz /= dlen;

        double vx = dx * 1.5;
        double vy = dy * 1.5;
        double vz = dz * 1.5;

        // ProjectileEntity.setVelocity adds the user's velocity (excl. y when on-ground)
        Vec3d pv = mc.player.getVelocity();
        vx += pv.x;
        vz += pv.z;
        if (!mc.player.isOnGround()) vy += pv.y;

        // Spawn position: vanilla spawns at (player.x, player.eyeY - 0.1, player.z)
        Vec3d pos = new Vec3d(mc.player.getX(),
                              mc.player.getEyeY() - 0.10000000149011612D,
                              mc.player.getZ());

        trajectoryPoints.add(pos);

        int limit = maxTicks.get();
        int worldBottom = mc.world.getBottomY();
        int worldTop = worldBottom + mc.world.getHeight();

        for (int t = 0; t < limit; t++) {
            Vec3d nextPos = pos.add(vx, vy, vz);

            BlockHitResult hit = mc.world.raycast(new RaycastContext(
                pos, nextPos,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player
            ));

            if (hit.getType() == HitResult.Type.BLOCK) {
                Vec3d hitPos = hit.getPos();
                trajectoryPoints.add(hitPos);
                landingPos = hitPos;
                landingDistance = playerPos().distanceTo(landingPos);
                break;
            }

            trajectoryPoints.add(nextPos);
            pos = nextPos;

            // Vanilla order: move → drag → gravity (per tick after move)
            // Drag is 0.8 in water/lava, 0.99 in air. We approximate by sampling block at pos.
            double drag = 0.99;
            try {
                BlockState bs = mc.world.getBlockState(BlockPos.ofFloored(pos));
                if (!bs.getFluidState().isEmpty()) drag = 0.8;
            } catch (Throwable ignored) {}

            vx *= drag;
            vy *= drag;
            vz *= drag;
            vy -= 0.03; // pearl gravity

            if (pos.y < worldBottom - 64 || pos.y > worldTop + 64) {
                landingPos = pos;
                landingDistance = playerPos().distanceTo(landingPos);
                break;
            }
        }
    }

    // ─── Render ───────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (trajectoryPoints.size() < 2) return;

        SettingColor lc = lineColor.get();

        // Vẽ arc: line segment từng điểm liên tiếp
        // Fade alpha ra dần cuối trajectory để trông tự nhiên hơn
        int total = trajectoryPoints.size();
        for (int i = 0; i < total - 1; i++) {
            Vec3d a = trajectoryPoints.get(i);
            Vec3d b = trajectoryPoints.get(i + 1);

            // Alpha fade: đầu full, cuối 30%
            float progress = (float) i / (total - 1);
            int alpha = (int)(lc.a * (1.0f - progress * 0.7f));
            alpha = Math.max(alpha, 20);

            event.renderer.line(
                a.x, a.y, a.z,
                b.x, b.y, b.z,
                new SettingColor(lc.r, lc.g, lc.b, alpha)
            );
        }

        // Vẽ landing box
        if (showLandingBox.get() && landingPos != null) {
            // Box nhỏ 0.5x0.5x0.5 tại landing point
            double hs = 0.25; // half size
            event.renderer.box(
                landingPos.x - hs, landingPos.y,       landingPos.z - hs,
                landingPos.x + hs, landingPos.y + 0.5, landingPos.z + hs,
                landingColor.get(), landingLineColor.get(),
                shapeMode.get(), 0
            );
        }
    }

    // ─── HUD info ─────────────────────────────────────────────────────────────

    @Override
    public String getInfoString() {
        if (!showDistance.get() || landingPos == null) return null;
        return String.format("%.1fm", landingDistance);
    }
}
