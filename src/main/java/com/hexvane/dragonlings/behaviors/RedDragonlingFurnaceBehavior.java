package com.hexvane.dragonlings.behaviors;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hexvane.dragonlings.DragonlingData;
import it.unimi.dsi.fastutil.objects.ObjectList;
import javax.annotation.Nonnull;

/**
 * Behavior system for Red dragonlings - speeds up furnaces using Blow animation.
 */
public class RedDragonlingFurnaceBehavior extends EntityTickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double FURNACE_RADIUS = 8.0;
    private static final double BOOST_COOLDOWN = 2.0; // Seconds between boost attempts
    private static final double SPEED_BOOST = 2.0; // Additional seconds of progress per boost
    // Note: Base role has StopDistance of 2.0, so NPC will stop within 2.0 blocks of target
    private static final double APPROACH_DISTANCE = 3.0; // Distance to trigger boost (allows blowing from further away)
    
    @Nonnull
    private final ComponentType<EntityStore, NPCEntity> npcComponentType;
    @Nonnull
    private final ComponentType<EntityStore, DragonlingData> dragonlingDataType;
    @Nonnull
    private final Query<EntityStore> query;
    
    // Track boost cooldowns per dragonling
    private final java.util.Map<Ref<EntityStore>, Double> boostCooldowns = new java.util.HashMap<>();
    
    // Track last position to detect if NPC has stopped moving
    private final java.util.Map<Ref<EntityStore>, Vector3d> lastPositions = new java.util.HashMap<>();
    private final java.util.Map<Ref<EntityStore>, Double> lastPositionTime = new java.util.HashMap<>();
    
    public RedDragonlingFurnaceBehavior(
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
        
        // Only process Red dragonlings that are leashed
        if (!npcComponent.getRoleName().contains("Red") || !data.isLeashed()) {
            return;
        }
        
        Vector3d leashPos = data.getLeashPosition();
        if (leashPos == null) {
            return;
        }
        
        // Reduced logging frequency - only log occasionally
        // LOGGER.atInfo().log("[RedFurnace] %s checking for furnaces to boost near leash position", npcComponent.getRoleName());
        
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
        Double lastBoost = boostCooldowns.get(npcRef);
        
        Vector3d npcPos = transform.getPosition();
        int centerX = (int) Math.floor(leashPos.x);
        int centerY = (int) Math.floor(leashPos.y);
        int centerZ = (int) Math.floor(leashPos.z);
        
        // Find nearest active furnace in range
        com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk centerChunk = 
            world.getChunkIfInMemory(com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(centerX, centerZ));
        if (centerChunk == null) {
            // No chunk loaded, reset to WANDER state
            data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
            data.setTargetPosition(null);
            return;
        }
        
        int bestFurnaceX = 0, bestFurnaceY = 0, bestFurnaceZ = 0;
        double bestDistance = Double.MAX_VALUE;
        com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType bestBlockType = null;
        com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk bestChunk = null;
        
        // Scan area for furnaces in a sphere
        int radius = (int) Math.ceil(FURNACE_RADIUS);
        double radiusSq = FURNACE_RADIUS * FURNACE_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int bx = centerX + dx;
                int bz = centerZ + dz;
                
                // Get chunk for this block position
                long blockChunkIndex = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(bx, bz);
                com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk blockChunk = 
                    world.getChunkIfInMemory(blockChunkIndex);
                if (blockChunk == null) {
                    continue;
                }
                
                // Check blocks at different Y levels (full sphere)
                for (int dy = -radius; dy <= radius; dy++) {
                    int by = centerY + dy;
                    
                    // Check 3D distance (sphere)
                    double distanceSq = dx * dx + dy * dy + dz * dz;
                    if (distanceSq > radiusSq) {
                        continue; // Outside sphere radius
                    }
                    
                    // Use getBlock() - parameters are (x, y, z) where y is vertical, z is north/south
                    int blockId = blockChunk.getBlock(bx, by, bz);
                    if (blockId == 0) {
                        continue; // Air or invalid block
                    }
                    
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType blockType = 
                        com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset(blockId);
                    if (blockType == null) {
                        continue;
                    }
                    
                    // Check if it's a furnace (by checking block ID contains "Furnace" or "Smelter")
                    String blockTypeId = blockType.getId();
                    if (blockTypeId == null || (!blockTypeId.contains("Furnace") && !blockTypeId.contains("Smelter"))) {
                        continue;
                    }
                    
                    // Check if it's a ProcessingBench (furnace) and get its state
                    @SuppressWarnings("removal") // BlockState is deprecated but needed for ProcessingBenchState access
                    com.hypixel.hytale.server.core.universe.world.meta.BlockState blockState = blockChunk.getState(bx, by, bz);
                    if (!(blockState instanceof com.hypixel.hytale.builtin.crafting.state.ProcessingBenchState)) {
                        continue; // Not a processing bench/furnace
                    }
                    
                    com.hypixel.hytale.builtin.crafting.state.ProcessingBenchState furnaceState = 
                        (com.hypixel.hytale.builtin.crafting.state.ProcessingBenchState) blockState;
                    
                    // Check if furnace is active and processing
                    boolean isActive = furnaceState.isActive();
                    boolean hasRecipe = furnaceState.getRecipe() != null;
                    if (!isActive || !hasRecipe) {
                        continue; // Furnace not processing
                    }
                    
                    // Calculate distance from NPC to this furnace
                    Vector3d furnacePos = new Vector3d(bx + 0.5, by + 0.5, bz + 0.5);
                    double distance = npcPos.distanceTo(furnacePos);
                    
                    // Track the nearest active furnace
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestFurnaceX = bx;
                        bestFurnaceY = by;
                        bestFurnaceZ = bz;
                        bestBlockType = blockType;
                        bestChunk = blockChunk;
                    }
                }
            }
        }
        
        // If we found a furnace, set AI state to SEEK_FURNACE and set target position
        if (bestBlockType == null || bestChunk == null) {
            // No furnace found - reset to WANDER state
            if (data.getAIState() == com.hexvane.dragonlings.DragonlingAIState.SEEK_FURNACE) {
                data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                data.setTargetPosition(null);
            }
            return;
        }
        
        // We found a furnace - set target position to the furnace itself
        // Base role has StopDistance of 1.5, so NPC will stop at ~1.5 blocks from furnace center
        Vector3d furnacePos = new Vector3d(bestFurnaceX + 0.5, bestFurnaceY + 0.5, bestFurnaceZ + 0.5);
        
        // Set target to the furnace position itself
        Vector3d targetPos = furnacePos.clone();
        
        
        double distance = npcPos.distanceTo(targetPos);
        double distanceToFurnace = npcPos.distanceTo(furnacePos);
        
        
        // Set AI state to SEEK_FURNACE and target position
        // The AI system will handle making the dragonling move towards it
        data.setAIState(com.hexvane.dragonlings.DragonlingAIState.SEEK_FURNACE);
        data.setTargetPosition(targetPos);
        
        // If close enough to furnace, play animation and boost
        // Use distanceToFurnace to check if we're actually at the furnace
        if (distanceToFurnace <= APPROACH_DISTANCE) {
            // Check cooldown before boosting
            if (lastBoost != null && (currentTime - lastBoost) < BOOST_COOLDOWN) {
                return; // Still on cooldown
            }
            
            // Check if NPC is moving too fast - if moving more than 1.0 blocks/second, wait
            // But if very close (within 1.5 blocks), allow it even if moving slightly
            Vector3d lastPos = lastPositions.get(npcRef);
            Double lastPosTime = lastPositionTime.get(npcRef);
            
            if (lastPos != null && lastPosTime != null) {
                double timeDelta = currentTime - lastPosTime;
                if (timeDelta > 0.1) { // At least 0.1 seconds have passed
                    double movementDistance = npcPos.distanceTo(lastPos);
                    double speed = movementDistance / timeDelta;
                    // Use stricter speed check if further away, more lenient if very close
                    double maxSpeed = distanceToFurnace < 1.5 ? 1.5 : 0.8;
                    if (speed > maxSpeed) {
                        // Moving too fast, wait for it to slow down
                        lastPositions.put(npcRef, npcPos.clone());
                        lastPositionTime.put(npcRef, currentTime);
                        return;
                    }
                }
            }
            
            // Update position tracking
            lastPositions.put(npcRef, npcPos.clone());
            lastPositionTime.put(npcRef, currentTime);
            
            // Calculate yaw to face the furnace (only when about to blow)
            Vector3d direction = furnacePos.clone().subtract(npcPos);
            Vector3d horizontalDir = direction.clone();
            horizontalDir.y = 0; // Keep horizontal only for rotation
            double dirLength = horizontalDir.distanceTo(Vector3d.ZERO);
            if (dirLength > 0.01) {
                horizontalDir.normalize();
                // Calculate yaw: atan2(x, z) gives angle, but we may need to invert it
                // If facing wrong direction, try adding π (180 degrees) to flip it
                double yaw = Math.atan2(horizontalDir.x, horizontalDir.z) + Math.PI;
                
                // Set NPC rotation to face the furnace
                TransformComponent transformMutable = commandBuffer.getComponent(npcRef, TransformComponent.getComponentType());
                if (transformMutable != null) {
                    Vector3f rotation = transformMutable.getRotation();
                    rotation.setYaw((float) yaw);
                }
                
                // Set head rotation to face the furnace
                HeadRotation headRotation = commandBuffer.ensureAndGetComponent(npcRef, HeadRotation.getComponentType());
                if (headRotation != null) {
                    Vector3f headRot = headRotation.getRotation();
                    headRot.setYaw((float) yaw);
                    headRot.setPitch(0.0f); // Look straight ahead
                }
            }
            
            // Play Blow animation
            npcComponent.playAnimation(npcRef, 
                com.hypixel.hytale.protocol.AnimationSlot.Action, 
                "Blow", 
                commandBuffer);
            
            // Spawn fire particles from the dragonling's mouth/snout
            // Calculate mouth position based on NPC position and rotation
            TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
            Vector3d mouthPos;
            Vector3f particleRotation = null;
            
            if (npcTransform != null) {
                Vector3d npcWorldPos = npcTransform.getPosition();
                Vector3f npcRotation = npcTransform.getRotation();
                particleRotation = npcRotation; // Store rotation for particle spawn
                
                // Use slightly below eye height for mouth position (snout level)
                double headYOffset = 0.45; // Slightly below eye height (0.8) for snout/mouth level
                double mouthForwardOffset = 0.5; // Forward offset for mouth position (in front of head)
                
                // Calculate forward direction from yaw rotation
                // Invert direction since yaw might be 180 degrees off (based on earlier rotation fixes)
                float yaw = npcRotation.getYaw();
                double forwardX = -Math.sin(yaw) * mouthForwardOffset;
                double forwardZ = -Math.cos(yaw) * mouthForwardOffset;
                
                // Calculate mouth position: NPC position + eye height + forward direction
                mouthPos = npcWorldPos.clone();
                mouthPos.y += headYOffset;
                mouthPos.x += forwardX;
                mouthPos.z += forwardZ;
            } else {
                // Fallback: spawn at NPC position + snout height if transform unavailable
                mouthPos = npcPos.clone();
                mouthPos.y += 0.65; // Snout/mouth level
            }
            
            // Spawn particle effect at mouth position
            // Collect nearby players so they can see the particles
            SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource = 
                commandBuffer.getResource(EntityModule.get().getPlayerSpatialResourceType());
            ObjectList<Ref<EntityStore>> playerRefs = SpatialResource.getThreadLocalReferenceList();
            playerSpatialResource.getSpatialStructure().collect(mouthPos, 75.0, playerRefs);
            
            // The particle spawner has a narrow velocity cone (-10 to 10 degrees Yaw/Pitch)
            // Pass the NPC's rotation so particles spawn in the direction the dragonling is facing
            if (particleRotation != null) {
                com.hypixel.hytale.server.core.universe.world.ParticleUtil.spawnParticleEffect(
                    "Dragonling_Red_Fire",
                    mouthPos,
                    particleRotation,
                    playerRefs,
                    commandBuffer
                );
            } else {
                // Fallback: spawn without rotation if unavailable
                com.hypixel.hytale.server.core.universe.world.ParticleUtil.spawnParticleEffect(
                    "Dragonling_Red_Fire",
                    mouthPos,
                    playerRefs,
                    commandBuffer
                );
            }
            
            // Speed up furnace by adding progress directly using reflection
            @SuppressWarnings("removal") // BlockState is deprecated but needed for ProcessingBenchState access
            com.hypixel.hytale.server.core.universe.world.meta.BlockState blockState = bestChunk.getState(bestFurnaceX, bestFurnaceY, bestFurnaceZ);
            if (blockState instanceof com.hypixel.hytale.builtin.crafting.state.ProcessingBenchState) {
                com.hypixel.hytale.builtin.crafting.state.ProcessingBenchState furnaceState = 
                    (com.hypixel.hytale.builtin.crafting.state.ProcessingBenchState) blockState;
                
                // Add progress to speed up processing (2x speed = add 1 second of progress per boost)
                try {
                    java.lang.reflect.Field inputProgressField = 
                        com.hypixel.hytale.builtin.crafting.state.ProcessingBenchState.class.getDeclaredField("inputProgress");
                    inputProgressField.setAccessible(true);
                    float currentProgress = inputProgressField.getFloat(furnaceState);
                    // Add SPEED_BOOST seconds of progress (2x speed)
                    inputProgressField.setFloat(furnaceState, currentProgress + (float)SPEED_BOOST);
                    
                } catch (Exception e) {
                    LOGGER.atWarning().withCause(e).log("[RedFurnace] Failed to boost furnace progress");
                }
            }
            
            boostCooldowns.put(npcRef, currentTime);
            // Keep in SEEK_FURNACE state so they stay near the furnace
            // The state will be reset when no furnace is found
        }
    }
    
    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }
}