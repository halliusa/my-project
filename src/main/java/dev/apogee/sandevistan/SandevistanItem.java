package dev.apogee.sandevistan;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The implant has no use-action: it works from the inventory. The logic lives in SandevistanManager (server).
 * TooltipContext is a plain data class, safe to reference on a dedicated server.
 */
public class SandevistanItem extends Item {
    public SandevistanItem(Settings settings) {
        super(settings);
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return true;
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.translatable("item.sandevistan_apogee.sandevistan_apogee.tooltip.1").formatted(Formatting.AQUA));
        tooltip.add(Text.translatable("item.sandevistan_apogee.sandevistan_apogee.tooltip.2").formatted(Formatting.GRAY));
        tooltip.add(Text.translatable("item.sandevistan_apogee.sandevistan_apogee.tooltip.3").formatted(Formatting.RED));
    }
}
