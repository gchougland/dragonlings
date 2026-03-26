package com.hexvane.dragonlings;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nonnull;

/**
 * Sets {@link com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport} stored positions so
 * {@code SensorReadPosition} / {@code LastSeen} activates {@code BodyMotion} Seek for tamed follow and legacy seek.
 * Uses reflection until follow migrates to Tamework template + Companion-only paths.
 */
public final class MarkedEntitySeekBridge {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private MarkedEntitySeekBridge() {}

    public static void setSeekPosition(@Nonnull Role role, @Nonnull Vector3d targetPos) {
        try {
            var markedEntitySupport = role.getMarkedEntitySupport();
            var storedPositionsField =
                    com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport.class.getDeclaredField("storedPositions");
            storedPositionsField.setAccessible(true);
            Vector3d[] storedPositions = (Vector3d[]) storedPositionsField.get(markedEntitySupport);

            if (storedPositions != null && storedPositions.length > 0) {
                for (int i = 0; i < storedPositions.length; i++) {
                    Vector3d storedPos = storedPositions[i];
                    if (storedPos == null) {
                        storedPos = new Vector3d();
                        storedPositions[i] = storedPos;
                    }
                    storedPos.assign(targetPos);
                }
            }
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("[DragonlingAI] Failed to set seek position using reflection");
        }
    }

    /**
     * Clears seek slots so wild NPCs do not path toward default (0,0,0) via ReadPosition.
     */
    public static void clearSeekPosition(@Nonnull Role role) {
        try {
            var markedEntitySupport = role.getMarkedEntitySupport();
            var storedPositionsField =
                    com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport.class.getDeclaredField("storedPositions");
            storedPositionsField.setAccessible(true);
            Vector3d[] storedPositions = (Vector3d[]) storedPositionsField.get(markedEntitySupport);

            if (storedPositions != null) {
                for (int i = 0; i < storedPositions.length; i++) {
                    Vector3d storedPos = storedPositions[i];
                    if (storedPos != null) {
                        storedPos.assign(Vector3d.MIN);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
