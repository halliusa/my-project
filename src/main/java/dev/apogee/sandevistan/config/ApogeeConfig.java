package dev.apogee.sandevistan.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.apogee.sandevistan.ApogeeMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.MathHelper;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side gameplay configuration: config/sandevistan_apogee.json.
 * On a dedicated server the server file is used; in singleplayer the integrated server uses the same file.
 */
public final class ApogeeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ApogeeConfig instance = new ApogeeConfig();

    /** Effect length in seconds. */
    public float durationSeconds = 5.0f;
    /** Cooldown in seconds, counted from the end of the effect. */
    public float cooldownSeconds = 30.0f;
    /** Area of effect (blocks) for slowing mobs and projectiles. */
    public float radius = 24.0f;
    /** Movement speed bonus as a multiplier of the total (1.2 = +120%). Fixed modifier, never stacks. */
    public double speedBonus = 1.2;
    /** Attack speed bonus as a multiplier of the total (0.35 = +35%). */
    public double attackSpeedBonus = 0.35;
    /** Vanilla Slowness amplifier applied to mobs (0 = Slowness I, 3 = Slowness IV). */
    public int mobSlownessAmplifier = 3;
    /** Foreign projectiles inside the radius are limited to this speed (blocks per tick). Vanilla arrow is about 3. */
    public float projectileMaxSpeed = 0.9f;
    /** Also slow peaceful/passive mobs, not only monsters. */
    public boolean affectPassiveMobs = true;
    /** Also slow other players and their projectiles. Disabled by default. */
    public boolean affectPlayers = false;
    /** Play the humming sound once per second while active. */
    public boolean playLoopSound = true;

    public static ApogeeConfig get() {
        return instance;
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("sandevistan_apogee.json");
        ApogeeConfig loaded = null;
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(reader, ApogeeConfig.class);
            } catch (Exception e) {
                ApogeeMod.LOGGER.warn("Could not read {}, using defaults", path, e);
            }
        }
        if (loaded == null) {
            loaded = new ApogeeConfig();
        }
        loaded.sanitize();
        instance = loaded;
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(loaded, writer);
        } catch (IOException e) {
            ApogeeMod.LOGGER.warn("Could not write {}", path, e);
        }
    }

    private void sanitize() {
        durationSeconds = MathHelper.clamp(durationSeconds, 0.5f, 120.0f);
        cooldownSeconds = MathHelper.clamp(cooldownSeconds, 0.0f, 3600.0f);
        radius = MathHelper.clamp(radius, 2.0f, 64.0f);
        speedBonus = MathHelper.clamp(speedBonus, 0.0, 4.0);
        attackSpeedBonus = MathHelper.clamp(attackSpeedBonus, 0.0, 3.0);
        mobSlownessAmplifier = MathHelper.clamp(mobSlownessAmplifier, 0, 6);
        projectileMaxSpeed = MathHelper.clamp(projectileMaxSpeed, 0.1f, 3.0f);
    }

    public int durationTicks() {
        return Math.max(1, Math.round(durationSeconds * 20.0f));
    }

    public int cooldownTicks() {
        return Math.max(0, Math.round(cooldownSeconds * 20.0f));
    }
}
