package dev.apogee.sandevistan.client;

import dev.apogee.sandevistan.network.ApogeeNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Client-side packet receivers. Buffers are read on the network thread, state is changed on the client thread. */
public final class ClientNetworking {
    private ClientNetworking() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ApogeeNetworking.OWNER_STATE,
                (client, handler, buf, responseSender) -> {
                    int kind = buf.readByte();
                    int active = buf.readVarInt();
                    int cooldown = buf.readVarInt();
                    int activeTotal = buf.readVarInt();
                    int cooldownTotal = buf.readVarInt();
                    client.execute(() -> ClientState.onOwnerState(kind, active, cooldown, activeTotal, cooldownTotal));
                });

        ClientPlayNetworking.registerGlobalReceiver(ApogeeNetworking.TRAIL,
                (client, handler, buf, responseSender) -> {
                    int kind = buf.readByte();
                    int entityId = buf.readVarInt();
                    client.execute(() -> ClientState.onTrail(kind, entityId));
                });
    }
}
