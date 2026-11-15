package dev.apogee.sandevistan;

import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Rarity;

public final class ApogeeItems {
    private ApogeeItems() {
    }

    public static final Item SANDEVISTAN = new SandevistanItem(
            new FabricItemSettings().maxCount(1).rarity(Rarity.EPIC).fireproof());

    public static final ItemGroup GROUP = FabricItemGroup.builder()
            .icon(() -> new ItemStack(SANDEVISTAN))
            .displayName(Text.translatable("itemGroup.sandevistan_apogee.cyberware"))
            .entries((displayContext, entries) -> entries.add(SANDEVISTAN))
            .build();

    public static void register() {
        Registry.register(Registries.ITEM, ApogeeMod.id("sandevistan_apogee"), SANDEVISTAN);
        Registry.register(Registries.ITEM_GROUP, ApogeeMod.id("cyberware"), GROUP);
    }
}
