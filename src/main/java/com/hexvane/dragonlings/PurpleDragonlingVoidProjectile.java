package com.hexvane.dragonlings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Supplies a {@link ProjectileConfig} for the purple dragonling void orb.
 *
 * <p><strong>Important:</strong> {@link com.hypixel.hytale.assetstore.AssetStore#loadAssetsFromPaths} must not run on
 * the world {@link com.hypixel.hytale.server.core.util.thread.TickingThread} (e.g. during combat/damage handlers) — it
 * acquires a store write lock and can deadlock the tick. Patched configs are loaded once from {@link #warmup} during
 * plugin {@link JavaPlugin#start()}; {@link #resolve} only reads the cache.
 */
public final class PurpleDragonlingVoidProjectile {
    private static final String STOCK_ASSET_ID = "Dragonling_Void_Projectile";
    private static final String RESOURCE = "/Server/ProjectileConfigs/Dragonling_Void_Projectile.json";

    private static volatile String packKey;
    private static volatile Path generatedProjectileConfigsDir;

    /** Populated only by {@link #warmup}; key is {@link Double#doubleToLongBits(double)}. */
    private static final ConcurrentHashMap<Long, ProjectileConfig> WARMED = new ConcurrentHashMap<>();

    private PurpleDragonlingVoidProjectile() {}

    public static void init(@Nonnull JavaPlugin plugin) {
        packKey = new PluginIdentifier(plugin.getManifest()).toString();
        generatedProjectileConfigsDir =
            plugin.getDataDirectory().resolve("generated").resolve("Server").resolve("ProjectileConfigs");
    }

    /**
     * Loads a patched projectile asset when {@code physicalDamage} differs from the stock default. Call once from
     * {@link JavaPlugin#start()} after {@link #init} and after plugin config is readable — not from entity ticks or
     * damage events.
     */
    public static void warmup(@Nonnull HytaleLogger logger, double physicalDamage) {
        if (Double.compare(physicalDamage, DragonlingsTameCapConfig.DEFAULT_PURPLE_VOID_PROJECTILE_PHYSICAL_DAMAGE) == 0) {
            return;
        }
        if (packKey == null || generatedProjectileConfigsDir == null) {
            logger.atWarning().log("PurpleDragonlingVoidProjectile.init was not called; skipping warmup for custom damage");
            return;
        }
        long key = Double.doubleToLongBits(physicalDamage);
        String variantId = variantAssetId(physicalDamage);
        try {
            Files.createDirectories(generatedProjectileConfigsDir);
            Path out = generatedProjectileConfigsDir.resolve(variantId + ".json");
            if (!Files.isRegularFile(out) || needsRewrite(out, physicalDamage)) {
                writePatchedProjectileJson(out, physicalDamage, logger);
            }
            ProjectileConfig.getAssetStore().loadAssetsFromPaths(packKey, List.of(out.toAbsolutePath().normalize()));
            ProjectileConfig loaded = ProjectileConfig.getAssetMap().getAsset(variantId);
            if (loaded == null) {
                logger.atWarning().log(
                    "After warmup loadAssetsFromPaths, asset '%s' was not registered; combat will use stock %s",
                    variantId,
                    STOCK_ASSET_ID);
                return;
            }
            WARMED.put(key, loaded);
            logger.atInfo().log(
                "Preloaded purple void projectile '%s' (physical=%s) for combat",
                variantId,
                physicalDamage);
        } catch (Exception e) {
            logger.atWarning().withCause(e).log(
                "Warmup failed for purple void projectile (physical=%s); combat will use stock asset",
                physicalDamage);
        }
    }

    /**
     * Fast lookup for combat code paths. Never loads assets — see {@link #warmup}.
     */
    @Nullable
    public static ProjectileConfig resolve(@Nonnull HytaleLogger logger, double physicalDamage) {
        if (Double.compare(physicalDamage, DragonlingsTameCapConfig.DEFAULT_PURPLE_VOID_PROJECTILE_PHYSICAL_DAMAGE) == 0) {
            return ProjectileConfig.getAssetMap().getAsset(STOCK_ASSET_ID);
        }
        long key = Double.doubleToLongBits(physicalDamage);
        ProjectileConfig warmed = WARMED.get(key);
        if (warmed != null) {
            return warmed;
        }
        logger.atFine().log(
            "No warmed purple void projectile for physical=%s (expected warmup at plugin start); using stock %s",
            physicalDamage,
            STOCK_ASSET_ID);
        return ProjectileConfig.getAssetMap().getAsset(STOCK_ASSET_ID);
    }

    private static boolean needsRewrite(@Nonnull Path out, double physicalDamage) {
        try {
            String text = Files.readString(out, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                return true;
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonObject interactions = root.getAsJsonObject("Interactions");
            if (interactions == null) {
                return true;
            }
            JsonElement hit = interactions.get("ProjectileHit");
            if (hit == null || !hit.isJsonObject()) {
                return true;
            }
            JsonArray chain = hit.getAsJsonObject().getAsJsonArray("Interactions");
            if (chain == null) {
                return true;
            }
            for (JsonElement step : chain) {
                if (!step.isJsonObject()) {
                    continue;
                }
                JsonObject o = step.getAsJsonObject();
                if (!"DamageEntity".equals(getString(o, "Type"))) {
                    continue;
                }
                JsonObject dc = o.getAsJsonObject("DamageCalculator");
                if (dc == null) {
                    return true;
                }
                JsonObject base = dc.getAsJsonObject("BaseDamage");
                if (base != null && base.has("Physical")) {
                    return Math.abs(base.get("Physical").getAsDouble() - physicalDamage) > 1e-6;
                }
                return true;
            }
            return true;
        } catch (Exception ignored) {
            return true;
        }
    }

    @Nullable
    private static String getString(@Nonnull JsonObject o, @Nonnull String k) {
        JsonElement e = o.get(k);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    @Nonnull
    private static String variantAssetId(double physicalDamage) {
        return STOCK_ASSET_ID + "_" + Long.toHexString(Double.doubleToLongBits(physicalDamage));
    }

    private static void writePatchedProjectileJson(@Nonnull Path out, double physicalDamage, @Nonnull HytaleLogger logger)
            throws Exception {
        try (InputStream stream = PurpleDragonlingVoidProjectile.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            JsonElement parsed;
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                parsed = JsonParser.parseReader(reader);
            }
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("Resource " + RESOURCE + " is not a JSON object");
            }
            JsonObject root = parsed.getAsJsonObject();
            if (!patchPhysicalDamage(root, physicalDamage)) {
                throw new IllegalStateException("Could not patch BaseDamage.Physical in " + RESOURCE);
            }
            Files.writeString(out, root.toString(), StandardCharsets.UTF_8);
            logger.atInfo().log("Wrote generated purple void projectile config %s (physical=%s)", out, physicalDamage);
        }
    }

    private static boolean patchPhysicalDamage(@Nonnull JsonObject root, double physicalDamage) {
        JsonObject interactions = root.getAsJsonObject("Interactions");
        if (interactions == null) {
            return false;
        }
        JsonElement hitEl = interactions.get("ProjectileHit");
        if (hitEl == null || !hitEl.isJsonObject()) {
            return false;
        }
        JsonArray chain = hitEl.getAsJsonObject().getAsJsonArray("Interactions");
        if (chain == null || chain.isEmpty()) {
            return false;
        }
        for (JsonElement step : chain) {
            if (!step.isJsonObject()) {
                continue;
            }
            JsonObject stepObj = step.getAsJsonObject();
            JsonElement type = stepObj.get("Type");
            if (type == null || !"DamageEntity".equals(type.getAsString())) {
                continue;
            }
            JsonObject damageCalculator = stepObj.getAsJsonObject("DamageCalculator");
            if (damageCalculator == null) {
                continue;
            }
            JsonObject baseDamage = damageCalculator.getAsJsonObject("BaseDamage");
            if (baseDamage == null) {
                continue;
            }
            baseDamage.addProperty("Physical", physicalDamage);
            return true;
        }
        return false;
    }
}
