package com.hexvane.dragonlings.behaviors;

import com.hexvane.dragonlings.DragonlingTamework;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Cancels void-orb damage from a tamed purple dragonling when the victim is the owner
 * or another Tamework pet owned by that same player.
 */
public class PurpleDragonlingProjectileFriendlyFireFilter extends DamageEventSystem {
    @Nonnull
    private final Query<EntityStore> query = EntityStatMap.getComponentType();

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage) {
        Damage.Source source = damage.getSource();
        if (!(source instanceof Damage.EntitySource entitySource)) {
            return;
        }

        Ref<EntityStore> shooterRef = entitySource.getRef();
        if (shooterRef == null || !shooterRef.isValid()) {
            return;
        }

        NPCEntity shooterNpc = store.getComponent(shooterRef, NPCEntity.getComponentType());
        if (shooterNpc == null) {
            return;
        }
        String roleName = shooterNpc.getRoleName();
        if (roleName == null || !roleName.contains("Purple") || !DragonlingTamework.isTamed(store, shooterRef)) {
            return;
        }

        UUID ownerId = DragonlingTamework.getOwnerId(store, shooterRef);
        if (ownerId == null) {
            return;
        }

        Ref<EntityStore> victimRef = archetypeChunk.getReferenceTo(index);
        if (isOwnerPlayer(store, victimRef, ownerId) || isSameOwnerPet(store, victimRef, ownerId)) {
            damage.setCancelled(true);
        }
    }

    private static boolean isOwnerPlayer(
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> victimRef, @Nonnull UUID ownerId) {
        if (store.getComponent(victimRef, Player.getComponentType()) == null) {
            return false;
        }
        UUIDComponent uuidComponent = store.getComponent(victimRef, UUIDComponent.getComponentType());
        return uuidComponent != null && ownerId.equals(uuidComponent.getUuid());
    }

    private static boolean isSameOwnerPet(
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> victimRef, @Nonnull UUID ownerId) {
        if (!DragonlingTamework.isTamed(store, victimRef)) {
            return false;
        }
        return ownerId.equals(DragonlingTamework.getOwnerId(store, victimRef));
    }
}
