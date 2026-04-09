package com.hexvane.dragonlings;

import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Per-variant tame totals from {@link DragonlingsTameCountStore} (not derived from loaded NPCs). */
public final class DragonlingsTameLimits {
    private DragonlingsTameLimits() {}

    public static int getRecordedCount(
            @Nonnull DragonlingsTameCountStore store, @Nullable UUID ownerId, @Nonnull String roleName) {
        return store.getCount(ownerId, roleName);
    }
}
