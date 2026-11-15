package dev.apogee.sandevistan.client;

import dev.apogee.sandevistan.ApogeeItems;
import dev.apogee.sandevistan.client.mixin.HandledScreenAccessor;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;

/**
 * Remembers the "open neuroimplant" button placed in the inventory screen so the mixin can draw
 * the implant item on top of it. Vanilla buttons can't render items by themselves, so the icon is
 * painted manually right after the widget pass.
 *
 * <p>The button is re-glued to the panel every frame ({@link #track}): the survival inventory
 * shifts the panel by 77 px when the recipe book is toggled, and widgets added at init time would
 * otherwise stay behind.
 */
public final class ImplantButtonTracker {
    private static final ItemStack ICON = new ItemStack(ApogeeItems.SANDEVISTAN);

    /** How far the button sits from the panel's top-right corner. */
    private static final int OFFSET_X = 152;
    private static final int OFFSET_Y = 3;

    private static ButtonWidget button;

    private ImplantButtonTracker() {
    }

    /** Called by ScreenInitMixin when the inventory screen is (re)created. */
    public static void place(ButtonWidget button) {
        ImplantButtonTracker.button = button;
    }

    public static void clear() {
        ImplantButtonTracker.button = null;
    }

    /** Re-anchors the button to the panel's current position. No-op until the button exists. */
    public static void track(HandledScreenAccessor panel) {
        if (button != null && panel != null) {
            button.setPosition(panel.sandevistanApogee$panelX() + OFFSET_X,
                    panel.sandevistanApogee$panelY() + OFFSET_Y);
        }
    }

    /** Draws the implant icon on top of the button. Panel-relative: the foreground pass runs inside the
     * pose translated to the panel origin (see AbstractContainerScreen#render). */
    public static void draw(DrawContext g) {
        if (button != null && button.visible) {
            g.drawItem(ICON, OFFSET_X + 1, OFFSET_Y + 1);
        }
    }
}
