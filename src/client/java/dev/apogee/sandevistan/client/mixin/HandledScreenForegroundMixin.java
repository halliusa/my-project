package dev.apogee.sandevistan.client.mixin;

import dev.apogee.sandevistan.client.ImplantButtonTracker;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Maintains the "open neuroimplant" button of the inventory screen (see {@link ScreenInitMixin}).
 *
 * <p>Targets {@code InventoryScreen#drawForeground} directly, and not {@code HandledScreen#drawForeground}:
 * the inventory screen <b>overrides</b> the method, and the override is what the render pipeline calls.
 * Injecting into the base method would never run (the base method's bytecode is not executed).
 *
 * <p>Runs once per frame, after the widget pass, with the pose already translated to the panel origin:
 * the button is re-glued to the panel's current position (the recipe book toggle shifts the panel
 * without re-initialising the screen) and the implant icon is painted on top of it.
 *
 * <p>Registered with default require = 0 (see sandevistan_apogee.client.mixins.json).
 */
@Mixin(InventoryScreen.class)
public class HandledScreenForegroundMixin {
    @Inject(method = "drawForeground", at = @At("TAIL"))
    private void sandevistanApogee$drawImplantIcon(DrawContext context, int mouseX, int mouseY,
                                                   CallbackInfo ci) {
        try {
            ImplantButtonTracker.track((HandledScreenAccessor) (Object) this);
            ImplantButtonTracker.draw(context);
        } catch (Throwable ignored) {
            // Decorative only; never propagate.
        }
    }
}
