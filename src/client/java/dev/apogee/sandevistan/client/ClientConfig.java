package dev.apogee.sandevistan.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.apogee.sandevistan.ApogeeMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.MathHelper;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Visual settings, config/sandevistan_apogee_client.json. Can be changed in game with /apogeefx. */
public final class ClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ClientConfig instance = new ClientConfig();

    /** Global strength of all visual effects, 0.0 .. 1.0 (0 = everything off, HUD still works). */
    public float intensity = 1.0f;
    public boolean cameraShake = true;
    public boolean cameraFov = true;
    public boolean screenFlash = true;
    public boolean screenOverlay = true;
    public boolean afterimages = true;
    public boolean arcs = true;
    public boolean particles = true;
    public boolean hud = true;
    /** TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT or BOTTOM_RIGHT. */
    public String hudAnchor = "BOTTOM_RIGHT";
    public int hudOffsetX = 10;
    public int hudOffsetY = 10;

    public static ClientConfig get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("sandevistan_apogee_client.json");
    }

    public static void load() {
        ClientConfig loaded = null;
        if (Files.exists(path())) {
            try (Reader reader = Files.newBufferedReader(path(), StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(reader, ClientConfig.class);
            } catch (Exception e) {
                ApogeeMod.LOGGER.warn("Could not read client config, using defaults", e);
            }
        }
        instance = loaded == null ? new ClientConfig() : loaded;
        instance.intensity = MathHelper.clamp(instance.intensity, 0.0f, 1.0f);
        if (instance.hudAnchor == null) {
            instance.hudAnchor = "BOTTOM_RIGHT";
        }
        save();
    }

    public static void save() {
        try (Writer writer = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
            GSON.toJson(instance, writer);
        } catch (Exception e) {
            ApogeeMod.LOGGER.warn("Could not write client config", e);
        }
    }
}
