package dev.apogee.sandevistan.implant;

import dev.apogee.sandevistan.ApogeeItems;
import dev.apogee.sandevistan.ApogeeScreenHandlers;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

/**
 * Screen handler backing the neuroimplant GUI. One special slot (the implant) plus the player's own
 * inventory: it works like a tiny, personal "cyberware bay" that travels with the player everywhere
 * (contents persist in the world save, see {@link ImplantManager}).
 *
 * <p>Slot indices: 0 = implant, 1..27 = main inventory, 28..36 = hotbar.
 */
public class CyberwareScreenHandler extends ScreenHandler {
    /** GUI-space coordinates of the implant slot. Kept in sync with the screen rendering. */
    public static final int IMPLANT_X = 79;
    public static final int IMPLANT_Y = 30;

    private static final int MAIN_ROWS = 3;
    private static final int SLOTS = 1 + MAIN_ROWS * 9 + 9;

    private final SimpleInventory implantInventory;

    /** Client-side constructor (called by the {@link net.minecraft.screen.ScreenHandlerType} factory). */
    public CyberwareScreenHandler(int syncId, PlayerInventory playerInventory) {
        this(syncId, playerInventory, new SimpleInventory(1));
    }

    /** Server-side constructor. The implant inventory itself is owned by {@link ImplantManager}. */
    public CyberwareScreenHandler(int syncId, PlayerInventory playerInventory, SimpleInventory implantInventory) {
        super(ApogeeScreenHandlers.CYBERWARE, syncId);
        this.implantInventory = implantInventory;

        this.addSlot(new ImplantSlot(implantInventory, 0, IMPLANT_X, IMPLANT_Y));

        for (int row = 0; row < MAIN_ROWS; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 96 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 150));
        }
    }

    public boolean hasImplant() {
        return !this.implantInventory.getStack(0).isEmpty();
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        // The implant bay is the player's own: there is no container to be too far away from.
        return true;
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        ItemStack newStack = ItemStack.EMPTY;
        Slot fromSlot = this.getSlot(slot);
        if (fromSlot != null && fromSlot.hasStack()) {
            ItemStack stack = fromSlot.getStack();
            newStack = stack.copy();

            if (slot == 0) {
                if (!this.insertItem(stack, 1, SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (stack.isOf(ApogeeItems.SANDEVISTAN) && !this.getSlot(0).hasStack()) {
                // Prefer the implant slot for the implant item, whatever the player clicked from.
                if (!this.insertItem(stack, 0, 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (slot <= 27) { // main inventory -> hotbar
                if (!this.insertItem(stack, 28, SLOTS, false)) {
                    return ItemStack.EMPTY;
                }
            } else { // hotbar -> main inventory
                if (!this.insertItem(stack, 1, 28, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (stack.isEmpty()) {
                fromSlot.setStack(ItemStack.EMPTY);
            } else {
                fromSlot.markDirty();
            }
            if (stack.getCount() == newStack.getCount()) {
                return ItemStack.EMPTY;
            }
            fromSlot.onTakeItem(player, stack);
        }
        return newStack;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        ImplantManager.requestSave();
    }
}
