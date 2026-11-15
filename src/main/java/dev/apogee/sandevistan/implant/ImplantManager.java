package dev.apogee.sandevistan.implant;

import dev.apogee.sandevistan.ApogeeItems;
import dev.apogee.sandevistan.ApogeeMod;
import dev.apogee.sandevistan.ApogeeSounds;
import dev.apogee.sandevistan.network.ApogeeNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the implant inventories of every player on this world.
 *
 * <p>The implant is <b>not</b> an ordinary inventory item: it lives in a dedicated one-slot inventory
 * per player that is serialised into the world save ({@code data/sandevistan_apogee_implants.dat}).
 * It therefore survives death, relogs, dimension changes and server restarts, and it cannot be thrown
 * out, /clear-ed or piped into a hopper by accident.
 *
 * <p>The client learns whether the implant is installed through the {@code implant_sync} packet; the actual
 * contents travel through the vanilla screen-handler sync only while the GUI is open.
 */
public final class ImplantManager {
    private ImplantManager() {
    }

    private static final String FILE_NAME = "sandevistan_apogee_implants.dat";
    /** How often (in ticks) online players are checked for implant install/removal. */
    private static final int SCAN_INTERVAL = 8;

    private static final Map<UUID, SimpleInventory> INVENTORIES = new ConcurrentHashMap<>();
    /** Last installed-state broadcast to each player; used to send packets and UI sounds only on changes. */
    private static final Map<UUID, Boolean> LAST_NOTIFIED = new ConcurrentHashMap<>();

    private static Path file;
    private static boolean dirty;
    private static int tickCounter;

    // ------------------------------------------------------------------ lifecycle

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(ImplantManager::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> save());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> shutdown());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendStateTo(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            // The implant itself must stay in INVENTORIES (persistence); the notify cache is per-session.
            LAST_NOTIFIED.remove(handler.player.getUuid());
        });
        ServerTickEvents.END_SERVER_TICK.register(ImplantManager::tick);
    }

    // ------------------------------------------------------------------ inventory access

    /** Returns (creating if needed) the implant inventory of the given player. */
    public static SimpleInventory get(UUID uuid) {
        return INVENTORIES.computeIfAbsent(uuid, id -> new SimpleInventory(1));
    }

    public static SimpleInventory get(ServerPlayerEntity player) {
        return get(player.getUuid());
    }

    /** Hot path (runs every few ticks): read-only, never creates entries. */
    public static boolean hasImplant(ServerPlayerEntity player) {
        SimpleInventory inv = INVENTORIES.get(player.getUuid());
        return inv != null && !inv.getStack(0).isEmpty();
    }

    public static boolean isInstalled(UUID uuid) {
        SimpleInventory inv = INVENTORIES.get(uuid);
        return inv != null && !inv.getStack(0).isEmpty();
    }

    // ------------------------------------------------------------------ GUI opening

    /** Opens the neuroimplant screen for the player. Called on the server thread. */
    public static void openScreen(ServerPlayerEntity player) {
        if (player == null || !player.isAlive() || player.isSpectator()) {
            return;
        }
        SimpleInventory implant = get(player);
        NamedScreenHandlerFactory factory = new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, p) -> new CyberwareScreenHandler(syncId, inventory, implant),
                Text.translatable("gui.sandevistan_apogee.title"));
        player.openHandledScreen(factory);
    }

    public static void sendStateTo(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        // Make sure the inventory exists so a fresh player still gets a definite "not installed" answer.
        ApogeeNetworking.sendImplantState(player, isInstalled(player.getUuid()));
    }

    /** Ask the next scan to persist the data (called when the GUI closes or a change is spotted). */
    public static void requestSave() {
        dirty = true;
    }

    // ------------------------------------------------------------------ per-tick sync

    private static void tick(MinecraftServer server) {
        if (++tickCounter % SCAN_INTERVAL != 0) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            SimpleInventory inv = get(player);
            boolean installed = !inv.getStack(0).isEmpty();
            Boolean last = LAST_NOTIFIED.get(player.getUuid());
            if (last == null || last.booleanValue() != installed) {
                LAST_NOTIFIED.put(player.getUuid(), installed);
                ApogeeNetworking.sendImplantState(player, installed);
                if (last != null) {
                    // The player really inserted / removed the implant: give some audio feedback.
                    if (installed) {
                        player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                                ApogeeSounds.ACTIVATE, SoundCategory.PLAYERS, 0.8f, 1.5f);
                    } else {
                        player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                                ApogeeSounds.DEACTIVATE, SoundCategory.PLAYERS, 0.8f, 1.4f);
                    }
                }
                dirty = true;
            }
        }
        if (dirty) {
            save();
            dirty = false;
        }
    }

    // ------------------------------------------------------------------ persistence

    private static void load(MinecraftServer server) {
        INVENTORIES.clear();
        LAST_NOTIFIED.clear();
        dirty = false;
        tickCounter = 0;
        try {
            file = server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(FILE_NAME);
        } catch (Exception e) {
            file = null;
            ApogeeMod.LOGGER.warn("Could not resolve the implant save path — implants will not persist", e);
            return;
        }
        if (!Files.exists(file)) {
            ApogeeMod.LOGGER.info("No implant save found at {}, starting fresh", file);
            return;
        }
        try {
            NbtCompound root = NbtIo.readCompressed(file.toFile());
            NbtList list = root.getList("implants", NbtElement.COMPOUND_TYPE);
            int loaded = 0;
            for (int i = 0; i < list.size(); i++) {
                NbtCompound entry = list.getCompound(i);
                String uuid = entry.getString("uuid");
                try {
                    UUID id = UUID.fromString(uuid);
                    ItemStack stack = ItemStack.fromNbt(entry.getCompound("stack"));
                    if (stack.isEmpty() || !stack.isOf(ApogeeItems.SANDEVISTAN)) {
                        continue; // implant definition gone or item swapped: skip silently
                    }
                    SimpleInventory inv = new SimpleInventory(1);
                    inv.setStack(0, stack);
                    INVENTORIES.put(id, inv);
                    loaded++;
                } catch (Exception e) {
                    ApogeeMod.LOGGER.warn("Skipping malformed implant entry '{}'", uuid, e);
                }
            }
            ApogeeMod.LOGGER.info("Loaded {} installed implant(s) from {}", loaded, file);
        } catch (Exception e) {
            ApogeeMod.LOGGER.warn("Could not read {} — starting with empty implants", file, e);
        }
    }

    /** Writes the current implant state to the world save. Safe to call at any time. */
    public static void save() {
        Path target = file;
        if (target == null) {
            return;
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            NbtCompound root = new NbtCompound();
            root.putInt("version", 1);
            NbtList list = new NbtList();
            int saved = 0;
            for (Map.Entry<UUID, SimpleInventory> entry : INVENTORIES.entrySet()) {
                ItemStack stack = entry.getValue().getStack(0);
                if (stack.isEmpty()) {
                    continue;
                }
                NbtCompound item = new NbtCompound();
                item.putString("uuid", entry.getKey().toString());
                item.put("stack", stack.writeNbt(new NbtCompound()));
                list.add(item);
                saved++;
            }
            root.put("implants", list);
            NbtIo.writeCompressed(root, target.toFile());
            if (saved > 0) {
                ApogeeMod.LOGGER.debug("Saved {} implant(s) to {}", saved, target);
            }
        } catch (IOException | RuntimeException e) {
            ApogeeMod.LOGGER.warn("Could not write {}", target, e);
        }
    }

    private static void shutdown() {
        INVENTORIES.clear();
        LAST_NOTIFIED.clear();
        file = null;
        dirty = false;
        tickCounter = 0;
    }
}
