package com.hexvane.dragonlings;

import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.command.system.CommandRegistry;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.Config;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hexvane.dragonlings.behaviors.BlueDragonlingWaterBehavior;
import com.hexvane.dragonlings.behaviors.GreenDragonlingHarvestBehavior;
import com.hexvane.dragonlings.behaviors.PurpleDragonlingCombatBehavior;
import com.hexvane.dragonlings.behaviors.RedDragonlingFurnaceBehavior;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.DoubleSupplier;
public class DragonlingsPlugin extends JavaPlugin {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final Config<DragonlingsTameCapConfig> tameCapConfig = this.withConfig(DragonlingsTameCapConfig.CODEC);
    private final DragonlingsTameCountStore tameCountStore = new DragonlingsTameCountStore(this.getDataDirectory());
    private ComponentType<EntityStore, DragonlingData> dragonlingDataType;

    public DragonlingsPlugin(JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("Hello from %s version %s", this.getName(), this.getManifest().getVersion().toString());
    }

    @Override
    protected void setup() {
        ComponentRegistryProxy<EntityStore> entityStoreRegistry = this.getEntityStoreRegistry();

        this.dragonlingDataType = entityStoreRegistry.registerComponent(
            DragonlingData.class,
            "DragonlingData",
            DragonlingData.CODEC
        );
        DragonlingData.setComponentType(this.dragonlingDataType);
    }

    @Override
    protected void start() {
        this.ensureDefaultTameCapConfigOnDisk();
        this.tameCountStore.load();
        PurpleDragonlingVoidProjectile.init(this);
        PurpleDragonlingVoidProjectile.warmup(
            LOGGER, this.tameCapConfig.get().getPurpleVoidProjectilePhysicalDamage());

        ComponentRegistryProxy<EntityStore> entityStoreRegistry = this.getEntityStoreRegistry();
        ComponentType<EntityStore, NPCEntity> npcComponentType = NPCEntity.getComponentType();

        if (npcComponentType == null) {
            LOGGER.atSevere().log("NPCEntity component type is not available - NPC module may not be loaded");
            return;
        }

        // Ensure DragonlingData for tamed NPCs before behaviors (they query NPCEntity + DragonlingData).
        entityStoreRegistry.registerSystem(new DragonlingDataBootstrapSystem(npcComponentType, this.dragonlingDataType));
        // Behavior systems set DragonlingData AI state/target first; DragonlingAISystem applies seek last (same tick).
        entityStoreRegistry.registerSystem(new GreenDragonlingHarvestBehavior(npcComponentType, this.dragonlingDataType));
        entityStoreRegistry.registerSystem(new BlueDragonlingWaterBehavior(npcComponentType, this.dragonlingDataType));
        entityStoreRegistry.registerSystem(new RedDragonlingFurnaceBehavior(npcComponentType, this.dragonlingDataType));
        DoubleSupplier purpleVoidProjectileDamage =
            () -> this.tameCapConfig.get().getPurpleVoidProjectilePhysicalDamage();
        entityStoreRegistry.registerSystem(new PurpleDragonlingCombatBehavior(npcComponentType, purpleVoidProjectileDamage));
        entityStoreRegistry.registerSystem(
            new com.hexvane.dragonlings.behaviors.PurpleDragonlingCombatListener(npcComponentType, purpleVoidProjectileDamage));
        entityStoreRegistry.registerSystem(new DragonlingAISystem(npcComponentType, this.dragonlingDataType));
        entityStoreRegistry.registerSystem(new DragonlingsTameCountDeathSystem(npcComponentType, this.tameCountStore));

        CommandRegistry commandRegistry = this.getCommandRegistry();
        commandRegistry.registerCommand(
            new DragonlingsCommand(this.dragonlingDataType, this.tameCapConfig, this.tameCountStore));

        DragonlingsTameworkTameCapRegistrar.register(
            LOGGER, () -> this.tameCapConfig.get(), this.tameCountStore);

        LOGGER.atInfo().log("Dragonlings plugin systems registered");
    }

    public ComponentType<EntityStore, DragonlingData> getDragonlingDataType() {
        return this.dragonlingDataType;
    }

    /**
     * {@link Config#load()} applies defaults when {@code config.json} is missing but does not create the file. Write it
     * so operators can edit caps without creating the path by hand.
     */
    private void ensureDefaultTameCapConfigOnDisk() {
        Path path = this.getDataDirectory().resolve("config.json");
        if (Files.exists(path)) {
            return;
        }
        try {
            this.tameCapConfig.get();
            this.tameCapConfig.save().join();
            LOGGER.atInfo().log("Wrote default tame cap config to %s", path);
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Could not write default config.json to %s", path);
        }
    }
}
