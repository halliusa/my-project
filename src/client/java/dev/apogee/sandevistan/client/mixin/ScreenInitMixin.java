package dev.apogee.sandevistan.client.mixin;

import dev.apogee.sandevistan.client.ApogeeClient;
import dev.apogee.sandevistan.client.ImplantButtonTracker;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a small "open neuroimplant" button to the player inventory screen, in the empty strip at the
 * top right of the vanilla 176x166 panel, above the crafting result slot.
 *
 * <p>Targets the concrete {@code InventoryScreen#init()}: its superclass chain (HandledScreen →
 * Screen) never calls {@code Screen.init()}, so injecting there would never run. At TAIL of
 * InventoryScreen#init the recipe-book panel offset has been applied, which is why the button is
 * placed from the panel's own coordinates (see {@link HandledScreenAccessor}) instead of window math.
 * The button is additionally re-glued to the panel every frame in HandledScreenForegroundMixin,
 * because toggling the recipe book shifts the panel without re-initialising the screen.
 *
 * <p>The button itself is a plain vanilla button; the implant icon on top of it is painted by
 * {@link HandledScreenForegroundMixin} right after the widget pass, so the button never covers the icon.
 * Creative mode uses a different screen and is unaffected; the keybind works everywhere.
 *
 * <p>Registered with default require = 0 (see sandevistan_apogee.client.mixins.json): if this ever
 * fails to apply, the button simply does not appear; the keybind keeps working.
 */
@Mixin(InventoryScreen.class)
public class ScreenInitMixin {
    private static final int BUTTON_X_OFFSET = 152;
    private static final int BUTTON_Y_OFFSET = 3;
    private static final int BUTTON_SIZE = 18;

    @Inject(method = "init()V", at = @At("TAIL"))
    private void sandevistanApogee$addImplantButton(CallbackInfo ci) {
        try {
            HandledScreenAccessor panel = (HandledScreenAccessor) (Object) this;
            Screen self = (Screen) (Object) this;
            int x = panel.sandevistanApogee$panelX() + BUTTON_X_OFFSET;
            int y = panel.sandevistanApogee$panelY() + BUTTON_Y_OFFSET;
            ButtonWidget button = ButtonWidget.builder(Text.empty(), btn -> ApogeeClient.requestOpenImplant())
                    .dimensions(x, y, BUTTON_SIZE, BUTTON_SIZE)
                    .tooltip(Tooltip.of(Text.translatable("gui.sandevistan_apogee.button")))
                    .build();
            self.addDrawableChild(button);
            ImplantButtonTracker.place(button);
        } catch (Throwable ignored) {
            // Never break the inventory screen over a decorative button.
            ImplantButtonTracker.clear();
        }
    }
}
