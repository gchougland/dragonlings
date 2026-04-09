package com.hexvane.dragonlings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Persistent per-player counts of tamed dragonlings by NPC role id, incremented when a tame completes (interaction effect
 * or admin give) and decremented when a tamed dragonling dies. Does not depend on loaded chunks.
 */
public final class DragonlingsTameCountStore {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String FILE_NAME = "tame_counts.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Object lock = new Object();
    /** Player UUID -> role id (e.g. Dragonling_Green) -> count */
    private final Map<UUID, Map<String, Integer>> byPlayer = new HashMap<>();

    public DragonlingsTameCountStore(@Nonnull Path dataDirectory) {
        this.file = dataDirectory.resolve(FILE_NAME);
    }

    /** Load from disk; no-op if missing. Call from {@link com.hypixel.hytale.server.core.plugin.JavaPlugin#start()}. */
    public void load() {
        synchronized (this.lock) {
            this.byPlayer.clear();
            if (!Files.isRegularFile(this.file)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(this.file)) {
                FileDto dto = GSON.fromJson(reader, FileDto.class);
                if (dto == null || dto.players == null) {
                    return;
                }
                for (Map.Entry<String, Map<String, Integer>> e : dto.players.entrySet()) {
                    UUID id;
                    try {
                        id = UUID.fromString(e.getKey());
                    } catch (IllegalArgumentException ex) {
                        LOGGER.atWarning().withCause(ex).log("Skipping invalid UUID in tame_counts: %s", e.getKey());
                        continue;
                    }
                    Map<String, Integer> roles = e.getValue();
                    if (roles == null || roles.isEmpty()) {
                        continue;
                    }
                    Map<String, Integer> copy = new HashMap<>();
                    for (Map.Entry<String, Integer> re : roles.entrySet()) {
                        if (re.getKey() == null || re.getKey().isEmpty() || re.getValue() == null || re.getValue() <= 0) {
                            continue;
                        }
                        copy.put(re.getKey(), re.getValue());
                    }
                    if (!copy.isEmpty()) {
                        this.byPlayer.put(id, copy);
                    }
                }
            } catch (IOException ex) {
                LOGGER.atSevere().withCause(ex).log("Failed to load %s", this.file);
            }
        }
    }

    public int getCount(@Nullable UUID playerId, @Nonnull String roleName) {
        if (playerId == null || roleName.isEmpty()) {
            return 0;
        }
        synchronized (this.lock) {
            Map<String, Integer> roles = this.byPlayer.get(playerId);
            if (roles == null) {
                return 0;
            }
            return Math.max(0, roles.getOrDefault(roleName, 0));
        }
    }

    /** Called after a tame succeeds (Tamework custom effect or {@code /dragonlings give}). */
    public void recordTame(@Nonnull UUID playerId, @Nonnull String roleName) {
        if (roleName.isEmpty()) {
            return;
        }
        synchronized (this.lock) {
            this.byPlayer
                .computeIfAbsent(playerId, k -> new HashMap<>())
                .merge(roleName, 1, Integer::sum);
            this.saveLocked();
        }
    }

    /**
     * Called when a tamed dragonling NPC dies ({@link com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent}
     * added). Does not run on chunk unload.
     */
    public void recordDeath(@Nonnull UUID ownerId, @Nonnull String roleName) {
        if (roleName.isEmpty()) {
            return;
        }
        synchronized (this.lock) {
            Map<String, Integer> roles = this.byPlayer.get(ownerId);
            if (roles == null) {
                return;
            }
            int v = roles.getOrDefault(roleName, 0);
            if (v <= 0) {
                return;
            }
            int next = v - 1;
            if (next <= 0) {
                roles.remove(roleName);
            } else {
                roles.put(roleName, next);
            }
            if (roles.isEmpty()) {
                this.byPlayer.remove(ownerId);
            }
            this.saveLocked();
        }
    }

    private void saveLocked() {
        try {
            Files.createDirectories(this.file.getParent());
            FileDto dto = new FileDto();
            dto.players = new HashMap<>();
            for (Map.Entry<UUID, Map<String, Integer>> pe : this.byPlayer.entrySet()) {
                dto.players.put(pe.getKey().toString(), new HashMap<>(pe.getValue()));
            }
            try (Writer w = Files.newBufferedWriter(this.file)) {
                GSON.toJson(dto, w);
            }
        } catch (IOException ex) {
            LOGGER.atSevere().withCause(ex).log("Failed to save %s", this.file);
        }
    }

    private static final class FileDto {
        Map<String, Map<String, Integer>> players = new HashMap<>();
    }
}
