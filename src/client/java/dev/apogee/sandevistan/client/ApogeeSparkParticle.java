package dev.apogee.sandevistan.client;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DefaultParticleType;

/** Small glowing neon spark (cyan or red-orange), fades out smoothly. */
public class ApogeeSparkParticle extends SpriteBillboardParticle {
    protected ApogeeSparkParticle(ClientWorld world, double x, double y, double z,
                                  double vx, double vy, double vz, SpriteProvider sprites) {
        super(world, x, y, z, vx, vy, vz);
        this.velocityX = vx;
        this.velocityY = vy;
        this.velocityZ = vz;
        this.maxAge = 8 + this.random.nextInt(8);
        this.scale = 0.06f + this.random.nextFloat() * 0.06f;
        this.gravityStrength = 0.0f;
        this.collidesWithWorld = false;
        if (this.random.nextFloat() < 0.35f) {
            this.setColor(1.0f, 0.32f + this.random.nextFloat() * 0.2f, 0.12f);
        } else {
            this.setColor(0.15f, 0.8f + this.random.nextFloat() * 0.2f, 1.0f);
        }
        this.setSprite(sprites.getSprite(this.random));
    }

    @Override
    public void tick() {
        super.tick();
        this.setAlpha(Math.max(0.0f, 1.0f - (float) this.age / (float) this.maxAge));
    }

    @Override
    public ParticleTextureSheet getType() {
        return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public int getBrightness(float tint) {
        return 15728880;
    }

    public static class Factory implements ParticleFactory<DefaultParticleType> {
        private final SpriteProvider sprites;

        public Factory(SpriteProvider sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(DefaultParticleType type, ClientWorld world, double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new ApogeeSparkParticle(world, x, y, z, vx, vy, vz, this.sprites);
        }
    }
}
