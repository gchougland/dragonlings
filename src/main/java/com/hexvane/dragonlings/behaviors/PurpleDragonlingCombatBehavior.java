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
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
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
            LOGGER.atInfo().log("[PurpleCombat] %s has no owner UUID", npcComponent.getRoleName());
            return;
        }
        
        // Find owner
        Ref<EntityStore> ownerRef = findPlayerByUUID(store, ownerUUID);
        if (ownerRef == null || !ownerRef.isValid()) {
            LOGGER.atInfo().log("[PurpleCombat] %s owner not found or invalid", npcComponent.getRoleName());
            return;
        }
        
        LOGGER.atInfo().log("[PurpleCombat] %s checking for combat targets near owner", npcComponent.getRoleName());
        
        Player ownerPlayer = store.getComponent(ownerRef, Player.getComponentType());
        if (ownerPlayer == null) {
            return;
        }
        
        // Check if owner is in combat with a hostile entity
        // When owner attacks a hostile entity, dragonling should assist
        // This would require listening to damage/attack events
        // For now, this is a placeholder that would need:
        // 1. Event listener for player attack events
        // 2. Check if target is hostile
        // 3. Trigger Blow animation
        // 4. Spawn projectile entity
        // 5. Projectile deals damage on impact
        
        Ref<EntityStore> npcRef = archetypeChunk.getReferenceTo(index);
        double currentTime = System.currentTimeMillis() / 1000.0;
        Double lastAttack = lastAttackTime.get(npcRef);
        
        if (lastAttack != null && (currentTime - lastAttack) < ATTACK_COOLDOWN) {
            return; // Still on cooldown
        }
        
        // Implementation would go here - checking for combat targets and firing projectiles
    }
    
    /**
     * Finds a player entity by UUID.
     */
    @javax.annotation.Nullable
    private Ref<EntityStore> findPlayerByUUID(@Nonnull Store<EntityStore> store, @Nonnull UUID uuid) {
        ComponentType<EntityStore, Player> playerType = Player.getComponentType();
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
