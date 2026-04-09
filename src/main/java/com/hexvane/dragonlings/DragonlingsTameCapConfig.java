package com.hexvane.dragonlings;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Plugin {@code config.json}: tame caps per variant and purple dragonling combat tuning.
 */
public final class DragonlingsTameCapConfig {
    public static final int DEFAULT_MAX_PER_TYPE = 4;
    /** Matches {@code Server/ProjectileConfigs/Dragonling_Void_Projectile.json} first hit physical damage. */
    public static final double DEFAULT_PURPLE_VOID_PROJECTILE_PHYSICAL_DAMAGE = 20.0;

    public static final BuilderCodec<DragonlingsTameCapConfig> CODEC =
        BuilderCodec.builder(DragonlingsTameCapConfig.class, DragonlingsTameCapConfig::new)
            .addField(
                new KeyedCodec<>("MaxGreen", Codec.INTEGER),
                (c, v) -> c.maxGreen = clampMax(v),
                c -> c.maxGreen)
            .addField(
                new KeyedCodec<>("MaxBlue", Codec.INTEGER),
                (c, v) -> c.maxBlue = clampMax(v),
                c -> c.maxBlue)
            .addField(
                new KeyedCodec<>("MaxRed", Codec.INTEGER),
                (c, v) -> c.maxRed = clampMax(v),
                c -> c.maxRed)
            .addField(
                new KeyedCodec<>("MaxPurple", Codec.INTEGER),
                (c, v) -> c.maxPurple = clampMax(v),
                c -> c.maxPurple)
            .addField(
                new KeyedCodec<>("MaxTemplatePilot", Codec.INTEGER),
                (c, v) -> c.maxTemplatePilot = clampMax(v),
                c -> c.maxTemplatePilot)
            .addField(
                new KeyedCodec<>("PurpleVoidProjectilePhysicalDamage", Codec.DOUBLE),
                (c, v) -> c.purpleVoidProjectilePhysicalDamage = clampDamage(v),
                c -> c.purpleVoidProjectilePhysicalDamage)
            .build();

    private int maxGreen = DEFAULT_MAX_PER_TYPE;
    private int maxBlue = DEFAULT_MAX_PER_TYPE;
    private int maxRed = DEFAULT_MAX_PER_TYPE;
    private int maxPurple = DEFAULT_MAX_PER_TYPE;
    private int maxTemplatePilot = DEFAULT_MAX_PER_TYPE;
    private double purpleVoidProjectilePhysicalDamage = DEFAULT_PURPLE_VOID_PROJECTILE_PHYSICAL_DAMAGE;

    private static int clampMax(@Nonnull Integer v) {
        if (v == null) {
            return DEFAULT_MAX_PER_TYPE;
        }
        return Math.max(0, v);
    }

    private static double clampDamage(@Nullable Double v) {
        if (v == null) {
            return DEFAULT_PURPLE_VOID_PROJECTILE_PHYSICAL_DAMAGE;
        }
        return Math.max(0.0, v);
    }

    public int getMaxGreen() {
        return maxGreen;
    }

    public int getMaxBlue() {
        return maxBlue;
    }

    public int getMaxRed() {
        return maxRed;
    }

    public int getMaxPurple() {
        return maxPurple;
    }

    public int getMaxTemplatePilot() {
        return maxTemplatePilot;
    }

    /**
     * Absolute physical damage for the purple dragonling’s void projectile on hit (see {@code Dragonling_Void_Projectile}
     * asset).
     */
    public double getPurpleVoidProjectilePhysicalDamage() {
        return purpleVoidProjectilePhysicalDamage;
    }

    /**
     * Max tames allowed for this NPC role id (e.g. {@code Dragonling_Green}). Unknown roles default to
     * {@link #DEFAULT_MAX_PER_TYPE}.
     */
    public int getMaxForRole(@Nonnull String roleName) {
        if (roleName.isEmpty()) {
            return DEFAULT_MAX_PER_TYPE;
        }
        return switch (roleName) {
            case "Dragonling_Green" -> maxGreen;
            case "Dragonling_Blue" -> maxBlue;
            case "Dragonling_Red" -> maxRed;
            case "Dragonling_Purple" -> maxPurple;
            case "Dragonling_TemplatePilot" -> maxTemplatePilot;
            default -> DEFAULT_MAX_PER_TYPE;
        };
    }
}
