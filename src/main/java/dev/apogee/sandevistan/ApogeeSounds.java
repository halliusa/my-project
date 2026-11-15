package dev.apogee.sandevistan;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public final class ApogeeSounds {
    private ApogeeSounds() {
    }

    public static final SoundEvent ACTIVATE = create("sandevistan.activate");
    public static final SoundEvent LOOP = create("sandevistan.loop");
    public static final SoundEvent DEACTIVATE = create("sandevistan.deactivate");
    public static final SoundEvent READY = create("sandevistan.ready");

    private static SoundEvent create(String path) {
        return SoundEvent.of(ApogeeMod.id(path));
    }

    public static void register() {
        reg(ACTIVATE);
        reg(LOOP);
        reg(DEACTIVATE);
        reg(READY);
    }

    private static void reg(SoundEvent event) {
        Identifier id = event.getId();
        Registry.register(Registries.SOUND_EVENT, id, event);
    }
}
