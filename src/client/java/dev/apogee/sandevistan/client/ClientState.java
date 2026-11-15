package dev.apogee.sandevistan.client;

import dev.apogee.sandevistan.ApogeeSounds;
import dev.apogee.sandevistan.network.ApogeeNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Client mirror of the server state. It is only for display (HUD / effects): the server is the single authority.
 * Timers are counted down locally in unpaused client ticks and corrected by server packets.
 */
public final class ClientState {
    private ClientState() {
    }

    public static int activeLeft;
    public static int activeTotal;
    public static int cooldownLeft;
    public static int cooldownTotal;
    /** Ticks of the highlighted "ready" state after the READY packet. */
    public static int readyFlash;

    /** Other players that currently have the effect: entity id -> ticks until expiry (refreshed by the server). */
    public static final Map<Integer, Integer> remote = new HashMap<>();

    public static void reset() {
        activeLeft = 0;
        activeTotal = 0;
        cooldownLeft = 0;
        cooldownTotal = 0;
        readyFlash = 0;
        remote.clear();
    }

    public static void tick() {
        if (activeLeft > 0) {
            activeLeft--;
        }
        if (cooldownLeft > 0) {
            cooldownLeft--;
        }
        if (readyFlash > 0) {
            readyFlash--;
        }
        Iterator<Map.Entry<Integer, Integer>> it = remote.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> entry = it.next();
            int left = entry.getValue() - 1;
            if (left <= 0) {
                it.remove();
            } else {
                entry.setValue(left);
            }
        }
    }

    public static void onOwnerState(int kind, int active, int cooldown, int activeTot, int cooldownTot) {
        MinecraftClient mc = MinecraftClient.getInstance();
        activeLeft = active;
        cooldownLeft = cooldown;
        activeTotal = activeTot;
        cooldownTotal = cooldownTot;
        switch (kind) {
            case ApogeeNetworking.KIND_ACTIVATED -> {
                ApogeeFx.onActivate();
                if (mc.player != null) {
                    ApogeeWorldFx.addShock(mc.player.getPos(), false);
                }
            }
            case ApogeeNetworking.KIND_ENDED -> {
                ApogeeFx.onEnd();
                if (mc.player != null) {
                    ApogeeWorldFx.addShock(mc.player.getPos(), true);
                }
            }
            case ApogeeNetworking.KIND_READY -> {
                readyFlash = 30;
                mc.getSoundManager().play(PositionedSoundInstance.master(ApogeeSounds.READY, 1.0f, 0.7f));
            }
            default -> {
            }
        }
    }

    public static void onTrail(int kind, int entityId) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) {
            return;
        }
        Entity entity = mc.world.getEntityById(entityId);
        switch (kind) {
            case ApogeeNetworking.TRAIL_START -> {
                remote.put(entityId, 30);
                if (entity != null) {
                    ApogeeWorldFx.addShock(entity.getPos(), false);
                }
            }
            case ApogeeNetworking.TRAIL_END -> {
                remote.remove(entityId);
                if (entity != null) {
                    ApogeeWorldFx.addShock(entity.getPos(), true);
                }
            }
            default -> remote.put(entityId, 30);
        }
    }
}
