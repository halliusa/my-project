package dev.apogee.sandevistan.client.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Absolute X/Y of a HandledScreen panel (yarn field names {@code x}/{@code y}, protected in vanilla).
 *
 * <p>The survival inventory screen shifts the panel horizontally when the recipe book is shown,
 * so window-size math is not reliable for placing widgets on the panel. Registered with
 * default require = 0 (see sandevistan_apogee.client.mixins.json); callers guard against the
 * accessor not applying.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int sandevistanApogee$panelX();

    @Accessor("y")
    int sandevistanApogee$panelY();
}
