package dev.apogee.sandevistan.client;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.apogee.sandevistan.ApogeeItems;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Random;

/**
 * HUD indicator plus screen effects. The screen effects are drawn with plain GUI primitives (no post-process
 * shader), which is the reliable fallback: it cannot conflict with shader packs or other rendering mods.
 *
 * <h3>Sandevistan activation cut-in (Edgerunners style), 26 ticks = 1.3 s</h3>
 * <ol>
 *   <li><b>Phase 1, ticks 0-6 (boot):</b> the screen sinks into a dark cyber vignette with green scanlines, a slow
 *       dolly-in starts and a zoomed 3D plan of the player's BACK (hips to nape) fades in out of the darkness
 *       while a holographic scan line sweeps up the body.</li>
 *   <li><b>Phase 2, ticks 7-20 (spine ignition):</b> the implant lights up SEGMENT BY SEGMENT from the lower back
 *       to the neck, slowly enough to count every vertebra. Each vertebra snaps on with a white-hot flare, then
 *       settles to neon green. Electric arcs jump sideways from the vertebra that just ignited and neon rings
 *       expand around the spine. On tick 20 the neck is reached and the overload charges (shake, white core).</li>
 *   <li><b>Phase 3, ticks 21-26 (overload):</b> the neck explodes: green-white flash, radial streaks (radial blur
 *       imitation), shock rings, the 3D model dissolves (collapsing slit + flying sparks), the backdrop fades out
 *       and gameplay is back.</li>
 * </ol>
 *
 * <p><b>Tuning:</b> change {@link #CUTIN_TICKS} only. All phase boundaries are defined for a 26 tick effect and are
 * rescaled automatically ({@link #scaled(float)}), so 18 gives a snappier cut-in and 40 a slow-motion one.
 *
 * <p>How it is triggered:
 * <ul>
 *   <li>Call {@link #startCutIn()} from the activation key handler to start it instantly on key press.</li>
 *   <li>Fallback: if you do not call it, the cut-in starts automatically on the rising edge of
 *       {@code ClientState.activeLeft} (0 -> &gt;0). A short grace window prevents a double trigger when both
 *       paths fire for the same activation.</li>
 * </ul>
 * No extra registration is needed: the tick hook is installed lazily on the first {@link #render} call.
 */
public final class ApogeeHud {
    private ApogeeHud() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("sandevistan_apogee/cutin");

    private static final int ORANGE = 0xFF4A2B;
    private static final int CYAN = 0x2BD9FF;
    private static final int READY = 0x7DFFE0;
    private static final int GREY = 0x6B6F80;

    // cut-in palette
    private static final int NEON = 0x39FF88;
    private static final int LIME = 0xB6FF3C;
    private static final int FLASH = 0xF2FFD8;
    private static final int DEEP_GREEN = 0x02120A;

    // ------------------------------------------------------------------ cut-in: timing

    /**
     * Whole effect length in client ticks (20 per second). 26 ticks = 1.3 s.
     * THE ONLY NUMBER YOU NEED TO CHANGE to make the cut-in faster or slower: every phase scales with it.
     */
    public static final int CUTIN_TICKS = 26;

    /** Phase 1 ends: vignette, scanlines and the back view are fully faded in. */
    private static final float FADE_END = scaled(6.0f);
    /** Phase 2 begins: the first (lowest) vertebra ignites. */
    private static final float SURGE_START = scaled(7.0f);
    /** The last (neck) vertebra ignites, the overload starts charging. */
    private static final float SURGE_END = scaled(20.0f);
    /** Phase 3 begins: explosion, white-out peak. */
    private static final float BLAST_TICK = scaled(21.0f);
    /** The 3D model is completely gone. */
    private static final float DISSOLVE_END = scaled(24.0f);

    /** Number of lit implant segments (vertebrae) along the spine. */
    private static final int SPINE_SEGMENTS = 12;

    /** Converts a tick number of the reference 26 tick timeline to the current {@link #CUTIN_TICKS}. */
    private static float scaled(float ticksAt26) {
        return ticksAt26 * CUTIN_TICKS / 26.0f;
    }

    // ------------------------------------------------------------------ cut-in: camera / model

    // player model proportions, in blocks above the feet
    private static final float HIP = 0.70f;
    private static final float NECK = 1.40f;
    /** Camera pitch of the cut-in in degrees. Flip the sign to look from above instead of from below. */
    private static final float CAMERA_PITCH_DEG = 6.0f;
    /**
     * Body yaw (degrees) that shows the BACK of the player to the viewer. Vanilla's GUI projection used by
     * {@link InventoryScreen#drawEntity} looks at the model from the front when the yaw is 180, so the back is 0.
     * If your build shows the face, set this to 180.
     */
    private static final float BACK_YAW_DEG = 0.0f;

    private static ItemStack icon;

    // cut-in state
    private static int cutTick = -1; // -1 = idle, otherwise 0..CUTIN_TICKS-1
    private static int lastActiveLeft = Integer.MAX_VALUE;
    private static int keyGrace;
    private static boolean tickHooked;
    private static boolean cutInFailed;
    private static boolean modelFailed;

    public static void render(DrawContext g, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) {
            return;
        }
        ensureTickHook();
        ClientConfig cfg = ClientConfig.get();
        renderScreenEffects(g, tickDelta, cfg);
        if (cfg.hud) {
            renderPanel(g, mc, tickDelta, cfg);
        }
        renderCutIn(g, mc, tickDelta, cfg);
    }

    // ------------------------------------------------------------------ cut-in: control

    /** Starts the cut-in right now (call from the activation key handler). Ignored while one is running. */
    public static void startCutIn() {
        keyGrace = 40;
        beginCutIn();
    }

    private static void beginCutIn() {
        if (cutTick < 0) {
            cutTick = 0;
        }
    }

    private static void ensureTickHook() {
        if (!tickHooked) {
            tickHooked = true;
            ClientTickEvents.END_CLIENT_TICK.register(ApogeeHud::clientTick);
        }
    }

    private static void clientTick(MinecraftClient mc) {
        if (mc.player == null) {
            cutTick = -1;
            keyGrace = 0;
            lastActiveLeft = Integer.MAX_VALUE; // no false edge right after joining a world
            return;
        }
        int active = ClientState.activeLeft;
        if (active > 0 && lastActiveLeft <= 0) {
            if (keyGrace > 0) {
                keyGrace = 0; // already started by the key press
            } else {
                beginCutIn();
            }
        }
        lastActiveLeft = active;
        if (keyGrace > 0) {
            keyGrace--;
        }
        if (cutTick >= 0 && ++cutTick >= CUTIN_TICKS) {
            cutTick = -1;
        }
    }

    // ------------------------------------------------------------------ cut-in: render

    private static void renderCutIn(DrawContext g, MinecraftClient mc, float td, ClientConfig cfg) {
        if (cutTick < 0 || cutInFailed || cfg.intensity <= 0.01f) {
            return;
        }
        final float t = cutTick + MathHelper.clamp(td, 0.0f, 0.999f);
        final float k = MathHelper.clamp(cfg.intensity, 0.0f, 1.0f);
        final int w = g.getScaledWindowWidth();
        final int h = g.getScaledWindowHeight();

        MatrixStack ms = g.getMatrices();
        MatrixStack.Entry base = ms.peek();

        g.draw(); // flush everything drawn so far so our state changes cannot affect it
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        try {
            renderScene(g, mc, t, td, w, h, k);
            float flash = flashAmount(t);
            if (flash > 0.01f) {
                g.fill(0, 0, w, h, argb(FLASH, flash * Math.max(k, 0.6f)));
            }
            g.draw();
        } catch (Throwable ex) {
            cutInFailed = true;
            cutTick = -1;
            LOGGER.error("Sandevistan cut-in failed, it is disabled until restart", ex);
            Add.abort();
        } finally {
            while (ms.peek() != base) {
                ms.pop(); // never leave an unbalanced matrix stack for the rest of the HUD
            }
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableBlend();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GlConst.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    /** Full-screen white-green flash: ramps up while the neck charges, hard peak on the blast, quick decay. */
    private static float flashAmount(float t) {
        if (t < SURGE_END) {
            return 0.0f;
        }
        if (t < BLAST_TICK) {
            float x = (t - SURGE_END) / (BLAST_TICK - SURGE_END);
            return x * x * 0.85f;
        }
        float x = clamp01((t - BLAST_TICK) / (CUTIN_TICKS - BLAST_TICK));
        return 0.85f * (float) Math.pow(1.0f - x, 2.0);
    }

    private static void renderScene(DrawContext g, MinecraftClient mc, float t, float td, int w, int h, float k) {
        // --- timeline values
        final float fadeIn = smoothstep(0.0f, FADE_END, t);                       // phase 1
        final float fadeOut = t < BLAST_TICK ? 1.0f : 1.0f - smoothstep(BLAST_TICK, CUTIN_TICKS, t);
        final float vis = fadeIn * fadeOut * k;
        final float dolly = smoothstep(0.0f, BLAST_TICK, t);                      // slow push-in
        final float charge = clamp01((t - SURGE_END) / (BLAST_TICK - SURGE_END)); // neck overload build-up
        final float punch = easeOutCubic(clamp01((t - BLAST_TICK) / scaled(2.5f)));
        final float dissolve = clamp01((t - BLAST_TICK) / (DISSOLVE_END - BLAST_TICK));

        drawBackdrop(g, w, h, t, vis);

        // Zoom-in: scale in pixels per block. The neck is anchored near the top edge so the head is cropped
        // by it and the camera stays on the back and neck.
        float size = h * MathHelper.lerp(dolly, 0.80f, 1.02f) * (1.0f + 0.07f * punch);
        int cx = w / 2;
        int neckY = Math.round(h * MathHelper.lerp(dolly, 0.30f, 0.22f));
        int cy = Math.round(neckY + NECK * size); // feet position; they are far below the screen

        // slight Dutch angle + camera shake that grows while the neck charges and kicks on the blast.
        // Deterministic per tick so it does not jitter between frames of the same tick.
        Random shake = new Random(cutTick * 1337L + 11L);
        float amp = h * (0.003f * dolly + 0.011f * charge * charge + 0.012f * (1.0f - punch) * (t >= BLAST_TICK ? 1.0f : 0.0f));
        float sx = (shake.nextFloat() - 0.5f) * 2.0f * amp;
        float sy = (shake.nextFloat() - 0.5f) * 2.0f * amp;
        float roll = (float) Math.toRadians(MathHelper.lerp(dolly, -3.5f, 1.2f));

        MatrixStack ms = g.getMatrices();
        ms.push();
        ms.translate(w / 2.0f + sx, h / 2.0f + sy, 0.0f);
        ms.multiply(RotationAxis.POSITIVE_Z.rotation(roll));
        ms.translate(-w / 2.0f, -h / 2.0f, 0.0f);

        // model brightness: emerges from the darkness in phase 1, flickers while it dissolves
        float reveal = smoothstep(scaled(0.5f), FADE_END, t);
        float bright = 0.03f + 0.97f * reveal;
        if (dissolve > 0.0f) {
            Random f = new Random(cutTick * 31L + 3L);
            bright *= 0.55f + 0.45f * f.nextFloat();
        }
        // dissolve: a vertical slit that collapses onto the spine
        float clipHalf = dissolve > 0.0f ? size * 0.62f * (1.0f - smoothstep(0.0f, 1.0f, dissolve)) : -1.0f;
        if (dissolve < 1.0f && (dissolve <= 0.0f || clipHalf >= 2.0f)) {
            drawBackModel(g, mc, cx, cy, Math.round(size), t, bright * 0.92f, bright, bright * 0.96f, clipHalf);
        }

        float spineA = vis * (1.0f - smoothstep(0.0f, 1.0f, dissolve));
        drawSpineSurge(g, cx, cy, size, t, spineA);
        drawBlast(g, cx, cy, size, t, Math.max(k, 0.6f), dissolve, clipHalf);

        ms.pop();

        float surge = clamp01((t - SURGE_START) / (SURGE_END - SURGE_START));
        drawLetterbox(g, mc, w, h, t, vis, surge, fadeOut);
    }

    // ------------------------------------------------------------------ cut-in: backdrop

    private static void drawBackdrop(DrawContext g, int w, int h, float t, float a) {
        // black-green tint
        g.fill(0, 0, w, h, argb(DEEP_GREEN, 0.60f * a));

        // vignette: soft dark edges
        int steps = 14;
        int edge = Math.max(16, w / 5);
        for (int i = 0; i < steps; i++) {
            float f = 1.0f - (float) i / steps;
            int c = argb(0x000000, 0.60f * f * f * a);
            int x0 = i * edge / steps;
            int x1 = (i + 1) * edge / steps;
            g.fill(x0, 0, x1, h, c);
            g.fill(w - x1, 0, w - x0, h, c);
        }
        int vEdge = Math.max(8, h / 4);
        g.fillGradient(0, 0, w, vEdge, argb(0x000000, 0.75f * a), argb(0x000000, 0.0f));
        g.fillGradient(0, h - vEdge, w, h, argb(0x000000, 0.0f), argb(0x000000, 0.75f * a));

        // neon backlight behind the spine (stacked translucent columns)
        int cx = w / 2;
        for (int i = 0; i < 8; i++) {
            int half = Math.round(h * (0.05f + 0.045f * i));
            g.fill(cx - half, 0, cx + half, h, argb(NEON, 0.022f * a));
        }

        // scanlines: dark line every 3 px, a faint green one every 6 px
        int dark = argb(0x000000, 0.22f * a);
        int green = argb(NEON, 0.055f * a);
        for (int y = 0; y < h; y += 3) {
            g.fill(0, y, w, y + 1, dark);
            if (((y / 3) & 1) == 0) {
                g.fill(0, y + 1, w, y + 2, green);
            }
        }
        // rolling interference band
        int bandH = Math.max(6, h / 8);
        int bandY = Math.round((t * h * 0.45f * 26.0f / CUTIN_TICKS) % (h + bandH)) - bandH;
        g.fillGradient(0, bandY, w, bandY + bandH / 2, argb(NEON, 0.0f), argb(NEON, 0.10f * a));
        g.fillGradient(0, bandY + bandH / 2, w, bandY + bandH, argb(NEON, 0.10f * a), argb(NEON, 0.0f));

        // glitch slices, new set every tick
        Random r = new Random(cutTick * 7907L + 5L);
        for (int i = 0; i < 4; i++) {
            int y = r.nextInt(Math.max(1, h));
            int sh = 1 + r.nextInt(5);
            int off = (r.nextInt(25) - 12);
            g.fill(off, y, w + off, y + sh, argb(NEON, (0.05f + 0.07f * r.nextFloat()) * a));
        }
    }

    private static void drawLetterbox(DrawContext g, MinecraftClient mc, int w, int h, float t, float a,
                                      float surge, float barOut) {
        float in = easeOutCubic(clamp01(t / (FADE_END * 0.6f)));
        int bar = Math.round(h * 0.085f * in * barOut);
        if (bar <= 0) {
            return;
        }
        int black = argb(0x000000, 0.92f * a);
        g.fill(0, 0, w, bar, black);
        g.fill(0, h - bar, w, h, black);
        g.fill(0, bar - 1, w, bar, argb(NEON, 0.85f * a));
        g.fill(0, h - bar, w, h - bar + 1, argb(NEON, 0.85f * a));

        // vanilla TextRenderer treats an alpha of 3 or less as fully opaque, so skip text while it is that faint
        if (bar >= 11 && a > 0.1f) {
            int ty = h - bar + (bar - 8) / 2;
            String left = "SANDEVISTAN // APOGEE";
            String right = String.format(Locale.ROOT, "OVERLOAD %03d%%", Math.round(surge * 100.0f));
            g.drawText(mc.textRenderer, left, 8, ty, argb(NEON, a), false);
            g.drawText(mc.textRenderer, right, w - 8 - mc.textRenderer.getWidth(right), ty, argb(LIME, a), false);
            if ((cutTick & 1) == 0 || surge > 0.8f) { // flickers, steady near the end
                String warn = "WARNING: SPINAL IMPLANT OVERCLOCK";
                int wy = (bar - 8) / 2;
                g.drawText(mc.textRenderer, warn, (w - mc.textRenderer.getWidth(warn)) / 2, wy, argb(ORANGE, a), false);
            }
        }
    }

    // ------------------------------------------------------------------ cut-in: 3D model (view from behind)

    /**
     * Renders the local player with its back to the camera using vanilla's own GUI entity path
     * ({@link InventoryScreen#drawEntity}) which sets up lighting, depth and the vertex consumers correctly.
     * The yaw / pitch fields are overridden only for the duration of the call and always restored.
     *
     * <p>{@code tintR/G/B} multiply the model colour through {@code RenderSystem.setShaderColor} (entity shaders use
     * it as ColorModulator), which gives the "emerging from the dark" fade-in and the dissolve flicker.
     * {@code clipHalf > 0} restricts the model to a vertical slit of that half-width around {@code cx}
     * (GL scissor) which is how the dissolve is done.
     *
     * <p>If anything goes wrong (or the skin is not loaded yet and the renderer throws) a flat silhouette is used.
     */
    private static void drawBackModel(DrawContext g, MinecraftClient mc, int cx, int cy, int size, float t,
                                      float tintR, float tintG, float tintB, float clipHalf) {
        g.draw();
        ClientPlayerEntity p = mc.player;
        if (p == null) {
            return;
        }
        if (modelFailed) {
            drawSilhouette(g, cx, cy, size);
            return;
        }

        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        MatrixStack ms = g.getMatrices();
        ms.push();
        MatrixStack.Entry base = ms.peek();

        float bodyYaw = p.bodyYaw;
        float prevBodyYaw = p.prevBodyYaw;
        float headYaw = p.headYaw;
        float prevHeadYaw = p.prevHeadYaw;
        float yaw = p.getYaw();
        float prevYaw = p.prevYaw;
        float pitch = p.getPitch();
        float prevPitch = p.prevPitch;
        boolean hitboxes = dispatcher.shouldRenderHitboxes();

        boolean ok = false;
        boolean scissor = false;
        try {
            // strictly from behind, with a slow sway for depth
            float sway = BACK_YAW_DEG + MathHelper.sin(t * 0.45f * 26.0f / CUTIN_TICKS) * 7.0f;
            p.setYaw(sway);
            p.prevYaw = sway;
            p.bodyYaw = sway;
            p.prevBodyYaw = sway;
            p.headYaw = sway;
            p.prevHeadYaw = sway;
            p.setPitch(-10.0f); // head slightly thrown back
            p.prevPitch = -10.0f;
            dispatcher.setRenderHitboxes(false);

            // the model needs a clean depth buffer, the rest of the HUD does not use depth
            RenderSystem.enableDepthTest();
            RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
            RenderSystem.setShaderColor(clamp01(tintR), clamp01(tintG), clamp01(tintB), 1.0f);

            if (clipHalf > 0.0f) {
                int x0 = Math.max(0, Math.round(cx - clipHalf));
                int x1 = Math.min(g.getScaledWindowWidth(), Math.round(cx + clipHalf));
                g.enableScissor(x0, 0, Math.max(x0 + 1, x1), g.getScaledWindowHeight());
                scissor = true;
            }

            Quaternionf camera = new Quaternionf().rotateX((float) Math.toRadians(CAMERA_PITCH_DEG));
            Quaternionf flip = new Quaternionf().rotateZ((float) Math.PI);
            flip.mul(camera);
            InventoryScreen.drawEntity(g, cx, cy, size, flip, camera, p);
            ok = true;
        } catch (Throwable ex) {
            modelFailed = true;
            LOGGER.warn("Could not render the player model for the cut-in, using a silhouette instead", ex);
            while (ms.peek() != base) {
                ms.pop();
            }
        } finally {
            p.bodyYaw = bodyYaw;
            p.prevBodyYaw = prevBodyYaw;
            p.headYaw = headYaw;
            p.prevHeadYaw = prevHeadYaw;
            p.setYaw(yaw);
            p.prevYaw = prevYaw;
            p.setPitch(pitch);
            p.prevPitch = prevPitch;
            dispatcher.setRenderHitboxes(hitboxes);
            dispatcher.setRenderShadows(true);
            ms.pop();
            g.draw();
            if (scissor) {
                g.disableScissor();
            }
            RenderSystem.clear(GlConst.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
        if (!ok) {
            drawSilhouette(g, cx, cy, size);
        }
    }

    /** Flat back view used only as a fallback: torso, shoulders, arms and the neck. */
    private static void drawSilhouette(DrawContext g, int cx, int cy, int size) {
        int body = 0xFF0A1A12;
        int rim = argb(NEON, 0.55f);
        int neckTop = Math.round(cy - (NECK + 0.22f) * size);
        int neckBot = Math.round(cy - NECK * size);
        int hipY = Math.round(cy - HIP * size);
        int halfBody = Math.round(0.235f * size);
        int halfNeck = Math.round(0.11f * size);
        int armW = Math.round(0.235f * size);

        g.fill(cx - halfNeck, neckTop, cx + halfNeck, neckBot, body);
        g.fill(cx - halfBody, neckBot, cx + halfBody, hipY, body);
        g.fill(cx - halfBody - armW, neckBot, cx - halfBody, hipY, 0xFF0D2218);
        g.fill(cx + halfBody, neckBot, cx + halfBody + armW, hipY, 0xFF0D2218);
        g.fill(cx - halfBody, neckBot, cx - halfBody + 1, hipY, rim);
        g.fill(cx + halfBody - 1, neckBot, cx + halfBody, hipY, rim);
        g.fill(cx - halfBody - armW, neckBot, cx + halfBody + armW, neckBot + 1, rim);
        g.draw();
    }

    // ------------------------------------------------------------------ cut-in: spine ignition (phase 2)

    /**
     * Neon implant along the spine, drawn in the same (rolled) screen space as the model.
     *
     * <ul>
     *   <li>{@link #SPINE_SEGMENTS} vertebrae are visible as dark frames from the very start and ignite one after
     *       another, bottom to top, between {@link #SURGE_START} and {@link #SURGE_END}.</li>
     *   <li>Each ignition: white-hot snap-on + horizontal flare, then it relaxes to steady neon green. A neon ring
     *       expands from it and electric arcs jump sideways for a few ticks.</li>
     *   <li>While the neck charges ({@code charge} 0..1) all segments pulse and a white column builds on the neck.</li>
     * </ul>
     * Everything is additive (SRC_ALPHA, ONE) and batched into a single draw call.
     */
    private static void drawSpineSurge(DrawContext g, int cx, int cy, float size, float t, float a) {
        if (a <= 0.003f) {
            return;
        }
        final float hipY = cy - HIP * size;
        final float neckY = cy - NECK * size;
        final float p = clamp01((t - SURGE_START) / (SURGE_END - SURGE_START)); // linear: slow and readable
        final float headY = MathHelper.lerp(p, hipY, neckY);
        final float charge = clamp01((t - SURGE_END) / (BLAST_TICK - SURGE_END));
        final int frame = (int) (t * 3.0f * 26.0f / CUTIN_TICKS); // arcs flicker ~3x per reference tick
        final float flareLen = scaled(1.6f);
        final float ringLife = scaled(3.2f);
        final float arcLife = scaled(3.0f);
        final float glow = Math.max(2.5f, size * 0.006f);
        final float core = Math.max(1.0f, size * 0.0022f);

        Add.begin(g);

        // holographic scan line sweeping up the body while the model fades in (phase 1)
        if (t < FADE_END + scaled(1.0f)) {
            float s = clamp01(t / FADE_END);
            float sy = MathHelper.lerp(s, hipY + 0.15f * size, neckY - 0.05f * size);
            float sa = (1.0f - smoothstep(FADE_END - scaled(1.0f), FADE_END + scaled(1.0f), t)) * a;
            Add.rect(cx - 0.52f * size, sy - 1.0f, cx + 0.52f * size, sy + 1.0f, argb(FLASH, 0.55f * sa));
            Add.quad(cx - 0.52f * size, sy - 0.10f * size, cx + 0.52f * size, sy, argb(NEON, 0.0f), argb(NEON, 0.16f * sa));
        }

        // faint dormant track along the whole spine
        Add.quad(cx - 0.006f * size, neckY, cx + 0.006f * size, hipY, argb(NEON, 0.16f * a), argb(NEON, 0.16f * a));

        // electric beam from the hips to the head of the ignition front
        if (p > 0.005f) {
            Add.quad(cx - 0.080f * size, headY, cx + 0.080f * size, hipY, argb(NEON, 0.07f * a), argb(NEON, 0.0f));
            Add.quad(cx - 0.036f * size, headY, cx + 0.036f * size, hipY, argb(NEON, 0.14f * a), argb(NEON, 0.02f * a));
            Add.quad(cx - 0.014f * size, headY, cx + 0.014f * size, hipY, argb(NEON, 0.30f * a), argb(NEON, 0.04f * a));
            Add.quad(cx - 0.005f * size, headY, cx + 0.005f * size, hipY, argb(FLASH, 0.85f * a), argb(NEON, 0.22f * a));
        }

        // vertebrae
        final int n = SPINE_SEGMENTS;
        for (int i = 0; i < n; i++) {
            float u = (float) i / (n - 1);
            float y = MathHelper.lerp(u, hipY, neckY);
            float ti = MathHelper.lerp(u, SURGE_START, SURGE_END); // ignition moment of this vertebra
            float age = t - ti;
            float hw = 0.050f * size * (1.0f - 0.28f * u);
            float hh = 0.017f * size;

            if (age < 0.0f) { // dormant: dark frame, barely visible
                Add.rect(cx - hw, y - hh, cx + hw, y + hh, argb(NEON, 0.07f * a));
                Add.frame(cx - hw, y - hh, cx + hw, y + hh, 1.0f, argb(NEON, 0.22f * a));
                continue;
            }

            float flare = clamp01(1.0f - age / flareLen);
            float pop = 1.0f + 0.45f * flare * flare;
            float b = MathHelper.lerp(flare, 0.72f + 0.28f * charge, 1.0f) * a;
            if (charge > 0.0f) {
                b *= 0.85f + 0.15f * MathHelper.sin(t * 4.0f); // overload pulse
            }
            int coreColor = mix(NEON, FLASH, 0.30f + 0.70f * flare);

            float gw = hw * pop;
            float gh = hh * pop;
            Add.rect(cx - gw * 2.6f, y - gh * 3.2f, cx + gw * 2.6f, y + gh * 3.2f, argb(NEON, 0.16f * b));
            Add.rect(cx - gw * 1.5f, y - gh * 1.8f, cx + gw * 1.5f, y + gh * 1.8f, argb(NEON, 0.40f * b));
            Add.rect(cx - gw, y - gh, cx + gw, y + gh, argb(coreColor, b));
            Add.frame(cx - gw * 1.25f, y - gh * 1.4f, cx + gw * 1.25f, y + gh * 1.4f, 1.0f, argb(LIME, 0.65f * b));
            // mechanical ribs left and right of the vertebra
            Add.rect(cx - 0.11f * size, y - 0.0025f * size, cx - gw, y + 0.0025f * size, argb(LIME, 0.55f * b));
            Add.rect(cx + gw, y - 0.0025f * size, cx + 0.11f * size, y + 0.0025f * size, argb(LIME, 0.55f * b));
            Add.rect(cx - 0.11f * size, y - 0.012f * size, cx - 0.105f * size, y + 0.012f * size, argb(LIME, 0.55f * b));
            Add.rect(cx + 0.105f * size, y - 0.012f * size, cx + 0.11f * size, y + 0.012f * size, argb(LIME, 0.55f * b));
            // ignition flare: a white horizontal streak that shrinks away
            if (flare > 0.02f) {
                float fl = 0.36f * size * flare;
                Add.quad(cx - fl, y - 0.004f * size, cx, y + 0.004f * size, argb(FLASH, 0.0f), argb(FLASH, 0.0f));
                Add.rect(cx - fl, y - 0.0035f * size, cx + fl, y + 0.0035f * size, argb(FLASH, 0.45f * flare * a));
                Add.rect(cx - fl * 0.5f, y - 0.0015f * size, cx + fl * 0.5f, y + 0.0015f * size, argb(FLASH, 0.9f * flare * a));
            }

            // expanding neon ring (perspective squashed ellipse) born at this vertebra
            float ra = age / ringLife;
            if (ra < 1.0f) {
                float radius = size * (0.10f + 0.50f * easeOutCubic(ra));
                float alpha = (float) Math.pow(1.0f - ra, 1.2f) * 0.95f * a;
                float thick = size * 0.010f * (1.3f - ra);
                Add.ring(cx, y, radius, radius * 0.24f, thick, argb(NEON, alpha * 0.35f), thick * 3.0f);
                Add.ring(cx, y, radius, radius * 0.24f, thick, argb(FLASH, alpha), thick);
            }

            // electric arcs jumping off the vertebra that just lit up
            if (age < arcLife) {
                Random r = new Random(i * 7919L + frame * 131L);
                float fade = 1.0f - age / arcLife;
                for (int dir = -1; dir <= 1; dir += 2) {
                    if (r.nextFloat() > 0.85f) {
                        continue;
                    }
                    float len = size * (0.14f + 0.30f * r.nextFloat()) * (0.6f + 0.4f * fade);
                    float ax = cx + dir * hw;
                    float ay = y;
                    float mx = ax;
                    float my = ay;
                    for (int s = 0; s < 5; s++) {
                        float nx = ax + dir * len / 5.0f;
                        float ny = ay + (r.nextFloat() - 0.5f) * size * 0.10f;
                        Add.line(ax, ay, nx, ny, glow, argb(NEON, 0.30f * fade * a));
                        Add.line(ax, ay, nx, ny, core, argb(FLASH, 0.95f * fade * a));
                        if (s == 2) {
                            mx = nx;
                            my = ny;
                        }
                        ax = nx;
                        ay = ny;
                    }
                    // small fork
                    float fx = mx + dir * size * 0.07f;
                    float fy = my + (r.nextBoolean() ? -1.0f : 1.0f) * size * 0.08f;
                    Add.line(mx, my, fx, fy, glow * 0.7f, argb(NEON, 0.25f * fade * a));
                    Add.line(mx, my, fx, fy, core, argb(FLASH, 0.80f * fade * a));
                }
            }
        }

        // flare at the head of the ignition front
        if (p > 0.02f && t < BLAST_TICK) {
            float fh = 0.012f * size;
            Add.rect(cx - 0.34f * size, headY - fh, cx + 0.34f * size, headY + fh, argb(NEON, 0.12f * a));
            Add.rect(cx - 0.20f * size, headY - fh * 0.7f, cx + 0.20f * size, headY + fh * 0.7f, argb(NEON, 0.26f * a));
            Add.rect(cx - 0.10f * size, headY - fh * 0.4f, cx + 0.10f * size, headY + fh * 0.4f, argb(FLASH, 0.65f * a));
            Add.quad(cx - 0.012f * size, headY - 0.16f * size, cx + 0.012f * size, headY,
                    argb(NEON, 0.0f), argb(FLASH, 0.80f * a));
        }

        // overload charging in the neck
        if (charge > 0.0f) {
            float s = size * (0.10f + 0.35f * charge);
            Add.rect(cx - s, neckY - s * 0.25f, cx + s, neckY + s * 0.25f, argb(NEON, 0.22f * charge * a));
            Add.rect(cx - s * 0.5f, neckY - s * 0.12f, cx + s * 0.5f, neckY + s * 0.12f, argb(FLASH, 0.50f * charge * a));
            Add.quad(cx - 0.03f * size * (1.0f + charge), neckY - 0.35f * size * charge,
                    cx + 0.03f * size * (1.0f + charge), neckY + 0.05f * size,
                    argb(FLASH, 0.0f), argb(FLASH, 0.60f * charge * a));
        }

        Add.end();
    }

    // ------------------------------------------------------------------ cut-in: overload explosion (phase 3)

    /**
     * Radial streaks (radial blur imitation), shock rings, a glowing core on the neck and, while the model
     * dissolves, bright slit edges plus flying sparks. {@code a} is the overall strength (config intensity).
     */
    private static void drawBlast(DrawContext g, int cx, int cy, float size, float t, float a,
                                  float dissolve, float clipHalf) {
        final float neckY = cy - NECK * size;
        final float hipY = cy - HIP * size;
        final float charge = clamp01((t - SURGE_END) / (BLAST_TICK - SURGE_END));
        final float x = t < BLAST_TICK ? 0.0f : clamp01((t - BLAST_TICK) / (CUTIN_TICKS - BLAST_TICK));
        final float e;
        if (t < SURGE_END) {
            e = 0.0f;
        } else if (t < BLAST_TICK) {
            e = 0.35f * charge;
        } else {
            e = (float) Math.pow(1.0f - x, 1.3);
        }
        if (e <= 0.01f) {
            return;
        }

        Add.begin(g);

        // glowing core
        float coreR = size * (0.12f + 0.55f * easeOutCubic(x) + 0.10f * charge);
        Add.disc(cx, neckY, coreR, coreR, argb(FLASH, 0.80f * e * a), argb(NEON, 0.0f));
        Add.disc(cx, neckY, coreR * 0.45f, coreR * 0.45f, argb(FLASH, 0.95f * e * a), argb(FLASH, 0.0f));

        // radial streaks: the radial blur / speed burst
        Random rs = new Random(4242L);
        for (int i = 0; i < 64; i++) {
            float ang = rs.nextFloat() * (float) (Math.PI * 2.0);
            float lenRand = 0.4f + rs.nextFloat() * 1.0f;
            float r0 = size * (0.06f + 0.55f * easeOutCubic(x)) * (0.6f + 0.4f * rs.nextFloat());
            float r1 = r0 + size * lenRand * (0.5f + 0.8f * e);
            float cs = (float) Math.cos(ang);
            float sn = (float) Math.sin(ang);
            Add.streak(cx + cs * r0, neckY + sn * r0, cx + cs * r1, neckY + sn * r1,
                    size * 0.007f, 0.0f, argb(FLASH, 0.85f * e * a), argb(NEON, 0.0f));
        }

        // shock rings
        if (t >= BLAST_TICK) {
            for (int j = 0; j < 3; j++) {
                float delay = j * 0.12f;
                if (x < delay) {
                    continue;
                }
                float xx = clamp01((x - delay) / (1.0f - delay));
                float radius = size * (0.10f + 1.5f * easeOutCubic(xx));
                float alpha = (float) Math.pow(1.0f - xx, 1.5f) * 0.9f * a;
                float thick = size * 0.012f * (1.2f - xx);
                Add.ring(cx, neckY, radius, radius * 0.55f, thick, argb(NEON, alpha * 0.40f), thick * 3.5f);
                Add.ring(cx, neckY, radius, radius * 0.55f, thick, argb(FLASH, alpha), thick);
            }
        }

        // dissolve: glowing slit edges + flying sparks
        if (dissolve > 0.0f && dissolve < 1.0f) {
            float top = -50.0f;
            float bottom = g.getScaledWindowHeight() + 50.0f;
            if (clipHalf > 1.0f) {
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    float ex = cx + sgn * clipHalf;
                    Add.quad(ex - 6.0f, top, ex + 6.0f, bottom, argb(NEON, 0.18f * (1.0f - dissolve) * a), argb(NEON, 0.18f * (1.0f - dissolve) * a));
                    Add.quad(ex - 1.5f, top, ex + 1.5f, bottom, argb(FLASH, 0.85f * (1.0f - dissolve) * a), argb(FLASH, 0.85f * (1.0f - dissolve) * a));
                }
            }
            Random d = new Random(99L);
            for (int i = 0; i < 40; i++) {
                float side = d.nextBoolean() ? 1.0f : -1.0f;
                float yy = MathHelper.lerp(d.nextFloat(), neckY, hipY);
                float sp = 0.10f + 0.50f * d.nextFloat();
                float rise = 0.30f * d.nextFloat();
                float sz = size * (0.006f + 0.014f * d.nextFloat());
                boolean white = d.nextBoolean();
                float px = cx + side * (Math.max(clipHalf, 0.0f) + sp * size * dissolve);
                float py = yy - rise * size * dissolve;
                Add.rect(px - sz, py - sz, px + sz, py + sz, argb(white ? FLASH : NEON, 0.90f * (1.0f - dissolve) * a));
            }
        }

        Add.end();
    }

    // ------------------------------------------------------------------ cut-in: additive batch helper

    /**
     * Minimal additive-blend batch (POSITION_COLOR quads, SRC_ALPHA + ONE). Every quad is emitted with both
     * windings, so the result does not depend on whether face culling happens to be enabled. The matrix of the
     * DrawContext at {@link #begin} time is used, so rolls / shakes apply automatically.
     */
    private static final class Add {
        private static BufferBuilder buf;
        private static Matrix4f mat;
        private static int count;

        static void begin(DrawContext g) {
            g.draw();
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            mat = g.getMatrices().peek().getPositionMatrix();
            buf = Tessellator.getInstance().getBuffer();
            buf.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            count = 0;
        }

        static void end() {
            if (buf == null) {
                return;
            }
            BufferBuilder b = buf;
            buf = null;
            if (count > 0) {
                BufferRenderer.drawWithGlobalProgram(b.end());
            } else {
                b.end().release();
            }
            RenderSystem.defaultBlendFunc();
        }

        /** Emergency cleanup if an exception interrupted a batch: the shared Tessellator must not stay open. */
        static void abort() {
            if (buf != null) {
                try {
                    if (buf.isBuilding()) {
                        buf.reset();
                    }
                } catch (Throwable ignored) {
                    // nothing else we can do
                }
                buf = null;
            }
        }

        private static void v(float x, float y, int argb) {
            buf.vertex(mat, x, y, 0.0f)
                    .color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF)
                    .next();
        }

        /** Quad with a vertical colour gradient; y0 is the top edge, y1 the bottom edge. */
        static void quad(float x0, float y0, float x1, float y1, int top, int bottom) {
            v(x0, y0, top);
            v(x1, y0, top);
            v(x1, y1, bottom);
            v(x0, y1, bottom);
            v(x0, y0, top);
            v(x0, y1, bottom);
            v(x1, y1, bottom);
            v(x1, y0, top);
            count++;
        }

        static void rect(float x0, float y0, float x1, float y1, int color) {
            quad(x0, y0, x1, y1, color, color);
        }

        /** Hollow rectangle outline with the given border thickness. */
        static void frame(float x0, float y0, float x1, float y1, float th, int color) {
            rect(x0, y0, x1, y0 + th, color);
            rect(x0, y1 - th, x1, y1, color);
            rect(x0, y0 + th, x0 + th, y1 - th, color);
            rect(x1 - th, y0 + th, x1, y1 - th, color);
        }

        /** Free quad from four corner points (used for lines and ring segments). */
        private static void poly(float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy, int color) {
            v(ax, ay, color);
            v(bx, by, color);
            v(cx, cy, color);
            v(dx, dy, color);
            v(ax, ay, color);
            v(dx, dy, color);
            v(cx, cy, color);
            v(bx, by, color);
            count++;
        }

        static void line(float x0, float y0, float x1, float y1, float thickness, int color) {
            float dx = x1 - x0;
            float dy = y1 - y0;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.001f) {
                return;
            }
            float nx = -dy / len * thickness * 0.5f;
            float ny = dx / len * thickness * 0.5f;
            poly(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, color);
        }

        /** Tapered beam with a colour gradient along its length (radial streaks). */
        static void streak(float x0, float y0, float x1, float y1, float w0, float w1, int c0, int c1) {
            float dx = x1 - x0;
            float dy = y1 - y0;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.001f) {
                return;
            }
            float nx = -dy / len;
            float ny = dx / len;
            float ax = x0 + nx * w0 * 0.5f, ay = y0 + ny * w0 * 0.5f;
            float bx = x1 + nx * w1 * 0.5f, by = y1 + ny * w1 * 0.5f;
            float cx = x1 - nx * w1 * 0.5f, cy = y1 - ny * w1 * 0.5f;
            float ex = x0 - nx * w0 * 0.5f, ey = y0 - ny * w0 * 0.5f;
            v(ax, ay, c0);
            v(bx, by, c1);
            v(cx, cy, c1);
            v(ex, ey, c0);
            v(ax, ay, c0);
            v(ex, ey, c0);
            v(cx, cy, c1);
            v(bx, by, c1);
            count++;
        }

        /** Soft radial glow: a triangle fan (as degenerate quads), centre colour fading to edge colour. */
        static void disc(float cx, float cy, float rx, float ry, int center, int edge) {
            final int seg = 32;
            for (int i = 0; i < seg; i++) {
                double a0 = (Math.PI * 2.0) * i / seg;
                double a1 = (Math.PI * 2.0) * (i + 1) / seg;
                float x0 = cx + (float) Math.cos(a0) * rx;
                float y0 = cy + (float) Math.sin(a0) * ry;
                float x1 = cx + (float) Math.cos(a1) * rx;
                float y1 = cy + (float) Math.sin(a1) * ry;
                v(cx, cy, center);
                v(x0, y0, edge);
                v(x1, y1, edge);
                v(cx, cy, center);
                v(cx, cy, center);
                v(x1, y1, edge);
                v(x0, y0, edge);
                v(cx, cy, center);
                count++;
            }
        }

        /** Elliptical ring made of 40 quads. {@code spread} widens the band, {@code thickness} is the half-width. */
        static void ring(float cx, float cy, float rx, float ry, float thickness, int color, float spread) {
            final int seg = 40;
            float th = Math.max(spread, thickness) * 0.5f;
            float thY = th * 0.6f;
            for (int i = 0; i < seg; i++) {
                double a0 = (Math.PI * 2.0) * i / seg;
                double a1 = (Math.PI * 2.0) * (i + 1) / seg;
                float c0 = (float) Math.cos(a0);
                float s0 = (float) Math.sin(a0);
                float c1 = (float) Math.cos(a1);
                float s1 = (float) Math.sin(a1);
                poly(cx + c0 * (rx + th), cy + s0 * (ry + thY),
                        cx + c1 * (rx + th), cy + s1 * (ry + thY),
                        cx + c1 * (rx - th), cy + s1 * (ry - thY),
                        cx + c0 * (rx - th), cy + s0 * (ry - thY), color);
            }
        }
    }

    // ------------------------------------------------------------------ math helpers

    private static float clamp01(float x) {
        return MathHelper.clamp(x, 0.0f, 1.0f);
    }

    private static float easeOutCubic(float x) {
        float f = 1.0f - clamp01(x);
        return 1.0f - f * f * f;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float f = clamp01((x - edge0) / (edge1 - edge0));
        return f * f * (3.0f - 2.0f * f);
    }

    /** Linear RGB mix of two 0xRRGGBB colours. */
    private static int mix(int c0, int c1, float f) {
        f = clamp01(f);
        int r = Math.round(MathHelper.lerp(f, (c0 >> 16) & 0xFF, (c1 >> 16) & 0xFF));
        int gr = Math.round(MathHelper.lerp(f, (c0 >> 8) & 0xFF, (c1 >> 8) & 0xFF));
        int b = Math.round(MathHelper.lerp(f, c0 & 0xFF, c1 & 0xFF));
        return (r << 16) | (gr << 8) | b;
    }

    // ------------------------------------------------------------------ screen effects

    private static void renderScreenEffects(DrawContext g, float td, ClientConfig cfg) {
        float intensity = cfg.intensity;
        if (intensity <= 0.01f) {
            return;
        }
        int w = g.getScaledWindowWidth();
        int h = g.getScaledWindowHeight();
        float blend = ApogeeFx.blend(td);

        if (cfg.screenOverlay && blend > 0.01f) {
            renderVignette(g, w, h, blend * intensity);
            renderSpeedLines(g, w, h, ApogeeFx.speedLines(td) * blend * intensity);
        }
        if (cfg.screenFlash) {
            float flash = ApogeeFx.flash(td);
            if (flash > 0.01f) {
                int color = ApogeeFx.flashKind() == 0 ? 0xFFB070 : 0x60C8FF;
                g.fill(0, 0, w, h, argb(color, flash * 0.30f * intensity));
            }
        }
    }

    private static void renderVignette(DrawContext g, int w, int h, float strength) {
        float pulse = 0.85f + 0.15f * MathHelper.sin(ApogeeFx.time() * 0.12f);
        float a = 0.30f * strength * pulse;
        int stripH = Math.max(8, h / 6);
        g.fillGradient(0, 0, w, stripH, argb(CYAN, a), argb(CYAN, 0.0f));
        g.fillGradient(0, h - stripH, w, h, argb(ORANGE, 0.0f), argb(ORANGE, a));
        int stripW = Math.max(8, w / 10);
        int steps = 10;
        for (int i = 0; i < steps; i++) {
            float k = 1.0f - (float) i / steps;
            int x0 = i * stripW / steps;
            int x1 = (i + 1) * stripW / steps;
            int colorLeft = argb(ORANGE, a * 0.9f * k * k);
            int colorRight = argb(CYAN, a * 0.9f * k * k);
            g.fill(x0, 0, x1, h, colorLeft);
            g.fill(w - x1, 0, w - x0, h, colorRight);
        }
    }

    private static void renderSpeedLines(DrawContext g, int w, int h, float strength) {
        if (strength < 0.02f) {
            return;
        }
        int count = Math.round(36 * Math.min(1.0f, strength * 1.3f));
        float minDim = Math.min(w, h);
        float time = ApogeeFx.time() * 0.05f;
        g.getMatrices().push();
        g.getMatrices().translate(w / 2.0f, h / 2.0f, 0.0f);
        for (int i = 0; i < count; i++) {
            float angle = i * (360.0f / 36.0f) + (i * 37 % 11);
            float wobble = 0.5f + 0.5f * MathHelper.sin(time + i * 1.7f);
            int r0 = (int) (minDim * (0.34f + 0.10f * ((i * 53) % 7) / 7.0f));
            int len = (int) (minDim * (0.08f + 0.14f * wobble));
            boolean warm = i % 4 == 0;
            int color = argb(warm ? ORANGE : CYAN, 0.22f * strength * (0.5f + 0.5f * wobble));
            g.getMatrices().push();
            g.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotationDegrees(angle));
            g.fill(r0, 0, r0 + len, 1, color);
            g.getMatrices().pop();
        }
        g.getMatrices().pop();
    }

    // ------------------------------------------------------------------ panel

    private static void renderPanel(DrawContext g, MinecraftClient mc, float td, ClientConfig cfg) {
        final int w = 132;
        final int h = 30;
        int sw = g.getScaledWindowWidth();
        int sh = g.getScaledWindowHeight();
        String anchor = cfg.hudAnchor.toUpperCase(Locale.ROOT);
        int x = anchor.endsWith("RIGHT") ? sw - w - cfg.hudOffsetX : cfg.hudOffsetX;
        int y = anchor.startsWith("BOTTOM") ? sh - h - cfg.hudOffsetY : cfg.hudOffsetY;

        boolean has = hasImplant(mc.player);
        int active = ClientState.activeLeft;
        int cooldown = ClientState.cooldownLeft;

        int accent;
        Text label;
        String timeText = "";
        float fill;
        float borderAlpha = 1.0f;

        if (active > 0) {
            float left = Math.max(0.0f, active - td);
            accent = ORANGE;
            label = Text.translatable("hud.sandevistan_apogee.active");
            timeText = String.format(Locale.ROOT, "%.1fs", left / 20.0f);
            fill = left / Math.max(1, ClientState.activeTotal);
        } else if (!has) {
            accent = GREY;
            label = Text.translatable("hud.sandevistan_apogee.missing");
            fill = 0.0f;
        } else if (cooldown > 0) {
            float left = Math.max(0.0f, cooldown - td);
            accent = CYAN;
            label = Text.translatable("hud.sandevistan_apogee.cooldown");
            timeText = String.format(Locale.ROOT, "%.1fs", left / 20.0f);
            fill = 1.0f - left / Math.max(1, ClientState.cooldownTotal);
        } else {
            accent = READY;
            Text key = ApogeeClient.activateKey.getBoundKeyLocalizedText();
            label = Text.translatable("hud.sandevistan_apogee.ready", key);
            fill = 1.0f;
            double phase = (System.currentTimeMillis() % 2400L) / 2400.0 * Math.PI * 2.0;
            borderAlpha = 0.65f + 0.35f * (float) Math.sin(phase);
        }

        int bg = ClientState.readyFlash > 0 ? 0xC0102A33 : 0xB00A0E1A;
        g.fill(x, y, x + w, y + h, bg);
        int border = argb(accent, borderAlpha);
        g.fill(x, y, x + w, y + 1, border);
        g.fill(x, y + h - 1, x + w, y + h, border);
        g.fill(x, y, x + 1, y + h, border);
        g.fill(x + w - 1, y, x + w, y + h, border);

        if (icon == null) {
            icon = new ItemStack(ApogeeItems.SANDEVISTAN);
        }
        g.drawItem(icon, x + 4, y + 7);

        int textColor = 0xFF000000 | accent;
        g.drawText(mc.textRenderer, label, x + 24, y + 5, textColor, true);
        if (!timeText.isEmpty()) {
            int tw = mc.textRenderer.getWidth(timeText);
            g.drawText(mc.textRenderer, timeText, x + w - 5 - tw, y + 5, 0xFFFFFFFF, true);
        }

        int bx0 = x + 24;
        int bx1 = x + w - 6;
        int by0 = y + 19;
        g.fill(bx0, by0, bx1, by0 + 4, 0xFF1A2030);
        int filled = bx0 + Math.round((bx1 - bx0) * MathHelper.clamp(fill, 0.0f, 1.0f));
        if (filled > bx0) {
            g.fill(bx0, by0, filled, by0 + 4, 0xFF000000 | accent);
            g.fill(bx0, by0, filled, by0 + 1, argb(0xFFFFFF, 0.35f));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean hasImplant(ClientPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (player.getInventory().getStack(i).isOf(ApogeeItems.SANDEVISTAN)) {
                return true;
            }
        }
        return false;
    }

    static int argb(int rgb, float alpha) {
        int a = MathHelper.clamp(Math.round(alpha * 255.0f), 0, 255);
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
