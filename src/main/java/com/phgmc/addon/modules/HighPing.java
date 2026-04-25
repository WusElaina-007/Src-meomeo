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

import java.util.ArrayList;
import java.util.List;

/**
 * HighPing ("Blink") – Meteor Client Addon Module
 *
 * Local player: moves and PvPs perfectly smooth.
 * Server/opponents: sees the player frozen, then violently
 *   teleporting to their real position in a burst of packets.
 *   Attack packets are forwarded immediately so damage always
 *   registers even while movement is being held.
 *
 * How it works:
 *   LAG PHASE  – movement packets are intercepted and queued.
 *                 Server thinks the player is standing still.
 *                 Attack, chat, inventory packets pass through
 *                 immediately so gameplay stays functional.
 *   FLUSH PHASE – all queued packets are sent in a single burst
 *                 within one network tick.  The server processes
 *                 them instantly → player appears to teleport.
 *                 Cycle then restarts.
 */
public class HighPing extends Module {

    // ─── Setting groups ───────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced");

    // ─── General ──────────────────────────────────────────────────────────────

    private final Setting<Boolean> loopMode = sgGeneral.add(new BoolSetting.Builder()
        .name("loop-mode")
        .description("Automatically alternate between lag and flush phases.")
        .defaultValue(true)
        .build());

    private final Setting<Double> loopLagSeconds = sgGeneral.add(new DoubleSetting.Builder()
        .name("loop-lag-seconds")
        .description("Seconds to hold movement packets (lag phase). Higher = longer freeze on server side.")
        .defaultValue(0.6).min(0.05).sliderMin(0.05).sliderMax(10.0)
        .visible(loopMode::get)
        .build());

    private final Setting<Double> loopNormalSeconds = sgGeneral.add(new DoubleSetting.Builder()
        .name("loop-normal-seconds")
        .description("Seconds to send normally after flush. Keep this short so the burst effect is clean.")
        .defaultValue(0.15).min(0.05).sliderMin(0.05).sliderMax(5.0)
        .visible(loopMode::get)
        .build());

    // ─── Advanced ─────────────────────────────────────────────────────────────

    /**
     * Key improvement over the original:
     * Attack + swing packets bypass the queue entirely so damage
     * always registers on the server, even during the lag phase.
     */
    private final Setting<Boolean> passAttacks = sgAdvanced.add(new BoolSetting.Builder()
        .name("pass-attacks")
        .description("Forward attack packets immediately. Damage registers even while lagging.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> passSprint = sgAdvanced.add(new BoolSetting.Builder()
        .name("pass-sprint-commands")
        .description("Forward sprint start/stop commands immediately to avoid kick on some servers.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> maxQueue = sgAdvanced.add(new IntSetting.Builder()
        .name("max-queue")
        .description("Maximum packets to queue before force-flushing (safety cap to avoid memory bloat).")
        .defaultValue(200).min(20).sliderMax(500)
        .build());

    // ─── State ────────────────────────────────────────────────────────────────

    /** Packets held during the lag phase, in order. */
    private final List<Packet<?>> queue = new ArrayList<>();

    private boolean lagging     = true;  // Start in lag phase
    private double  tickCounter = 0;

    // ─── Constructor ──────────────────────────────────────────────────────────

    public HighPing() {
        super(PhgMCAddon.PhgMC_PvP, "High-Ping", "Fake high ping lag / blink effect.");
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        queue.clear();
        tickCounter = 0;
        lagging     = true;
    }

    @Override
    public void onDeactivate() {
        // Always flush on disable so the player doesn't rubber-band weirdly
        flushAll();
    }

    // ─── Tick ─────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // Safety: force-flush if queue grows too large
        if (queue.size() >= maxQueue.get()) {
            startNormalPhase();
            return;
        }

        if (!loopMode.get()) return;

        tickCounter++;

        double lagTicks    = loopLagSeconds.get()    * 20.0;
        double normalTicks = loopNormalSeconds.get() * 20.0;

        if (lagging) {
            if (tickCounter >= lagTicks) startNormalPhase();
        } else {
            if (tickCounter >= normalTicks) startLagPhase();
        }
    }

    // ─── Packet intercept ────────────────────────────────────────────────────

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!lagging) return;

        Packet<?> pkt = event.packet;

        // ── Always let through: attacks (damage must register) ────────────────
        if (passAttacks.get() && isAttackPacket(pkt)) return;

        // ── Always let through: chat, abilities, inventory, etc. ─────────────
        if (isAlwaysPassPacket(pkt)) return;

        // ── Optionally let through sprint/sneak commands ──────────────────────
        if (passSprint.get() && isSprintCommand(pkt)) return;

        // ── Only queue movement packets – everything else passes freely ───────
        if (!isMovementPacket(pkt)) return;

        event.cancel();
        queue.add(pkt);
    }

    // ─── Phase transitions ───────────────────────────────────────────────────

    private void startNormalPhase() {
        tickCounter = 0;
        lagging     = false;
        // Dump all queued packets in one burst → the "teleport" / lag spike effect
        flushAll();
    }

    private void startLagPhase() {
        tickCounter = 0;
        lagging     = true;
    }

    /**
     * Sends every queued packet to the server in a single network tick.
     * This is the key to the effect: the server receives all position
     * updates at once and processes them sequentially, making the client
     * appear to teleport from its old position to its real one.
     */
    private void flushAll() {
        if (queue.isEmpty()) return;
        if (mc.getNetworkHandler() == null) { queue.clear(); return; }

        for (Packet<?> pkt : queue) {
            mc.getNetworkHandler().sendPacket(pkt);
        }
        queue.clear();
    }

    // ─── Packet classification ───────────────────────────────────────────────

    /**
     * Movement packets: hold these to create the lag illusion.
     * PlayerMoveC2SPacket covers all four subtypes:
     *   Full, LookAndOnGround, PositionAndOnGround, OnGroundOnly
     */
    private boolean isMovementPacket(Packet<?> pkt) {
        return pkt instanceof PlayerMoveC2SPacket;
    }

    /**
     * Attack packets: always pass so damage registers in real-time.
     *   PlayerInteractEntityC2SPacket – actual hit
     *   HandSwingC2SPacket            – swing animation (also triggers some server checks)
     */
    private boolean isAttackPacket(Packet<?> pkt) {
        return pkt instanceof PlayerInteractEntityC2SPacket
            || pkt instanceof HandSwingC2SPacket;
    }

    /**
     * Sprint / sneak state commands.  Some anti-cheats correlate
     * sprint state with position changes; passing these can help.
     */
    private boolean isSprintCommand(Packet<?> pkt) {
        if (pkt instanceof ClientCommandC2SPacket cmd) {
            var mode = cmd.getMode();
            return mode == ClientCommandC2SPacket.Mode.START_SPRINTING
                || mode == ClientCommandC2SPacket.Mode.STOP_SPRINTING;
        }
        return false;
    }

    /**
     * Packets that must always pass to keep the session alive and
     * functional (chat, inventory, slot changes, abilities, etc.)
     * Holding these would cause visible side effects or server kicks.
     */
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

    // ─── HUD ─────────────────────────────────────────────────────────────────

    @Override
    public String getInfoString() {
        if (!lagging) return "§aFlush";
        return "§cLag §7(" + queue.size() + ")";
    }
}
