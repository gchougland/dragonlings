package com.hexvane.dragonlings.behaviors;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hexvane.dragonlings.DragonlingData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Behavior system for Purple dragonlings - assists owner in combat with projectiles.
 */
public class PurpleDragonlingCombatBehavior extends EntityTickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    static final double ATTACK_RANGE = 16.0;
    private static final double ATTACK_COOLDOWN = 2.0; // Seconds between attacks
    static final double PROJECTILE_DAMAGE = 5.0;
    
    @Nonnull
    private final ComponentType<EntityStore, NPCEntity> npcComponentType;
    @Nonnull
    private final ComponentType<EntityStore, DragonlingData> dragonlingDataType;
    @Nonnull
    private final Query<EntityStore> query;
    
    // Track last attack time per dragonling
    private final Map<Ref<EntityStore>, Double> lastAttackTime = new HashMap<>();
    
    public PurpleDragonlingCombatBehavior(
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
        
        // Only process Purple dragonlings that are tamed (works whether leashed or not)
        if (!npcComponent.getRoleName().contains("Purple") || !data.isTamed()) {
            return;
        }
        
        UUID ownerUUID = data.getOwnerUUID();
        if (ownerUUID == null) {
            return;
        }
        
        // Get world's entity store - this is more reliable than using the ticking system's store
        com.hypixel.hytale.server.core.universe.world.World world = npcComponent.getWorld();
        if (world == null) {
            return;
        }
        
        Store<EntityStore> entityStore = world.getEntityStore().getStore();
        Ref<EntityStore> ownerRef = findPlayerByUUID(entityStore, ownerUUID);
        if (ownerRef == null || !ownerRef.isValid()) {
            return;
        }
        
        Player ownerPlayer = entityStore.getComponent(ownerRef, Player.getComponentType());
        if (ownerPlayer == null) {
            return;
        }
        
        Ref<EntityStore> npcRef = archetypeChunk.getReferenceTo(index);
        
        // Check if we have a combat target from the listener
        Ref<EntityStore> combatTargetRef = com.hexvane.dragonlings.behaviors.PurpleDragonlingCombatListener.getCombatTarget(npcRef);
        
        // If no target, nothing to do (listener will set one when player attacks)
        if (combatTargetRef == null || !combatTargetRef.isValid()) {
            return;
        }
        
        // Check if target is dead (has DeathComponent in its archetype)
        if (entityStore.getArchetype(combatTargetRef).contains(
                com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent.getComponentType())) {
            // Target is dead, clear it
            com.hexvane.dragonlings.behaviors.PurpleDragonlingCombatListener.clearCombatTarget(npcRef);
            return;
        }
        
        // Verify target still has EntityStatMap (can take damage)
        com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap targetStats = 
            entityStore.getComponent(combatTargetRef, com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap.getComponentType());
        
        if (targetStats == null) {
            // Target no longer exists or can't take damage, clear it
            com.hexvane.dragonlings.behaviors.PurpleDragonlingCombatListener.clearCombatTarget(npcRef);
            return;
        }
        
        // Check range
        com.hypixel.hytale.server.core.modules.entity.component.TransformComponent dragonlingTransform = 
            store.getComponent(npcRef, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
        com.hypixel.hytale.server.core.modules.entity.component.TransformComponent targetTransform = 
            entityStore.getComponent(combatTargetRef, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
        
        if (dragonlingTransform == null || targetTransform == null) {
            com.hexvane.dragonlings.behaviors.PurpleDragonlingCombatListener.clearCombatTarget(npcRef);
            return;
        }
        
        com.hypixel.hytale.math.vector.Vector3d dragonlingPos = dragonlingTransform.getPosition();
        com.hypixel.hytale.math.vector.Vector3d targetPos = targetTransform.getPosition();
        double distance = dragonlingPos.distanceTo(targetPos);
        
        if (distance > ATTACK_RANGE) {
            // Target out of range, clear it
            com.hexvane.dragonlings.behaviors.PurpleDragonlingCombatListener.clearCombatTarget(npcRef);
            return;
        }
        
        // Check cooldown
        double currentTime = System.currentTimeMillis() / 1000.0;
        Double lastAttack = lastAttackTime.get(npcRef);
        
        if (lastAttack != null && (currentTime - lastAttack) < ATTACK_COOLDOWN) {
            return; // Still on cooldown
        }
        
        // Attack the target!
        // Make dragonling face the target
        com.hypixel.hytale.math.vector.Vector3d direction = new com.hypixel.hytale.math.vector.Vector3d(targetPos).subtract(dragonlingPos).normalize();
        com.hypixel.hytale.math.vector.Vector3d horizontalDir = direction.clone();
        horizontalDir.y = 0;
        double dirLength = horizontalDir.distanceTo(com.hypixel.hytale.math.vector.Vector3d.ZERO);
        if (dirLength > 0.01) {
            horizontalDir.normalize();
        }
        
        double yaw = Math.atan2(horizontalDir.x, horizontalDir.z) + Math.PI;
        double pitch = -Math.asin(direction.y);
        
        // Set body rotation
        com.hypixel.hytale.server.core.modules.entity.component.TransformComponent transformMutable = 
            commandBuffer.getComponent(npcRef, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
        if (transformMutable != null) {
            com.hypixel.hytale.math.vector.Vector3f rotation = transformMutable.getRotation();
            rotation.setYaw((float) yaw);
        }
        
        // Set head rotation
        com.hypixel.hytale.server.core.modules.entity.component.HeadRotation headRot = 
            commandBuffer.ensureAndGetComponent(npcRef, com.hypixel.hytale.server.core.modules.entity.component.HeadRotation.getComponentType());
        if (headRot != null) {
            com.hypixel.hytale.math.vector.Vector3f headRotation = headRot.getRotation();
            headRotation.setYaw((float) yaw);
            headRotation.setPitch((float) pitch);
        }
        
        // Play Blow animation
        npcComponent.playAnimation(npcRef, 
            com.hypixel.hytale.protocol.AnimationSlot.Action, 
            "Blow", 
            commandBuffer);
        
        // Calculate mouth position
        com.hypixel.hytale.math.vector.Vector3d mouthPos;
        double headYOffset = 0.45;
        double mouthForwardOffset = 0.5;
        double forwardX = -Math.sin(yaw) * mouthForwardOffset;
        double forwardZ = -Math.cos(yaw) * mouthForwardOffset;
        
        mouthPos = dragonlingPos.clone();
        mouthPos.y += headYOffset;
        mouthPos.x += forwardX;
        mouthPos.z += forwardZ;
        
        // Spawn projectile
        try {
            com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig projectileConfig = 
                com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig.getAssetMap().getAsset("Dragonling_Void_Projectile");
            
            if (projectileConfig != null) {
                // spawnProjectile takes position and ADDS SpawnOffset to it (rotated by pitch/yaw)
                // SpawnOffset is now 0,0,0, so we pass the exact mouth position
                com.hypixel.hytale.server.core.modules.projectile.ProjectileModule.get().spawnProjectile(
                    npcRef,
                    commandBuffer,
                    projectileConfig,
                    mouthPos,  // Exact mouth position - SpawnOffset is 0,0,0 so it spawns here
                    direction  // Direction to fly
                );
            }
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("[PurpleCombat] Failed to spawn projectile for %s", npcComponent.getRoleName());
        }
        
        // Update cooldown
        lastAttackTime.put(npcRef, currentTime);
    }
    
    /**
     * Finds a player entity by UUID.
     * Note: In EntityTickingSystem, forEachChunk may not work properly.
     * This implementation tries forEachChunk first, but may need alternative approach.
     */
    @javax.annotation.Nullable
    private Ref<EntityStore> findPlayerByUUID(@Nonnull Store<EntityStore> store, @Nonnull UUID uuid) {
        ComponentType<EntityStore, Player> playerType = Player.getComponentType();
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        
        @SuppressWarnings("unchecked")
        Ref<EntityStore>[] result = new Ref[1];
        
        try {
            // Try using forEachChunk - this should work but may have issues in ticking systems
            store.forEachChunk(playerType, (chunk, chunkCommandBuffer) -> {
                if (result[0] != null) {
                    return; // Already found, skip remaining chunks
                }
                
                for (int i = 0; i < chunk.size(); i++) {
                    Player player = chunk.getComponent(i, playerType);
                    if (player != null) {
                        Ref<EntityStore> playerRef = chunk.getReferenceTo(i);
                        UUIDComponent uuidComponent = store.getComponent(playerRef, uuidType);
                        if (uuidComponent != null && uuidComponent.getUuid().equals(uuid)) {
                            result[0] = playerRef;
                            return; // Found the player, exit early
                        }
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("[PurpleCombat] Error in forEachChunk while finding player by UUID");
        }
        
        return result[0];
    }
    
    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }
}
