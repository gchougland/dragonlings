package com.hexvane.dragonlings;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.random.RandomExtra;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Handles taming interactions for dragonlings.
 * Checks if player is holding the correct essence item and attempts to tame with a chance-based system.
 */
public class DragonlingTamingHandler {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double BASE_TAME_CHANCE = 0.3; // 30% base chance
    
    /**
     * Attempts to tame a dragonling when a player interacts with it.
     * @param context The interaction context
     * @param npcRef Reference to the NPC entity
     * @param npcComponent The NPC component
     * @param playerRef Reference to the player entity
     * @param playerComponent The player component
     * @param commandBuffer The command buffer for component modifications
     * @return true if taming was attempted (successful or not), false if conditions weren't met
     */
    public static boolean attemptTaming(
            @Nonnull InteractionContext context,
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull NPCEntity npcComponent,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull Player playerComponent,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        
        // Check if already tamed
        DragonlingData data = commandBuffer.getComponent(npcRef, DragonlingData.getComponentType());
        if (data != null && data.isTamed()) {
            return false; // Already tamed
        }
        
        // Get the required taming item from the role parameters
        Role role = npcComponent.getRole();
        String tamingItem = getTamingItemFromRole(role, npcComponent.getRoleName());
        if (tamingItem == null) {
            return false; // Not a dragonling or no taming item specified
        }
        
        // Check if player is holding the correct item
        ItemStack heldItem = playerComponent.getInventory().getActiveHotbarItem();
        
        LOGGER.atInfo().log("[Taming] Checking taming - Role: %s, Required item: %s, Held item: %s", 
            npcComponent.getRoleName(), tamingItem, heldItem != null ? heldItem.getItemId() : "null");
        
        if (heldItem == null || !heldItem.getItemId().equals(tamingItem)) {
            LOGGER.atInfo().log("[Taming] Player not holding correct item - expected %s, got %s", 
                tamingItem, heldItem != null ? heldItem.getItemId() : "null");
            return false; // Not holding the correct item
        }
        
        LOGGER.atInfo().log("[Taming] Player holding correct item, attempting to tame...");
        
        // Consume the item on every attempt (before checking success)
        ItemContainer hotbar = playerComponent.getInventory().getHotbar();
        byte activeSlot = playerComponent.getInventory().getActiveHotbarSlot();
        if (activeSlot >= 0) {
            int newQuantity = heldItem.getQuantity() - 1;
            if (newQuantity <= 0) {
                hotbar.setItemStackForSlot(activeSlot, null);
            } else {
                ItemStack newStack = new ItemStack(heldItem.getItemId(), newQuantity);
                hotbar.setItemStackForSlot(activeSlot, newStack);
            }
        }
        
        // Attempt taming with chance
        boolean tamed = java.util.concurrent.ThreadLocalRandom.current().nextDouble() < BASE_TAME_CHANCE;
        
        if (tamed) {
            // Initialize component if needed
            if (data == null) {
                data = new DragonlingData();
                commandBuffer.putComponent(npcRef, DragonlingData.getComponentType(), data);
            } else {
                // Get a mutable copy from command buffer
                data = commandBuffer.getComponent(npcRef, DragonlingData.getComponentType());
            }
            
            // Set tamed state
            data.setTamed(true);
            UUIDComponent uuidComponent = commandBuffer.getComponent(playerRef, UUIDComponent.getComponentType());
            if (uuidComponent != null) {
                data.setOwnerUUID(uuidComponent.getUuid());
            }
            
            // Send success message
            playerComponent.sendMessage(Message.translation("server.dragonlings.tamed.success")
                .param("dragonling", npcComponent.getRoleName()));
            
            String playerUUIDStr = uuidComponent != null ? uuidComponent.getUuid().toString() : "unknown";
            LOGGER.atInfo().log("[Taming] SUCCESS - Dragonling %s tamed by player %s", npcComponent.getRoleName(), playerUUIDStr);
        } else {
            // Send failure message
            playerComponent.sendMessage(Message.translation("server.dragonlings.tamed.failed")
                .param("dragonling", npcComponent.getRoleName()));
            LOGGER.atInfo().log("[Taming] FAILED - Dragonling %s resisted taming attempt", npcComponent.getRoleName());
        }
        
        return true;
    }
    
    /**
     * Gets the required taming item for a dragonling variant based on role name.
     */
    @javax.annotation.Nullable
    private static String getTamingItemFromRole(@Nonnull Role role, @Nonnull String roleName) {
        // Map role names to taming items
        if (roleName.contains("Green")) {
            return "Ingredient_Life_Essence";
        } else if (roleName.contains("Blue")) {
            return "Ingredient_Ice_Essence";
        } else if (roleName.contains("Red")) {
            return "Ingredient_Fire_Essence";
        } else if (roleName.contains("Purple")) {
            return "Ingredient_Void_Essence";
        }
        return null;
    }
}
