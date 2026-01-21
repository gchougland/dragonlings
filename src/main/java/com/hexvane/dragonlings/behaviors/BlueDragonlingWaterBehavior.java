package com.hexvane.dragonlings.behaviors;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hexvane.dragonlings.DragonlingData;
import javax.annotation.Nonnull;

/**
 * Behavior system for Blue dragonlings - waters crops using Blow animation.
 */
public class BlueDragonlingWaterBehavior extends EntityTickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double WATER_RADIUS = 8.0;
    private static final double WATER_COOLDOWN = 5.0; // Seconds between watering attempts
    
    @Nonnull
    private final ComponentType<EntityStore, NPCEntity> npcComponentType;
    @Nonnull
    private final ComponentType<EntityStore, DragonlingData> dragonlingDataType;
    @Nonnull
    private final Query<EntityStore> query;
    
    // Track water cooldowns per dragonling
    private final java.util.Map<Ref<EntityStore>, Double> waterCooldowns = new java.util.HashMap<>();
    
    public BlueDragonlingWaterBehavior(
            @Nonnull ComponentType<EntityStore, NPCEntity> npcComponentType,
            @Nonnull ComponentType<EntityStore, DragonlingData> dragonlingDataType) {
        this.npcComponentType = npcComponentType;
        this.dragonlingDataType = dragonlingDataType;
        this.query = Query.and(npcComponentType, dragonlingDataType);
    }
    
    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        
        NPCEntity npcComponent = archetypeChunk.getComponent(index, this.npcComponentType);
        DragonlingData data = archetypeChunk.getComponent(index, this.dragonlingDataType);
        
        if (npcComponent == null || data == null) {
            return;
        }
        
        // Only process Blue dragonlings that are leashed
        if (!npcComponent.getRoleName().contains("Blue") || !data.isLeashed()) {
            return;
        }
        
        Vector3d leashPos = data.getLeashPosition();
        if (leashPos == null) {
            return;
        }
        
        LOGGER.atInfo().log("[BlueWater] %s checking for farmland to water near leash position", npcComponent.getRoleName());
        
        TransformComponent transform = store.getComponent(archetypeChunk.getReferenceTo(index), TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        
        World world = npcComponent.getWorld();
        if (world == null) {
            return;
        }
        
        // Track cooldown per dragonling
        Ref<EntityStore> npcRef = archetypeChunk.getReferenceTo(index);
        double currentTime = System.currentTimeMillis() / 1000.0;
        Double lastWater = waterCooldowns.get(npcRef);
        if (lastWater != null && (currentTime - lastWater) < WATER_COOLDOWN) {
            return;
        }
        
        Vector3d npcPos = transform.getPosition();
        int centerX = (int) Math.floor(leashPos.x);
        int centerY = (int) Math.floor(leashPos.y);
        int centerZ = (int) Math.floor(leashPos.z);
        
        // Get chunk
        com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk = 
            world.getChunkIfInMemory(com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(centerX, centerZ));
        if (chunk == null) {
            return;
        }
        
        // Scan area for farmland blocks
        int radius = (int) Math.ceil(WATER_RADIUS);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > WATER_RADIUS * WATER_RADIUS) {
                    continue;
                }
                
                int bx = centerX + dx;
                int bz = centerZ + dz;
                
                // Check blocks at different Y levels
                for (int by = centerY - 2; by <= centerY + 2; by++) {
                    com.hypixel.hytale.server.core.universe.world.meta.BlockState blockState = chunk.getState(bx, by, bz);
                    if (blockState == null) {
                        continue;
                    }
                    
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType blockType = blockState.getBlockType();
                    if (blockType == null) {
                        continue;
                    }
                    
                    // Check if it's farmland (checking for farming data)
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData farmingData = 
                        blockType.getFarming();
                    if (farmingData == null) {
                        continue;
                    }
                    
                    LOGGER.atInfo().log("[BlueWater] %s detected farmland at (%d, %d, %d): %s", 
                        npcComponent.getRoleName(), bx, by, bz, blockType.getId());
                    
                    // Try to set farmland to watered state
                    // Use setBlockInteractionState to set to "Watered" state if available
                    try {
                        chunk.setBlockInteractionState(bx, by, bz, blockType, "Watered", false);
                        
                        LOGGER.atInfo().log("[BlueWater] %s watering farmland at (%d, %d, %d)", 
                            npcComponent.getRoleName(), bx, by, bz);
                        
                        // Play Blow animation
                        npcComponent.playAnimation(npcRef, 
                            com.hypixel.hytale.protocol.AnimationSlot.Action, 
                            "Blow", 
                            commandBuffer);
                        
                        LOGGER.atInfo().log("[BlueWater] %s playing Blow animation", npcComponent.getRoleName());
                        
                        // Spawn water particles
                        com.hypixel.hytale.math.vector.Vector3d particlePos = 
                            new com.hypixel.hytale.math.vector.Vector3d(bx + 0.5, by + 1.0, bz + 0.5);
                        com.hypixel.hytale.server.core.universe.world.ParticleUtil.spawnParticleEffect(
                            "Dragonling_Blue_Water",
                            particlePos,
                            java.util.Collections.emptyList(),
                            commandBuffer
                        );
                        
                        LOGGER.atInfo().log("[BlueWater] %s spawned water particles at (%d, %d, %d)", 
                            npcComponent.getRoleName(), bx, by, bz);
                        
                        waterCooldowns.put(npcRef, currentTime);
                        LOGGER.atInfo().log("[BlueWater] %s watering complete, cooldown set", npcComponent.getRoleName());
                        return; // Only water one block per tick
                    } catch (Exception e) {
                        LOGGER.atInfo().log("[BlueWater] %s failed to set watered state at (%d, %d, %d): %s", 
                            npcComponent.getRoleName(), bx, by, bz, e.getMessage());
                        // State might not exist, continue searching
                        continue;
                    }
                }
            }
        }
    }
    
    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }
}
