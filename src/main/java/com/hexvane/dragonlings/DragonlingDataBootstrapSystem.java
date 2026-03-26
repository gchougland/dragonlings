package com.hexvane.dragonlings;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;

/** Attaches {@link DragonlingData} to tamed dragonlings before behavior systems that require it. */
public class DragonlingDataBootstrapSystem extends EntityTickingSystem<EntityStore> {
    @Nonnull
    private final ComponentType<EntityStore, NPCEntity> npcComponentType;
    @Nonnull
    private final ComponentType<EntityStore, DragonlingData> dragonlingDataType;
    @Nonnull
    private final Query<EntityStore> query;

    public DragonlingDataBootstrapSystem(
            @Nonnull ComponentType<EntityStore, NPCEntity> npcComponentType,
            @Nonnull ComponentType<EntityStore, DragonlingData> dragonlingDataType) {
        this.npcComponentType = npcComponentType;
        this.dragonlingDataType = dragonlingDataType;
        this.query = Query.and(npcComponentType);
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        NPCEntity npc = archetypeChunk.getComponent(index, this.npcComponentType);
        if (npc == null) {
            return;
        }
        String roleName = npc.getRoleName();
        if (roleName == null || !roleName.contains("Dragonling")) {
            return;
        }
        Ref<EntityStore> npcRef = archetypeChunk.getReferenceTo(index);
        if (!DragonlingTamework.isTamed(store, npcRef)) {
            return;
        }
        DragonlingData data = commandBuffer.getComponent(npcRef, this.dragonlingDataType);
        if (data == null) {
            commandBuffer.putComponent(npcRef, this.dragonlingDataType, new DragonlingData());
        }
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }
}
