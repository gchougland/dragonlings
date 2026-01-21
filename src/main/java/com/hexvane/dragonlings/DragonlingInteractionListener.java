package com.hexvane.dragonlings;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Listens for interaction events to handle taming and leashing.
 */
public class DragonlingInteractionListener {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    public static void register(@Nonnull EventRegistry eventRegistry) {
        // Register block interaction listener for leashing
        // Use Pre event to handle before block's own interaction consumes it
        eventRegistry.register(UseBlockEvent.Pre.class, event -> {
            // Only handle Secondary (right-click) interactions, not Use (F-key)
            if (event.getInteractionType() != InteractionType.Secondary) {
                return;
            }
            
            com.hypixel.hytale.server.core.entity.InteractionContext context = event.getContext();
            Ref<EntityStore> playerRef = context.getEntity();
            Store<EntityStore> store = context.getCommandBuffer().getStore();
            
            Player player = store.getComponent(playerRef, Player.getComponentType());
            if (player == null) {
                return;
            }
            
            com.hypixel.hytale.server.core.entity.UUIDComponent uuidComponent = store.getComponent(playerRef, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
            if (uuidComponent == null) {
                return;
            }
            UUID playerUUID = uuidComponent.getUuid();
            
            // Check if player is in leash mode
            if (DragonlingLeashHandler.isInLeashMode(playerUUID)) {
                Vector3i blockPos = event.getTargetBlock();
                if (blockPos == null) {
                    LOGGER.atWarning().log("[DragonlingInteraction] Player %s in leash mode but block position is null", playerUUID);
                    return;
                }
                
                Vector3d blockPosDouble = new Vector3d(blockPos.getX(), blockPos.getY(), blockPos.getZ());
                String blockType = event.getBlockType() != null ? event.getBlockType().getId() : "Unknown";
                
                LOGGER.atInfo().log("[DragonlingInteraction] Player %s in leash mode, handling block interaction at %s (block: %s)", 
                    playerUUID, blockPosDouble, blockType);
                
                CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
                if (commandBuffer != null) {
                    boolean handled = DragonlingLeashHandler.handleBlockInteraction(playerUUID, blockPosDouble, blockType, commandBuffer);
                    if (handled) {
                        LOGGER.atInfo().log("[DragonlingInteraction] Leash position set for player %s at %s", playerUUID, blockPosDouble);
                        // Cancel the block interaction so it doesn't trigger the block's own interaction
                        event.setCancelled(true);
                    } else {
                        LOGGER.atWarning().log("[DragonlingInteraction] Failed to handle block interaction for player %s - leash mode but handler returned false", 
                            playerUUID);
                    }
                } else {
                    LOGGER.atWarning().log("[DragonlingInteraction] CommandBuffer is null for player %s", playerUUID);
                }
            }
        });
        
        // Note: NPC interaction handling for taming/leashing will be done through
        // the custom interaction or by hooking into interaction completion events
        // For now, the DragonlingTameInteraction handles taming, and leashing
        // is handled when player right-clicks dragonling with whistle (would need
        // to hook into UseNPCInteraction completion)
        
        LOGGER.atInfo().log("Dragonling interaction listeners registered");
    }
}
