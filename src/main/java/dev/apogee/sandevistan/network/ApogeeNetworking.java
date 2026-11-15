package dev.apogee.sandevistan.network;

import dev.apogee.sandevistan.ApogeeMod;
import dev.apogee.sandevistan.implant.ImplantManager;
import dev.apogee.sandevistan.logic.SandevistanManager;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Packet ids and server-side send/receive. Safe for a dedicated server (no client classes).
 *
 * C2S ACTIVATE     : empty payload. A pure request, the server decides everything.
 * C2S OPEN_IMPLANT : empty payload. Asks the server to open the neuroimplant screen.
 * S2C OWNER_STATE  : byte kind, varint activeLeft, varint cooldownLeft, varint activeTotal, varint cooldownTotal.
 * S2C TRAIL        : byte kind, varint entityId. Sent to players tracking the owner (start / refresh every 10 ticks / end).
 * S2C IMPLANT_SYNC : boolean installed. Sent on join and whenever the implant slot changes.
 */
public final class ApogeeNetworking {
    private ApogeeNetworking() {
    }

    public static final Identifier ACTIVATE = ApogeeMod.id("activate");
    public static final Identifier OWNER_STATE = ApogeeMod.id("owner_state");
    public static final Identifier TRAIL = ApogeeMod.id("trail");
    public static final Identifier OPEN_IMPLANT = ApogeeMod.id("open_implant");
    public static final Identifier IMPLANT_SYNC = ApogeeMod.id("implant_sync");

    public static final int KIND_SYNC = 0;
    public static final int KIND_ACTIVATED = 1;
    public static final int KIND_ENDED = 2;
    public static final int KIND_READY = 3;

    public static final int TRAIL_REFRESH = 0;
    public static final int TRAIL_START = 1;
    public static final int TRAIL_END = 2;

    public static void registerServerReceivers() {
        ServerPlayNetworking.registerGlobalReceiver(ACTIVATE,
                (server, player, handler, buf, responseSender) ->
                        server.execute(() -> SandevistanManager.tryActivate(player)));
        ServerPlayNetworking.registerGlobalReceiver(OPEN_IMPLANT,
                (server, player, handler, buf, responseSender) ->
                        server.execute(() -> ImplantManager.openScreen(player)));
    }

    public static void sendOwnerState(ServerPlayerEntity player, int kind, int activeLeft, int cooldownLeft,
                                      int activeTotal, int cooldownTotal) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(kind);
        buf.writeVarInt(activeLeft);
        buf.writeVarInt(cooldownLeft);
        buf.writeVarInt(activeTotal);
        buf.writeVarInt(cooldownTotal);
        ServerPlayNetworking.send(player, OWNER_STATE, buf);
    }

    public static void sendTrail(ServerPlayerEntity owner, int kind) {
        for (ServerPlayerEntity watcher : PlayerLookup.tracking(owner)) {
            if (watcher == owner) {
                continue;
            }
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeByte(kind);
            buf.writeVarInt(owner.getId());
            ServerPlayNetworking.send(watcher, TRAIL, buf);
        }
    }

    public static void sendImplantState(ServerPlayerEntity player, boolean installed) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(installed);
        ServerPlayNetworking.send(player, IMPLANT_SYNC, buf);
    }
}
