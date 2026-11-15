package dev.apogee.sandevistan;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.particle.DefaultParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class ApogeeParticles {
    private ApogeeParticles() {
    }

    public static final DefaultParticleType SPARK = FabricParticleTypes.simple();

    public static void register() {
        Registry.register(Registries.PARTICLE_TYPE, ApogeeMod.id("spark"), SPARK);
    }
}
