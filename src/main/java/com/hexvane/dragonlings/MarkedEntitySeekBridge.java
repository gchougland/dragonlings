package com.hexvane.dragonlings;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3dUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Sets {@link MarkedEntitySupport} stored positions so {@code SensorReadPosition} / {@code LastSeen}
 * activates {@code BodyMotion} Seek for tamed follow and job seek.
 */
public final class MarkedEntitySeekBridge {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int MAX_POSITION_SLOTS = 32;

    private MarkedEntitySeekBridge() {}

    public static void setSeekPosition(
            @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull Vector3d targetPos) {
        forEachStoredPosition(accessor, npcRef, stored -> stored.set(targetPos));
    }

    /**
     * Clears seek slots so wild NPCs do not path toward default (0,0,0) via ReadPosition.
     */
    public static void clearSeekPosition(
            @Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> npcRef) {
        forEachStoredPosition(accessor, npcRef, stored -> stored.set(Vector3dUtil.MIN));
    }

    private static void forEachStoredPosition(
            @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull StoredPositionConsumer consumer) {
        if (!npcRef.isValid()) {
            return;
        }
        MarkedEntitySupport support = accessor.getComponent(npcRef, MarkedEntitySupport.getComponentType());
        if (support == null) {
            return;
        }
        try {
            for (int i = 0; i < MAX_POSITION_SLOTS; i++) {
                Vector3d stored = support.getStoredPosition(i);
                if (stored != null) {
                    consumer.accept(stored);
                }
            }
        } catch (IndexOutOfBoundsException | NullPointerException ignored) {
            // storedPositions is null or shorter than MAX_POSITION_SLOTS
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("[DragonlingAI] Failed to update seek position");
        }
    }

    @FunctionalInterface
    private interface StoredPositionConsumer {
        void accept(@Nonnull Vector3d stored);
    }
}
