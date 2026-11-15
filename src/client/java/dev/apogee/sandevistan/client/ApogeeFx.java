package dev.apogee.sandevistan.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * Smooth, flicker-free screen/camera effect state. Everything decays exponentially or follows a slow sine,
 * there is no random per-frame noise, so nothing strobes.
 */
public final class ApogeeFx {
    private ApogeeFx() {
    }

    private static float blend;
    private static float blendPrev;
    private static float pulse;
    private static float pulsePrev;
    private static float flash;
    private static float flashPrev;
    private static int flashKind; // 0 = warm (activation), 1 = cool (shutdown)
    private static float lines;
    private static float linesPrev;
    private static int time;

    private static final float[] CAMERA = new float[2];

    public static void reset() {
        blend = blendPrev = pulse = pulsePrev = flash = flashPrev = lines = linesPrev = 0.0f;
        flashKind = 0;
        time = 0;
    }

    public static void onActivate() {
        pulse = pulsePrev = 1.0f;
        flash = flashPrev = 1.0f;
        flashKind = 0;
    }

    public static void onEnd() {
        pulse = pulsePrev = 0.5f;
        flash = flashPrev = 0.6f;
        flashKind = 1;
    }

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        if (player == null) {
            return;
        }
        time++;
        boolean active = ClientState.activeLeft > 0;

        blendPrev = blend;
        blend += ((active ? 1.0f : 0.0f) - blend) * 0.25f;
        if (blend < 0.001f) {
            blend = 0.0f;
        }

        pulsePrev = pulse;
        pulse *= 0.80f;
        if (pulse < 0.01f) {
            pulse = 0.0f;
        }

        flashPrev = flash;
        flash *= 0.86f;
        if (flash < 0.01f) {
            flash = 0.0f;
        }

        double dx = player.getX() - player.prevX;
        double dz = player.getZ() - player.prevZ;
        float horizontalSpeed = (float) Math.sqrt(dx * dx + dz * dz);
        float target = active ? MathHelper.clamp(horizontalSpeed / 0.45f, 0.0f, 1.0f) : 0.0f;
        linesPrev = lines;
        lines += (target - lines) * 0.2f;
    }

    public static float blend(float td) {
        return MathHelper.lerp(td, blendPrev, blend);
    }

    public static float flash(float td) {
        return MathHelper.lerp(td, flashPrev, flash);
    }

    public static int flashKind() {
        return flashKind;
    }

    public static float speedLines(float td) {
        return MathHelper.lerp(td, linesPrev, lines);
    }

    public static int time() {
        return time;
    }

    /** Multiplier for the field of view (called from GameRendererMixin). */
    public static double fovMultiplier(float td) {
        ClientConfig cfg = ClientConfig.get();
        if (!cfg.cameraFov || cfg.intensity <= 0.01f) {
            return 1.0;
        }
        float b = MathHelper.lerp(td, blendPrev, blend);
        float p = MathHelper.lerp(td, pulsePrev, pulse);
        return 1.0 + cfg.intensity * (0.08f * b + 0.10f * p);
    }

    /** Small yaw/pitch offset in degrees (called from CameraMixin) or null when there is nothing to apply. */
    public static float[] cameraOffset(float td) {
        ClientConfig cfg = ClientConfig.get();
        if (!cfg.cameraShake || cfg.intensity <= 0.01f) {
            return null;
        }
        float b = MathHelper.lerp(td, blendPrev, blend);
        float p = MathHelper.lerp(td, pulsePrev, pulse);
        float amp = Math.min(1.2f, (0.25f * b + 1.0f * p) * cfg.intensity);
        if (amp < 0.005f) {
            return null;
        }
        float t = (time + td) * 0.8f;
        CAMERA[0] = (MathHelper.sin(t * 1.7f) * 0.6f + MathHelper.sin(t * 3.1f) * 0.4f) * amp;
        CAMERA[1] = MathHelper.sin(t * 2.3f + 1.0f) * amp * 0.7f;
        return CAMERA;
    }
}
