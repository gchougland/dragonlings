package com.hexvane.dragonlings.behaviors;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.Entity;
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
import com.hexvane.dragonlings.DragonlingData;
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
    private final ComponentType<EntityStore, DragonlingData> dragonlingDataType;
    @Nonnull
    private final Query<EntityStore> query;
    
    // Track last attack time per dragonling
    private final Map<Ref<EntityStore>, Double> lastAttackTime = new HashMap<>();
    
    public PurpleDragonlingCombatListener(
            @Nonnull ComponentType<EntityStore, NPCEntity> npcComponentType,
            @Nonnull ComponentType<EntityStore, DragonlingData> dragonlingDataType) {
        this.npcComponentType = npcComponentType;
        this.dragonlingDataType = dragonlingDataType;
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
        
        LOGGER.atInfo().log("[PurpleCombat] Player %s attacked entity, checking for Purple dragonlings to assist", attackerUUID);
        
        // Check if target is hostile (not a player, not tamed by the attacker)
        NPCEntity targetNPC = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (targetNPC == null) {
            LOGGER.atInfo().log("[PurpleCombat] Target is not an NPC, skipping");
            return; // Not an NPC, or already handled
        }
        
        LOGGER.atInfo().log("[PurpleCombat] Target NPC: %s", targetNPC.getRoleName());
        
        // Check if target is hostile to the attacker
        // For now, assume all NPCs that aren't tamed by the attacker are hostile
        DragonlingData targetData = store.getComponent(targetRef, DragonlingData.getComponentType());
        if (targetData != null && targetData.isTamed() && attackerUUID.equals(targetData.getOwnerUUID())) {
            LOGGER.atInfo().log("[PurpleCombat] Target is tamed by attacker, not assisting");
            return; // Target is tamed by attacker, don't assist
        }
        
        // Find all Purple dragonlings owned by the attacker
        Query<EntityStore> dragonlingQuery = Query.and(this.npcComponentType, this.dragonlingDataType);
        double currentTime = System.currentTimeMillis() / 1000.0;
        
        LOGGER.atInfo().log("[PurpleCombat] Searching for Purple dragonlings owned by %s", attackerUUID);
        
        store.forEachChunk(dragonlingQuery, (dragonlingChunk, chunkCommandBuffer) -> {
            for (int i = 0; i < dragonlingChunk.size(); i++) {
                NPCEntity npcComponent = dragonlingChunk.getComponent(i, this.npcComponentType);
                DragonlingData data = dragonlingChunk.getComponent(i, this.dragonlingDataType);
                
                if (npcComponent == null || data == null) {
                    continue;
                }
                
                if (!npcComponent.getRoleName().contains("Purple") || !data.isTamed()) {
                    continue;
                }
                
                if (!attackerUUID.equals(data.getOwnerUUID())) {
                    continue; // Not owned by attacker
                }
                
                Ref<EntityStore> dragonlingRef = dragonlingChunk.getReferenceTo(i);
                
                LOGGER.atInfo().log("[PurpleCombat] Found Purple dragonling %s owned by attacker", npcComponent.getRoleName());
                
                // Check cooldown
                Double lastAttack = lastAttackTime.get(dragonlingRef);
                if (lastAttack != null && (currentTime - lastAttack) < ATTACK_COOLDOWN) {
                    LOGGER.atInfo().log("[PurpleCombat] %s on cooldown (%.2f seconds remaining)", 
                        npcComponent.getRoleName(), ATTACK_COOLDOWN - (currentTime - lastAttack));
                    continue;
                }
                
                // Get dragonling position and rotation
                TransformComponent dragonlingTransform = store.getComponent(dragonlingRef, TransformComponent.getComponentType());
                HeadRotation dragonlingRotation = store.getComponent(dragonlingRef, HeadRotation.getComponentType());
                TransformComponent targetTransform = store.getComponent(targetRef, TransformComponent.getComponentType());
                
                if (dragonlingTransform == null || dragonlingRotation == null || targetTransform == null) {
                    continue;
                }
                
                // Check range
                Vector3d dragonlingPos = dragonlingTransform.getPosition();
                Vector3d targetPos = targetTransform.getPosition();
                double distance = dragonlingPos.distanceTo(targetPos);
                
                LOGGER.atInfo().log("[PurpleCombat] %s distance to target: %.2f (range: %.2f)", 
                    npcComponent.getRoleName(), distance, PurpleDragonlingCombatBehavior.ATTACK_RANGE);
                
                if (distance > PurpleDragonlingCombatBehavior.ATTACK_RANGE) {
                    LOGGER.atInfo().log("[PurpleCombat] %s out of range, skipping", npcComponent.getRoleName());
                    continue;
                }
                
                LOGGER.atInfo().log("[PurpleCombat] %s ATTACKING target %s at distance %.2f", 
                    npcComponent.getRoleName(), targetNPC.getRoleName(), distance);
                
                // Play Blow animation
                npcComponent.playAnimation(dragonlingRef, 
                    com.hypixel.hytale.protocol.AnimationSlot.Action, 
                    "Blow", 
                    commandBuffer);
                
                LOGGER.atInfo().log("[PurpleCombat] %s playing Blow animation", npcComponent.getRoleName());
                
                // Spawn void particles
                com.hypixel.hytale.server.core.universe.world.ParticleUtil.spawnParticleEffect(
                    "Dragonling_Purple_Void",
                    dragonlingPos,
                    java.util.Collections.emptyList(),
                    commandBuffer
                );
                
                LOGGER.atInfo().log("[PurpleCombat] %s spawned void particles", npcComponent.getRoleName());
                
                // Calculate direction to target
                Vector3d direction = new Vector3d(targetPos).subtract(dragonlingPos).normalize();
                
                // Spawn actual projectile
                try {
                    ProjectileConfig projectileConfig = 
                        ProjectileConfig.getAssetMap().getAsset("Dragonling_Void_Projectile");
                    
                    if (projectileConfig != null) {
                        LOGGER.atInfo().log("[PurpleCombat] %s spawning projectile", npcComponent.getRoleName());
                        
                        // Calculate spawn position (in front of dragonling)
                        Vector3f rotation = dragonlingRotation.getRotation();
                        Vector3d spawnPos = dragonlingPos.clone();
                        spawnPos.add(0, 0.8, 0); // Eye height offset
                        
                        // Spawn the projectile
                        ProjectileModule.get().spawnProjectile(
                            dragonlingRef,
                            commandBuffer,
                            projectileConfig,
                            spawnPos,
                            direction
                        );
                        
                        LOGGER.atInfo().log("[PurpleCombat] %s projectile spawned successfully", npcComponent.getRoleName());
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
                            LOGGER.atInfo().log("[PurpleCombat] %s applied direct damage (fallback)", npcComponent.getRoleName());
                        }
                    }
                } catch (Exception e) {
                    LOGGER.atWarning().withCause(e).log("[PurpleCombat] Failed to spawn projectile for %s", npcComponent.getRoleName());
                }
                
                lastAttackTime.put(dragonlingRef, currentTime);
                LOGGER.atInfo().log("[PurpleCombat] %s attack complete, cooldown set", npcComponent.getRoleName());
                break; // Only assist with one dragonling per damage event
            }
        });
    }
    
    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }
}
