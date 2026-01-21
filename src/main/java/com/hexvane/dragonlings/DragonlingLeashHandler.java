package com.hexvane.dragonlings;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles leashing dragonlings with the Dragon Whistle.
 * Right-click dragonling to enter leash mode, then right-click block to set leash position.
 */
public class DragonlingLeashHandler {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String DRAGON_WHISTLE_ID = "Dragon_Whistle";
    
    // Track players in "leash mode" - waiting to click a block
    private static final Map<UUID, Ref<EntityStore>> leashModePlayers = new HashMap<>();
    
    /**
     * Handles right-click interaction with a dragonling using the Dragon Whistle.
     * @param context The interaction context
     * @param npcRef Reference to the NPC entity
     * @param npcComponent The NPC component
     * @param playerRef Reference to the player entity
     * @param playerComponent The player component
     * @param commandBuffer The command buffer for component modifications
     * @return true if handled, false otherwise
     */
    public static boolean handleDragonlingInteraction(
            @Nonnull InteractionContext context,
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull NPCEntity npcComponent,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull Player playerComponent,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        
        // Check if player is holding Dragon Whistle
        ItemStack heldItem = playerComponent.getInventory().getActiveHotbarItem();
        
        if (heldItem == null || !heldItem.getItemId().equals(DRAGON_WHISTLE_ID)) {
            return false;
        }
        
        // Check if dragonling is tamed and owned by this player
        DragonlingData data = commandBuffer.getComponent(npcRef, DragonlingData.getComponentType());
        UUIDComponent uuidComponent = commandBuffer.getComponent(playerRef, UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            return false;
        }
        UUID playerUUID = uuidComponent.getUuid();
        
        if (data == null || !data.isTamed() || !playerUUID.equals(data.getOwnerUUID())) {
            playerComponent.sendMessage(Message.translation("server.dragonlings.leash.notOwned"));
            return true;
        }
        
        // Check if already leashed - if so, unleash it
        if (data.isLeashed()) {
            // Unleash the dragonling
            data = commandBuffer.getComponent(npcRef, DragonlingData.getComponentType());
            if (data == null) {
                return true;
            }
            
            // Get NPC's current position to set leash point to (so SensorLeash won't trigger)
            TransformComponent npcTransform = commandBuffer.getComponent(npcRef, TransformComponent.getComponentType());
            Vector3d currentPos = npcTransform != null ? npcTransform.getPosition() : new Vector3d(0, 0, 0);
            
            data.setLeashed(false);
            data.setLeashPosition(null);
            data.setLeashBlockType(null);
            
            // Set leash point to current position so SensorLeash won't match (distance = 0)
            npcComponent.setLeashPoint(currentPos);
            
            // Restore follow target so it follows the player again
            npcComponent.getRole().getMarkedEntitySupport().setMarkedEntity("LockedTarget", playerRef);
            
            playerComponent.sendMessage(Message.translation("server.dragonlings.leash.unleashed")
                .param("dragonling", npcComponent.getRoleName()));
            
            LOGGER.atInfo().log("[Leash] Player %s unleashed %s", playerUUID, npcComponent.getRoleName());
            return true;
        }
        
        // Enter leash mode
        leashModePlayers.put(playerUUID, npcRef);
        playerComponent.sendMessage(Message.translation("server.dragonlings.leash.modeEnter")
            .param("dragonling", npcComponent.getRoleName()));
        
        LOGGER.atInfo().log("[Leash] Player %s entered leash mode for %s", playerUUID, npcComponent.getRoleName());
        
        return true;
    }
    
    /**
     * Handles right-click on a block while in leash mode.
     * @param playerUUID The player's UUID
     * @param blockPosition The position of the clicked block
     * @param blockType The type of block clicked
     * @param store The entity store
     * @return true if leash was set, false if player wasn't in leash mode
     */
    public static boolean handleBlockInteraction(
            @Nonnull UUID playerUUID,
            @Nonnull Vector3d blockPosition,
            @Nonnull String blockType,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        
        Ref<EntityStore> npcRef = leashModePlayers.remove(playerUUID);
        if (npcRef == null || !npcRef.isValid()) {
            return false; // Not in leash mode
        }
        
        DragonlingData data = commandBuffer.getComponent(npcRef, DragonlingData.getComponentType());
        NPCEntity npcComponent = commandBuffer.getComponent(npcRef, NPCEntity.getComponentType());
        
        if (data == null || npcComponent == null) {
            return false;
        }
        
        // Get mutable component from command buffer
        if (data == null) {
            data = new DragonlingData();
            commandBuffer.putComponent(npcRef, DragonlingData.getComponentType(), data);
        } else {
            data = commandBuffer.getComponent(npcRef, DragonlingData.getComponentType());
        }
        
        // Set leash position in DragonlingData
        data.setLeashed(true);
        data.setLeashPosition(blockPosition);
        data.setLeashBlockType(blockType);
        
        // Also set leash point on NPCEntity for SensorLeash to use
        // This will be picked up by the role's SensorLeash instruction
        npcComponent.setLeashPoint(blockPosition);
        
        // Clear follow target when leashed
        npcComponent.getRole().getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);
        
        LOGGER.atInfo().log("[Leash] Setting leash for %s at position %s (block: %s) by player %s", 
            npcComponent.getRoleName(), blockPosition, blockType, playerUUID);
        
        // Send confirmation message
        Ref<EntityStore> playerRef = findPlayerByUUID(commandBuffer.getStore(), playerUUID);
        if (playerRef != null && playerRef.isValid()) {
            Player playerComponent = commandBuffer.getComponent(playerRef, Player.getComponentType());
            if (playerComponent != null) {
                playerComponent.sendMessage(Message.translation("server.dragonlings.leash.set")
                    .param("dragonling", npcComponent.getRoleName()));
                LOGGER.atInfo().log("[Leash] Sent confirmation message to player %s", playerUUID);
            }
        }
        
        LOGGER.atInfo().log("[Leash] SUCCESS - Dragonling %s leashed to position %s by player %s", 
            npcComponent.getRoleName(), blockPosition, playerUUID);
        
        return true;
    }
    
    /**
     * Cancels leash mode for a player.
     */
    public static void cancelLeashMode(@Nonnull UUID playerUUID) {
        leashModePlayers.remove(playerUUID);
    }
    
    /**
     * Checks if a player is in leash mode.
     */
    public static boolean isInLeashMode(@Nonnull UUID playerUUID) {
        return leashModePlayers.containsKey(playerUUID);
    }
    
    /**
     * Finds a player entity by UUID.
     */
    @Nullable
    private static Ref<EntityStore> findPlayerByUUID(@Nonnull Store<EntityStore> store, @Nonnull UUID uuid) {
        Ref<EntityStore>[] result = new Ref[1];
        ComponentType<EntityStore, Player> playerType = Player.getComponentType();
        store.forEachChunk(playerType, (chunk, commandBuffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                Player player = chunk.getComponent(i, playerType);
                if (player != null) {
                    UUIDComponent uuidComponent = store.getComponent(chunk.getReferenceTo(i), UUIDComponent.getComponentType());
                    if (uuidComponent != null && uuidComponent.getUuid().equals(uuid)) {
                        result[0] = chunk.getReferenceTo(i);
                        return;
                    }
                }
            }
        });
        return result[0];
    }
}
