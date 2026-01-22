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
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hexvane.dragonlings.DragonlingData;
import it.unimi.dsi.fastutil.objects.ObjectList;
import javax.annotation.Nonnull;

/**
 * Behavior system for Blue dragonlings - waters dry farmland using Blow animation.
 */
public class BlueDragonlingWaterBehavior extends EntityTickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double WATER_RADIUS = 11.0;
    private static final double WATER_COOLDOWN = 3.0; // Seconds between watering attempts (animation is 60 ticks = 3 seconds, add buffer)
    // Note: Base role has StopDistance of 2.0, so NPC will stop within 2.0 blocks of target
    private static final double APPROACH_DISTANCE = 3.0; // Distance to trigger watering (allows blowing from further away)
    
    @Nonnull
    private final ComponentType<EntityStore, NPCEntity> npcComponentType;
    @Nonnull
    private final ComponentType<EntityStore, DragonlingData> dragonlingDataType;
    @Nonnull
    private final Query<EntityStore> query;
    
    // Track water cooldowns per dragonling
    private final java.util.Map<Ref<EntityStore>, Double> waterCooldowns = new java.util.HashMap<>();
    
    // Track last position and time for movement stability check
    private final java.util.Map<Ref<EntityStore>, Vector3d> lastPositions = new java.util.HashMap<>();
    private final java.util.Map<Ref<EntityStore>, Double> lastPositionTime = new java.util.HashMap<>();
    
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
        
        Vector3d npcPos = transform.getPosition();
        Vector3d existingTarget = data.getTargetPosition();
        
        // If we have an existing target, check if we should water it first before scanning
        boolean shouldWaterExisting = false;
        if (existingTarget != null && data.getAIState() == com.hexvane.dragonlings.DragonlingAIState.WATER_CROPS) {
            double distanceToCurrentTarget = npcPos.distanceTo(existingTarget);
            if (distanceToCurrentTarget <= APPROACH_DISTANCE) {
                // We're close enough - check if we can water it (this will be handled in watering section)
                shouldWaterExisting = true;
            } else {
                // Still moving towards target - don't scan yet, but also don't return early
                // We'll scan later but prioritize the existing target
            }
        }
        
        int centerX = (int) Math.floor(leashPos.x);
        int centerY = (int) Math.floor(leashPos.y);
        int centerZ = (int) Math.floor(leashPos.z);
        
        // Get chunk
        com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk centerChunk = 
            world.getChunkIfInMemory(com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(centerX, centerZ));
        if (centerChunk == null) {
            // No chunk loaded, reset to WANDER state
            data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
            data.setTargetPosition(null);
            return;
        }
        
        int bestFarmlandX = 0, bestFarmlandY = 0, bestFarmlandZ = 0;
        double bestDistance = Double.MAX_VALUE;
        com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType bestBlockType = null;
        com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk bestChunk = null;
        
        // Scan area for farmland blocks (using same approach as green dragonling)
        int radius = (int) Math.ceil(WATER_RADIUS);
        double radiusSq = WATER_RADIUS * WATER_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int bx = centerX + dx;
                int bz = centerZ + dz;
                
                // Check 2D distance first (horizontal only, farmland is on ground)
                double horizontalDistSq = dx * dx + dz * dz;
                if (horizontalDistSq > radiusSq) {
                    continue; // Already outside radius even at closest Y
                }
                
                // Get chunk for this block position (might be different from center chunk)
                long blockChunkIndex = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(bx, bz);
                com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk blockChunk = 
                    world.getChunkIfInMemory(blockChunkIndex);
                if (blockChunk == null) {
                    continue; // Chunk not loaded, skip this block
                }
                
                // Check blocks at different Y levels (farmland is typically on ground)
                for (int dy = -2; dy <= 2; dy++) {
                    int by = centerY + dy;
                    
                    // Check 2D distance (horizontal only, farmland is on ground)
                    double distanceSq = horizontalDistSq;
                    if (distanceSq > radiusSq) {
                        continue;
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
                    
                    // Check if it's farmland (checking for farming data)
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData farmingData = 
                        blockType.getFarming();
                    if (farmingData == null) {
                        continue;
                    }
                    
                    // Check if farmland is already watered by checking TilledSoilBlock component
                    // Use the same chunk we're already working with
                    com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> checkBlockRef = 
                        blockChunk.getBlockComponentEntity(bx, by, bz);
                    if (checkBlockRef != null) {
                        com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> chunkStore = world.getChunkStore().getStore();
                        com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock tilledSoil = 
                            chunkStore.getComponent(checkBlockRef, com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock.getComponentType());
                        if (tilledSoil != null) {
                            // Check if farmland is currently watered
                            // Get game time from world's entity store resource (same as we'll use when watering)
                            com.hypixel.hytale.server.core.modules.time.WorldTimeResource worldTimeResource = 
                                world.getEntityStore().getStore().getResource(
                                    com.hypixel.hytale.server.core.modules.time.WorldTimeResource.getResourceType());
                            if (worldTimeResource != null) {
                                java.time.Instant gameTime = worldTimeResource.getGameTime();
                                java.time.Instant wateredUntil = tilledSoil.getWateredUntil();
                                boolean isWatered = tilledSoil.hasExternalWater() || 
                                    (wateredUntil != null && wateredUntil.isAfter(gameTime));
                                if (isWatered) {
                                    continue; // Already watered, skip this farmland
                                }
                            }
                        }
                    }
                    
                    // Calculate distance from NPC to this farmland
                    Vector3d farmlandPos = new Vector3d(bx + 0.5, by + 1.0, bz + 0.5);
                    double distance = npcPos.distanceTo(farmlandPos);
                    
                    // Track the nearest farmland that hasn't been recently watered
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestFarmlandX = bx;
                        bestFarmlandY = by;
                        bestFarmlandZ = bz;
                        bestBlockType = blockType;
                        bestChunk = blockChunk;
                    }
                }
            }
        }
        
        // Handle existing target if we're close enough to water it
        if (shouldWaterExisting && existingTarget != null) {
            // We have an existing target and we're close - try to water it
            // Need to reverse-engineer block position from target position
            int targetX = (int) Math.floor(existingTarget.x);
            int targetY = (int) Math.floor(existingTarget.y - 1.0); // Target is at block + 1.0 Y
            int targetZ = (int) Math.floor(existingTarget.z);
            
            // Get chunk for the target
            long targetChunkIndex = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(targetX, targetZ);
            com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk targetChunk = 
                world.getChunkIfInMemory(targetChunkIndex);
            
            if (targetChunk != null) {
                double distanceToTarget = npcPos.distanceTo(existingTarget);
                if (distanceToTarget <= APPROACH_DISTANCE) {
                    // Check if this farmland is already watered before proceeding
                    com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> checkBlockRef = 
                        targetChunk.getBlockComponentEntity(targetX, targetY, targetZ);
                    if (checkBlockRef != null) {
                        com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> chunkStore = 
                            world.getChunkStore().getStore();
                        com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock checkSoil = 
                            chunkStore.getComponent(checkBlockRef, com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock.getComponentType());
                        if (checkSoil != null) {
                            com.hypixel.hytale.server.core.modules.time.WorldTimeResource checkTimeResource = 
                                commandBuffer.getResource(com.hypixel.hytale.server.core.modules.time.WorldTimeResource.getResourceType());
                            if (checkTimeResource != null) {
                                java.time.Instant gameTime = checkTimeResource.getGameTime();
                                java.time.Instant wateredUntil = checkSoil.getWateredUntil();
                                boolean isWatered = checkSoil.hasExternalWater() || 
                                    (wateredUntil != null && wateredUntil.isAfter(gameTime));
                                if (isWatered) {
                                    // Already watered, clear target and look for a new one
                                    data.setTargetPosition(null);
                                    data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                                    return;
                                }
                            }
                        }
                    }
                    
                    // Use the existing target's farmland position for watering logic
                    // Get block type for the farmland at this position
                    int blockId = targetChunk.getBlock(targetX, targetY, targetZ);
                    if (blockId != 0) {
                        com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType blockType = 
                            com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset(blockId);
                        if (blockType != null && blockType.getFarming() != null) {
                            bestFarmlandX = targetX;
                            bestFarmlandY = targetY;
                            bestFarmlandZ = targetZ;
                            bestChunk = targetChunk;
                            bestBlockType = blockType;
                            bestDistance = distanceToTarget;
                            // Fall through to watering logic below - skip scanning since we already have a target
                        } else {
                            // Not farmland anymore, clear target
                            data.setTargetPosition(null);
                            data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                            return;
                        }
                    } else {
                        // Block doesn't exist, clear target
                        data.setTargetPosition(null);
                        data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                        return;
                    }
                } else {
                    // Not close enough yet, keep moving
                    return;
                }
            } else {
                // Chunk unloaded, clear target
                data.setTargetPosition(null);
                data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                return;
            }
        }
        
        // If we found dry farmland, set AI state to WATER_CROPS and set target position
        if (bestBlockType == null || bestChunk == null) {
            // No dry farmland found - reset to WANDER state
            if (data.getAIState() == com.hexvane.dragonlings.DragonlingAIState.WATER_CROPS) {
                data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                data.setTargetPosition(null);
            }
            return;
        }
        
        // We found dry farmland - set target position to the farmland
        Vector3d farmlandPos = new Vector3d(bestFarmlandX + 0.5, bestFarmlandY + 1.0, bestFarmlandZ + 0.5);
        Vector3d targetPos = farmlandPos.clone();
        
        double distanceToFarmland = npcPos.distanceTo(farmlandPos);
        
        // Check if we're already targeting this farmland
        boolean isAlreadyTargeting = (existingTarget != null && 
            Math.abs(existingTarget.x - targetPos.x) < 0.1 && 
            Math.abs(existingTarget.y - targetPos.y) < 0.1 && 
            Math.abs(existingTarget.z - targetPos.z) < 0.1);
        
        if (!isAlreadyTargeting) {
            // Set AI state to WATER_CROPS and target position
            // The AI system will handle making the dragonling move towards it
            data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WATER_CROPS);
            data.setTargetPosition(targetPos);
        }
        
        // If close enough to farmland, check if it still needs watering and water it
        if (distanceToFarmland <= APPROACH_DISTANCE) {
            // Check cooldown before watering
            if (lastWater != null && (currentTime - lastWater) < WATER_COOLDOWN) {
                return;
            }
            
            // Double-check that this farmland is still dry before watering
            // Use commandBuffer resource to get time (same as we'll use when watering)
            com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> checkBlockRef = 
                bestChunk.getBlockComponentEntity(bestFarmlandX, bestFarmlandY, bestFarmlandZ);
            if (checkBlockRef != null) {
                com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> chunkStore = 
                    world.getChunkStore().getStore();
                com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock checkSoil = 
                    chunkStore.getComponent(checkBlockRef, com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock.getComponentType());
                if (checkSoil != null) {
                    com.hypixel.hytale.server.core.modules.time.WorldTimeResource checkTimeResource = 
                        commandBuffer.getResource(com.hypixel.hytale.server.core.modules.time.WorldTimeResource.getResourceType());
                    if (checkTimeResource != null) {
                        java.time.Instant gameTime = checkTimeResource.getGameTime();
                        java.time.Instant wateredUntil = checkSoil.getWateredUntil();
                        boolean isWatered = checkSoil.hasExternalWater() || 
                            (wateredUntil != null && wateredUntil.isAfter(gameTime));
                        if (isWatered) {
                            // Already watered, clear target and look for a new one
                            data.setTargetPosition(null);
                            data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                            return;
                        }
                    }
                }
            }
            
            // Check movement stability - don't water if moving too fast (bouncing around)
            Vector3d lastPos = lastPositions.get(npcRef);
            Double lastPosTime = lastPositionTime.get(npcRef);
            if (lastPos != null && lastPosTime != null) {
                double timeDelta = currentTime - lastPosTime;
                if (timeDelta > 0.1) { // At least 0.1 seconds have passed
                    double movementDistance = npcPos.distanceTo(lastPos);
                    double speed = movementDistance / timeDelta;
                    double maxSpeed = distanceToFarmland < 1.5 ? 1.5 : 0.8;
                    if (speed > maxSpeed) {
                        lastPositions.put(npcRef, npcPos.clone());
                        lastPositionTime.put(npcRef, currentTime);
                        return;
                    }
                }
            }
            
            // Update position tracking
            lastPositions.put(npcRef, npcPos.clone());
            lastPositionTime.put(npcRef, currentTime);
            
            // Final check: Make absolutely sure the farmland is still dry before playing animation and watering
            // This prevents the animation from playing if farmland was just watered
            com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> finalCheckBlockRef = 
                bestChunk.getBlockComponentEntity(bestFarmlandX, bestFarmlandY, bestFarmlandZ);
            if (finalCheckBlockRef != null) {
                com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> chunkStore = 
                    world.getChunkStore().getStore();
                com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock finalCheckSoil = 
                    chunkStore.getComponent(finalCheckBlockRef, com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock.getComponentType());
                if (finalCheckSoil != null) {
                    com.hypixel.hytale.server.core.modules.time.WorldTimeResource finalCheckTimeResource = 
                        commandBuffer.getResource(com.hypixel.hytale.server.core.modules.time.WorldTimeResource.getResourceType());
                    if (finalCheckTimeResource != null) {
                        java.time.Instant gameTime = finalCheckTimeResource.getGameTime();
                        java.time.Instant wateredUntil = finalCheckSoil.getWateredUntil();
                        boolean isWatered = finalCheckSoil.hasExternalWater() || 
                            (wateredUntil != null && wateredUntil.isAfter(gameTime));
                        if (isWatered) {
                            // Already watered, clear target and return immediately (don't play animation)
                            data.setTargetPosition(null);
                            data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                            return;
                        }
                    }
                }
            }
            
            // Play Blow animation BEFORE watering (same as red dragonling)
            // Only play if we've confirmed the farmland is still dry
            npcComponent.playAnimation(npcRef, 
                com.hypixel.hytale.protocol.AnimationSlot.Action, 
                "Blow", 
                commandBuffer);
            
            // Try to water the farmland (using same approach as UseWateringCanInteraction)
            try {
                // Get or ensure block entity exists (using same approach as watering can)
                com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> blockRef = 
                    bestChunk.getBlockComponentEntity(bestFarmlandX, bestFarmlandY, bestFarmlandZ);
                if (blockRef == null) {
                    try {
                        blockRef = com.hypixel.hytale.server.core.modules.block.BlockModule.ensureBlockEntity(
                            bestChunk, bestFarmlandX, bestFarmlandY, bestFarmlandZ);
                    } catch (IllegalArgumentException e) {
                        // Block entity was created by another thread, get it again
                        blockRef = bestChunk.getBlockComponentEntity(bestFarmlandX, bestFarmlandY, bestFarmlandZ);
                    }
                }
                
                if (blockRef != null) {
                    com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> chunkStore = 
                        world.getChunkStore().getStore();
                    com.hypixel.hytale.server.core.modules.time.WorldTimeResource worldTimeResource = 
                        commandBuffer.getResource(com.hypixel.hytale.server.core.modules.time.WorldTimeResource.getResourceType());
                    
                    com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock soil = 
                        chunkStore.getComponent(blockRef, com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock.getComponentType());
                    if (soil == null) {
                        // Create TilledSoilBlock component if it doesn't exist (farmland that hasn't been watered yet)
                        soil = new com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock(false, false, false, null, null);
                        chunkStore.addComponent(blockRef, com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock.getComponentType(), soil);
                    }
                    
                    // Set watered until 60 REAL seconds from now
                    // IMPORTANT: Hytale's game time advances much faster than real time
                    // Based on logs: 8 real seconds ≈ 5.5 game minutes (330 game seconds)
                    // So 1 real second ≈ 41 game seconds
                    // For 60 real seconds, we need: 60 * 41 ≈ 2460 game seconds ≈ 41 game minutes
                    // Using 2400 game seconds (40 minutes) to be safe
                    java.time.Instant gameTime = worldTimeResource.getGameTime();
                    java.time.Instant wateredUntil = gameTime.plus(2400, java.time.temporal.ChronoUnit.SECONDS);
                    
                    // Set the watered until time on the component
                    soil.setWateredUntil(wateredUntil);
                    
                    // Enable ticking and schedule tick (required for the watered state to persist)
                    // Using same methods as UseWateringCanInteraction (deprecated but still the standard way)
                    // scheduleTick schedules a block update at the specified time (when it should check if it needs to dry)
                    bestChunk.setTicking(bestFarmlandX, bestFarmlandY, bestFarmlandZ, true);
                    bestChunk.getBlockChunk().getSectionAtBlockY(bestFarmlandY).scheduleTick(
                        com.hypixel.hytale.math.util.ChunkUtil.indexBlock(bestFarmlandX, bestFarmlandY, bestFarmlandZ), 
                        wateredUntil);
                    bestChunk.setTicking(bestFarmlandX, bestFarmlandY + 1, bestFarmlandZ, true);
                    
                    // Spawn water particles from the dragonling's mouth/snout (same as red dragonling fire particles)
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
                        mouthPos.y += 0.45; // Snout/mouth level
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
                            "Dragonling_Blue_Water",
                            mouthPos,
                            particleRotation,
                            playerRefs,
                            commandBuffer
                        );
                    } else {
                        // Fallback: spawn without rotation if unavailable
                        com.hypixel.hytale.server.core.universe.world.ParticleUtil.spawnParticleEffect(
                            "Dragonling_Blue_Water",
                            mouthPos,
                            playerRefs,
                            commandBuffer
                        );
                    }
                    
                    // Update cooldown to prevent immediate re-watering (animation is 60 ticks = 3 seconds)
                    waterCooldowns.put(npcRef, currentTime);
                    
                    // Clear target position so it looks for a new farmland on next scan
                    // Don't immediately scan for a new one - let the dragonling move away first
                    data.setTargetPosition(null);
                    data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                    return; // Exit early - don't scan for new farmland this tick
                } else {
                    // Block entity doesn't exist - check if there's a crop on top that needs the soil below watered
                    com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> chunkStore = 
                        world.getChunkStore().getStore();
                    com.hypixel.hytale.server.core.modules.time.WorldTimeResource worldTimeResource = 
                        commandBuffer.getResource(com.hypixel.hytale.server.core.modules.time.WorldTimeResource.getResourceType());
                    
                    // Try to get block entity again after ensuring it
                    blockRef = bestChunk.getBlockComponentEntity(bestFarmlandX, bestFarmlandY, bestFarmlandZ);
                    if (blockRef == null) {
                        try {
                            blockRef = com.hypixel.hytale.server.core.modules.block.BlockModule.ensureBlockEntity(
                                bestChunk, bestFarmlandX, bestFarmlandY, bestFarmlandZ);
                        } catch (IllegalArgumentException e) {
                            // Block entity was created by another thread, get it again
                            blockRef = bestChunk.getBlockComponentEntity(bestFarmlandX, bestFarmlandY, bestFarmlandZ);
                        }
                    }
                    if (blockRef != null) {
                        com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock farmingState = 
                            chunkStore.getComponent(blockRef, com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock.getComponentType());
                        if (farmingState != null) {
                            // Water the soil block below
                            com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> soilRef = 
                                bestChunk.getBlockComponentEntity(bestFarmlandX, bestFarmlandY - 1, bestFarmlandZ);
                            if (soilRef == null) {
                                try {
                                    soilRef = com.hypixel.hytale.server.core.modules.block.BlockModule.ensureBlockEntity(
                                        bestChunk, bestFarmlandX, bestFarmlandY - 1, bestFarmlandZ);
                                } catch (IllegalArgumentException e) {
                                    // Block entity was created by another thread, get it again
                                    soilRef = bestChunk.getBlockComponentEntity(bestFarmlandX, bestFarmlandY - 1, bestFarmlandZ);
                                }
                            }
                            if (soilRef != null) {
                                com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock soil = 
                                    chunkStore.getComponent(soilRef, com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock.getComponentType());
                                if (soil == null) {
                                    // Create TilledSoilBlock component if it doesn't exist
                                    soil = new com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock(false, false, false, null, null);
                                    chunkStore.addComponent(soilRef, com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock.getComponentType(), soil);
                                }
                                
                                // Set watered until 60 REAL seconds from now (same calculation as direct farmland watering above)
                                // Hytale's game time advances much faster than real time, so use 2400 game seconds
                                java.time.Instant gameTime = worldTimeResource.getGameTime();
                                java.time.Instant wateredUntil = gameTime.plus(2400, java.time.temporal.ChronoUnit.SECONDS);
                                soil.setWateredUntil(wateredUntil);
                                
                                // Using same methods as UseWateringCanInteraction (deprecated but still the standard way)
                                bestChunk.getBlockChunk().getSectionAtBlockY(bestFarmlandY - 1).scheduleTick(
                                    com.hypixel.hytale.math.util.ChunkUtil.indexBlock(bestFarmlandX, bestFarmlandY - 1, bestFarmlandZ), 
                                    wateredUntil);
                                bestChunk.setTicking(bestFarmlandX, bestFarmlandY - 1, bestFarmlandZ, true);
                                bestChunk.setTicking(bestFarmlandX, bestFarmlandY, bestFarmlandZ, true);
                                
                                // Spawn water particles (same as direct farmland watering above)
                                TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
                                Vector3d mouthPos;
                                Vector3f particleRotation = null;
                                
                                if (npcTransform != null) {
                                    Vector3d npcWorldPos = npcTransform.getPosition();
                                    Vector3f npcRotation = npcTransform.getRotation();
                                    particleRotation = npcRotation;
                                    
                                    double headYOffset = 0.45;
                                    double mouthForwardOffset = 0.5;
                                    float yaw = npcRotation.getYaw();
                                    double forwardX = -Math.sin(yaw) * mouthForwardOffset;
                                    double forwardZ = -Math.cos(yaw) * mouthForwardOffset;
                                    
                                    mouthPos = npcWorldPos.clone();
                                    mouthPos.y += headYOffset;
                                    mouthPos.x += forwardX;
                                    mouthPos.z += forwardZ;
                                } else {
                                    mouthPos = npcPos.clone();
                                    mouthPos.y += 0.45;
                                }
                                
                                SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource = 
                                    commandBuffer.getResource(EntityModule.get().getPlayerSpatialResourceType());
                                ObjectList<Ref<EntityStore>> playerRefs = SpatialResource.getThreadLocalReferenceList();
                                playerSpatialResource.getSpatialStructure().collect(mouthPos, 75.0, playerRefs);
                                
                                if (particleRotation != null) {
                                    com.hypixel.hytale.server.core.universe.world.ParticleUtil.spawnParticleEffect(
                                        "Dragonling_Blue_Water",
                                        mouthPos,
                                        particleRotation,
                                        playerRefs,
                                        commandBuffer
                                    );
                                } else {
                                    com.hypixel.hytale.server.core.universe.world.ParticleUtil.spawnParticleEffect(
                                        "Dragonling_Blue_Water",
                                        mouthPos,
                                        playerRefs,
                                        commandBuffer
                                    );
                                }
                                
                                // Update cooldown to prevent immediate re-watering (animation is 60 ticks = 3 seconds)
                                waterCooldowns.put(npcRef, currentTime);
                                
                                // Clear target position so it looks for a new farmland on next scan
                                // Don't immediately scan for a new one - let the dragonling move away first
                                data.setTargetPosition(null);
                                data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                                return; // Exit early - don't scan for new farmland this tick
                            }
                        }
                    }
                }
                
                // Play Blow animation (only if we successfully watered)
                npcComponent.playAnimation(npcRef, 
                    com.hypixel.hytale.protocol.AnimationSlot.Action, 
                    "Blow", 
                    commandBuffer);
                
                // Spawn water particles from the dragonling's mouth/snout
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
                    mouthPos.y += 0.45; // Snout/mouth level
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
                        "Dragonling_Blue_Water",
                        mouthPos,
                        particleRotation,
                        playerRefs,
                        commandBuffer
                    );
                } else {
                    // Fallback: spawn without rotation if unavailable
                    com.hypixel.hytale.server.core.universe.world.ParticleUtil.spawnParticleEffect(
                        "Dragonling_Blue_Water",
                        mouthPos,
                        playerRefs,
                        commandBuffer
                    );
                }
            } catch (Exception e) {
                LOGGER.atWarning().withCause(e).log("[BlueWater] Failed to water farmland at (%d, %d, %d)", 
                    bestFarmlandX, bestFarmlandY, bestFarmlandZ);
            }
        }
    }
    
    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }
}
