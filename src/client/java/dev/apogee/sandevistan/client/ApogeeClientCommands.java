package dev.apogee.sandevistan.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.util.function.BiConsumer;

/**
 * /apogeefx intensity [0..1]
 * /apogeefx shake|fov|flash|overlay|tint|lowtime|afterimages|arcs|particles|hud [true|false]
 * Client-side only; values are saved to config/sandevistan_apogee_client.json.
 */
public final class ApogeeClientCommands {
    private ApogeeClientCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal("apogeefx");

            root.then(ClientCommandManager.literal("intensity")
                    .then(ClientCommandManager.argument("value", FloatArgumentType.floatArg(0.0f, 1.0f))
                            .executes(ctx -> {
                                float value = FloatArgumentType.getFloat(ctx, "value");
                                ClientConfig.get().intensity = value;
                                ClientConfig.save();
                                feedback(ctx.getSource(), "intensity", String.valueOf(value));
                                return 1;
                            })));

            root.then(toggle("shake", (c, v) -> c.cameraShake = v));
            root.then(toggle("fov", (c, v) -> c.cameraFov = v));
            root.then(toggle("flash", (c, v) -> c.screenFlash = v));
            root.then(toggle("overlay", (c, v) -> c.screenOverlay = v));
            root.then(toggle("tint", (c, v) -> c.screenTint = v));
            root.then(toggle("lowtime", (c, v) -> c.lowTime = v));
            root.then(toggle("afterimages", (c, v) -> c.afterimages = v));
            root.then(toggle("arcs", (c, v) -> c.arcs = v));
            root.then(toggle("particles", (c, v) -> c.particles = v));
            root.then(toggle("hud", (c, v) -> c.hud = v));

            dispatcher.register(root);
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> toggle(String name,
                                                                            BiConsumer<ClientConfig, Boolean> setter) {
        return ClientCommandManager.literal(name)
                .then(ClientCommandManager.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean value = BoolArgumentType.getBool(ctx, "value");
                            setter.accept(ClientConfig.get(), value);
                            ClientConfig.save();
                            feedback(ctx.getSource(), name, String.valueOf(value));
                            return 1;
                        }));
    }

    private static void feedback(FabricClientCommandSource source, String key, String value) {
        source.sendFeedback(Text.translatable("command.sandevistan_apogee.fx_set", key, value));
    }
}
