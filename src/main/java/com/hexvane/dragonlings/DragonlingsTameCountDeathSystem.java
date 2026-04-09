package com.hexvane.dragonlings;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * When a dragonling NPC gains {@link DeathComponent}, decrements the owner's persisted tame count for that role (chunk
 * unload does not add death).
 */
public final class DragonlingsTameCountDeathSystem extends RefChangeSystem<EntityStore, DeathComponent> {
    private final ComponentType<EntityStore, NPCEntity> npcComponentType;
    private final DragonlingsTameCountStore tameCountStore;

    public DragonlingsTameCountDeathSystem(
            @Nonnull ComponentType<EntityStore, NPCEntity> npcComponentType,
            @Nonnull DragonlingsTameCountStore tameCountStore) {
        this.npcComponentType = npcComponentType;
        this.tameCountStore = tameCountStore;
    }

    @Nonnull
    @Override
    public ComponentType<EntityStore, DeathComponent> componentType() {
        return DeathComponent.getComponentType();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(this.npcComponentType, DeathComponent.getComponentType());
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        NPCEntity npc = store.getComponent(ref, this.npcComponentType);
        if (npc == null) {
            return;
        }
        String roleName = npc.getRoleName();
        if (roleName == null || !roleName.startsWith("Dragonling_")) {
            return;
        }
        if (!DragonlingTamework.isTamed(store, ref)) {
            return;
        }
        UUID owner = DragonlingTamework.getOwnerId(store, ref);
        if (owner == null) {
            return;
        }
        this.tameCountStore.recordDeath(owner, roleName);
    }

    @Override
    public void onComponentSet(
            @Nonnull Ref<EntityStore> ref,
            @Nullable DeathComponent oldComponent,
            @Nonnull DeathComponent newComponent,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {}

    @Override
    public void onComponentRemoved(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {}
}
