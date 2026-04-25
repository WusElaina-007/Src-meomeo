package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class StudentAura extends Module {

    // ─── Aim Mode ────────────────────────────────────────────────────────────

    public enum AimMode {
        Wander, Head, Body, Feet
    }

    // ─── Setting Groups ───────────────────────────────────────────────────────

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgCrit     = settings.createGroup("Crit");
    private final SettingGroup sgRotation = settings.createGroup("Rotation");
    private final SettingGroup sgBypass   = settings.createGroup("Bypass");

    // ─── General ─────────────────────────────────────────────────────────────

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Attack range.")
        .defaultValue(3.1).min(1).sliderMax(6)
        .build());

    private final Setting<Double> wallRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("wall-range")
        .description("Through-wall attack range (0 = disabled).")
        .defaultValue(0).min(0).sliderMax(6)
        .build());

    private final Setting<Set<EntityType<?>>> targetEntities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("targets")
        .description("Entity types to attack.")
        .defaultValue(EntityType.PLAYER)
        .build());

    private final Setting<SortPriority> priority = sgGeneral.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("Target selection priority.")
        .defaultValue(SortPriority.ClosestAngle)
        .build());

    private final Setting<Double> fov = sgGeneral.add(new DoubleSetting.Builder()
        .name("fov")
        .description("Only target entities within this FOV angle (0 = unlimited).")
        .defaultValue(180).min(0).sliderMax(360)
        .build());

    private final Setting<Boolean> onlyWeapon = sgGeneral.add(new BoolSetting.Builder()
        .name("only-weapon")
        .description("Only attack with a sword or axe in hand.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-use")
        .description("Pause while using an item.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> ignoreCreative = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-creative")
        .description("Skip players in creative mode.")
        .defaultValue(true)
        .build());

    /**
     * NEW: name blacklist – comma-separated player names to never target.
     * Matching is case-insensitive.
     */
    private final Setting<List<String>> nameBlacklist = sgGeneral.add(new StringListSetting.Builder()
        .name("name-blacklist")
        .description("Player names (case-insensitive) to never attack.")
        .defaultValue(new ArrayList<>())
        .build());

    // ─── Crit ─────────────────────────────────────────────────────────────────

    private final Setting<Boolean> smartCrit = sgCrit.add(new BoolSetting.Builder()
        .name("smart-crit")
        .description("Only attack when a critical hit is possible.")
        .defaultValue(true)
        .build());

    private final Setting<Double> attackCooldown = sgCrit.add(new DoubleSetting.Builder()
        .name("attack-cooldown")
        .description("Minimum weapon cooldown progress before attacking (0.5–1.0).")
        .defaultValue(0.9).min(0.5).sliderMax(1.0)
        .build());

    private final Setting<Boolean> autoJump = sgCrit.add(new BoolSetting.Builder()
        .name("auto-jump")
        .description("Automatically jump to get critical hits.")
        .defaultValue(true)
        .build());

    // ─── Rotation ─────────────────────────────────────────────────────────────

    /**
     * NEW: AimMode controls which part of the target's hitbox is being aimed at.
     *  Wander – original organic wandering behaviour (most legit-looking).
     *  Head   – always aim at the top portion of the hitbox (headshot style).
     *  Body   – always aim at the centre of the hitbox.
     *  Feet   – always aim at the lower portion (great for crit baiting).
     */
    private final Setting<AimMode> aimMode = sgRotation.add(new EnumSetting.Builder<AimMode>()
        .name("aim-mode")
        .description("Which part of the target hitbox to aim at.")
        .defaultValue(AimMode.Wander)
        .build());

    private final Setting<Double> yawSpeed = sgRotation.add(new DoubleSetting.Builder()
        .name("yaw-speed")
        .description("Maximum yaw degrees rotated per tick.")
        .defaultValue(20).min(5).sliderMax(60)
        .build());

    private final Setting<Double> pitchSpeed = sgRotation.add(new DoubleSetting.Builder()
        .name("pitch-speed")
        .description("Maximum pitch degrees rotated per tick.")
        .defaultValue(15).min(3).sliderMax(40)
        .build());

    private final Setting<Boolean> predict = sgRotation.add(new BoolSetting.Builder()
        .name("predict")
        .description("Lead aim toward moving targets.")
        .defaultValue(true)
        .build());

    private final Setting<Double> predictScale = sgRotation.add(new DoubleSetting.Builder()
        .name("predict-scale")
        .description("How many ticks ahead to predict target movement.")
        .defaultValue(1.0).min(0.1).sliderMax(3.0)
        .build());

    /**
     * NEW: micro-jitter – adds a tiny randomised noise to rotations each tick,
     * making the pattern harder for AC heuristics that detect perfectly-smooth
     * aimbot curves.
     */
    private final Setting<Boolean> microJitter = sgRotation.add(new BoolSetting.Builder()
        .name("micro-jitter")
        .description("Add tiny random noise to rotations for anti-cheat bypass.")
        .defaultValue(true)
        .build());

    private final Setting<Double> jitterStrength = sgRotation.add(new DoubleSetting.Builder()
        .name("jitter-strength")
        .description("Maximum micro-jitter magnitude in degrees.")
        .defaultValue(0.4).min(0.05).sliderMax(2.0)
        .visible(() -> microJitter.get())
        .build());

    // ─── Bypass ───────────────────────────────────────────────────────────────

    private final Setting<Integer> hitCooldown = sgBypass.add(new IntSetting.Builder()
        .name("hit-cooldown")
        .description("Base ticks to wait between attacks (gets a small random offset).")
        .defaultValue(11).min(5).sliderMax(20)
        .build());

    private final Setting<Boolean> dropSprint = sgBypass.add(new BoolSetting.Builder()
        .name("drop-sprint")
        .description("Stop sprinting before hitting to enable crits.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> returnSprint = sgBypass.add(new BoolSetting.Builder()
        .name("return-sprint")
        .description("Re-enable sprinting after the attack.")
        .defaultValue(true)
        .build());

    /**
     * NEW: W-Tap – briefly stop forward movement input for 1 tick before hitting.
     * This disables the sprint momentum, then immediately restores it.
     * Effect: the server registers the hit as a non-sprinting attack → critical
     * hit is possible AND the target takes extra knockback from the sprint reset.
     * More aggressive than drop-sprint alone; combine both for maximum effect.
     */
    private final Setting<Boolean> wTap = sgBypass.add(new BoolSetting.Builder()
        .name("w-tap")
        .description("Release forward key briefly before each attack to boost knockback.")
        .defaultValue(false)
        .build());

    /**
     * NEW: Reach jitter – randomly vary the effective attack range within a small
     * window around the configured range value.  This breaks statistical AC
     * signatures that look for a constant reach value across many attacks.
     */
    private final Setting<Boolean> reachJitter = sgBypass.add(new BoolSetting.Builder()
        .name("reach-jitter")
        .description("Slightly randomise the effective reach per attack.")
        .defaultValue(true)
        .build());

    private final Setting<Double> reachJitterAmount = sgBypass.add(new DoubleSetting.Builder()
        .name("reach-jitter-amount")
        .description("Max ± deviation from configured range.")
        .defaultValue(0.12).min(0.01).sliderMax(0.5)
        .visible(() -> reachJitter.get())
        .build());

    private final Setting<Boolean> shieldBreaker = sgBypass.add(new BoolSetting.Builder()
        .name("shield-breaker")
        .description("Silently swap to axe when target is blocking with a shield.")
        .defaultValue(true)
        .build());

    // ─── State ────────────────────────────────────────────────────────────────

    private final List<Entity> targets = new ArrayList<>();
    private final Random random = new Random();

    // Current virtual rotation applied to Rotations.rotate()
    private float rotYaw, rotPitch;

    // Pitch acceleration: ramps up while off-target, resets when on-target
    private float pitchAccel = 1f;

    // Legit look: wandering point inside the target's hitbox
    private Vec3d rotPoint  = Vec3d.ZERO;
    private Vec3d rotMotion = Vec3d.ZERO;

    // Attack pacing
    private int hitTicksLeft = 0;

    // Track the previous target so we can reset rotation jitter on switch
    private Entity lastTarget = null;

    // NEW: combo counter – consecutive ticks we've attacked the same target
    private int comboCount = 0;

    // NEW: effective reach for the current attack cycle (updated per hit)
    private double effectiveRange;

    // NEW: w-tap state – true for 1 tick after tap to restore forward key
    private boolean wTapPending = false;

    // ─── Constructor ─────────────────────────────────────────────────────────

    public StudentAura() {
        super(AddonTemplate.Student_pvp, "Student-Aura", "Better Kill Aura.");
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        rotYaw       = mc.player.getYaw();
        rotPitch     = mc.player.getPitch();
        hitTicksLeft = 0;
        lastTarget   = null;
        comboCount   = 0;
        wTapPending  = false;
        effectiveRange = range.get();
        resetRotState(null);
    }

    @Override
    public void onDeactivate() {
        targets.clear();
        lastTarget  = null;
        comboCount  = 0;
        wTapPending = false;
        if (mc.player != null && wTap.get()) {
            mc.options.forwardKey.setPressed(false); // safety release
        }
        resetRotState(null);
    }

    // ─── Main Tick ───────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!mc.player.isAlive()) return;
        if (pauseOnUse.get() && mc.player.isUsingItem()) return;
        if (onlyWeapon.get() && !hasWeapon()) return;

        // Restore forward key if w-tap was applied last tick
        if (wTapPending) {
            mc.options.forwardKey.setPressed(true);
            wTapPending = false;
        }

        if (hitTicksLeft > 0) { hitTicksLeft--; return; }

        targets.clear();
        TargetUtils.getList(targets, this::entityCheck, priority.get(), 1);

        if (targets.isEmpty()) {
            lastTarget = null;
            comboCount = 0;
            return;
        }

        Entity target = targets.getFirst();

        // Reset wandering/combo state whenever the target changes
        if (target != lastTarget) {
            resetRotState(target);
            lastTarget = target;
            comboCount = 0;
        }

        // Update effective reach for this cycle
        effectiveRange = range.get();
        if (reachJitter.get()) {
            double jit = reachJitterAmount.get();
            effectiveRange += (random.nextDouble() * 2.0 - 1.0) * jit;
            effectiveRange = Math.max(1.0, effectiveRange);
        }

        calcRotations(target);

        if (!canCrit(target)) return;

        // W-Tap: release forward key 1 tick before hitting to kill sprint momentum
        if (wTap.get() && mc.player.isSprinting()) {
            mc.options.forwardKey.setPressed(false);
            wTapPending = true;
        }

        Rotations.rotate(rotYaw, rotPitch, 50, false, () -> doAttack(target));

        comboCount++;
        // Randomise delay slightly; combo cap at 3 ticks off to avoid being too bursty
        hitTicksLeft = hitCooldown.get() + random.nextInt(3) - Math.min(comboCount / 8, 3);
        hitTicksLeft = Math.max(hitTicksLeft, 5); // hard floor
    }

    // ─── Rotation ─────────────────────────────────────────────────────────────

    /**
     * Resets all wandering/acceleration state.
     * If {@code target} is provided, rotPoint is seeded to a plausible
     * center-area position inside its hitbox so the first look is sensible.
     */
    private void resetRotState(Entity target) {
        rotMotion  = Vec3d.ZERO;
        pitchAccel = 1f;

        if (target != null) {
            Box bb = target.getBoundingBox();
            rotPoint = new Vec3d(
                rand((float) (-bb.getLengthX() * 0.15f), (float) (bb.getLengthX() * 0.15f)),
                bb.getLengthY() * (0.45 + random.nextDouble() * 0.2),
                rand((float) (-bb.getLengthZ() * 0.15f), (float) (bb.getLengthZ() * 0.15f))
            );
        } else {
            rotPoint = Vec3d.ZERO;
        }
    }

    /**
     * Returns a world-space aim point based on the selected AimMode.
     *
     *  Wander – organic wandering point (original behaviour).
     *  Head   – upper 75–90 % of hitbox height, slight XZ drift.
     *  Body   – centre of hitbox (50–65 % height), slight XZ drift.
     *  Feet   – lower 15–30 % of hitbox height, slight XZ drift.
     *
     * All modes apply motion prediction when enabled.
     */
    private Vec3d getLegitLook(Entity target) {
        AimMode mode = aimMode.get();

        if (mode == AimMode.Wander) {
            return getWanderLook(target);
        }

        // Fixed-zone modes: pick a Y fraction based on mode, small XZ jitter
        Box bb = target.getBoundingBox();
        double halfX = bb.getLengthX() * 0.20;
        double halfZ = bb.getLengthZ() * 0.20;

        double yFrac;
        switch (mode) {
            case Head  -> yFrac = 0.75 + random.nextDouble() * 0.15; // 75–90 %
            case Feet  -> yFrac = 0.15 + random.nextDouble() * 0.15; // 15–30 %
            default    -> yFrac = 0.50 + random.nextDouble() * 0.15; // 50–65 % (Body)
        }

        Vec3d base = new Vec3d(target.getX(), target.getY(), target.getZ());
        if (predict.get()) {
            base = base.add(target.getVelocity().multiply(predictScale.get()));
        }

        // Small XZ jitter so it doesn't look pixel-perfect static
        return base.add(
            rand(-(float)halfX, (float)halfX),
            bb.getLengthY() * yFrac,
            rand(-(float)halfZ, (float)halfZ)
        );
    }

    /** Original wander-based aim (unchanged from v1). */
    private Vec3d getWanderLook(Entity target) {
        final float minMXZ = 0.003f, maxMXZ = 0.022f;
        final float minMY  = 0.002f, maxMY  = 0.018f;

        Box bb = target.getBoundingBox();

        double halfX = bb.getLengthX() * 0.40;
        double halfZ = bb.getLengthZ() * 0.40;
        double loY   = bb.getLengthY() * 0.15;
        double hiY   = bb.getLengthY() * 0.85;

        if (rotMotion.equals(Vec3d.ZERO))
            rotMotion = new Vec3d(rand(-0.03f, 0.03f), rand(minMY, maxMY), rand(-0.03f, 0.03f));

        rotPoint = rotPoint.add(rotMotion);

        if (rotPoint.x >  halfX) rotMotion = new Vec3d(-rand(minMXZ, maxMXZ), rotMotion.y,              rotMotion.z);
        if (rotPoint.x < -halfX) rotMotion = new Vec3d( rand(minMXZ, maxMXZ), rotMotion.y,              rotMotion.z);
        if (rotPoint.y >  hiY)   rotMotion = new Vec3d(rotMotion.x,          -rand(minMY,  maxMY),      rotMotion.z);
        if (rotPoint.y <  loY)   rotMotion = new Vec3d(rotMotion.x,           rand(minMY,  maxMY),      rotMotion.z);
        if (rotPoint.z >  halfZ) rotMotion = new Vec3d(rotMotion.x,           rotMotion.y, -rand(minMXZ, maxMXZ));
        if (rotPoint.z < -halfZ) rotMotion = new Vec3d(rotMotion.x,           rotMotion.y,  rand(minMXZ, maxMXZ));

        Vec3d base = new Vec3d(target.getX(), target.getY(), target.getZ());
        if (predict.get()) {
            base = base.add(target.getVelocity().multiply(predictScale.get()));
        }

        return base.add(rotPoint);
    }

    /**
     * Advances rotYaw/rotPitch toward the legit look point using a
     * per-tick step cap (human-like), GCD-quantised for anti-cheat bypass.
     * NEW: optional micro-jitter applied after GCD quantisation.
     */
    private void calcRotations(Entity target) {
        Vec3d look = getLegitLook(target);
        Vec3d eye  = mc.player.getEyePos();

        double dX     = look.x - eye.x;
        double dY     = look.y - eye.y;
        double dZ     = look.z - eye.z;
        double distXZ = Math.sqrt(dX * dX + dZ * dZ);

        float targetYaw   = (float)(Math.toDegrees(Math.atan2(dZ, dX)) - 90f);
        float targetPitch = (float)(-Math.toDegrees(Math.atan2(dY, distXZ)));

        float deltaYaw   = MathHelper.wrapDegrees(targetYaw - rotYaw);
        float deltaPitch = targetPitch - rotPitch;

        if (Math.abs(deltaPitch) < 1.5f) {
            pitchAccel = 1f;
        } else {
            pitchAccel = Math.min(pitchAccel * 1.4f, (float) pitchSpeed.get().doubleValue());
        }

        float maxYaw   = (float) yawSpeed.get().doubleValue() + rand(-1.5f, 1.5f);
        float maxPitch = pitchAccel + rand(-0.4f, 0.4f);

        float clampedYaw   = MathHelper.clamp(deltaYaw,   -maxYaw,   maxYaw);
        float clampedPitch = MathHelper.clamp(deltaPitch, -maxPitch, maxPitch);

        float rawYaw   = rotYaw   + clampedYaw;
        float rawPitch = MathHelper.clamp(rotPitch + clampedPitch, -90f, 90f);

        // GCD fix: quantise rotation to match mouse-sensitivity granularity
        double gcd = Math.pow(mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2, 3.0) * 1.2;
        rotYaw   = (float)(rawYaw   - (rawYaw   - rotYaw)   % gcd);
        rotPitch = (float)(rawPitch - (rawPitch - rotPitch) % gcd);

        // NEW: micro-jitter – applied after GCD so it doesn't negate the fix,
        // but still breaks the perfectly-smooth curve fingerprint
        if (microJitter.get()) {
            float j = (float) jitterStrength.get().doubleValue();
            rotYaw   += rand(-j, j);
            rotPitch  = MathHelper.clamp(rotPitch + rand(-j * 0.6f, j * 0.6f), -90f, 90f);
        }
    }

    // ─── Crit Logic ───────────────────────────────────────────────────────────

    private boolean canCrit(Entity target) {
        if (!smartCrit.get()) return true;

        if (mc.player.getAbilities().flying || mc.player.isGliding()) return true;

        if (mc.player.getAttackCooldownProgress(0.5f) < attackCooldown.get().floatValue()) return false;

        if (mc.player.isOnGround()) {
            if (autoJump.get()) mc.player.jump();
            return false;
        }

        double velY   = mc.player.getVelocity().y;
        double height = mc.player.getY() - Math.floor(mc.player.getY());
        return velY < 0 && height > 0.05 && height < 0.65;
    }

    // ─── Attack ───────────────────────────────────────────────────────────────

    private void doAttack(Entity target) {
        // Shield breaker: silent-swap to axe when target is actively blocking
        if (shieldBreaker.get()
                && target instanceof PlayerEntity pl
                && pl.isBlocking()) {
            int axeSlot = findAxeSlot();
            if (axeSlot != -1) {
                int prev = mc.player.getInventory().getSelectedSlot();
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(axeSlot));
                mc.interactionManager.attackEntity(mc.player, target);
                mc.player.swingHand(Hand.MAIN_HAND);
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(prev));
                return;
            }
        }

        // Drop-sprint → crit eligibility (server rejects crits while sprinting)
        boolean wasSprinting = mc.player.isSprinting();
        if (wasSprinting && dropSprint.get()) {
            mc.player.setSprinting(false);
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(
                mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        }

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);

        // Restore sprint immediately after swing
        if (wasSprinting && dropSprint.get() && returnSprint.get()) {
            mc.player.setSprinting(true);
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(
                mc.player, ClientCommandC2SPacket.Mode.START_SPRINTING));
        }
    }

    // ─── Entity Filter ────────────────────────────────────────────────────────

    private boolean entityCheck(Entity entity) {
        if (entity.equals(mc.player)) return false;
        if (entity instanceof ArmorStandEntity) return false;
        if (!(entity instanceof LivingEntity le)) return false;
        if (le.isDead() || !entity.isAlive()) return false;
        if (!targetEntities.get().contains(entity.getType())) return false;

        // NEW: name blacklist check (case-insensitive)
        if (entity instanceof PlayerEntity player) {
            String name = player.getName().getString().toLowerCase();
            for (String blocked : nameBlacklist.get()) {
                if (name.equals(blocked.toLowerCase())) return false;
            }
        }

        // FOV check: angle between player's facing and direction to entity center
        if (fov.get() < 360) {
            Vec3d toEntity = new Vec3d(entity.getX(), entity.getY(), entity.getZ())
                .add(0, entity.getHeight() / 2.0, 0)
                .subtract(mc.player.getEyePos())
                .normalize();
            Vec3d facing = Vec3d.fromPolar(mc.player.getPitch(), mc.player.getYaw());
            double angle = Math.toDegrees(Math.acos(MathHelper.clamp(toEntity.dotProduct(facing), -1.0, 1.0)));
            if (angle > fov.get() / 2.0) return false;
        }

        // NEW: use effectiveRange (with jitter) for range check
        double checkRange = (hitTicksLeft == 0) ? effectiveRange : range.get();
        Box hitbox = entity.getBoundingBox();
        boolean inRange = PlayerUtils.isWithin(
            MathHelper.clamp(mc.player.getX(), hitbox.minX, hitbox.maxX),
            MathHelper.clamp(mc.player.getY(), hitbox.minY, hitbox.maxY),
            MathHelper.clamp(mc.player.getZ(), hitbox.minZ, hitbox.maxZ),
            checkRange
        );
        boolean throughWall = wallRange.get() > 0 && PlayerUtils.isWithin(entity, wallRange.get());

        if (!inRange && !throughWall) return false;
        if (!PlayerUtils.canSeeEntity(entity) && !throughWall) return false;

        if (entity instanceof PlayerEntity player) {
            if (ignoreCreative.get() && player.isCreative()) return false;
            if (!Friends.get().shouldAttack(player)) return false;
        }

        return true;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private boolean hasWeapon() {
        var stack = mc.player.getMainHandStack();
        return stack.isIn(ItemTags.SWORDS) || stack.getItem() instanceof AxeItem;
    }

    private int findAxeSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof AxeItem) return i;
        }
        return -1;
    }

    private float rand(float min, float max) {
        return min + random.nextFloat() * (max - min);
    }

    /** NEW: includes combo counter in the HUD info string. */
    @Override
    public String getInfoString() {
        if (targets.isEmpty()) return null;
        String name = targets.getFirst().getName().getString();
        return comboCount > 1 ? name + " §7x" + comboCount : name;
    }
}
