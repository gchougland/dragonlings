package com.hexvane.dragonlings.behaviors;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.projectile.ProjectileModule;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hexvane.dragonlings.DragonlingTamework;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Listens for damage events to trigger Purple dragonling combat assistance.
 */
public class PurpleDragonlingCombatListener extends DamageEventSystem {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double ATTACK_COOLDOWN = 2.0;
    
    @Nonnull
    private final ComponentType<EntityStore, NPCEntity> npcComponentType;
    @Nonnull
    private final Query<EntityStore> query;
    
    // Track last attack time per dragonling
    private final Map<Ref<EntityStore>, Double> lastAttackTime = new HashMap<>();
    // Track combat targets per dragonling (dragonling ref -> target entity ref)
    // Static so both listener and behavior can access it
    private static final Map<Ref<EntityStore>, Ref<EntityStore>> combatTargets = new HashMap<>();
    
    public PurpleDragonlingCombatListener(@Nonnull ComponentType<EntityStore, NPCEntity> npcComponentType) {
        this.npcComponentType = npcComponentType;
        // Query for all entities that can take damage (have EntityStatMap)
        this.query = EntityStatMap.getComponentType();
    }
    
    @Override
    public void handle(
            int index,
            @Nonnull com.hypixel.hytale.component.ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage) {
        
        Ref<EntityStore> targetRef = archetypeChunk.getReferenceTo(index);
        
        // Check if damage source is from an entity (could be a player)
        Damage.Source source = damage.getSource();
        if (!(source instanceof Damage.EntitySource)) {
            return;
        }
        
        Damage.EntitySource entitySource = (Damage.EntitySource) source;
        Ref<EntityStore> attackerRef = entitySource.getRef();
        if (attackerRef == null || !attackerRef.isValid()) {
            return;
        }
        
        Player attackerPlayer = store.getComponent(attackerRef, Player.getComponentType());
        if (attackerPlayer == null) {
            return; // Not a player
        }
        
        UUIDComponent attackerUUIDComponent = store.getComponent(attackerRef, UUIDComponent.getComponentType());
        if (attackerUUIDComponent == null) {
            return;
        }
        
        UUID attackerUUID = attackerUUIDComponent.getUuid();
        
        // Check if target is hostile (not a player, not tamed by the attacker)
        NPCEntity targetNPC = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (targetNPC == null) {
            return; // Not an NPC, or already handled
        }
        
        // Target tamed by attacker — do not assist
        if (DragonlingTamework.isTamed(store, targetRef)) {
            UUID targetOwner = DragonlingTamework.getOwnerId(store, targetRef);
            if (attackerUUID.equals(targetOwner)) {
                return;
            }
        }
        
        // Find all Purple dragonlings owned by the attacker
        Query<EntityStore> dragonlingQuery = Query.and(this.npcComponentType);
        double currentTime = System.currentTimeMillis() / 1000.0;
        
        store.forEachChunk(dragonlingQuery, (dragonlingChunk, chunkCommandBuffer) -> {
            for (int i = 0; i < dragonlingChunk.size(); i++) {
                NPCEntity npcComponent = dragonlingChunk.getComponent(i, this.npcComponentType);
                
                if (npcComponent == null) {
                    continue;
                }
                
                Ref<EntityStore> dragonlingRef = dragonlingChunk.getReferenceTo(i);
                
                if (!npcComponent.getRoleName().contains("Purple") || !DragonlingTamework.isTamed(store, dragonlingRef)) {
                    continue;
                }
                
                if (!attackerUUID.equals(DragonlingTamework.getOwnerId(store, dragonlingRef))) {
                    continue;
                }
                
                // Check cooldown
                Double lastAttack = lastAttackTime.get(dragonlingRef);
                if (lastAttack != null && (currentTime - lastAttack) < ATTACK_COOLDOWN) {
                    continue;
                }
                
                // Get dragonling position and rotation
                TransformComponent dragonlingTransform = store.getComponent(dragonlingRef, TransformComponent.getComponentType());
                TransformComponent targetTransform = store.getComponent(targetRef, TransformComponent.getComponentType());
                
                if (dragonlingTransform == null || targetTransform == null) {
                    continue;
                }
                
                // Check range
                Vector3d dragonlingPos = dragonlingTransform.getPosition();
                Vector3d targetPos = targetTransform.getPosition();
                double distance = dragonlingPos.distanceTo(targetPos);
                
                if (distance > PurpleDragonlingCombatBehavior.ATTACK_RANGE) {
                    continue;
                }
                
                // Make dragonling face the target before attacking
                Vector3d direction = new Vector3d(targetPos).subtract(dragonlingPos).normalize();
                Vector3d horizontalDir = direction.clone();
                horizontalDir.y = 0; // Keep horizontal only for rotation
                double dirLength = horizontalDir.distanceTo(Vector3d.ZERO);
                if (dirLength > 0.01) {
                    horizontalDir.normalize();
                }
                
                // Calculate yaw to face target (same as red dragonling)
                double yaw = Math.atan2(horizontalDir.x, horizontalDir.z) + Math.PI;
                double pitch = -Math.asin(direction.y);
                
                // Set NPC body rotation to face target
                TransformComponent transformMutable = commandBuffer.getComponent(dragonlingRef, TransformComponent.getComponentType());
                if (transformMutable != null) {
                    Vector3f rotation = transformMutable.getRotation();
                    rotation.setYaw((float) yaw);
                }
                
                // Set head rotation to face target
                HeadRotation headRot = commandBuffer.ensureAndGetComponent(dragonlingRef, HeadRotation.getComponentType());
                if (headRot != null) {
                    Vector3f headRotation = headRot.getRotation();
                    headRotation.setYaw((float) yaw);
                    headRotation.setPitch((float) pitch);
                }
                
                // Play Blow animation
                npcComponent.playAnimation(dragonlingRef, 
                    com.hypixel.hytale.protocol.AnimationSlot.Action, 
                    "Blow", 
                    commandBuffer);
                
                // Calculate mouth position for projectile spawn (particles will come from projectile trail)
                Vector3d mouthPos;
                double headYOffset = 0.45; // Slightly below eye height (0.8) for snout/mouth level
                double mouthForwardOffset = 0.5; // Forward offset for mouth position (in front of head)
                
                // Calculate forward direction from yaw rotation
                double forwardX = -Math.sin(yaw) * mouthForwardOffset;
                double forwardZ = -Math.cos(yaw) * mouthForwardOffset;
                
                mouthPos = dragonlingPos.clone();
                mouthPos.y += headYOffset;
                mouthPos.x += forwardX;
                mouthPos.z += forwardZ;
                
                // Spawn actual projectile (particles will follow it via Trail in projectile config)
                try {
                    ProjectileConfig projectileConfig = 
                        ProjectileConfig.getAssetMap().getAsset("Dragonling_Void_Projectile");
                    
                    if (projectileConfig != null) {
                        // Spawn the projectile
                        // spawnProjectile takes position and ADDS SpawnOffset to it (rotated by pitch/yaw)
                        // SpawnOffset is now 0,0,0, so we pass the exact mouth position
                        ProjectileModule.get().spawnProjectile(
                            dragonlingRef,
                            commandBuffer,
                            projectileConfig,
                            mouthPos,  // Exact mouth position - SpawnOffset is 0,0,0 so it spawns here
                            direction  // Direction to fly
                        );
                    } else {
                        LOGGER.atWarning().log("[PurpleCombat] Projectile config 'Dragonling_Void_Projectile' not found, falling back to direct damage");
                        // Fallback to direct damage if config not found
                        int causeIndex = com.hypixel.hytale.server.core.modules.entity.damage.DamageCause.getAssetMap().getIndex("Projectile");
                        if (causeIndex == Integer.MIN_VALUE) {
                            causeIndex = com.hypixel.hytale.server.core.modules.entity.damage.DamageCause.getAssetMap().getIndex("Generic");
                        }
                        
                        if (causeIndex != Integer.MIN_VALUE) {
                            Damage projectileDamage = new Damage(
                                new Damage.ProjectileSource(dragonlingRef, dragonlingRef),
                                causeIndex,
                                (float) PurpleDragonlingCombatBehavior.PROJECTILE_DAMAGE
                            );
                            DamageSystems.executeDamage(targetRef, commandBuffer, projectileDamage);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.atWarning().withCause(e).log("[PurpleCombat] Failed to spawn projectile for %s", npcComponent.getRoleName());
                }
                
                lastAttackTime.put(dragonlingRef, currentTime);
                // Store the target so the dragonling can continue attacking it
                combatTargets.put(dragonlingRef, targetRef);
                break; // Only assist with one dragonling per damage event
            }
        });
    }
    
    /**
     * Get the combat target for a dragonling, or null if none.
     */
    @javax.annotation.Nullable
    public static Ref<EntityStore> getCombatTarget(@Nonnull Ref<EntityStore> dragonlingRef) {
        return combatTargets.get(dragonlingRef);
    }
    
    /**
     * Clear the combat target for a dragonling.
     */
    public static void clearCombatTarget(@Nonnull Ref<EntityStore> dragonlingRef) {
        combatTargets.remove(dragonlingRef);
    }
    
    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }
}
