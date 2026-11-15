package dev.apogee.sandevistan.client.mixin;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the {@code model} field of LivingEntityRenderer (it is {@code protected} in vanilla and there
 * is no public getter in 1.20.1). Used by the afterimage renderer to pose the real player model.
 *
 * <p>Registered with default require = 0 (see sandevistan_apogee.client.mixins.json); the call site
 * additionally guards against the mixin not applying (afterimages would silently not render).
 */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererAccessor {
    @Accessor("model")
    EntityModel<?> sandevistanApogee$getModel();
}
