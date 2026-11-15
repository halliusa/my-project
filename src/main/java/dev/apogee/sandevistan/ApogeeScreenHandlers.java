package dev.apogee.sandevistan;

import dev.apogee.sandevistan.implant.CyberwareScreenHandler;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.screen.ScreenHandlerType;

/**
 * Registry entries shared by both sides: the screen handler type must exist on the dedicated server
 * (it is serialised into the open-screen packet) and on the client (it maps the type to the actual screen).
 */
public final class ApogeeScreenHandlers {
    private ApogeeScreenHandlers() {
    }

    public static final ScreenHandlerType<CyberwareScreenHandler> CYBERWARE =
            new ScreenHandlerType<>(CyberwareScreenHandler::new, FeatureSet.empty());

    public static void register() {
        Registry.register(Registries.SCREEN_HANDLER, ApogeeMod.id("cyberware"), CYBERWARE);
    }
}
