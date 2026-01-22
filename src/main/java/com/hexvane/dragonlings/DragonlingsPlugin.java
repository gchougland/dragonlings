package com.hexvane.dragonlings;

import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hexvane.dragonlings.behaviors.BlueDragonlingWaterBehavior;
import com.hexvane.dragonlings.behaviors.GreenDragonlingHarvestBehavior;
import com.hexvane.dragonlings.behaviors.PurpleDragonlingCombatBehavior;
import com.hexvane.dragonlings.behaviors.RedDragonlingFurnaceBehavior;

public class DragonlingsPlugin extends JavaPlugin {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private ComponentType<EntityStore, DragonlingData> dragonlingDataType;

    public DragonlingsPlugin(JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("Hello from %s version %s", this.getName(), this.getManifest().getVersion().toString());
    }

    @Override
    protected void setup() {
        ComponentRegistryProxy<EntityStore> entityStoreRegistry = this.getEntityStoreRegistry();
        
        // Register DragonlingData component with CODEC for persistence (doesn't depend on NPC module)
        this.dragonlingDataType = entityStoreRegistry.registerComponent(
            DragonlingData.class,
            "DragonlingData",
            DragonlingData.CODEC
        );
        DragonlingData.setComponentType(this.dragonlingDataType);
        
        // Register custom interaction (doesn't depend on NPC module)
        Interaction.CODEC.register("TameDragonling", DragonlingTameInteraction.class, DragonlingTameInteraction.CODEC);
        // Register custom UseNPC interaction for dragonlings
        Interaction.CODEC.register("DragonlingUseNPC", DragonlingUseNPCInteraction.class, DragonlingUseNPCInteraction.CODEC);
        
        // Register event listeners (doesn't depend on NPC module)
        EventRegistry eventRegistry = this.getEventRegistry();
        DragonlingInteractionListener.register(eventRegistry);
    }
    
    @Override
    protected void start() {
        // NPC module should be available by now (start() is called after assets/modules are loaded)
        ComponentRegistryProxy<EntityStore> entityStoreRegistry = this.getEntityStoreRegistry();
        ComponentType<EntityStore, NPCEntity> npcComponentType = NPCEntity.getComponentType();
        
        if (npcComponentType == null) {
            LOGGER.atSevere().log("NPCEntity component type is not available - NPC module may not be loaded");
            return;
        }
        
        // Register custom interaction handler for dragonlings
        entityStoreRegistry.registerSystem(new DragonlingInteractionHandlerSystem(npcComponentType));
        
        // Register systems
        entityStoreRegistry.registerSystem(new DragonlingAISystem(npcComponentType, this.dragonlingDataType));
        // DragonlingLeashWanderSystem removed - DragonlingAISystem handles leashing now
        entityStoreRegistry.registerSystem(new GreenDragonlingHarvestBehavior(npcComponentType, this.dragonlingDataType));
        entityStoreRegistry.registerSystem(new BlueDragonlingWaterBehavior(npcComponentType, this.dragonlingDataType));
        entityStoreRegistry.registerSystem(new RedDragonlingFurnaceBehavior(npcComponentType, this.dragonlingDataType));
        entityStoreRegistry.registerSystem(new PurpleDragonlingCombatBehavior(npcComponentType, this.dragonlingDataType));
        entityStoreRegistry.registerSystem(new com.hexvane.dragonlings.behaviors.PurpleDragonlingCombatListener(npcComponentType, this.dragonlingDataType));
        
        LOGGER.atInfo().log("Dragonlings plugin systems and listeners registered");
    }
    
    public ComponentType<EntityStore, DragonlingData> getDragonlingDataType() {
        return this.dragonlingDataType;
    }
}
