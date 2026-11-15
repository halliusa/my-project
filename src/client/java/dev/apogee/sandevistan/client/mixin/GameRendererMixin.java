package dev.apogee.sandevistan.client.mixin;

import dev.apogee.sandevistan.client.ApogeeFx;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Soft FOV pulse. Applied only to the world FOV (changingFov == true), not to the hand. Client only. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void sandevistanApogee$fov(Camera camera, float tickDelta, boolean changingFov,
                                       CallbackInfoReturnable<Double> cir) {
        if (changingFov) {
            double multiplier = ApogeeFx.fovMultiplier(tickDelta);
            if (multiplier != 1.0) {
                cir.setReturnValue(cir.getReturnValueD() * multiplier);
            }
        }
    }
}
