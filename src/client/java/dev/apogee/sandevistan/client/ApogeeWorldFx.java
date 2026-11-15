package dev.apogee.sandevistan.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.apogee.sandevistan.ApogeeMod;
import dev.apogee.sandevistan.ApogeeParticles;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModel;
import dev.apogee.sandevistan.client.mixin.LivingEntityRendererAccessor;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Client-only world effects (neon green / Edgerunners palette):
 *  - afterimages: translucent, emissive silhouettes of the player model in earlier positions
 *    (the vanilla player model is rendered directly; no entities are spawned, the skin is not modified);
 *  - shockwave rings, electric arcs (camera-facing ribbons, additive blending);
 *  - sparks (custom particle);
 *  - bio-mechanical body pulses: an energy impulse running along the spine on activation (bottom -> top)
 *    and a discharge on deactivation (top -> bottom, fading and dispersing sideways).
 * Trail state of other players comes from the server (start / refresh / end), snapshots are taken locally,
 * so nothing is sent every frame.
 */
public final class ApogeeWorldFx {
    private ApogeeWorldFx() {
    }

    private static final Identifier GHOST_TEXTURE = ApogeeMod.id("textures/entity/ghost.png");
    private static final Random RNG = new Random();

    private static final class Snapshot {
        double x, y, z;
        float bodyYaw, headYaw, pitch, limbPos, limbSpeed, age;
        boolean sneaking;
        int tick;
        int life;
    }

    private static final class Arc {
        int entityId;
        Vec3d[] offsets;
        int born;
        int life;
        boolean warm;
    }

    private static final class Shock {
        double x, y, z;
        int born;
        int life;
        float maxRadius;
        boolean cool;
    }

    /** Body pulse: activation (energy climbs the spine) or deactivation (energy is dumped downwards). */
    private static final class Pulse {
        int entityId;
        boolean activation;
        int born;
        int life;
        long seed;
    }

    private static final Map<Integer, ArrayDeque<Snapshot>> TRAILS = new HashMap<>();
    private static final List<Arc> ARCS = new ArrayList<>();
    private static final List<Shock> SHOCKS = new ArrayList<>();
    private static final List<Pulse> PULSES = new ArrayList<>();
    /** Entities that were "active" on the previous tick: used to detect activation / deactivation edges. */
    private static final Set<Integer> ACTIVE_PREV = new HashSet<>();
    private static int tickCount;

    // ---- pulse tuning ----
    private static final int PULSE_LIFE_ON = 8;    // ~0.4 s, sharp
    private static final int PULSE_LIFE_OFF = 12;  // ~0.6 s, slower dissipation
    private static final float BODY_H = 1.95f;     // pulse travel height (feet -> above the head), blocks
    private static final int PULSE_RINGS = 3;

    // ---- neon green palette (R, G, B) ----
    /** Main matrix / edgerunners green. */
    private static final float[] NEON = {0.10f, 1.00f, 0.20f};
    /** Bright lime highlight (young ghosts, arc tips, glow pass). */
    private static final float[] LIME = {0.30f, 1.00f, 0.45f};
    /** Deep green (old ghosts, arc roots, shock walls). */
    private static final float[] DEEP = {0.00f, 0.55f, 0.12f};
    /** Yellowish lime for "warm" arcs and the non-cooldown shockwave. */
    private static final float[] ACID = {0.62f, 1.00f, 0.18f};
    /** Pale green-white core of electric arcs. */
    private static final float[] CORE = {0.85f, 1.00f, 0.88f};

    /** Scratch colour to avoid allocations every frame (render thread only). */
    private static final float[] TMP = new float[3];

    public static void reset() {
        TRAILS.clear();
        ARCS.clear();
        SHOCKS.clear();
        PULSES.clear();
        ACTIVE_PREV.clear();
        tickCount = 0;
    }

    public static void addShock(Vec3d pos, boolean cool) {
        if (ClientConfig.get().intensity <= 0.01f) {
            return;
        }
        if (SHOCKS.size() > 8) {
            SHOCKS.remove(0);
        }
        Shock shock = new Shock();
        shock.x = pos.x;
        shock.y = pos.y;
        shock.z = pos.z;
        shock.born = tickCount;
        shock.life = cool ? 12 : 16;
        shock.maxRadius = cool ? 6.0f : 12.0f;
        shock.cool = cool;
        SHOCKS.add(shock);
    }

    /**
     * Starts a body pulse for the given player. Normally you do NOT need to call this manually:
     * {@link #tick} detects activation / deactivation edges by itself. Duplicates within 4 ticks are ignored,
     * so it is safe to also call it from a network handler.
     *
     * @param activation true = bottom-to-top activation impulse, false = top-to-bottom discharge
     */
    public static void addPulse(int entityId, boolean activation) {
        if (ClientConfig.get().intensity <= 0.01f) {
            return;
        }
        for (Pulse other : PULSES) {
            if (other.entityId == entityId && other.activation == activation && tickCount - other.born < 4) {
                return;
            }
        }
        if (PULSES.size() >= 16) {
            PULSES.remove(0);
        }
        Pulse pulse = new Pulse();
        pulse.entityId = entityId;
        pulse.activation = activation;
        pulse.born = tickCount;
        pulse.life = activation ? PULSE_LIFE_ON : PULSE_LIFE_OFF;
        pulse.seed = RNG.nextLong();
        PULSES.add(pulse);
    }

    // ------------------------------------------------------------------ ticking

    public static void tick(MinecraftClient mc) {
        tickCount++;
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) {
            return;
        }
        ClientConfig cfg = ClientConfig.get();
        float intensity = cfg.intensity;

        List<Integer> ids = new ArrayList<>();
        if (ClientState.activeLeft > 0) {
            ids.add(mc.player.getId());
        }
        ids.addAll(ClientState.remote.keySet());

        // activation / deactivation edge detection -> body pulses
        Set<Integer> activeNow = new HashSet<>(ids);
        for (int id : activeNow) {
            if (!ACTIVE_PREV.contains(id)) {
                addPulse(id, true);
            }
        }
        for (int id : ACTIVE_PREV) {
            if (!activeNow.contains(id)) {
                addPulse(id, false);
            }
        }
        ACTIVE_PREV.clear();
        ACTIVE_PREV.addAll(activeNow);

        Vec3d camPos = mc.gameRenderer.getCamera().getPos();
        for (int id : ids) {
            Entity entity = world.getEntityById(id);
            if (!(entity instanceof AbstractClientPlayerEntity player) || !player.isAlive()) {
                continue;
            }
            if (camPos.squaredDistanceTo(player.getPos()) > 48.0 * 48.0) {
                continue;
            }
            if (cfg.afterimages && intensity > 0.01f) {
                record(player, intensity);
            }
            if (cfg.arcs && intensity > 0.15f && tickCount % 2 == 0) {
                spawnArcs(player, intensity);
            }
            if (cfg.particles && intensity > 0.05f) {
                spawnSparks(world, player, intensity);
            }
        }

        if (cfg.particles && intensity > 0.05f) {
            for (Pulse pulse : PULSES) {
                Entity entity = world.getEntityById(pulse.entityId);
                if (entity instanceof AbstractClientPlayerEntity player && player.isAlive()
                        && camPos.squaredDistanceTo(player.getPos()) <= 48.0 * 48.0) {
                    spawnPulseSparks(world, player, pulse, intensity);
                }
            }
        }

        Iterator<Map.Entry<Integer, ArrayDeque<Snapshot>>> it = TRAILS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ArrayDeque<Snapshot>> entry = it.next();
            ArrayDeque<Snapshot> queue = entry.getValue();
            while (!queue.isEmpty() && tickCount - queue.peekFirst().tick >= queue.peekFirst().life) {
                queue.pollFirst();
            }
            if (queue.isEmpty() && !ids.contains(entry.getKey())) {
                it.remove();
            }
        }
        ARCS.removeIf(arc -> tickCount - arc.born >= arc.life);
        SHOCKS.removeIf(shock -> tickCount - shock.born >= shock.life);
        PULSES.removeIf(pulse -> tickCount - pulse.born >= pulse.life);
    }

    private static void record(AbstractClientPlayerEntity player, float intensity) {
        ArrayDeque<Snapshot> queue = TRAILS.computeIfAbsent(player.getId(), id -> new ArrayDeque<>());
        Snapshot last = queue.peekLast();
        if (last != null) {
            double dx = last.x - player.getX();
            double dy = last.y - player.getY();
            double dz = last.z - player.getZ();
            if (dx * dx + dy * dy + dz * dz < 0.03) {
                return; // standing still: no new afterimages
            }
        }
        Snapshot s = new Snapshot();
        s.x = player.getX();
        s.y = player.getY();
        s.z = player.getZ();
        s.bodyYaw = player.bodyYaw;
        s.headYaw = player.headYaw;
        s.pitch = player.getPitch();
        s.limbPos = player.limbAnimator.getPos(1.0f);
        s.limbSpeed = Math.min(1.0f, player.limbAnimator.getSpeed(1.0f));
        s.age = player.age;
        s.sneaking = player.isInSneakingPose();
        s.tick = tickCount;
        s.life = Math.round(12 + 9 * intensity);
        queue.addLast(s);
        int max = 8 + Math.round(12 * intensity);
        while (queue.size() > max) {
            queue.pollFirst();
        }
    }

    private static void spawnArcs(AbstractClientPlayerEntity player, float intensity) {
        if (ARCS.size() > 40) {
            return;
        }
        int count = Math.max(1, Math.round(2 * intensity));
        for (int n = 0; n < count; n++) {
            Arc arc = new Arc();
            arc.entityId = player.getId();
            arc.born = tickCount;
            arc.life = 4 + RNG.nextInt(3);
            arc.warm = RNG.nextFloat() < 0.35f;
            double angle = RNG.nextDouble() * Math.PI * 2.0;
            double radius = 0.35 + RNG.nextDouble() * 0.2;
            Vec3d start = new Vec3d(Math.cos(angle) * radius, 0.1 + RNG.nextDouble() * 1.6, Math.sin(angle) * radius);
            Vec3d dir = new Vec3d(RNG.nextDouble() - 0.5, RNG.nextDouble() - 0.5, RNG.nextDouble() - 0.5).normalize();
            double length = 0.6 + RNG.nextDouble() * 0.8;
            int points = 5;
            arc.offsets = new Vec3d[points];
            for (int i = 0; i < points; i++) {
                double f = (double) i / (points - 1);
                Vec3d p = start.add(dir.multiply(length * f));
                double jitter = (i == 0 || i == points - 1) ? 0.0 : 0.12;
                arc.offsets[i] = p.add((RNG.nextDouble() - 0.5) * jitter, (RNG.nextDouble() - 0.5) * jitter,
                        (RNG.nextDouble() - 0.5) * jitter);
            }
            ARCS.add(arc);
        }
    }

    private static void spawnSparks(ClientWorld world, AbstractClientPlayerEntity player, float intensity) {
        int count = (int) (intensity * 2.0f + RNG.nextFloat());
        double mx = player.getX() - player.prevX;
        double mz = player.getZ() - player.prevZ;
        for (int i = 0; i < count; i++) {
            double ox = (RNG.nextDouble() - 0.5) * 0.8;
            double oy = RNG.nextDouble() * 1.8;
            double oz = (RNG.nextDouble() - 0.5) * 0.8;
            world.addParticle(ApogeeParticles.SPARK,
                    player.getX() + ox, player.getY() + oy, player.getZ() + oz,
                    -mx * 0.15 + (RNG.nextDouble() - 0.5) * 0.04,
                    (RNG.nextDouble() - 0.5) * 0.04,
                    -mz * 0.15 + (RNG.nextDouble() - 0.5) * 0.04);
        }
    }

    /**
     * Sparks that ride the pulse head. Activation: tight sparks shooting up along the body.
     * Deactivation: sparks flung sideways and slightly down ("heat dump").
     */
    private static void spawnPulseSparks(ClientWorld world, AbstractClientPlayerEntity player, Pulse pulse,
                                         float intensity) {
        float t = (tickCount - pulse.born + 0.5f) / pulse.life;
        if (t >= 1.0f) {
            return;
        }
        float hs = heightScale(player);
        double y = player.getY() + pulseHead(pulse.activation, t) * hs;
        int count = Math.max(1, Math.round((pulse.activation ? 4.0f : 5.0f) * intensity));
        for (int i = 0; i < count; i++) {
            double ang = RNG.nextDouble() * Math.PI * 2.0;
            double cos = Math.cos(ang);
            double sin = Math.sin(ang);
            double radius = 0.28 + RNG.nextDouble() * 0.08;
            double vx, vy, vz;
            if (pulse.activation) {
                vx = cos * 0.02;
                vz = sin * 0.02;
                vy = 0.06 + RNG.nextDouble() * 0.08;
            } else {
                double speed = 0.07 + RNG.nextDouble() * 0.09;
                vx = cos * speed;
                vz = sin * speed;
                vy = -0.01 - RNG.nextDouble() * 0.04;
            }
            world.addParticle(ApogeeParticles.SPARK,
                    player.getX() + cos * radius, y + (RNG.nextDouble() - 0.5) * 0.15, player.getZ() + sin * radius,
                    vx, vy, vz);
        }
    }

    // ------------------------------------------------------------------ colour helpers

    private static float smooth(float x) {
        x = MathHelper.clamp(x, 0.0f, 1.0f);
        return x * x * (3.0f - 2.0f * x);
    }

    /** Writes lerp(a, b, t) into TMP and returns it. */
    private static float[] mix(float[] a, float[] b, float t) {
        TMP[0] = MathHelper.lerp(t, a[0], b[0]);
        TMP[1] = MathHelper.lerp(t, a[1], b[1]);
        TMP[2] = MathHelper.lerp(t, a[2], b[2]);
        return TMP;
    }

    /** Ghost gradient: lime (fresh) -> neon (middle) -> deep green (old). Result is in TMP. */
    private static float[] ghostColor(float t) {
        if (t < 0.4f) {
            return mix(LIME, NEON, smooth(t / 0.4f));
        }
        return mix(NEON, DEEP, smooth((t - 0.4f) / 0.6f));
    }

    // ------------------------------------------------------------------ afterimages

    public static void renderGhosts(WorldRenderContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientConfig cfg = ClientConfig.get();
        if (mc.world == null || TRAILS.isEmpty() || !cfg.afterimages || cfg.intensity <= 0.01f) {
            return;
        }
        MatrixStack matrices = context.matrixStack();
        if (matrices == null) {
            return;
        }
        float td = context.tickDelta();
        Vec3d cam = context.camera().getPos();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();
        // Emissive translucent layer: ignores the world lightmap/shading, so the silhouette stays
        // crisp and bright in caves and at night, and does not write depth.
        RenderLayer layer = RenderLayer.getEntityTranslucentEmissive(GHOST_TEXTURE);
        float baseAlpha = 0.70f * cfg.intensity;
        boolean drew = false;

        for (Map.Entry<Integer, ArrayDeque<Snapshot>> entry : TRAILS.entrySet()) {
            Entity entity = mc.world.getEntityById(entry.getKey());
            if (!(entity instanceof AbstractClientPlayerEntity player) || player.isInvisible() || player.isSpectator()) {
                continue;
            }
            EntityRenderer<?> renderer = dispatcher.getRenderer(player);
            if (!(renderer instanceof LivingEntityRendererAccessor accessor)) {
                continue; // mixin not applied: afterimages unavailable, no crash
            }
            EntityModel<?> rawModel = accessor.sandevistanApogee$getModel();
            if (!(rawModel instanceof BipedEntityModel<?> model)) {
                continue; // non-biped player model (unlikely): skip rather than crash
            }
            VertexConsumer consumer = immediate.getBuffer(layer);

            for (Snapshot s : entry.getValue()) {
                float age = (tickCount - s.tick) + td;
                if (age < 1.2f || age >= s.life) {
                    continue; // skip the newest one: it would overlap the real model
                }
                float t = age / s.life;

                // smooth appearance, then a dynamic ease-out melt with a subtle energy flicker
                float fadeIn = smooth(age / 2.5f);
                float fade = (float) Math.pow(1.0f - t, 1.8f);
                float flicker = 0.92f + 0.08f * MathHelper.sin((tickCount + td) * 1.7f + s.age * 0.9f);
                float alpha = Math.min(1.0f, baseAlpha * fadeIn * fade * flicker);
                if (alpha < 0.01f) {
                    continue;
                }

                float[] c = ghostColor(t);
                float r = c[0], g = c[1], b = c[2];

                model.setVisible(true);
                model.handSwingProgress = 0.0f;
                model.riding = false;
                model.child = false;
                model.sneaking = s.sneaking;
                model.leaningPitch = 0.0f;
                model.leftArmPose = BipedEntityModel.ArmPose.EMPTY;
                model.rightArmPose = BipedEntityModel.ArmPose.EMPTY;
                model.setAngles(player, s.limbPos, s.limbSpeed, s.age,
                        MathHelper.wrapDegrees(s.headYaw - s.bodyYaw), s.pitch);

                // pass 0: slightly inflated, brighter halo (outline / glow); pass 1: main body
                for (int pass = 0; pass < 2; pass++) {
                    boolean halo = pass == 0;
                    matrices.push();
                    matrices.translate(s.x - cam.x, s.y - cam.y, s.z - cam.z);
                    matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - s.bodyYaw));
                    matrices.scale(-1.0f, -1.0f, 1.0f);
                    matrices.scale(0.9375f, 0.9375f, 0.9375f);
                    if (halo) {
                        matrices.scale(1.08f, 1.03f, 1.08f); // scaled around the feet
                    }
                    matrices.translate(0.0f, -1.501f, 0.0f);
                    if (halo) {
                        model.render(matrices, consumer, LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                OverlayTexture.DEFAULT_UV,
                                MathHelper.lerp(0.5f, r, LIME[0]),
                                MathHelper.lerp(0.5f, g, LIME[1]),
                                MathHelper.lerp(0.5f, b, LIME[2]),
                                alpha * 0.38f);
                    } else {
                        model.render(matrices, consumer, LightmapTextureManager.MAX_LIGHT_COORDINATE,
                                OverlayTexture.DEFAULT_UV, r, g, b, alpha);
                    }
                    matrices.pop();
                }
                drew = true;
            }
        }
        if (drew) {
            immediate.draw(layer);
        }
    }

    // ------------------------------------------------------------------ shockwaves, arcs and body pulses

    public static void renderBeams(WorldRenderContext context) {
        if (SHOCKS.isEmpty() && ARCS.isEmpty() && PULSES.isEmpty()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientConfig cfg = ClientConfig.get();
        MatrixStack matrices = context.matrixStack();
        if (mc.world == null || matrices == null || cfg.intensity <= 0.01f) {
            return;
        }
        float td = context.tickDelta();
        Vec3d cam = context.camera().getPos();
        Matrix4f m = matrices.peek().getPositionMatrix();
        float intensity = cfg.intensity;

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buf = tessellator.getBuffer();
        buf.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        final int segments = 40;
        for (Shock s : SHOCKS) {
            float t = (tickCount - s.born + td) / s.life;
            if (t < 0.0f || t >= 1.0f) {
                continue;
            }
            float ease = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
            float radius = s.maxRadius * ease;
            float inner = Math.max(0.0f, radius - 1.1f - 0.6f * t);
            float alpha = (1.0f - t) * 0.6f * intensity;
            float height = 0.4f + 1.4f * (1.0f - t);
            float[] ringColor = s.cool ? NEON : ACID;
            float[] wallColor = s.cool ? DEEP : NEON;
            double bx = s.x - cam.x;
            double by = s.y - cam.y + 0.05;
            double bz = s.z - cam.z;
            for (int i = 0; i < segments; i++) {
                double a0 = i * (Math.PI * 2.0 / segments);
                double a1 = (i + 1) * (Math.PI * 2.0 / segments);
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
                float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                // flat ring on the ground
                vertex(buf, m, bx + c0 * inner, by, bz + s0 * inner, ringColor, 0.0f);
                vertex(buf, m, bx + c0 * radius, by, bz + s0 * radius, ringColor, alpha);
                vertex(buf, m, bx + c1 * radius, by, bz + s1 * radius, ringColor, alpha);
                vertex(buf, m, bx + c1 * inner, by, bz + s1 * inner, ringColor, 0.0f);
                // fading wall
                vertex(buf, m, bx + c0 * radius, by, bz + s0 * radius, wallColor, alpha * 0.7f);
                vertex(buf, m, bx + c1 * radius, by, bz + s1 * radius, wallColor, alpha * 0.7f);
                vertex(buf, m, bx + c1 * radius, by + height, bz + s1 * radius, wallColor, 0.0f);
                vertex(buf, m, bx + c0 * radius, by + height, bz + s0 * radius, wallColor, 0.0f);
            }
        }

        for (Arc arc : ARCS) {
            Entity entity = mc.world.getEntityById(arc.entityId);
            if (entity == null) {
                continue;
            }
            float t = (tickCount - arc.born + td) / arc.life;
            if (t < 0.0f || t >= 1.0f) {
                continue;
            }
            float alpha = (1.0f - t) * intensity;
            Vec3d base = entity.getLerpedPos(td).subtract(cam);
            // gradient along the arc: deep green at the root -> lime / acid at the tip
            float[] rootColor = DEEP;
            float[] tipColor = arc.warm ? ACID : LIME;
            int last = arc.offsets.length - 1;
            for (int i = 0; i < last; i++) {
                Vec3d p0 = base.add(arc.offsets[i]);
                Vec3d p1 = base.add(arc.offsets[i + 1]);
                float f0 = (float) i / last;
                float f1 = (float) (i + 1) / last;
                float[] ca = mix(rootColor, tipColor, f0).clone();
                float[] cb = mix(rootColor, tipColor, f1).clone();
                // wide soft glow, then a thin bright core
                ribbon(buf, m, p0, p1, 0.08f, ca, cb, 0.32f * alpha);
                ribbon(buf, m, p0, p1, 0.016f, CORE, CORE, 0.9f * alpha);
            }
        }

        renderPulses(mc, cfg, buf, m, cam, td);

        tessellator.draw();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    // ------------------------------------------------------------------ body pulses

    /** Pulse head height above the feet (blocks, before the pose scale). */
    private static float pulseHead(boolean activation, float t) {
        t = MathHelper.clamp(t, 0.0f, 1.0f);
        if (activation) {
            return BODY_H * smooth(t);                      // lower back -> head, sharp ease in/out
        }
        return BODY_H * (1.0f - (float) Math.pow(t, 1.6)); // head -> feet, accelerating "drop"
    }

    /** Sneaking / swimming players are shorter: squash the pulse path accordingly. */
    private static float heightScale(AbstractClientPlayerEntity player) {
        return MathHelper.clamp(player.getHeight() / 1.8f, 0.5f, 1.0f);
    }

    /** Body-local (lx = to the player's left, lz = towards the back) -> camera-relative world position. */
    private static Vec3d body(Vec3d base, float cs, float sn, double lx, double y, double lz) {
        return new Vec3d(base.x + cs * lx + sn * lz, base.y + y, base.z + sn * lx - cs * lz);
    }

    private static void renderPulses(MinecraftClient mc, ClientConfig cfg, BufferBuilder buf, Matrix4f m,
                                     Vec3d cam, float td) {
        if (PULSES.isEmpty()) {
            return;
        }
        float intensity = cfg.intensity;
        boolean firstPersonCamera = mc.options.getPerspective().isFirstPerson();

        for (Pulse p : PULSES) {
            float t = (tickCount - p.born + td) / p.life;
            if (t < 0.0f || t >= 1.0f) {
                continue;
            }
            Entity entity = mc.world.getEntityById(p.entityId);
            if (!(entity instanceof AbstractClientPlayerEntity player) || player.isSpectator()
                    || cam.squaredDistanceTo(player.getPos()) > 48.0 * 48.0) {
                continue;
            }
            boolean act = p.activation;
            // first person: dimmer and wider, so the effect stays on the periphery of the screen
            boolean first = firstPersonCamera && player == mc.player;
            float vis = first ? 0.45f : 1.0f;
            float spread = first ? 1.35f : 1.0f;
            float hs = heightScale(player);

            float yaw = MathHelper.lerpAngleDegrees(td, player.prevBodyYaw, player.bodyYaw);
            double rad = Math.toRadians(yaw);
            float cs = (float) Math.cos(rad);
            float sn = (float) Math.sin(rad);
            Vec3d base = player.getLerpedPos(td).subtract(cam);
            float dir = act ? 1.0f : -1.0f; // direction of travel: +1 up, -1 down

            // ---- 1. horizontal energy rings following the pulse head ----
            for (int i = 0; i < PULSE_RINGS; i++) {
                float rt = (t - i * 0.12f) / 0.76f;
                if (rt <= 0.0f || rt >= 1.0f) {
                    continue;
                }
                float y = pulseHead(act, rt) * hs;
                float env = smooth(rt * 5.0f) * smooth((1.0f - rt) * 4.0f);
                float alpha = env * (act ? 0.85f : 0.55f * (1.0f - 0.6f * rt)) * (1.0f - i * 0.2f)
                        * intensity * vis;
                // activation: tight and hugging the body; deactivation: the ring disperses outwards
                float rs = (act ? 1.0f + 0.25f * rt : 1.0f + 1.6f * rt) * spread;
                float[] lead = (act ? mix(ACID, NEON, rt) : mix(NEON, DEEP, rt)).clone();
                float[] tail = act ? NEON : DEEP;
                ringBand(buf, m, base, cs, sn, y, rs, dir, alpha, lead, tail);
            }

            if (!cfg.arcs) {
                continue;
            }

            // ---- 2. jagged electric arcs: spine + arcs hugging the torso + flung sparks ----
            Random r = new Random(p.seed + (long) tickCount * 341873128712L); // stable within a tick
            float head = pulseHead(act, t) * hs;
            float trail = (act ? 1.0f : 0.8f) * hs;
            float lo = act ? Math.max(0.15f * hs, head - trail) : head;
            float hi = act ? head : Math.min(BODY_H * hs, head + trail);
            // deactivation: arcs dim and drop out; activation: stays bright
            float arcAlpha = intensity * vis * (act ? 1.0f : (float) Math.pow(1.0f - t, 1.3) * 0.85f);
            float dropout = act ? 0.0f : t * 0.55f;

            // spine
            if (hi - lo > 0.05f) {
                final int n = 9;
                Vec3d[] pts = new Vec3d[n];
                for (int k = 0; k < n; k++) {
                    double y = MathHelper.lerp((float) k / (n - 1), lo, hi);
                    double jx = (r.nextDouble() - 0.5) * 0.09;
                    double jz = (r.nextDouble() - 0.5) * 0.06;
                    pts[k] = body(base, cs, sn, jx, y, 0.16 + jz);
                }
                for (int k = 0; k < n - 1; k++) {
                    if (r.nextFloat() < dropout) {
                        continue;
                    }
                    float yMid = (float) ((pts[k].y + pts[k + 1].y) * 0.5 - base.y);
                    float g = MathHelper.clamp(1.0f - Math.abs(yMid - head) / Math.max(0.01f, trail), 0.0f, 1.0f);
                    float[] ca = mix(DEEP, LIME, g).clone();
                    ribbon(buf, m, pts[k], pts[k + 1], 0.05f, ca, ca, 0.38f * arcAlpha * (0.3f + 0.7f * g));
                    ribbon(buf, m, pts[k], pts[k + 1], 0.012f, CORE, CORE, 0.95f * arcAlpha * g);
                }
            }

            // arcs hugging the body around the pulse head
            int hug = Math.max(1, Math.round((act ? 3.0f : 2.0f) * intensity));
            for (int h = 0; h < hug; h++) {
                float side = r.nextBoolean() ? 1.0f : -1.0f;
                float sweep = 1.2f + r.nextFloat() * 0.9f;
                float y0 = head + (r.nextFloat() - 0.5f) * 0.3f * hs;
                float dy = dir * (0.10f + r.nextFloat() * 0.25f) * hs;
                boolean warm = r.nextFloat() < 0.3f;
                final int pn = 6;
                Vec3d[] pts = new Vec3d[pn];
                for (int k = 0; k < pn; k++) {
                    float f = (float) k / (pn - 1);
                    double phi = side * sweep * f;
                    double jit = (k == 0 || k == pn - 1) ? 0.0 : 0.03;
                    double lx = Math.sin(phi) * 0.30 * spread + (r.nextDouble() - 0.5) * jit;
                    double lz = Math.cos(phi) * 0.22 * spread + 0.04 + (r.nextDouble() - 0.5) * jit;
                    double y = y0 + dy * f + (r.nextDouble() - 0.5) * jit;
                    pts[k] = body(base, cs, sn, lx, y, lz);
                }
                float[] tipColor = warm ? ACID : LIME;
                for (int k = 0; k < pn - 1; k++) {
                    if (r.nextFloat() < dropout) {
                        continue;
                    }
                    float[] ca = mix(DEEP, tipColor, (float) k / (pn - 1)).clone();
                    float[] cb = mix(DEEP, tipColor, (float) (k + 1) / (pn - 1)).clone();
                    ribbon(buf, m, pts[k], pts[k + 1], 0.035f, ca, cb, 0.35f * arcAlpha);
                    ribbon(buf, m, pts[k], pts[k + 1], 0.010f, CORE, CORE, 0.85f * arcAlpha);
                }
            }

            // deactivation only: short sparks flung radially outwards ("heat dump")
            if (!act) {
                for (int k = 0; k < 4; k++) {
                    double ang = r.nextDouble() * Math.PI * 2.0;
                    double r0 = 0.30 * spread;
                    double r1 = r0 + 0.20 + 0.50 * t + r.nextDouble() * 0.2;
                    double y = head + (r.nextDouble() - 0.5) * 0.25 - 0.10 * t;
                    Vec3d a = body(base, cs, sn, Math.cos(ang) * r0, y, Math.sin(ang) * r0);
                    Vec3d b = body(base, cs, sn, Math.cos(ang) * r1, y - 0.05 - 0.1 * t, Math.sin(ang) * r1);
                    ribbon(buf, m, a, b, 0.02f, LIME, DEEP, 0.4f * arcAlpha);
                    ribbon(buf, m, a, b, 0.006f, CORE, DEEP, 0.7f * arcAlpha);
                }
            }
        }
    }

    /**
     * One horizontal energy ring around the body: a vertical band that is brightest at the leading edge and
     * fades out behind it, a thin white-hot line on the leading edge and a soft flat halo on the horizontal plane.
     * {@code dir}: +1 = pulse moves up (trail below), -1 = moves down (trail above).
     */
    private static void ringBand(BufferBuilder buf, Matrix4f m, Vec3d base, float cs, float sn, float y, float rs,
                                 float dir, float alpha, float[] lead, float[] tail) {
        if (alpha < 0.01f) {
            return;
        }
        final int seg = 28;
        final float ax = 0.42f * rs;
        final float az = 0.34f * rs;
        final float ox = ax + 0.20f;
        final float oz = az + 0.20f;
        double yHead = base.y + y;
        double yTail = yHead - dir * 0.30;
        for (int i = 0; i < seg; i++) {
            double a0 = i * (Math.PI * 2.0 / seg);
            double a1 = (i + 1) * (Math.PI * 2.0 / seg);
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);

            double x0 = base.x + cs * (c0 * ax) + sn * (s0 * az);
            double z0 = base.z + sn * (c0 * ax) - cs * (s0 * az);
            double x1 = base.x + cs * (c1 * ax) + sn * (s1 * az);
            double z1 = base.z + sn * (c1 * ax) - cs * (s1 * az);

            // fading band (bright at the head of the pulse)
            vertex(buf, m, x0, yHead, z0, lead, alpha);
            vertex(buf, m, x1, yHead, z1, lead, alpha);
            vertex(buf, m, x1, yTail, z1, tail, 0.0f);
            vertex(buf, m, x0, yTail, z0, tail, 0.0f);

            // white-hot leading edge
            vertex(buf, m, x0, yHead - 0.012, z0, CORE, alpha * 0.9f);
            vertex(buf, m, x1, yHead - 0.012, z1, CORE, alpha * 0.9f);
            vertex(buf, m, x1, yHead + 0.012, z1, CORE, alpha * 0.9f);
            vertex(buf, m, x0, yHead + 0.012, z0, CORE, alpha * 0.9f);

            // soft flat halo (reads well from above / below and from first person)
            double ex0 = base.x + cs * (c0 * ox) + sn * (s0 * oz);
            double ez0 = base.z + sn * (c0 * ox) - cs * (s0 * oz);
            double ex1 = base.x + cs * (c1 * ox) + sn * (s1 * oz);
            double ez1 = base.z + sn * (c1 * ox) - cs * (s1 * oz);
            vertex(buf, m, x0, yHead, z0, lead, alpha * 0.45f);
            vertex(buf, m, x1, yHead, z1, lead, alpha * 0.45f);
            vertex(buf, m, ex1, yHead, ez1, tail, 0.0f);
            vertex(buf, m, ex0, yHead, ez0, tail, 0.0f);
        }
    }

    private static void vertex(BufferBuilder buf, Matrix4f m, double x, double y, double z, float[] c, float a) {
        buf.vertex(m, (float) x, (float) y, (float) z).color(c[0], c[1], c[2], a).next();
    }

    /** Camera-facing ribbon between two points given relative to the camera; colour blends from ca to cb. */
    private static void ribbon(BufferBuilder buf, Matrix4f m, Vec3d a, Vec3d b, float width,
                               float[] ca, float[] cb, float alpha) {
        Vec3d dir = b.subtract(a);
        Vec3d view = a.add(b).multiply(0.5);
        Vec3d side = dir.crossProduct(view);
        if (side.lengthSquared() < 1.0e-8) {
            return;
        }
        side = side.normalize().multiply(width);
        vertex(buf, m, a.x - side.x, a.y - side.y, a.z - side.z, ca, alpha);
        vertex(buf, m, a.x + side.x, a.y + side.y, a.z + side.z, ca, alpha);
        vertex(buf, m, b.x + side.x, b.y + side.y, b.z + side.z, cb, alpha);
        vertex(buf, m, b.x - side.x, b.y - side.y, b.z - side.z, cb, alpha);
    }
}
