package dev.apogee.sandevistan.client;

import dev.apogee.sandevistan.ApogeeMod;
import dev.apogee.sandevistan.ApogeeParticles;
import dev.apogee.sandevistan.network.ApogeeNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Client entrypoint. Everything here lives in the "client" source set and is never loaded on a dedicated server.
 */
public class ApogeeClient implements ClientModInitializer {
    public static KeyBinding activateKey;
    public static KeyBinding cycleFxKey;

    private static final float[] INTENSITY_STEPS = {0.0f, 0.35f, 0.65f, 1.0f};

    @Override
    public void onInitializeClient() {
        ClientConfig.load();

        activateKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.sandevistan_apogee.activate", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V,
                "key.categories.sandevistan_apogee"));
        cycleFxKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.sandevistan_apogee.cycle_fx", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.sandevistan_apogee"));

        ParticleFactoryRegistry.getInstance().register(ApogeeParticles.SPARK, ApogeeSparkParticle.Factory::new);

        ClientNetworking.register();
        ApogeeClientCommands.register();

        HudRenderCallback.EVENT.register(ApogeeHud::render);
        WorldRenderEvents.AFTER_ENTITIES.register(ApogeeWorldFx::renderGhosts);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ApogeeWorldFx::renderBeams);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            handleKeys(client);
            if (client.world == null || client.player == null || client.isPaused()) {
                return;
            }
            ClientState.tick();
            ApogeeFx.tick(client);
            ApogeeWorldFx.tick(client);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetAll());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetAll());

        ApogeeMod.LOGGER.info("Sandevistan: Apogee client initialised");
    }

    private static void resetAll() {
        ClientState.reset();
        ApogeeFx.reset();
        ApogeeWorldFx.reset();
    }

    private static void handleKeys(MinecraftClient client) {
        while (activateKey.wasPressed()) {
            if (client.player == null || client.currentScreen != null) {
                continue;
            }
            if (ClientPlayNetworking.canSend(ApogeeNetworking.ACTIVATE)) {
                // Empty request: the server decides everything.
                ClientPlayNetworking.send(ApogeeNetworking.ACTIVATE, PacketByteBufs.empty());
            } else {
                client.player.sendMessage(Text.translatable("message.sandevistan_apogee.no_server"), true);
            }
        }
        while (cycleFxKey.wasPressed()) {
            ClientConfig cfg = ClientConfig.get();
            float next = INTENSITY_STEPS[0];
            for (float step : INTENSITY_STEPS) {
                if (step > cfg.intensity + 0.01f) {
                    next = step;
                    break;
                }
            }
            cfg.intensity = next;
            ClientConfig.save();
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("message.sandevistan_apogee.fx_intensity",
                        Math.round(next * 100)), true);
            }
        }
    }
}
