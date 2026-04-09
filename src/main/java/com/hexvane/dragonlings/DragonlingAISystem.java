package com.hexvane.dragonlings;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Seek bridge for owner follow, leash sync to Tamework home, and job state when using the Dragon Whistle.
 *
 * <p>The role's Instructions handle the actual movement using BodyMotion Seek/Wander.
 */
public class DragonlingAISystem extends EntityTickingSystem<EntityStore> {

    /**
     * Ambient follow only: snap dragonling to owner when farther than this (blocks). Companion command teleport distances
     * in {@code TwCompanionConfig} are unrelated.
     */
    public static final double TELEPORT_DISTANCE = 32.0;
    
    @Nonnull
    private final ComponentType<EntityStore, NPCEntity> npcComponentType;
    @Nonnull
    private final ComponentType<EntityStore, DragonlingData> dragonlingDataType;
    @Nonnull
    private final Query<EntityStore> query;
    
    private final java.util.Map<Ref<EntityStore>, Vector3d> lastHomeAnchors = new java.util.HashMap<>();
    
    public DragonlingAISystem(
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
        
        NPCEntity npcComponent = archetypeChunk.getComponent(index, this.npcComponentType);
        
        if (npcComponent == null) {
            return;
        }
        
        Ref<EntityStore> npcRef = archetypeChunk.getReferenceTo(index);
        Role role = npcComponent.getRole();
        if (role == null) {
            return;
        }
        
        String roleName = npcComponent.getRoleName();
        if (roleName == null || !roleName.contains("Dragonling")) {
            return;
        }
        
        DragonlingData data = commandBuffer.getComponent(npcRef, this.dragonlingDataType);
        // DragonlingData for tamed dragonlings is attached in DragonlingDataBootstrapSystem (runs before color behaviors).
        if (data != null) {
            DragonlingTamework.migrateLegacyDragonlingTame(commandBuffer, npcRef, data);
        }
        
        boolean tamedTw = DragonlingTamework.isTamed(store, npcRef);
        if (tamedTw && data != null && !data.isTameNotifySent()) {
            UUID tameOwner = DragonlingTamework.getOwnerId(store, npcRef);
            if (tameOwner != null) {
                Ref<EntityStore> ownerRef = findPlayerByUUID(store, tameOwner);
                if (ownerRef != null && ownerRef.isValid()) {
                    Player ownerPlayer = store.getComponent(ownerRef, Player.getComponentType());
                    if (ownerPlayer != null && roleName != null) {
                        ownerPlayer.sendMessage(
                            Message.translation("server.dragonlings.tame.success")
                                .param("dragonling", Message.translation("server.npcRoles." + roleName + ".name")));
                    }
                }
            }
            data.setTameNotifySent(true);
            commandBuffer.putComponent(npcRef, this.dragonlingDataType, data);
        }
        Vector3d tameworkHome = DragonlingTamework.getWorkAnchor(commandBuffer, npcRef);
        boolean hasHome = tameworkHome != null;
        if (!hasHome) {
            lastHomeAnchors.remove(npcRef);
        }

        // Wild, no home: clear seek so default (0,0,0) is not used as a target near world origin.
        if (!tamedTw && !hasHome) {
            MarkedEntitySeekBridge.clearSeekPosition(role);
            return;
        }
        
        if (data == null) {
            return;
        }

        // Whistle / role state: pause harvest-water-furnace jobs for Hold, Follow (away from home), combat, etc.
        if (tamedTw && hasHome && DragonlingTamework.shouldPauseHomeAssignmentWork(npcComponent, commandBuffer, npcRef)) {
            DragonlingAIState st0 = data.getAIState();
            if (st0 == DragonlingAIState.SEEK_FURNACE
                || st0 == DragonlingAIState.HARVEST_CROPS
                || st0 == DragonlingAIState.WATER_CROPS) {
                data.setAIState(DragonlingAIState.WANDER);
                data.setTargetPosition(null);
            }
            boolean followCmd = DragonlingTamework.isFollowState(npcComponent);
            boolean holdCmd = DragonlingTamework.isHoldState(npcComponent);
            if (!followCmd && !holdCmd) {
                MarkedEntitySeekBridge.clearSeekPosition(role);
            }
            if (DragonlingTamework.shouldDriveOwnerFollowWithSeekBridge(npcComponent)) {
                data.setAIState(DragonlingAIState.FOLLOW_OWNER);
            }
            commandBuffer.putComponent(npcRef, this.dragonlingDataType, data);
            return;
        }
        
        // Handle AI state transitions for tamed/leashed dragonlings
        DragonlingAIState aiState = data.getAIState();
        
        // SEEK states (furnace, crops, etc.) — only when a home anchor exists (behaviors use that radius)
        boolean isSeekState = (aiState == DragonlingAIState.SEEK_FURNACE ||
            aiState == DragonlingAIState.HARVEST_CROPS ||
            aiState == DragonlingAIState.WATER_CROPS);

        if (isSeekState && hasHome) {
            handleSeekState(npcRef, npcComponent, role, data, store, commandBuffer);
        } else if (isSeekState && !hasHome) {
            MarkedEntitySeekBridge.clearSeekPosition(role);
            data.setAIState(DragonlingAIState.WANDER);
            data.setTargetPosition(null);
        } else if (tamedTw && !hasHome) {
            MarkedEntitySeekBridge.clearSeekPosition(role);
            data.setAIState(DragonlingAIState.FOLLOW_OWNER);
            handleFollowing(npcRef, npcComponent, role, data, store, commandBuffer);
        } else if (hasHome) {
            // Sync leash point to home once per home-position change so vanilla
            // WanderInCircle uses the correct anchor.  Do NOT re-set it every tick —
            // that prevents Tamework Follow/Recall from moving the NPC away from home.
            Vector3d lastAnchor = lastHomeAnchors.get(npcRef);
            if (lastAnchor == null || lastAnchor.distanceTo(tameworkHome) > 0.1) {
                npcComponent.setLeashPoint(tameworkHome);
                lastHomeAnchors.put(npcRef, tameworkHome.clone());
            }

            // Clear mod seek when not on Tamework Follow — otherwise passive follow (handleFollowing) leaves a stale
            // seek target and Idle / Return Home still chase the owner.
            if (!DragonlingTamework.shouldDriveOwnerFollowWithSeekBridge(npcComponent)) {
                MarkedEntitySeekBridge.clearSeekPosition(role);
            }

            // Transition from mod-driven follow to idle-at-home (home takes priority
            // over the mod's passive follow; Tamework's Follow command is separate).
            if (aiState == DragonlingAIState.FOLLOW_OWNER) {
                data.setAIState(DragonlingAIState.WANDER);
            }

            // Otherwise don't touch seek positions, LockedTarget, or AI state —
            // Tamework handles Follow/Recall/ReturnHome/AttackTarget independently.
        }

        commandBuffer.putComponent(npcRef, this.dragonlingDataType, data);
    }
    
    /** Passive follow when tamed with no Tamework home: seek ring around owner, teleport if very far. */
    private void handleFollowing(
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull NPCEntity npcComponent,
            @Nonnull Role role,
            @Nonnull DragonlingData data,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        
        UUID ownerUUID = DragonlingTamework.getOwnerId(store, npcRef);
        if (ownerUUID == null) {
            return;
        }
        
        // Find owner entity
        Ref<EntityStore> ownerRef = findPlayerByUUID(store, ownerUUID);
        if (ownerRef == null || !ownerRef.isValid()) {
            // Owner not found, clear target position so NPC stops seeking
            MarkedEntitySeekBridge.clearSeekPosition(role);
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

        // Near owner: aim Seek at self so Seek+StopDistance does not fight (reduces idle orbiting).
        final double holdFollowDistance = 3.0;
        final double followRingRadius = 2.4;
        UUIDComponent uuidComp = store.getComponent(npcRef, UUIDComponent.getComponentType());
        Vector3d followTargetPos = ownerPos;
        if (uuidComp != null) {
            UUID nid = uuidComp.getUuid();
            long mix = nid.getLeastSignificantBits() ^ (nid.getMostSignificantBits() << 1);
            double angle = ((mix & 0x7FFFFFFFFFFFFFFFL) / (double)0x7FFFFFFFFFFFFFFFL) * (Math.PI * 2);
            followTargetPos = new Vector3d(
                ownerPos.x + Math.cos(angle) * followRingRadius,
                ownerPos.y,
                ownerPos.z + Math.sin(angle) * followRingRadius
            );
        }

        // Teleport if too far
        if (distance > DragonlingAISystem.TELEPORT_DISTANCE) {
            Vector3d teleportPos = followTargetPos.clone();
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
        }

        if (distance <= holdFollowDistance) {
            MarkedEntitySeekBridge.setSeekPosition(role, npcPos);
        } else {
            MarkedEntitySeekBridge.setSeekPosition(role, followTargetPos);
        }
    }

    /** Updates {@link MarkedEntitySeekBridge} toward harvest/water/furnace targets. */
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
            MarkedEntitySeekBridge.clearSeekPosition(role);
            data.setAIState(DragonlingAIState.WANDER);
            data.setTargetPosition(null);
            return;
        }
        
        MarkedEntitySeekBridge.setSeekPosition(role, targetPos);
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
