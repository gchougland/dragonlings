package com.hexvane.dragonlings;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Custom interaction for taming dragonlings.
 * This will be triggered when a player right-clicks a dragonling with the correct essence item.
 */
public class DragonlingTameInteraction extends SimpleInstantInteraction {
    public static final BuilderCodec<DragonlingTameInteraction> CODEC = BuilderCodec.builder(
            DragonlingTameInteraction.class, DragonlingTameInteraction::new, SimpleInstantInteraction.CODEC
        )
        .documentation("Tames a dragonling with the correct essence item.")
        .build();
    
    public DragonlingTameInteraction(String id) {
        super(id);
    }
    
    protected DragonlingTameInteraction() {
    }
    
    @Override
    protected final void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        Ref<EntityStore> ref = context.getEntity();
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Player playerComponent = commandBuffer.getComponent(ref, Player.getComponentType());
        
        if (playerComponent == null) {
            HytaleLogger.getLogger().at(Level.INFO).log("DragonlingTameInteraction requires a Player");
            context.getState().state = InteractionState.Failed;
            return;
        }
        
        Ref<EntityStore> targetRef = context.getTargetEntity();
        if (targetRef == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        
        NPCEntity npcComponent = commandBuffer.getComponent(targetRef, NPCEntity.getComponentType());
        if (npcComponent == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        
        // Attempt taming
        boolean handled = DragonlingTamingHandler.attemptTaming(
            context, targetRef, npcComponent, ref, playerComponent, commandBuffer
        );
        
        if (!handled) {
            context.getState().state = InteractionState.Failed;
        }
    }
    
    @Nonnull
    @Override
    public String toString() {
        return "DragonlingTameInteraction{} " + super.toString();
    }
}
