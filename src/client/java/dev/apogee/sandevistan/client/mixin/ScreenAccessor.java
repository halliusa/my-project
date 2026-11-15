package dev.apogee.sandevistan.client.mixin;

import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the {@code protected} {@code addDrawableChild} of Screen (in yarn it is protected, so the
 * mixin cannot call it from outside the class hierarchy). Used by ScreenInitMixin to add the
 * neuroimplant button to the inventory screen.
 *
 * <p>Registered with default require = 0 (see sandevistan_apogee.client.mixins.json); the call site
 * guards against the invoker not applying (the button simply does not appear, keybind keeps working).
 */
@Mixin(Screen.class)
public interface ScreenAccessor {
    @Invoker("addDrawableChild")
    <T extends Element & Drawable & Selectable> T sandevistanApogee$addDrawableChild(T drawable);
}
