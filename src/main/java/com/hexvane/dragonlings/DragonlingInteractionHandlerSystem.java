package com.hexvane.dragonlings;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;

/**
 * System that adds custom interaction handler and sets custom interaction ID for dragonling NPCs.
 */
public class DragonlingInteractionHandlerSystem extends HolderSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    @Nonnull
    private final ComponentType<EntityStore, NPCEntity> npcEntityComponentType;
    @Nonnull
    private final Query<EntityStore> query;
    
    public DragonlingInteractionHandlerSystem(@Nonnull ComponentType<EntityStore, NPCEntity> npcEntityComponentType) {
        if (npcEntityComponentType == null) {
            throw new IllegalArgumentException("npcEntityComponentType cannot be null");
        }
        this.npcEntityComponentType = npcEntityComponentType;
        // Initialize query - ComponentType implements Query, so we can use it directly
        Query<EntityStore> queryValue = npcEntityComponentType;
        if (queryValue == null) {
            throw new IllegalStateException("Failed to create query from npcEntityComponentType");
        }
        this.query = queryValue;
    }
    
    @Override
    public void onEntityAdd(@Nonnull Holder<EntityStore> holder, @Nonnull AddReason reason, @Nonnull com.hypixel.hytale.component.Store<EntityStore> store) {
        NPCEntity npcComponent = holder.getComponent(this.npcEntityComponentType);
        if (npcComponent == null) {
            return;
        }
        
        // Check if this is a dragonling
        String roleName = npcComponent.getRoleName();
        if (roleName == null || !roleName.contains("Dragonling")) {
            return;
        }
        
        // Check if InteractionManager already exists
        InteractionModule interactionModule = InteractionModule.get();
        if (interactionModule == null) {
            return; // InteractionModule not available yet
        }
        
        ComponentType<EntityStore, InteractionManager> interactionManagerComponent = interactionModule.getInteractionManagerComponent();
        if (holder.getComponent(interactionManagerComponent) == null) {
            // Add custom interaction handler
            holder.addComponent(
                interactionManagerComponent,
                new InteractionManager(npcComponent, null, new DragonlingInteractionSimulationHandler())
            );
        }
        
        // Set custom interaction ID for Use interactions on dragonlings
        // This replaces *UseNPC with our custom DragonlingUseNPC interaction
        ComponentType<EntityStore, Interactions> interactionsComponentType = Interactions.getComponentType();
        Interactions interactions = holder.ensureAndGetComponent(interactionsComponentType);
        interactions.setInteractionId(InteractionType.Use, "DragonlingUseNPC");
    }
    
    @Override
    public void onEntityRemoved(@Nonnull Holder<EntityStore> holder, @Nonnull com.hypixel.hytale.component.RemoveReason reason, @Nonnull com.hypixel.hytale.component.Store<EntityStore> store) {
        // Nothing to clean up
    }
    
    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }
}
