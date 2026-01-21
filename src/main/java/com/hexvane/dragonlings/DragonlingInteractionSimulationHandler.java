package com.hexvane.dragonlings;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.IInteractionSimulationHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.interactions.NPCInteractionSimulationHandler;
import javax.annotation.Nonnull;

/**
 * Custom interaction handler for dragonlings that intercepts interactions
 * to handle taming and leashing.
 */
public class DragonlingInteractionSimulationHandler extends NPCInteractionSimulationHandler {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    @Override
    public boolean isCharging(
            boolean firstRun, 
            float time, 
            InteractionType type, 
            InteractionContext context, 
            Ref<EntityStore> ref, 
            CooldownHandler cooldownHandler) {
        
        // Check for taming or leashing on first run
        // When a player uses an item on an NPC, ref is the NPC (the entity with this handler)
        // We need to find the player from the interaction context
        if (firstRun && type == InteractionType.Use) {
            LOGGER.atInfo().log("[DragonlingInteraction] isCharging called - firstRun=%s, type=%s, ref=%s", firstRun, type, ref);
            
            // ref is the NPC (the entity with this handler)
            NPCEntity npcComponent = context.getCommandBuffer().getComponent(ref, NPCEntity.getComponentType());
            if (npcComponent == null) {
                LOGGER.atInfo().log("[DragonlingInteraction] No NPC component found for ref %s", ref);
                return super.isCharging(firstRun, time, type, context, ref, cooldownHandler);
            }
            
            String roleName = npcComponent.getRoleName();
            if (roleName == null || !roleName.contains("Dragonling")) {
                return super.isCharging(firstRun, time, type, context, ref, cooldownHandler);
            }
            
            LOGGER.atInfo().log("[DragonlingInteraction] Dragonling detected: %s", roleName);
            
            // Try to get player from different sources
            // context.getEntity() might be the NPC when handler is on NPC, so try getOwningEntity or getTargetEntity
            Ref<EntityStore> playerRef = context.getOwningEntity();
            if (playerRef == null || !playerRef.isValid()) {
                playerRef = context.getEntity();
                LOGGER.atInfo().log("[DragonlingInteraction] Trying context.getEntity() for player: %s", playerRef);
            } else {
                LOGGER.atInfo().log("[DragonlingInteraction] Got player from getOwningEntity(): %s", playerRef);
            }
            
            if (playerRef == null || !playerRef.isValid() || playerRef.equals(ref)) {
                // If playerRef is the same as ref (NPC), try getTargetEntity
                Ref<EntityStore> targetRef = context.getTargetEntity();
                LOGGER.atInfo().log("[DragonlingInteraction] Trying context.getTargetEntity() for player: %s", targetRef);
                if (targetRef != null && targetRef.isValid() && !targetRef.equals(ref)) {
                    playerRef = targetRef;
                } else {
                    LOGGER.atInfo().log("[DragonlingInteraction] No valid player reference found for interaction with %s", roleName);
                    return super.isCharging(firstRun, time, type, context, ref, cooldownHandler);
                }
            }
            
            Player playerComponent = context.getCommandBuffer().getComponent(playerRef, Player.getComponentType());
            if (playerComponent == null) {
                LOGGER.atInfo().log("[DragonlingInteraction] Entity %s is not a player", playerRef);
                // Not a player interaction, let default handler deal with it
                return super.isCharging(firstRun, time, type, context, ref, cooldownHandler);
            }
            
            LOGGER.atInfo().log("[DragonlingInteraction] Player %s interacting with %s", playerRef, roleName);
            
            // Try taming first (checks for essence items)
            boolean tamed = DragonlingTamingHandler.attemptTaming(
                context, ref, npcComponent, playerRef, playerComponent, context.getCommandBuffer()
            );
            
            if (tamed) {
                LOGGER.atInfo().log("[DragonlingInteraction] Taming interaction handled for %s", roleName);
                // Mark interaction as consumed by setting state to finished
                context.getState().state = com.hypixel.hytale.protocol.InteractionState.Finished;
                return false; // Don't charge, interaction handled
            }
            
            // Try leashing (checks for Dragon Whistle)
            boolean leashed = DragonlingLeashHandler.handleDragonlingInteraction(
                context, ref, npcComponent, playerRef, playerComponent, context.getCommandBuffer()
            );
            
            if (leashed) {
                LOGGER.atInfo().log("[DragonlingInteraction] Leash interaction handled for %s", roleName);
                // Mark interaction as consumed
                context.getState().state = com.hypixel.hytale.protocol.InteractionState.Finished;
                return false; // Don't charge, interaction handled
            }
            
            LOGGER.atInfo().log("[DragonlingInteraction] No custom interaction matched for %s, falling back to default", roleName);
        }
        
        return super.isCharging(firstRun, time, type, context, ref, cooldownHandler);
    }
}
