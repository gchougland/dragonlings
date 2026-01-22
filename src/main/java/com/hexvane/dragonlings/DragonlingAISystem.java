package com.hexvane.dragonlings;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * System that manages AI behavior for dragonlings using the built-in NPC AI systems:
 * - Sets follow target using MarkedEntitySupport for tamed dragonlings
 * - Sets leash point using NPCEntity.setLeashPoint() for leashed dragonlings
 * - Handles teleportation when owner is too far
 * 
 * The role's Instructions handle the actual movement using BodyMotion Seek/Wander.
 */
public class DragonlingAISystem extends EntityTickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double TELEPORT_DISTANCE = 32.0; // Distance at which to teleport
    
    @Nonnull
    private final ComponentType<EntityStore, NPCEntity> npcComponentType;
    @Nonnull
    private final ComponentType<EntityStore, DragonlingData> dragonlingDataType;
    @Nonnull
    private final Query<EntityStore> query;
    
    // Track last leash positions to avoid log spam
    private final java.util.Map<Ref<EntityStore>, Vector3d> lastLeashPositions = new java.util.HashMap<>();
    
    // Track marker entities for SEEK states (one per NPC)
    private final java.util.Map<Ref<EntityStore>, Ref<EntityStore>> seekMarkers = new java.util.HashMap<>();
    private final java.util.Map<Ref<EntityStore>, Vector3d> lastTargetPositions = new java.util.HashMap<>();
    
    public DragonlingAISystem(
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
        
        Ref<EntityStore> npcRef = archetypeChunk.getReferenceTo(index);
        Role role = npcComponent.getRole();
        if (role == null) {
            return;
        }
        
        // For untamed dragonlings, don't interfere at all - let the role's default behavior work
        if (!data.isTamed() && !data.isLeashed()) {
            // Completely skip AI system for untamed dragonlings
            // They should use the default WanderInCircle behavior from the role JSON
            return;
        }
        
        // Handle AI state transitions for tamed/leashed dragonlings
        DragonlingAIState aiState = data.getAIState();
        
        // Check for SEEK states (furnace, crops, etc.) - these take priority
        // BUT: Only handle SEEK states if the dragonling is leashed (behaviors only run when leashed)
        boolean isSeekState = (aiState == DragonlingAIState.SEEK_FURNACE || 
                              aiState == DragonlingAIState.HARVEST_CROPS || 
                              aiState == DragonlingAIState.WATER_CROPS);
        
        if (isSeekState && data.isLeashed()) {
            handleSeekState(npcRef, npcComponent, role, data, store, commandBuffer);
        } else {
            // If in a SEEK state but not leashed, clear it so the dragonling can follow owner
            if (isSeekState && !data.isLeashed()) {
                clearSeekPosition(role);
                cleanupSeekMarker(npcRef, npcComponent, role);
                data.setAIState(DragonlingAIState.WANDER);
                data.setTargetPosition(null);
            }
            // Not in a SEEK state - clean up any existing marker and clear stored position
            cleanupSeekMarker(npcRef, npcComponent, role);
            clearSeekPosition(role);
            
            if (data.isTamed() && !data.isLeashed()) {
                // Tamed but not leashed - should follow owner using MarkedEntitySupport
                data.setAIState(DragonlingAIState.FOLLOW_OWNER);
                handleFollowing(npcRef, npcComponent, role, data, store, commandBuffer);
            } else if (data.isLeashed()) {
                // Leashed - should use SensorLeash with NPCEntity leash point
                // BUT: If in a SEEK state, we temporarily disable the leash to prevent conflicts
                boolean isSeeking = (aiState == DragonlingAIState.SEEK_FURNACE || 
                                    aiState == DragonlingAIState.HARVEST_CROPS || 
                                    aiState == DragonlingAIState.WATER_CROPS);
                
                if (!isSeeking) {
                    // Not seeking - restore leash point if we temporarily disabled it
                    Vector3d leashPos = data.getLeashPosition();
                    if (leashPos != null) {
                        Vector3d currentLeashPoint = npcComponent.getLeashPoint();
                        TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
                        if (npcTransform != null && currentLeashPoint != null) {
                            Vector3d npcPos = npcTransform.getPosition();
                            double distToLeash = npcPos.distanceTo(currentLeashPoint);
                            // If leash point is at NPC position (we set it there during seeking), restore it
                            if (distToLeash < 0.1) {
                                npcComponent.setLeashPoint(leashPos);
                            }
                        }
                    }
                    data.setAIState(DragonlingAIState.WANDER);
                    handleLeashing(npcRef, npcComponent, data, store, commandBuffer);
                }
                // If seeking, leash is already disabled in handleSeekState, so don't call handleLeashing
            }
        }
    }
    
    /**
     * Handles following behavior for tamed dragonlings.
     * Uses MarkedEntitySupport to set the owner as a target, which the role's
     * SensorTarget + BodyMotion Seek will automatically use.
     * Also implements offset positioning for multiple followers to prevent stacking.
     */
    private void handleFollowing(
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull NPCEntity npcComponent,
            @Nonnull Role role,
            @Nonnull DragonlingData data,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        
        UUID ownerUUID = data.getOwnerUUID();
        if (ownerUUID == null) {
            return;
        }
        
        // Find owner entity
        Ref<EntityStore> ownerRef = findPlayerByUUID(store, ownerUUID);
        if (ownerRef == null || !ownerRef.isValid()) {
            // Owner not found, clear target position so NPC stops seeking
            clearSeekPosition(role);
            return;
        }
        
        TransformComponent ownerTransform = store.getComponent(ownerRef, TransformComponent.getComponentType());
        TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
        
        if (ownerTransform == null || npcTransform == null) {
            return;
        }
        
        Vector3d ownerPos = ownerTransform.getPosition();
        Vector3d npcPos = npcTransform.getPosition();
        double distance = npcPos.distanceTo(ownerPos);
        
        // Teleport if too far
        if (distance > TELEPORT_DISTANCE) {
            Vector3d teleportPos = ownerPos.clone();
            // Add random offset in X and Z, but keep Y at owner's level
            // Add small Y offset to ensure NPC doesn't spawn inside ground
            teleportPos.add(
                (Math.random() - 0.5) * 2.0,
                0.5, // Small Y offset to avoid spawning in ground
                (Math.random() - 0.5) * 2.0
            );
            TransformComponent npcTransformMutable = commandBuffer.getComponent(npcRef, TransformComponent.getComponentType());
            if (npcTransformMutable != null) {
                npcTransformMutable.setPosition(teleportPos);
            }
            LOGGER.atInfo().log("[DragonlingAI] Teleported %s to owner (distance: %.2f)", npcComponent.getRoleName(), distance);
        }
        
        // Set owner position in stored positions (same approach as seeking)
        // The role's SensorReadPosition with slot "LastSeen" will detect this
        // and BodyMotion Seek will automatically follow it
        // The separation system (enabled in role JSON) will handle spacing between multiple followers
        setSeekPosition(role, ownerPos);
        
        // Only log if distance changed significantly or on teleport
        // (removed frequent logging to reduce spam)
    }
    
    /**
     * Handles SEEK states where dragonlings are seeking a specific target (furnace, crops, etc.).
     * Uses a marker entity with LockedTarget to make NPC path directly to coordinates.
     * This STOPS wandering and makes the NPC use BodyMotion Seek to move directly to the target.
     */
    private void handleSeekState(
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull NPCEntity npcComponent,
            @Nonnull Role role,
            @Nonnull DragonlingData data,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        
        Vector3d targetPos = data.getTargetPosition();
        if (targetPos == null) {
            // No target, reset to WANDER and clean up
            clearSeekPosition(role);
            cleanupSeekMarker(npcRef, npcComponent, role);
            data.setAIState(DragonlingAIState.WANDER);
            data.setTargetPosition(null);
            return;
        }
        
        TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (npcTransform == null) {
            return;
        }
        
        Vector3d npcPos = npcTransform.getPosition();
        double distance = npcPos.distanceTo(targetPos);
        
        // Use marker entity approach - this is more reliable than SensorReadPosition
        // Create/update a marker entity at the target position and set it as LockedTarget
        // SensorTarget will detect it and activate Seek behavior
        
        // Verify target position from data matches what we expect
        Vector3d dataTargetPos = data.getTargetPosition();
        if (dataTargetPos != null && dataTargetPos.distanceTo(targetPos) > 0.1) {
            LOGGER.atWarning().log("[DragonlingAI] WARNING: Target position mismatch! Data has (" + 
                dataTargetPos.x + ", " + dataTargetPos.y + ", " + dataTargetPos.z + 
                "), but we're using (" + targetPos.x + ", " + targetPos.y + ", " + targetPos.z + ")");
        }
        
        // Use SensorReadPosition instead of marker entities - set the stored position directly
        // This avoids issues with marker entities causing sliding and head jerking
        setSeekPosition(role, targetPos);
        
        // Clean up any existing marker entities (we're not using them anymore)
        cleanupSeekMarker(npcRef, npcComponent, role);
        
        // When seeking, DON'T set the leash point - it interferes with Seek behavior
    }
    
    /**
     * Sets the stored position for the "LastSeen" slot (using existing slot name).
     * This makes SensorReadPosition detect the position and activate Seek behavior.
     * 
     * Since we can't get the slot number at runtime, we set the position in ALL slots
     * to ensure "LastSeen" gets it. This is a bit wasteful but ensures it works.
     */
    private void setSeekPosition(@Nonnull Role role, @Nonnull Vector3d targetPos) {
        try {
            com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport markedEntitySupport = role.getMarkedEntitySupport();
            
            // Use reflection to access the storedPositions array
            java.lang.reflect.Field storedPositionsField = 
                com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport.class.getDeclaredField("storedPositions");
            storedPositionsField.setAccessible(true);
            Vector3d[] storedPositions = (Vector3d[]) storedPositionsField.get(markedEntitySupport);
            
            if (storedPositions != null && storedPositions.length > 0) {
                // Set position in ALL slots to ensure "LastSeen" gets it
                // This is necessary because we can't determine which slot "LastSeen" maps to at runtime
                for (int i = 0; i < storedPositions.length; i++) {
                    Vector3d storedPos = storedPositions[i];
                    if (storedPos == null) {
                        storedPos = new Vector3d();
                        storedPositions[i] = storedPos;
                    }
                    storedPos.assign(targetPos);
                }
                
            }
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("[DragonlingAI] Failed to set seek position using reflection");
        }
    }
    
    /**
     * Clears the stored position for seeking.
     * For untamed dragonlings, we want to ensure ReadPosition doesn't match so they can wander.
     * We only modify existing positions - we don't create new ones to avoid initializing the array.
     */
    private void clearSeekPosition(@Nonnull Role role) {
        try {
            com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport markedEntitySupport = role.getMarkedEntitySupport();
            java.lang.reflect.Field storedPositionsField = 
                com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport.class.getDeclaredField("storedPositions");
            storedPositionsField.setAccessible(true);
            Vector3d[] storedPositions = (Vector3d[]) storedPositionsField.get(markedEntitySupport);
            
            if (storedPositions != null) {
                // Only modify existing positions - don't create new ones
                // Setting to MIN should make ReadPosition with Range: 512 fail to match
                // because MIN is far outside any reasonable range
                for (int i = 0; i < storedPositions.length; i++) {
                    Vector3d storedPos = storedPositions[i];
                    if (storedPos != null) {
                        // Set to MIN - this should be outside the 512 block range
                        // If ReadPosition still matches, the sensor might be checking existence rather than range
                        storedPos.assign(Vector3d.MIN);
                    }
                    // Don't create new Vector3d objects - leave null if they don't exist
                    // This way ReadPosition might fail if it checks for null
                }
            }
        } catch (Exception e) {
            // Ignore - position might not be set
        }
    }
    
    /**
     * Ensures a marker entity exists at the target position and sets it as LockedTarget.
     * This makes SensorTarget detect it and activate BodyMotion Seek.
     */
    private void ensureSeekMarker(
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull NPCEntity npcComponent,
            @Nonnull Role role,
            @Nonnull Vector3d targetPos,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        
        Ref<EntityStore> existingMarker = seekMarkers.get(npcRef);
        Vector3d lastTargetPos = lastTargetPositions.get(npcRef);
        
        // Check if target position changed significantly (more than 0.5 blocks)
        // If it changed significantly, destroy and recreate the marker to avoid store context issues
        // Using a smaller threshold to ensure marker is always at the correct position
        boolean targetChanged = lastTargetPos == null || lastTargetPos.distanceTo(targetPos) > 0.5;
        
        // If marker exists and target changed significantly, destroy it and recreate
        if (existingMarker != null && existingMarker.isValid() && targetChanged) {
            LOGGER.atInfo().log("[DragonlingAI] Target changed significantly, destroying old marker and recreating");
            cleanupSeekMarker(npcRef, npcComponent, role);
            existingMarker = null;
            seekMarkers.remove(npcRef);
            lastTargetPositions.remove(npcRef);
        }
        
        // If marker exists and is still valid, update its position and reuse it
        if (existingMarker != null && existingMarker.isValid()) {
            // Update marker position to match current target (marker might be in different store context)
            // We need to update it via world.execute() since we can't modify entities during system tick
            com.hypixel.hytale.server.core.universe.world.World world = npcComponent.getWorld();
            if (world != null) {
                final Vector3d finalTargetPos = targetPos.clone();
                final Ref<EntityStore> finalMarkerRef = existingMarker;
                
                world.execute(() -> {
                    try {
                        com.hypixel.hytale.component.Store<EntityStore> entityStore = world.getEntityStore().getStore();
                        if (finalMarkerRef.isValid()) {
                            TransformComponent markerTransform = entityStore.getComponent(finalMarkerRef, TransformComponent.getComponentType());
                            if (markerTransform != null) {
                                Vector3d oldPos = markerTransform.getPosition();
                                markerTransform.setPosition(finalTargetPos);
                                LOGGER.atInfo().log("[DragonlingAI] Updated marker position from (" + oldPos.x + ", " + oldPos.y + ", " + oldPos.z + 
                                    ") to (" + finalTargetPos.x + ", " + finalTargetPos.y + ", " + finalTargetPos.z + ")");
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.atWarning().withCause(e).log("[DragonlingAI] Failed to update marker position");
                    }
                });
            }
            
            // Verify marker position from current store context
            TransformComponent markerTransform = store.getComponent(existingMarker, TransformComponent.getComponentType());
            TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
            if (markerTransform != null && npcTransform != null) {
                Vector3d markerPos = markerTransform.getPosition();
                Vector3d npcPos = npcTransform.getPosition();
                
                // Calculate what direction Seek will use (from NPC to marker)
                Vector3d seekDirection = markerPos.clone().subtract(npcPos);
                double seekDist = seekDirection.distanceTo(Vector3d.ZERO);
                if (seekDist > 0.01) {
                    seekDirection.normalize();
                }
                
                // Calculate expected direction (from NPC to target)
                Vector3d expectedDirection = targetPos.clone().subtract(npcPos);
                double expectedDist = expectedDirection.distanceTo(Vector3d.ZERO);
                if (expectedDist > 0.01) {
                    expectedDirection.normalize();
                }
                
                LOGGER.atInfo().log("[DragonlingAI] Reusing marker - Marker at (" + markerPos.x + ", " + markerPos.y + ", " + markerPos.z + 
                    "), NPC at (" + npcPos.x + ", " + npcPos.y + ", " + npcPos.z + 
                    "), Target at (" + targetPos.x + ", " + targetPos.y + ", " + targetPos.z +
                    "), Seek direction: (" + seekDirection.x + ", " + seekDirection.y + ", " + seekDirection.z + 
                    "), Expected direction: (" + expectedDirection.x + ", " + expectedDirection.y + ", " + expectedDirection.z + ")");
            }
            
            // Update last target position
            lastTargetPositions.put(npcRef, targetPos.clone());
            
            // Ensure the marker is still set as the target - this activates Seek behavior
            role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", existingMarker);
            LOGGER.atInfo().log("[DragonlingAI] Using existing marker entity as LockedTarget");
            return;
        }
        
        // Need to create a new marker entity
        // Must use world.execute() since we can't add entities during system tick
        com.hypixel.hytale.server.core.universe.world.World world = npcComponent.getWorld();
        if (world != null && npcRef.isValid()) {
            final Vector3d finalTargetPos = targetPos.clone();
            final Ref<EntityStore> finalNpcRef = npcRef;
            
            world.execute(() -> {
                try {
                    com.hypixel.hytale.component.Store<EntityStore> entityStore = world.getEntityStore().getStore();
                    
                    // Validate NPC reference is still valid
                    if (!finalNpcRef.isValid()) {
                        return;
                    }
                    
                    // Check if NPC still exists
                    NPCEntity npcComp = entityStore.getComponent(finalNpcRef, NPCEntity.getComponentType());
                    if (npcComp == null) {
                        return;
                    }
                    
                    com.hypixel.hytale.server.npc.role.Role npcRole = npcComp.getRole();
                    if (npcRole == null) {
                        return;
                    }
                    
                    // Create a simple marker entity at the target position
                    com.hypixel.hytale.component.Holder<EntityStore> markerHolder = 
                        entityStore.getRegistry().newHolder();
                    
                    markerHolder.putComponent(TransformComponent.getComponentType(), 
                        new TransformComponent(finalTargetPos.clone(), com.hypixel.hytale.math.vector.Vector3f.ZERO));
                    
                    // Add UUID component for identification
                    com.hypixel.hytale.server.core.entity.UUIDComponent uuidComponent = 
                        new com.hypixel.hytale.server.core.entity.UUIDComponent(java.util.UUID.randomUUID());
                    markerHolder.addComponent(
                        com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType(), 
                        uuidComponent);
                    
                    // Add BoundingBox component - required for NPC pathfinding to work
                    com.hypixel.hytale.math.vector.Vector3d min = new com.hypixel.hytale.math.vector.Vector3d(-0.05, 0.0, -0.05);
                    com.hypixel.hytale.math.vector.Vector3d max = new com.hypixel.hytale.math.vector.Vector3d(0.05, 0.1, 0.05);
                    com.hypixel.hytale.math.shape.Box box = new com.hypixel.hytale.math.shape.Box(min, max);
                    com.hypixel.hytale.server.core.modules.entity.component.BoundingBox boundingBox = 
                        new com.hypixel.hytale.server.core.modules.entity.component.BoundingBox(box);
                    markerHolder.addComponent(
                        com.hypixel.hytale.server.core.modules.entity.component.BoundingBox.getComponentType(),
                        boundingBox);
                    
                    // Spawn the marker entity
                    com.hypixel.hytale.component.Ref<EntityStore> markerRef = 
                        entityStore.addEntity(markerHolder, com.hypixel.hytale.component.AddReason.SPAWN);
                    
                    // Validate marker was created successfully
                    if (markerRef == null || !markerRef.isValid()) {
                        LOGGER.atWarning().log("[DragonlingAI] Failed to create marker entity - markerRef is null or invalid");
                        return;
                    }
                    
                    // Verify the marker position immediately
                    TransformComponent markerTransform = entityStore.getComponent(markerRef, TransformComponent.getComponentType());
                    if (markerTransform != null) {
                        Vector3d actualMarkerPos = markerTransform.getPosition();
                        LOGGER.atInfo().log("[DragonlingAI] Created marker entity at (" + actualMarkerPos.x + ", " + actualMarkerPos.y + ", " + actualMarkerPos.z + 
                            ") and setting as LockedTarget (expected: " + finalTargetPos.x + ", " + finalTargetPos.y + ", " + finalTargetPos.z + ")");
                    } else {
                        LOGGER.atWarning().log("[DragonlingAI] Created marker entity but TransformComponent is null!");
                    }
                    
                    // Validate NPC is still valid before setting target
                    if (!finalNpcRef.isValid()) {
                        // Clean up marker
                        try {
                            entityStore.removeEntity(markerRef, com.hypixel.hytale.component.RemoveReason.REMOVE);
                        } catch (Exception e) {
                            // Ignore cleanup errors
                        }
                        return;
                    }
                    
                    npcComp = entityStore.getComponent(finalNpcRef, NPCEntity.getComponentType());
                    if (npcComp == null) {
                        // Clean up marker
                        try {
                            entityStore.removeEntity(markerRef, com.hypixel.hytale.component.RemoveReason.REMOVE);
                        } catch (Exception e) {
                            // Ignore cleanup errors
                        }
                        return;
                    }
                    
                    npcRole = npcComp.getRole();
                    if (npcRole != null) {
                        // Double-check marker position is correct before setting as target
                        TransformComponent verifyMarkerTransform = entityStore.getComponent(markerRef, TransformComponent.getComponentType());
                        if (verifyMarkerTransform != null) {
                            Vector3d verifyPos = verifyMarkerTransform.getPosition();
                            double posError = verifyPos.distanceTo(finalTargetPos);
                            if (posError > 0.1) {
                                LOGGER.atWarning().log("[DragonlingAI] WARNING: Marker position mismatch! Marker at (" + 
                                    verifyPos.x + ", " + verifyPos.y + ", " + verifyPos.z + 
                                    "), expected (" + finalTargetPos.x + ", " + finalTargetPos.y + ", " + finalTargetPos.z + 
                                    "), error: " + posError);
                                // Try to fix it
                                verifyMarkerTransform.setPosition(finalTargetPos);
                                LOGGER.atInfo().log("[DragonlingAI] Corrected marker position");
                            }
                        }
                        
                        // Store the marker reference for this NPC
                        seekMarkers.put(finalNpcRef, markerRef);
                        lastTargetPositions.put(finalNpcRef, finalTargetPos.clone());
                        
                        // Set it as the target - this activates Seek behavior and STOPS WanderInCircle
                        npcRole.getMarkedEntitySupport().setMarkedEntity("LockedTarget", markerRef);
                        
                        // Verify one more time after setting
                        TransformComponent finalMarkerTransform = entityStore.getComponent(markerRef, TransformComponent.getComponentType());
                        if (finalMarkerTransform != null) {
                            Vector3d finalPos = finalMarkerTransform.getPosition();
                            LOGGER.atInfo().log("[DragonlingAI] Set marker as LockedTarget - Final marker position: (" + 
                                finalPos.x + ", " + finalPos.y + ", " + finalPos.z + 
                                "), Expected: (" + finalTargetPos.x + ", " + finalTargetPos.y + ", " + finalTargetPos.z + ")");
                        }
                    } else {
                        LOGGER.atWarning().log("[DragonlingAI] Created marker entity but NPC role is null!");
                    }
                } catch (Exception e) {
                    LOGGER.atWarning().withCause(e).log("[DragonlingAI] Failed to create marker entity for seek state");
                }
            });
        }
    }
    
    /**
     * Cleans up the seek marker for an NPC when it's no longer in a SEEK state.
     */
    private void cleanupSeekMarker(
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull NPCEntity npcComponent,
            @Nonnull Role role) {
        
        Ref<EntityStore> markerRef = seekMarkers.remove(npcRef);
        lastTargetPositions.remove(npcRef);
        
        if (markerRef != null) {
            // Clear the target
            role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);
            
            // Remove the marker entity
            com.hypixel.hytale.server.core.universe.world.World world = npcComponent.getWorld();
            if (world != null && markerRef.isValid()) {
                world.execute(() -> {
                    try {
                        com.hypixel.hytale.component.Store<EntityStore> entityStore = world.getEntityStore().getStore();
                        if (markerRef.isValid()) {
                            entityStore.removeEntity(markerRef, com.hypixel.hytale.component.RemoveReason.REMOVE);
                        }
                    } catch (Exception e) {
                        // Marker might already be removed, that's okay
                    }
                });
            }
        }
    }
    
    /**
     * Handles leashing behavior for leashed dragonlings.
     * Uses the same pattern as the base game's leash behavior:
     * - SensorLeash detects when NPC is too far from leash point
     * - BodyMotion Seek makes them walk back using pathfinding (should not teleport)
     * 
     * If they're too far, we still set the leash point - the BodyMotion Seek should
     * handle pathfinding and make them walk back normally without teleportation.
     */
    private void handleLeashing(
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull NPCEntity npcComponent,
            @Nonnull DragonlingData data,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        
        Vector3d leashPos = data.getLeashPosition();
        if (leashPos == null) {
            return;
        }
        
        TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (npcTransform == null) {
            return;
        }
        
        Vector3d npcPos = npcTransform.getPosition();
        double distance = npcPos.distanceTo(leashPos);
        double leashRadius = data.getLeashRadius();
        
        // Check if leash position changed to avoid log spam
        Vector3d lastPos = lastLeashPositions.get(npcRef);
        boolean positionChanged = lastPos == null || !lastPos.equals(leashPos);
        
        Role role = npcComponent.getRole();
        if (role == null) {
            return;
        }
        
        // Clear follow target when leashed
        role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);
        
        // Always set the leash point - WanderInCircle uses it as the center
        // WanderInCircle will keep them wandering around the leash point without teleporting
        // This prevents teleportation entirely - they'll just wander in a circle around the leash point
        npcComponent.setLeashPoint(leashPos);
        
        // Track last leash position to avoid unnecessary updates
        if (positionChanged) {
            lastLeashPositions.put(npcRef, leashPos.clone());
        }
    }
    
    /**
     * Finds a player entity by UUID.
     */
    @javax.annotation.Nullable
    private Ref<EntityStore> findPlayerByUUID(@Nonnull Store<EntityStore> store, @Nonnull UUID uuid) {
        ComponentType<EntityStore, Player> playerType = Player.getComponentType();
        @SuppressWarnings("unchecked")
        Ref<EntityStore>[] result = new Ref[1];
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
    
    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }
}
