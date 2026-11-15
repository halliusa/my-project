package dev.apogee.sandevistan.client.mixin;

import dev.apogee.sandevistan.client.ApogeeFx;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a very small, smooth camera shake while the effect is active. Client only. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("RETURN"))
    private void sandevistanApogee$shake(BlockView area, Entity focusedEntity, boolean thirdPerson,
                                         boolean inverseView, float tickDelta, CallbackInfo ci) {
        float[] offset = ApogeeFx.cameraOffset(tickDelta);
        if (offset != null) {
            Camera self = (Camera) (Object) this;
            this.setRotation(self.getYaw() + offset[0], self.getPitch() + offset[1]);
        }
    }
}
