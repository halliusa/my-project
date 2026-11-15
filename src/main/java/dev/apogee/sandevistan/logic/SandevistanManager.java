package dev.apogee.sandevistan.logic;

import dev.apogee.sandevistan.ApogeeItems;
import dev.apogee.sandevistan.ApogeeSounds;
import dev.apogee.sandevistan.config.ApogeeConfig;
import dev.apogee.sandevistan.network.ApogeeNetworking;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Authoritative server-side logic. The client only sends a request; presence of the item, duration and cooldown
 * are validated here. All timings use server ticks (MinecraftServer#getTicks), the world tick rate is never touched.
 */
public final class SandevistanManager {
    private SandevistanManager() {
    }

    /** Fixed UUIDs: re-adding a modifier with the same id replaces it, so bonuses can never stack. */
    public static final UUID SPEED_MODIFIER_ID = UUID.fromString("5b1e0d2a-8c7f-4e3a-9a41-0c6f2b7d1a01");
    public static final UUID ATTACK_MODIFIER_ID = UUID.fromString("5b1e0d2a-8c7f-4e3a-9a41-0c6f2b7d1a02");

    private static final int MOB_EFFECT_TICKS = 12;

    private static final Map<UUID, State> STATES = new HashMap<>();

    private static final class State {
        int activeUntil = -1;      // server tick when the effect ends; -1 = not active
        int activeTotal = 0;
        int cooldownUntil = 0;     // server tick when the cooldown ends
        int cooldownTotal = 0;
        boolean readySent = true;  // has the "ready" notification already been sent
    }

    public static void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(SandevistanManager::tick);

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity player) {
                end(player, true);
            }
            return true;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            clearModifiers(handler.player);
            sendState(handler.player, ApogeeNetworking.KIND_SYNC);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(handler.player));

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            end(oldPlayer, false);
            clearModifiers(newPlayer);
            sendState(newPlayer, ApogeeNetworking.KIND_SYNC);
        });

        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                end(player, true));

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> STATES.clear());
    }

    // ------------------------------------------------------------------ activation

    public static void tryActivate(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null || !player.isAlive() || player.isSpectator()) {
            return;
        }
        if (!hasImplant(player)) {
            player.sendMessage(Text.translatable("message.sandevistan_apogee.no_implant"), true);
            return;
        }
        ApogeeConfig cfg = ApogeeConfig.get();
        int now = server.getTicks();
        State state = STATES.computeIfAbsent(player.getUuid(), id -> new State());
        if (state.activeUntil >= 0) {
            return; // already active: never stack
        }
        if (now < state.cooldownUntil) {
            String seconds = String.format(Locale.ROOT, "%.1f", (state.cooldownUntil - now) / 20.0f);
            player.sendMessage(Text.translatable("message.sandevistan_apogee.cooldown", seconds), true);
            return;
        }

        int duration = cfg.durationTicks();
        state.activeTotal = duration;
        state.activeUntil = now + duration;

        applyModifiers(player, cfg);
        player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                ApogeeSounds.ACTIVATE, SoundCategory.PLAYERS, 1.4f, 1.0f);
        ApogeeNetworking.sendOwnerState(player, ApogeeNetworking.KIND_ACTIVATED, duration, 0, duration,
                state.cooldownTotal);
        ApogeeNetworking.sendTrail(player, ApogeeNetworking.TRAIL_START);
        slowMobs(player, cfg);
    }

    /** Ends the effect (if active) and starts the cooldown. Safe to call at any time. */
    public static void end(ServerPlayerEntity player, boolean notify) {
        State state = STATES.get(player.getUuid());
        if (state == null || state.activeUntil < 0) {
            return;
        }
        MinecraftServer server = player.getServer();
        int now = server == null ? 0 : server.getTicks();
        ApogeeConfig cfg = ApogeeConfig.get();

        removeModifiers(player);
        state.activeUntil = -1;
        state.cooldownTotal = cfg.cooldownTicks();
        state.cooldownUntil = now + state.cooldownTotal;
        state.readySent = state.cooldownTotal == 0;

        if (notify) {
            if (player.getWorld() instanceof ServerWorld) {
                releaseMobs(player, cfg);
            }
            player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                    ApogeeSounds.DEACTIVATE, SoundCategory.PLAYERS, 1.2f, 1.0f);
            ApogeeNetworking.sendOwnerState(player, ApogeeNetworking.KIND_ENDED, 0, state.cooldownTotal,
                    state.activeTotal, state.cooldownTotal);
            ApogeeNetworking.sendTrail(player, ApogeeNetworking.TRAIL_END);
        }
    }

    private static void onDisconnect(ServerPlayerEntity player) {
        end(player, false);
        clearModifiers(player);
        State state = STATES.get(player.getUuid());
        MinecraftServer server = player.getServer();
        // The cooldown intentionally survives a relog (anti-exploit); finished states are dropped.
        if (state != null && server != null && state.activeUntil < 0 && server.getTicks() >= state.cooldownUntil) {
            STATES.remove(player.getUuid());
        }
    }

    // ------------------------------------------------------------------ per-tick

    private static void tick(MinecraftServer server) {
        if (STATES.isEmpty()) {
            return;
        }
        ApogeeConfig cfg = ApogeeConfig.get();
        int now = server.getTicks();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            State state = STATES.get(player.getUuid());
            if (state == null) {
                continue;
            }
            if (state.activeUntil >= 0) {
                if (!player.isAlive() || player.isSpectator() || !hasImplant(player) || now >= state.activeUntil) {
                    end(player, true);
                    continue;
                }
                ensureModifiers(player, cfg);
                int elapsed = now - (state.activeUntil - state.activeTotal);
                if (elapsed > 0 && elapsed % 20 == 10 && cfg.playLoopSound) {
                    player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                            ApogeeSounds.LOOP, SoundCategory.PLAYERS, 0.9f, 1.0f);
                }
                if (elapsed > 0 && elapsed % 20 == 0) {
                    sendState(player, ApogeeNetworking.KIND_SYNC);
                }
                if (elapsed > 0 && elapsed % 10 == 0) {
                    ApogeeNetworking.sendTrail(player, ApogeeNetworking.TRAIL_REFRESH);
                }
                if (elapsed % 4 == 0) {
                    slowMobs(player, cfg);
                }
                slowProjectiles(player, cfg);
            } else if (!state.readySent && now >= state.cooldownUntil) {
                state.readySent = true;
                if (player.isAlive() && hasImplant(player)) {
                    sendState(player, ApogeeNetworking.KIND_READY);
                }
            }
        }
    }

    // ------------------------------------------------------------------ world effects

    private static void slowMobs(ServerPlayerEntity owner, ApogeeConfig cfg) {
        if (!(owner.getWorld() instanceof ServerWorld world)) {
            return;
        }
        double radius = cfg.radius;
        double radiusSq = radius * radius;
        Box box = owner.getBoundingBox().expand(radius);

        List<MobEntity> mobs = world.getEntitiesByClass(MobEntity.class, box, mob ->
                mob.isAlive() && mob.squaredDistanceTo(owner) <= radiusSq && shouldSlowMob(mob, owner, cfg));
        for (MobEntity mob : mobs) {
            mob.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, MOB_EFFECT_TICKS,
                    cfg.mobSlownessAmplifier, true, false, false));
        }
        if (cfg.affectPlayers) {
            List<ServerPlayerEntity> players = world.getEntitiesByClass(ServerPlayerEntity.class, box, other ->
                    other != owner && other.isAlive() && !other.isSpectator()
                            && other.squaredDistanceTo(owner) <= radiusSq);
            for (ServerPlayerEntity other : players) {
                other.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, MOB_EFFECT_TICKS,
                        cfg.mobSlownessAmplifier, true, false, false));
            }
        }
    }

    /** Removes our short slowness from creatures in range right after the effect ends. */
    private static void releaseMobs(ServerPlayerEntity owner, ApogeeConfig cfg) {
        if (!(owner.getWorld() instanceof ServerWorld world)) {
            return;
        }
        Box box = owner.getBoundingBox().expand(cfg.radius + 2.0);
        List<net.minecraft.entity.LivingEntity> targets = world.getEntitiesByClass(
                net.minecraft.entity.LivingEntity.class, box,
                e -> e != owner && (e instanceof MobEntity || e instanceof ServerPlayerEntity));
        for (net.minecraft.entity.LivingEntity target : targets) {
            StatusEffectInstance inst = target.getStatusEffect(StatusEffects.SLOWNESS);
            if (inst != null && inst.isAmbient() && !inst.shouldShowParticles()
                    && inst.getDuration() <= MOB_EFFECT_TICKS && inst.getAmplifier() == cfg.mobSlownessAmplifier) {
                target.removeStatusEffect(StatusEffects.SLOWNESS);
            }
        }
    }

    private static boolean shouldSlowMob(MobEntity mob, ServerPlayerEntity owner, ApogeeConfig cfg) {
        if (mob instanceof Tameable tameable && owner.getUuid().equals(tameable.getOwnerUuid())) {
            return false; // never slow the owner's pets
        }
        return cfg.affectPassiveMobs || mob instanceof Monster;
    }

    /**
     * Foreign projectiles are limited to a maximum speed. Velocity is only ever reduced, never accelerated,
     * and nothing is restored afterwards: once the effect is over, the projectile simply flies on normally.
     */
    private static void slowProjectiles(ServerPlayerEntity owner, ApogeeConfig cfg) {
        if (!(owner.getWorld() instanceof ServerWorld world)) {
            return;
        }
        double radius = cfg.radius;
        double radiusSq = radius * radius;
        double cap = cfg.projectileMaxSpeed;
        Box box = owner.getBoundingBox().expand(radius);
        List<ProjectileEntity> projectiles = world.getEntitiesByClass(ProjectileEntity.class, box, projectile -> {
            if (!projectile.isAlive() || projectile.squaredDistanceTo(owner) > radiusSq) {
                return false;
            }
            Entity projectileOwner = projectile.getOwner();
            if (projectileOwner == owner) {
                return false;
            }
            return cfg.affectPlayers || !(projectileOwner instanceof PlayerEntity);
        });
        for (ProjectileEntity projectile : projectiles) {
            Vec3d velocity = projectile.getVelocity();
            double speed = velocity.length();
            if (speed > cap) {
                projectile.setVelocity(velocity.multiply(cap / speed));
                projectile.velocityModified = true;
            }
        }
    }

    // ------------------------------------------------------------------ attributes

    private static void applyModifiers(ServerPlayerEntity player, ApogeeConfig cfg) {
        EntityAttributeInstance speed = player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(SPEED_MODIFIER_ID);
            speed.addTemporaryModifier(new EntityAttributeModifier(SPEED_MODIFIER_ID, "Sandevistan speed",
                    cfg.speedBonus, EntityAttributeModifier.Operation.MULTIPLY_TOTAL));
        }
        EntityAttributeInstance attack = player.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_SPEED);
        if (attack != null) {
            attack.removeModifier(ATTACK_MODIFIER_ID);
            attack.addTemporaryModifier(new EntityAttributeModifier(ATTACK_MODIFIER_ID, "Sandevistan attack speed",
                    cfg.attackSpeedBonus, EntityAttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }

    private static void ensureModifiers(ServerPlayerEntity player, ApogeeConfig cfg) {
        EntityAttributeInstance speed = player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        EntityAttributeInstance attack = player.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_SPEED);
        if ((speed != null && speed.getModifier(SPEED_MODIFIER_ID) == null)
                || (attack != null && attack.getModifier(ATTACK_MODIFIER_ID) == null)) {
            applyModifiers(player, cfg);
        }
    }

    private static void removeModifiers(ServerPlayerEntity player) {
        clearModifiers(player);
    }

    private static void clearModifiers(ServerPlayerEntity player) {
        EntityAttributeInstance speed = player.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(SPEED_MODIFIER_ID);
        }
        EntityAttributeInstance attack = player.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_SPEED);
        if (attack != null) {
            attack.removeModifier(ATTACK_MODIFIER_ID);
        }
    }

    // ------------------------------------------------------------------ helpers

    public static boolean hasImplant(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (player.getInventory().getStack(i).isOf(ApogeeItems.SANDEVISTAN)) {
                return true;
            }
        }
        return false;
    }

    private static void sendState(ServerPlayerEntity player, int kind) {
        MinecraftServer server = player.getServer();
        int now = server == null ? 0 : server.getTicks();
        State state = STATES.get(player.getUuid());
        int activeLeft = 0;
        int cooldownLeft = 0;
        int activeTotal = 0;
        int cooldownTotal = 0;
        if (state != null) {
            if (state.activeUntil >= 0) {
                activeLeft = Math.max(0, state.activeUntil - now);
            }
            cooldownLeft = Math.max(0, state.cooldownUntil - now);
            activeTotal = state.activeTotal;
            cooldownTotal = state.cooldownTotal;
        }
        ApogeeNetworking.sendOwnerState(player, kind, activeLeft, cooldownLeft, activeTotal, cooldownTotal);
    }
}
