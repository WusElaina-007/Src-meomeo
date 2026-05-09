package com.phgmc.addon.modules;

import com.phgmc.addon.PhgMCAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.network.packet.c2s.play.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * High-Ping (safer blink). Holds movement packets to fake ping spike, then
 * flushes them. Designed to reduce flag rate on heuristic anti-cheats
 * (Vulcan, Matrix, Spartan, NCP) by:
 *   - Capping accumulated distance per flush (no large warps).
 *   - Randomising lag/normal duration (no fixed cadence).
 *   - Sending periodic on-ground packets so server doesn't time out.
 *   - Streaming the flush over several ticks instead of one burst.
 *
 * Movement-prediction anti-cheats (Grim, Polar) still detect this by design.
 */
public class HighPing extends Module {

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced");

    private final Setting<Boolean> loopMode = sgGeneral.add(new BoolSetting.Builder()
        .name("loop-mode")
        .description("Tự động chuyển giữa pha lag và pha flush.")
        .defaultValue(true)
        .build());

    private final Setting<Double> loopLagSeconds = sgGeneral.add(new DoubleSetting.Builder()
        .name("loop-lag-seconds")
        .description("Thời gian giữ packet (pha lag). Càng dài càng dễ bị AC bắt.")
        .defaultValue(0.2).min(0.05).sliderMin(0.05).sliderMax(2.0)
        .visible(loopMode::get)
        .build());

    private final Setting<Double> loopNormalSeconds = sgGeneral.add(new DoubleSetting.Builder()
        .name("loop-normal-seconds")
        .description("Thời gian gửi bình thường giữa các lần lag. Càng dài thì heuristic AC càng khó flag.")
        .defaultValue(0.6).min(0.05).sliderMin(0.05).sliderMax(5.0)
        .visible(loopMode::get)
        .build());

    private final Setting<Double> distanceCap = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance-cap")
        .description("Auto-flush khi tích lũy quãng đường ngang vượt ngưỡng (block). Quan trọng nhất để né AC heuristic — flush trông giống lag spike thật chứ không phải warp xa.")
        .defaultValue(6.0).min(1.0).sliderMin(1.0).sliderMax(40.0)
        .build());

    private final Setting<Boolean> randomizeTiming = sgAdvanced.add(new BoolSetting.Builder()
        .name("randomize-timing")
        .description("Random ±30% lag/normal duration mỗi cycle để né pattern detect.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> keepAlive = sgAdvanced.add(new BoolSetting.Builder()
        .name("keep-alive-look")
        .description("Trong lúc lag, định kỳ gửi 1 OnGround packet tại vị trí cũ → server thấy player vẫn alive, không kick timeout.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> keepAliveInterval = sgAdvanced.add(new IntSetting.Builder()
        .name("keep-alive-interval")
        .description("Gửi 1 keep-alive packet mỗi N tick.")
        .defaultValue(4).min(1).sliderMin(1).sliderMax(20)
        .visible(keepAlive::get)
        .build());

    private final Setting<Integer> flushPerTick = sgAdvanced.add(new IntSetting.Builder()
        .name("flush-per-tick")
        .description("Số packet được flush mỗi tick. Lớn = flush nhanh + giật nhiều. Nhỏ = trơn hơn nhưng tốn nhiều tick.")
        .defaultValue(3).min(1).sliderMin(1).sliderMax(20)
        .build());

    private final Setting<Boolean> passAttacks = sgAdvanced.add(new BoolSetting.Builder()
        .name("pass-attacks")
        .description("Cho attack/swing đi thẳng → damage register kể cả khi đang lag. Tự động flush queue trước attack để né AC reach check.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> flushBeforeAttack = sgAdvanced.add(new BoolSetting.Builder()
        .name("flush-before-attack")
        .description("Flush hết queue ngay trước attack. Giảm risk AC reject hit do server thấy bạn ở vị trí cũ.")
        .defaultValue(true)
        .visible(passAttacks::get)
        .build());

    private final Setting<Boolean> passSprint = sgAdvanced.add(new BoolSetting.Builder()
        .name("pass-sprint-commands")
        .description("Cho sprint start/stop đi thẳng để né AC sprint-state check.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> maxQueue = sgAdvanced.add(new IntSetting.Builder()
        .name("max-queue")
        .description("Tối đa packet trong queue trước khi force flush (an toàn bộ nhớ).")
        .defaultValue(100).min(20).sliderMax(500)
        .build());

    // ─── State ────────────────────────────────────────────────────────────────

    private final Deque<Packet<?>> queue = new ArrayDeque<>();
    private final Random random = new Random();

    private enum Phase { LAG, FLUSH, NORMAL }
    private Phase phase = Phase.LAG;

    private int phaseTicks;
    private int phaseDurationTicks;
    private int keepAliveCounter;

    /** Last position seen in a packet — used to compute accumulated distance. */
    private double lastX = Double.NaN, lastY = Double.NaN, lastZ = Double.NaN;
    private double accumulatedDistance = 0.0;

    /** Last on-ground state seen — used for keep-alive packets. */
    private boolean lastOnGround = true;

    public HighPing() {
        super(PhgMCAddon.PhgMC_PvP, "High-Ping", "Fake high ping / blink, có giới hạn distance để giảm khả năng bị AC bắt.");
    }

    @Override
    public void onActivate() {
        queue.clear();
        accumulatedDistance = 0.0;
        keepAliveCounter = 0;
        lastX = lastY = lastZ = Double.NaN;
        if (mc.player != null) {
            lastX = mc.player.getX();
            lastY = mc.player.getY();
            lastZ = mc.player.getZ();
            lastOnGround = mc.player.isOnGround();
        }
        startPhase(Phase.LAG);
    }

    @Override
    public void onDeactivate() {
        flushAll();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        if (queue.size() >= maxQueue.get()) {
            startPhase(Phase.FLUSH);
        }

        if (phase == Phase.FLUSH) {
            int n = flushPerTick.get();
            for (int i = 0; i < n && !queue.isEmpty(); i++) {
                mc.getNetworkHandler().sendPacket(queue.pollFirst());
            }
            if (queue.isEmpty()) startPhase(loopMode.get() ? Phase.NORMAL : Phase.LAG);
            return;
        }

        if (!loopMode.get()) return;

        phaseTicks++;

        if (phase == Phase.LAG) {
            if (keepAlive.get() && ++keepAliveCounter >= keepAliveInterval.get()) {
                keepAliveCounter = 0;
                mc.getNetworkHandler().sendPacket(
                    new PlayerMoveC2SPacket.OnGroundOnly(lastOnGround, false));
            }
            if (phaseTicks >= phaseDurationTicks) startPhase(Phase.FLUSH);
        } else if (phase == Phase.NORMAL) {
            if (phaseTicks >= phaseDurationTicks) startPhase(Phase.LAG);
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        Packet<?> pkt = event.packet;

        if (passAttacks.get() && isAttackPacket(pkt)) {
            if (flushBeforeAttack.get() && !queue.isEmpty()) flushAll();
            return;
        }

        if (phase != Phase.LAG) return;

        if (isAlwaysPassPacket(pkt)) return;
        if (passSprint.get() && isSprintCommand(pkt)) return;
        if (!isMovementPacket(pkt)) return;

        // Track position + accumulated horizontal distance for distance cap.
        if (pkt instanceof PlayerMoveC2SPacket move) {
            double nx = move.getX(Double.isNaN(lastX) ? 0 : lastX);
            double ny = move.getY(Double.isNaN(lastY) ? 0 : lastY);
            double nz = move.getZ(Double.isNaN(lastZ) ? 0 : lastZ);
            if (!Double.isNaN(lastX) && move.changesPosition()) {
                double dx = nx - lastX, dz = nz - lastZ;
                accumulatedDistance += Math.sqrt(dx * dx + dz * dz);
            }
            lastX = nx; lastY = ny; lastZ = nz;
            lastOnGround = move.isOnGround();
        }

        event.cancel();
        queue.addLast(pkt);

        if (accumulatedDistance >= distanceCap.get()) {
            startPhase(Phase.FLUSH);
        }
    }

    private void startPhase(Phase next) {
        phase = next;
        phaseTicks = 0;
        keepAliveCounter = 0;

        if (next == Phase.LAG) {
            accumulatedDistance = 0.0;
            phaseDurationTicks = jitter(loopLagSeconds.get() * 20.0);
        } else if (next == Phase.NORMAL) {
            phaseDurationTicks = jitter(loopNormalSeconds.get() * 20.0);
        }
    }

    private int jitter(double baseTicks) {
        if (!randomizeTiming.get()) return Math.max(1, (int) baseTicks);
        double factor = 0.7 + random.nextDouble() * 0.6; // ±30%
        return Math.max(1, (int) (baseTicks * factor));
    }

    private void flushAll() {
        if (queue.isEmpty()) return;
        if (mc.getNetworkHandler() == null) { queue.clear(); return; }
        for (Packet<?> pkt : queue) mc.getNetworkHandler().sendPacket(pkt);
        queue.clear();
        accumulatedDistance = 0.0;
    }

    private boolean isMovementPacket(Packet<?> pkt) {
        return pkt instanceof PlayerMoveC2SPacket;
    }

    private boolean isAttackPacket(Packet<?> pkt) {
        return pkt instanceof PlayerInteractEntityC2SPacket
            || pkt instanceof HandSwingC2SPacket;
    }

    private boolean isSprintCommand(Packet<?> pkt) {
        if (pkt instanceof ClientCommandC2SPacket cmd) {
            var mode = cmd.getMode();
            return mode == ClientCommandC2SPacket.Mode.START_SPRINTING
                || mode == ClientCommandC2SPacket.Mode.STOP_SPRINTING;
        }
        return false;
    }

    private boolean isAlwaysPassPacket(Packet<?> pkt) {
        return pkt instanceof ChatMessageC2SPacket
            || pkt instanceof CommandExecutionC2SPacket
            || pkt instanceof ClickSlotC2SPacket
            || pkt instanceof CloseHandledScreenC2SPacket
            || pkt instanceof UpdateSelectedSlotC2SPacket
            || pkt instanceof PlayerActionC2SPacket
            || pkt instanceof PlayerInteractBlockC2SPacket
            || pkt instanceof PlayerInteractItemC2SPacket
            || pkt instanceof KeepAliveC2SPacket;
    }

    @Override
    public String getInfoString() {
        String dist = String.format("%.1f", accumulatedDistance);
        return switch (phase) {
            case LAG    -> "§cLag §7(" + queue.size() + " §8| §7" + dist + "m)";
            case FLUSH  -> "§eFlush §7(" + queue.size() + ")";
            case NORMAL -> "§aNormal";
        };
    }
}
