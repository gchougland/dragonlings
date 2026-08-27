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
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hexvane.dragonlings.DragonlingData;
import com.hexvane.dragonlings.DragonlingTamework;
import com.hexvane.dragonlings.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.math.util.ChunkUtil;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Behavior system for Blue dragonlings - waters dry farmland using Blow animation.
 */
public class BlueDragonlingWaterBehavior extends EntityTickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    public static final double WATER_RADIUS = 11.0;
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
        Ref<EntityStore> npcRef = archetypeChunk.getReferenceTo(index);
        DragonlingData data = commandBuffer.getComponent(npcRef, this.dragonlingDataType);
        
        if (npcComponent == null || data == null) {
            return;
        }
        
        if (!npcComponent.getRoleName().contains("Blue")) {
            return;
        }

        try {
        Vector3d leashPos = DragonlingTamework.getWorkAnchor(commandBuffer, npcRef);
        if (leashPos == null) {
            return;
        }
        if (DragonlingTamework.isTamed(store, npcRef)
            && DragonlingTamework.shouldPauseHomeAssignmentWork(npcComponent, commandBuffer, npcRef)) {
            return;
        }
        
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        
        World world = npcComponent.getWorld();
        if (world == null) {
            return;
        }

        // Same clock as watering / entity tick — world entity store time can disagree and mark wet soil as "dry".
        com.hypixel.hytale.server.core.modules.time.WorldTimeResource scanTimeResource =
            commandBuffer.getResource(com.hypixel.hytale.server.core.modules.time.WorldTimeResource.getResourceType());
        
        double currentTime = System.currentTimeMillis() / 1000.0;
        Double lastWater = waterCooldowns.get(npcRef);
        
        Vector3d npcPos = transform.getPosition();
        Vector3d existingTarget = data.getTargetPosition();
        
        // If we have an existing target, check if we should water it first before scanning
        boolean shouldWaterExisting = false;
        if (existingTarget != null && data.getAIState() == com.hexvane.dragonlings.DragonlingAIState.WATER_CROPS) {
            double distanceToCurrentTarget = npcPos.distance(existingTarget);
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
        if (!ChunkSectionBlockUtil.isChunkInMemory(world, centerX, centerZ)) {
            data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
            data.setTargetPosition(null);
            return;
        }
        
        int bestFarmlandX = 0, bestFarmlandY = 0, bestFarmlandZ = 0;
        double bestDistance = Double.MAX_VALUE;
        com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType bestBlockType = null;
        
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
                
                if (!ChunkSectionBlockUtil.isChunkInMemory(world, bx, bz)) {
                    continue; // Chunk not loaded, skip this block
                }
                
                com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> chunkStore =
                    world.getChunkStore().getStore();
                for (int dy = -radius; dy <= radius; dy++) {
                    int by = centerY + dy;
                    double distanceSq = horizontalDistSq + (long) dy * dy;
                    if (distanceSq > radiusSq) {
                        continue;
                    }
                    int blockId = ChunkSectionBlockUtil.blockId(world, bx, by, bz);
                    if (blockId == 0) {
                        continue;
                    }
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType blockType =
                        com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset(blockId);
                    if (blockType == null) {
                        continue;
                    }
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData farmingData =
                        blockType.getFarming();
                    String blockTypeId = blockType.getId();
                    boolean isTilledSoilType = blockTypeId != null
                        && (blockTypeId.contains("Tilled") || blockTypeId.contains("Farmland"));

                    com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> soilRef =
                        ChunkSectionBlockUtil.blockEntityRefAt(world, bx, by, bz);
                    com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock tilledSoil =
                        soilRef != null
                            ? chunkStore.getComponent(
                                soilRef,
                                com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock.getComponentType())
                            : null;
                    int waterX = bx;
                    int waterY = by;
                    int waterZ = bz;
                    if (tilledSoil == null && farmingData != null && by > ChunkUtil.MIN_Y) {
                        com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> belowRef =
                            ChunkSectionBlockUtil.blockEntityRefAt(world, bx, by - 1, bz);
                        if (belowRef != null) {
                            tilledSoil =
                                chunkStore.getComponent(
                                    belowRef,
                                    com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock.getComponentType());
                            waterX = bx;
                            waterY = by - 1;
                            waterZ = bz;
                        }
                    }
                    if (tilledSoil == null && !isTilledSoilType) {
                        continue;
                    }
                    // tilledSoil == null && isTilledSoilType → freshly tilled, never watered → definitely dry
                    if (tilledSoil != null) {
                        if (scanTimeResource != null) {
                            java.time.Instant gameTime = scanTimeResource.getGameTime();
                            java.time.Instant wateredUntil = tilledSoil.getWateredUntil();
                            boolean isWatered =
                                tilledSoil.hasExternalWater()
                                    || (wateredUntil != null && wateredUntil.isAfter(gameTime));
                            if (isWatered) {
                                continue;
                            }
                        } else if (tilledSoil.hasExternalWater() || tilledSoil.getWateredUntil() != null) {
                            continue;
                        }
                    }

                    int waterBlockId = ChunkSectionBlockUtil.blockId(world, waterX, waterY, waterZ);
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType waterBlockType =
                        com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset(waterBlockId);
                    if (waterBlockType == null) {
                        continue;
                    }

                    Vector3d farmlandPos = new Vector3d(waterX + 0.5, waterY + 1.0, waterZ + 0.5);
                    double distance = npcPos.distance(farmlandPos);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestFarmlandX = waterX;
                        bestFarmlandY = waterY;
                        bestFarmlandZ = waterZ;
                        bestBlockType = waterBlockType;
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
            if (ChunkSectionBlockUtil.isChunkInMemory(world, targetX, targetZ)) {
                double distanceToTarget = npcPos.distance(existingTarget);
                if (distanceToTarget <= APPROACH_DISTANCE) {
                    // Check if this farmland is already watered before proceeding
                    com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> checkBlockRef = 
                        ChunkSectionBlockUtil.blockEntityRefAt(world, targetX, targetY, targetZ);
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
                    int blockId = ChunkSectionBlockUtil.blockId(world, targetX, targetY, targetZ);
                    if (blockId != 0) {
                        com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType blockType = 
                            com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset(blockId);
                        String existingBlockTypeId = blockType != null ? blockType.getId() : null;
                        boolean isSoilOrCrop = (blockType != null && blockType.getFarming() != null)
                            || (existingBlockTypeId != null
                                && (existingBlockTypeId.contains("Tilled") || existingBlockTypeId.contains("Farmland")));
                        if (isSoilOrCrop) {
                            bestFarmlandX = targetX;
                            bestFarmlandY = targetY;
                            bestFarmlandZ = targetZ;
                            bestBlockType = blockType;
                            bestDistance = distanceToTarget;
                        } else {
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
        if (bestBlockType == null) {
            // No dry farmland found - reset to WANDER state
            if (data.getAIState() == com.hexvane.dragonlings.DragonlingAIState.WATER_CROPS) {
                data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                data.setTargetPosition(null);
            }
            return;
        }
        
        // We found dry farmland - set target position to the farmland
        Vector3d farmlandPos = new Vector3d(bestFarmlandX + 0.5, bestFarmlandY + 1.0, bestFarmlandZ + 0.5);
        Vector3d targetPos = new Vector3d(farmlandPos);
        
        double distanceToFarmland = npcPos.distance(farmlandPos);
        
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
                ChunkSectionBlockUtil.blockEntityRefAt(world, bestFarmlandX, bestFarmlandY, bestFarmlandZ);
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
                            data.setTargetPosition(null);
                            data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                            return;
                        }
                    } else if (checkSoil.hasExternalWater() || checkSoil.getWateredUntil() != null) {
                        data.setTargetPosition(null);
                        data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                        return;
                    }
                }
            }
            
            // Final check: Make absolutely sure the farmland is still dry before playing animation and watering
            // This prevents the animation from playing if farmland was just watered
            com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> finalCheckBlockRef = 
                ChunkSectionBlockUtil.blockEntityRefAt(world, bestFarmlandX, bestFarmlandY, bestFarmlandZ);
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
                            data.setTargetPosition(null);
                            data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                            return;
                        }
                    } else if (finalCheckSoil.hasExternalWater() || finalCheckSoil.getWateredUntil() != null) {
                        data.setTargetPosition(null);
                        data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                        return;
                    }
                }
            }

            // Try to water the farmland (same path as UseWateringCanInteraction)
            try {
                com.hypixel.hytale.server.core.modules.time.WorldTimeResource worldTimeResource =
                    commandBuffer.getResource(com.hypixel.hytale.server.core.modules.time.WorldTimeResource.getResourceType());
                if (worldTimeResource == null) {
                    return;
                }
                java.time.Instant gameTime = worldTimeResource.getGameTime();
                java.time.Instant wateredUntil = gameTime.plus(2400, java.time.temporal.ChronoUnit.SECONDS);
                boolean watered = waterSoilAt(world, bestFarmlandX, bestFarmlandY, bestFarmlandZ, wateredUntil);
                if (!watered && bestFarmlandY > ChunkUtil.MIN_Y) {
                    watered = waterSoilAt(world, bestFarmlandX, bestFarmlandY - 1, bestFarmlandZ, wateredUntil);
                }
                if (watered) {
                    npcComponent.playAnimation(
                        npcRef,
                        com.hypixel.hytale.protocol.AnimationSlot.Action,
                        "Blow",
                        commandBuffer);
                    spawnBlueWaterParticles(store, commandBuffer, npcRef, npcPos);
                    waterCooldowns.put(npcRef, currentTime);
                    data.setTargetPosition(null);
                    data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                    return;
                }
            } catch (Exception e) {
                LOGGER.atWarning().withCause(e).log("[BlueWater] Failed to water farmland at (%d, %d, %d)", 
                    bestFarmlandX, bestFarmlandY, bestFarmlandZ);
            }
        }
        } finally {
            commandBuffer.putComponent(npcRef, this.dragonlingDataType, data);
        }
    }

    /**
     * Waters tilled soil at the cell, matching vanilla {@code UseWateringCanInteraction#waterBlockAt}.
     */
    private static boolean waterSoilAt(
            @Nonnull World world,
            int x,
            int y,
            int z,
            @Nonnull java.time.Instant wateredUntil) {
        var chunkStore = world.getChunkStore();
        var store = chunkStore.getStore();
        var section = ChunkSectionBlockUtil.sectionRefAt(world, x, y, z);
        if (section == null) {
            return false;
        }
        var blockSection = store.getComponent(section, com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection.getComponentType());
        var blockComponentSection = store.getComponent(
            section, com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection.getComponentType());
        if (blockSection == null || blockComponentSection == null) {
            return false;
        }
        var blockType = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap()
            .getAsset(blockSection.get(x, y, z));
        if (blockType == null) {
            return false;
        }
        var blockRef = blockComponentSection.getBlockReference(ChunkUtil.indexBlock(x, y, z));
        if (blockRef == null || !blockRef.isValid()) {
            blockRef = com.hypixel.hytale.server.core.modules.block.BlockEntity.ensureBlockEntity(
                store,
                section,
                blockComponentSection,
                x,
                y,
                z,
                blockType,
                blockSection.getFiller(x, y, z));
        }
        if (blockRef == null || !blockRef.isValid()) {
            return false;
        }
        var tilledSoil = store.getComponent(
            blockRef, com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock.getComponentType());
        if (tilledSoil == null) {
            tilledSoil = new com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock(false, false, false, null, null);
            store.addComponent(
                blockRef,
                com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock.getComponentType(),
                tilledSoil);
        }
        tilledSoil.setWateredUntil(wateredUntil);
        blockComponentSection.markBlockNeedsSaving(ChunkUtil.indexBlock(x, y, z));
        blockSection.setTicking(x, y, z, true);
        blockSection.scheduleTick(ChunkUtil.indexBlock(x, y, z), wateredUntil);
        ChunkSectionBlockUtil.setTicking(world, x, y + 1, z, true);
        return true;
    }

    private static void spawnBlueWaterParticles(
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull Vector3d npcPos) {
        TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
        Vector3d mouthPos;
        Rotation3f particleRotation = null;
        if (npcTransform != null) {
            Vector3d npcWorldPos = npcTransform.getPosition();
            Rotation3f npcRotation = npcTransform.getRotation();
            particleRotation = npcRotation;
            float yaw = npcRotation.yaw();
            mouthPos = new Vector3d(npcWorldPos);
            mouthPos.y += 0.45;
            mouthPos.x += -Math.sin(yaw) * 0.5;
            mouthPos.z += -Math.cos(yaw) * 0.5;
        } else {
            mouthPos = new Vector3d(npcPos);
            mouthPos.y += 0.45;
        }
        SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource =
            commandBuffer.getResource(EntityModule.get().getPlayerSpatialResourceType());
        List<Ref<EntityStore>> playerRefs = SpatialResource.getThreadLocalReferenceList();
        playerSpatialResource.getSpatialStructure().collect(mouthPos, 75.0, playerRefs);
        if (particleRotation != null) {
            com.hypixel.hytale.server.core.universe.world.ParticleUtil.spawnParticleEffect(
                "Dragonling_Blue_Water", mouthPos, particleRotation, playerRefs, commandBuffer);
        } else {
            com.hypixel.hytale.server.core.universe.world.ParticleUtil.spawnParticleEffect(
                "Dragonling_Blue_Water", mouthPos, playerRefs, commandBuffer);
        }
    }
    
    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }
}
