package com.hexvane.dragonlings;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.blackboard.Blackboard;
import com.hypixel.hytale.server.npc.blackboard.view.interaction.InteractionView;
import com.hypixel.hytale.server.npc.blackboard.view.interaction.ReservationStatus;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;

/**
 * Custom UseNPC interaction that handles taming and leashing for dragonlings
 * before falling back to default NPC interaction behavior.
 */
public class DragonlingUseNPCInteraction extends SimpleInstantInteraction {
    public static final BuilderCodec<DragonlingUseNPCInteraction> CODEC = BuilderCodec.builder(
            DragonlingUseNPCInteraction.class, DragonlingUseNPCInteraction::new, SimpleInstantInteraction.CODEC
        )
        .documentation("Interacts with a target NPC, with custom handling for dragonlings.")
        .build();
    
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    public DragonlingUseNPCInteraction(String id) {
        super(id);
    }
    
    protected DragonlingUseNPCInteraction() {
    }
    
    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        Ref<EntityStore> ref = context.getEntity();
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Player playerComponent = commandBuffer.getComponent(ref, Player.getComponentType());
        
        if (playerComponent == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        
        // Get target entity - try from meta store first, then from client state (like UseEntityInteraction does)
        Ref<EntityStore> targetRef = context.getTargetEntity();
        
        if (targetRef == null) {
            // Try getting from client state like UseEntityInteraction does
            com.hypixel.hytale.protocol.InteractionSyncData chainData = context.getClientState();
            
            if (chainData != null && chainData.entityId >= 0) {
                targetRef = commandBuffer.getStore().getExternalData().getRefFromNetworkId(chainData.entityId);
            }
        }
        
        // Check if player is in leash mode and clicking a block (no NPC target)
        if (targetRef == null || !targetRef.isValid()) {
            // Check if player is in leash mode - if so, they might be clicking a block
            com.hypixel.hytale.server.core.entity.UUIDComponent uuidComponent = commandBuffer.getComponent(ref, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
            if (uuidComponent != null && DragonlingLeashHandler.isInLeashMode(uuidComponent.getUuid())) {
                // Player is in leash mode - check if they're clicking a block
                BlockPosition targetBlock = context.getTargetBlock();
                if (targetBlock != null) {
                    // Handle block interaction for leashing
                    Vector3d blockPos = new Vector3d(targetBlock.x, targetBlock.y, targetBlock.z);
                    
                    // Get block type from world
                    String blockType = "Unknown";
                    try {
                        World world = commandBuffer.getStore().getExternalData().getWorld();
                        if (world != null) {
                            long chunkIndex = ChunkUtil.indexChunkFromBlock(targetBlock.x, targetBlock.z);
                            WorldChunk chunk = world.getChunk(chunkIndex);
                            if (chunk != null) {
                                int blockId = chunk.getBlock(targetBlock.x, targetBlock.y, targetBlock.z);
                                BlockType blockTypeObj = BlockType.getAssetMap().getAsset(blockId);
                                if (blockTypeObj != null) {
                                    blockType = blockTypeObj.getId();
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Ignore block type retrieval errors
                    }
                    
                    boolean handled = DragonlingLeashHandler.handleBlockInteraction(
                        uuidComponent.getUuid(), blockPos, blockType, commandBuffer
                    );
                    
                    if (handled) {
                        context.getState().state = InteractionState.Finished;
                        return;
                    }
                }
                
                // Mark as finished so it doesn't show an error
                context.getState().state = InteractionState.Finished;
                return;
            }
            
            context.getState().state = InteractionState.Failed;
            return;
        }
        
        NPCEntity npcComponent = commandBuffer.getComponent(targetRef, NPCEntity.getComponentType());
        if (npcComponent == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        
        String roleName = npcComponent.getRoleName();
        boolean isDragonling = roleName != null && roleName.contains("Dragonling");
        
        if (isDragonling) {
            // Try taming first (checks for essence items)
            boolean tamed = DragonlingTamingHandler.attemptTaming(
                context, targetRef, npcComponent, ref, playerComponent, commandBuffer
            );
            
            if (tamed) {
                // Interaction handled, don't proceed to default NPC interaction
                return;
            }
            
            // Try leashing (checks for Dragon Whistle)
            boolean leashed = DragonlingLeashHandler.handleDragonlingInteraction(
                context, targetRef, npcComponent, ref, playerComponent, commandBuffer
            );
            
            if (leashed) {
                // Interaction handled, don't proceed to default NPC interaction
                return;
            }
        }
        
        // Proceed with default NPC interaction behavior
        if (!npcComponent.getRole().getStateSupport().willInteractWith(ref)) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        
        InteractionView interactionView = commandBuffer.getResource(Blackboard.getResourceType()).getView(InteractionView.class, 0L);
        if (interactionView.getReservationStatus(targetRef, ref, commandBuffer) == ReservationStatus.RESERVED_OTHER) {
            playerComponent.sendMessage(Message.translation("server.npc.npc.isBusy").param("roleName", npcComponent.getRoleName()));
            context.getState().state = InteractionState.Failed;
        } else {
            npcComponent.getRole().getStateSupport().addInteraction(playerComponent);
        }
    }
    
    @Nonnull
    @Override
    public String toString() {
        return "DragonlingUseNPCInteraction{} " + super.toString();
    }
}
