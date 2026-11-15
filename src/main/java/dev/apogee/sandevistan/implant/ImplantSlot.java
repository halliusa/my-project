package dev.apogee.sandevistan.implant;

import dev.apogee.sandevistan.ApogeeItems;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;

/**
 * The dedicated neuroimplant slot. Accepts exactly the Sandevistan implant, holds a single item
 * and never accepts anything else (not even in creative mode through the GUI).
 */
public class ImplantSlot extends Slot {
    public ImplantSlot(Inventory inventory, int index, int x, int y) {
        super(inventory, index, x, y);
    }

    @Override
    public boolean canInsert(ItemStack stack) {
        return stack.isOf(ApogeeItems.SANDEVISTAN);
    }

    @Override
    public int getMaxItemCount() {
        return 1;
    }

    @Override
    public int getMaxItemCount(ItemStack stack) {
        return 1;
    }
}
