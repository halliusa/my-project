package dev.apogee.sandevistan;

import dev.apogee.sandevistan.config.ApogeeConfig;
import dev.apogee.sandevistan.logic.SandevistanManager;
import dev.apogee.sandevistan.network.ApogeeNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (client + server) entrypoint. Contains no client-only classes.
 */
public class ApogeeMod implements ModInitializer {
    public static final String MOD_ID = "sandevistan_apogee";
    public static final Logger LOGGER = LoggerFactory.getLogger("SandevistanApogee");

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ApogeeConfig.load();
        ApogeeSounds.register();
        ApogeeParticles.register();
        ApogeeItems.register();
        ApogeeNetworking.registerServerReceivers();
        SandevistanManager.registerEvents();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("sandevistan")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("reload").executes(ctx -> {
                            ApogeeConfig.load();
                            ctx.getSource().sendFeedback(
                                    () -> Text.translatable("command.sandevistan_apogee.reloaded"), true);
                            return 1;
                        }))));

        LOGGER.info("Sandevistan: Apogee initialised");
    }
}
