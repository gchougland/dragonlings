package com.hexvane.dragonlings;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Component to store dragonling-specific data like tamed state, owner, and leash information.
 */
public class DragonlingData implements Component<EntityStore> {
    private static ComponentType<EntityStore, DragonlingData> componentType;
    
    public static final BuilderCodec<DragonlingData> CODEC = BuilderCodec.builder(DragonlingData.class, DragonlingData::new)
        .addField(new KeyedCodec<>("Tamed", Codec.BOOLEAN), 
            (data, tamed) -> data.isTamed = tamed, 
            data -> data.isTamed)
        .addField(new KeyedCodec<>("OwnerUUID", Codec.UUID_STRING), 
            (data, uuid) -> data.ownerUUID = uuid, 
            data -> data.ownerUUID)
        .addField(new KeyedCodec<>("Leashed", Codec.BOOLEAN), 
            (data, leashed) -> data.isLeashed = leashed, 
            data -> data.isLeashed)
        .addField(new KeyedCodec<>("LeashPos", Vector3d.CODEC), 
            (data, pos) -> data.leashPosition = pos != null ? pos.clone() : null, 
            data -> data.leashPosition)
        .addField(new KeyedCodec<>("LeashBlockType", Codec.STRING), 
            (data, blockType) -> data.leashBlockType = blockType, 
            data -> data.leashBlockType)
        .addField(new KeyedCodec<>("LeashRadius", Codec.DOUBLE), 
            (data, radius) -> data.leashRadius = radius, 
            data -> data.leashRadius)
        .addField(new KeyedCodec<>("AIState", Codec.STRING), 
            (data, stateStr) -> {
                if (stateStr != null) {
                    try {
                        data.aiState = DragonlingAIState.valueOf(stateStr);
                    } catch (IllegalArgumentException e) {
                        data.aiState = DragonlingAIState.WANDER;
                    }
                } else {
                    data.aiState = DragonlingAIState.WANDER;
                }
            }, 
            data -> data.aiState != null ? data.aiState.name() : DragonlingAIState.WANDER.name())
        .addField(new KeyedCodec<>("TargetPos", Vector3d.CODEC), 
            (data, pos) -> data.targetPosition = pos != null ? pos.clone() : null, 
            data -> data.targetPosition)
        .build();
    
    private boolean isTamed;
    @Nullable
    private UUID ownerUUID;
    private boolean isLeashed;
    @Nullable
    private Vector3d leashPosition;
    @Nullable
    private String leashBlockType; // Store block type for chest checking
    private double leashRadius = 8.0; // Default wander radius when leashed
    @Nonnull
    private DragonlingAIState aiState = DragonlingAIState.WANDER; // Current AI state
    @Nullable
    private Vector3d targetPosition; // Target position when in SEEK states
    
    public static ComponentType<EntityStore, DragonlingData> getComponentType() {
        return componentType;
    }
    
    public static void setComponentType(ComponentType<EntityStore, DragonlingData> type) {
        componentType = type;
    }
    
    public boolean isTamed() {
        return isTamed;
    }
    
    public void setTamed(boolean tamed) {
        this.isTamed = tamed;
    }
    
    @Nullable
    public UUID getOwnerUUID() {
        return ownerUUID;
    }
    
    public void setOwnerUUID(@Nullable UUID ownerUUID) {
        this.ownerUUID = ownerUUID;
    }
    
    public boolean isLeashed() {
        return isLeashed;
    }
    
    public void setLeashed(boolean leashed) {
        this.isLeashed = leashed;
    }
    
    @Nullable
    public Vector3d getLeashPosition() {
        return leashPosition;
    }
    
    public void setLeashPosition(@Nullable Vector3d leashPosition) {
        this.leashPosition = leashPosition != null ? leashPosition.clone() : null;
    }
    
    @Nullable
    public String getLeashBlockType() {
        return leashBlockType;
    }
    
    public void setLeashBlockType(@Nullable String leashBlockType) {
        this.leashBlockType = leashBlockType;
    }
    
    public double getLeashRadius() {
        return leashRadius;
    }
    
    public void setLeashRadius(double leashRadius) {
        this.leashRadius = leashRadius;
    }
    
    @Nonnull
    public DragonlingAIState getAIState() {
        return aiState;
    }
    
    public void setAIState(@Nonnull DragonlingAIState aiState) {
        this.aiState = aiState;
    }
    
    @Nullable
    public Vector3d getTargetPosition() {
        return targetPosition;
    }
    
    public void setTargetPosition(@Nullable Vector3d targetPosition) {
        this.targetPosition = targetPosition != null ? targetPosition.clone() : null;
    }
    
    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        DragonlingData cloned = new DragonlingData();
        cloned.isTamed = this.isTamed;
        cloned.ownerUUID = this.ownerUUID;
        cloned.isLeashed = this.isLeashed;
        cloned.leashPosition = this.leashPosition != null ? this.leashPosition.clone() : null;
        cloned.leashBlockType = this.leashBlockType;
        cloned.leashRadius = this.leashRadius;
        cloned.aiState = this.aiState;
        cloned.targetPosition = this.targetPosition != null ? this.targetPosition.clone() : null;
        return cloned;
    }
}
