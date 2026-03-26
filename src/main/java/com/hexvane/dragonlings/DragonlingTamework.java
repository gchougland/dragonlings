package com.hexvane.dragonlings;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reflective access to Tamework components by registry id ({@value #ID_TAMED}, {@value #ID_OWNER}, {@value #ID_COMMAND_LINKS})
 * so we do not depend on {@code com.alechilles.*} at compile time. Migrates legacy {@link DragonlingData} tame fields once.
 */
@SuppressWarnings("unchecked")
public final class DragonlingTamework {
    /** Matches Tamework's {@code registerComponent(..., "TameworkTamed", ...)}. */
    public static final String ID_TAMED = "TameworkTamed";
    /** Matches Tamework's {@code registerComponent(..., "TameworkOwner", ...)}. */
    public static final String ID_OWNER = "TameworkOwner";
    /** Matches Tamework's {@code registerComponent(..., "TameworkCommandLinks", ...)} — stores Set Home position. */
    public static final String ID_COMMAND_LINKS = "TameworkCommandLinks";

    private DragonlingTamework() {}

    public static boolean isTamed(@Nullable Store<EntityStore> store, @Nonnull Ref<EntityStore> npcRef) {
        if (store == null) {
            return false;
        }
        ComponentType<EntityStore, ?> t = componentType(store, ID_TAMED);
        if (t == null) {
            return false;
        }
        Object c = store.getComponent(npcRef, (ComponentType<EntityStore, Component<EntityStore>>) t);
        return c != null && Boolean.TRUE.equals(invokeNoArgBoolean(c, "isTamed"));
    }

    public static boolean isTamed(@Nullable CommandBuffer<EntityStore> buffer, @Nonnull Ref<EntityStore> npcRef) {
        if (buffer == null) {
            return false;
        }
        return isTamed(buffer.getStore(), npcRef);
    }

    @Nullable
    public static UUID getOwnerId(@Nullable Store<EntityStore> store, @Nonnull Ref<EntityStore> npcRef) {
        if (store == null) {
            return null;
        }
        ComponentType<EntityStore, ?> t = componentType(store, ID_OWNER);
        if (t == null) {
            return null;
        }
        Object c = store.getComponent(npcRef, (ComponentType<EntityStore, Component<EntityStore>>) t);
        if (c == null) {
            return null;
        }
        Object id = invokeNoArg(c, "getOwnerId");
        if (id instanceof UUID u) {
            return u;
        }
        id = invokeNoArg(c, "getOwnerUUID");
        return id instanceof UUID u2 ? u2 : null;
    }

    @Nullable
    public static UUID getOwnerId(@Nullable CommandBuffer<EntityStore> buffer, @Nonnull Ref<EntityStore> npcRef) {
        if (buffer == null) {
            return null;
        }
        return getOwnerId(buffer.getStore(), npcRef);
    }

    /** Set Home position from {@code TameworkCommandLinks}. */
    @Nullable
    public static Vector3d getHomePosition(@Nullable Store<EntityStore> store, @Nonnull Ref<EntityStore> npcRef) {
        if (store == null) {
            return null;
        }
        ComponentType<EntityStore, ?> t = componentType(store, ID_COMMAND_LINKS);
        if (t == null) {
            return null;
        }
        Object c = store.getComponent(npcRef, (ComponentType<EntityStore, Component<EntityStore>>) t);
        if (c == null) {
            return null;
        }
        Object v = invokeNoArg(c, "getHomePosition");
        return v instanceof Vector3d vec ? vec : null;
    }

    /**
     * Prefer reading {@code TameworkCommandLinks} from the {@link CommandBuffer} so Set Home is visible in the same
     * tick as Tamework writes (store-only reads can miss pending component updates).
     */
    @Nullable
    public static Vector3d getHomePosition(@Nullable CommandBuffer<EntityStore> buffer, @Nonnull Ref<EntityStore> npcRef) {
        if (buffer == null) {
            return null;
        }
        Store<EntityStore> store = buffer.getStore();
        ComponentType<EntityStore, ?> t = componentType(store, ID_COMMAND_LINKS);
        if (t == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        ComponentType<EntityStore, Component<EntityStore>> typed = (ComponentType<EntityStore, Component<EntityStore>>) t;
        Object c = buffer.getComponent(npcRef, typed);
        if (c == null) {
            c = store.getComponent(npcRef, typed);
        }
        if (c == null) {
            return null;
        }
        Object v = invokeNoArg(c, "getHomePosition");
        return v instanceof Vector3d vec ? vec : null;
    }

    /** Block-centered work anchor from Set Home only (not {@link NPCEntity#getLeashPoint()}). */
    @Nullable
    public static Vector3d getWorkAnchor(
            @Nullable CommandBuffer<EntityStore> commandBuffer, @Nonnull Ref<EntityStore> npcRef) {
        if (commandBuffer == null) {
            return null;
        }
        Vector3d home = getHomePosition(commandBuffer, npcRef);
        return home != null ? snapToBlockCenter(home) : null;
    }

    @Nonnull
    public static Vector3d snapToBlockCenter(@Nonnull Vector3d hitPos) {
        return new Vector3d(
            Math.floor(hitPos.x) + 0.5,
            Math.floor(hitPos.y - 0.01) + 0.5,
            Math.floor(hitPos.z) + 0.5);
    }

    public static void migrateLegacyDragonlingTame(
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> npcRef,
            @Nullable DragonlingData data) {
        if (data == null || !data.isTamed() || data.getOwnerUUID() == null) {
            return;
        }
        Store<EntityStore> store = commandBuffer.getStore();
        ComponentType<EntityStore, ?> tamedT = componentType(store, ID_TAMED);
        ComponentType<EntityStore, ?> ownerT = componentType(store, ID_OWNER);
        if (tamedT == null || ownerT == null) {
            return;
        }
        Object existingTamed =
            commandBuffer.getComponent(npcRef, (ComponentType<EntityStore, Component<EntityStore>>) tamedT);
        if (existingTamed != null && Boolean.TRUE.equals(invokeNoArgBoolean(existingTamed, "isTamed"))) {
            data.setTamed(false);
            data.setOwnerUUID(null);
            return;
        }
        var reg = store.getRegistry().getData();
        Object tamed = reg.createComponent((ComponentType<EntityStore, Component<EntityStore>>) tamedT);
        Object owner = reg.createComponent((ComponentType<EntityStore, Component<EntityStore>>) ownerT);
        invokeVoidBoolean(tamed, "setTamed", true);
        invokeVoidUuid(owner, "setOwnerId", data.getOwnerUUID());
        invokeVoidString(owner, "setOwnerName", "");
        commandBuffer.putComponent(npcRef, (ComponentType<EntityStore, Component<EntityStore>>) tamedT, (Component<EntityStore>) tamed);
        commandBuffer.putComponent(npcRef, (ComponentType<EntityStore, Component<EntityStore>>) ownerT, (Component<EntityStore>) owner);
        data.setTamed(false);
        data.setOwnerUUID(null);
    }

    /** Top-level role state (e.g. Follow, Hold): {@link StateSupport#getStateName()} before the first {@code '.'}. */
    @Nullable
    public static String getNpcRoleStateName(@Nonnull NPCEntity npc) {
        Role role = npc.getRole();
        if (role != null) {
            StateSupport ss = role.getStateSupport();
            if (ss != null) {
                String full = ss.getStateName();
                if (full != null && !full.isBlank()) {
                    int dot = full.indexOf('.');
                    if (dot > 0) {
                        return full.substring(0, dot);
                    }
                    return full;
                }
            }
        }
        for (Object target : new Object[] {npc, role}) {
            if (target == null) {
                continue;
            }
            for (String method :
                new String[] {"getState", "getCurrentState", "getStateName", "getPrimaryStateName"}) {
                Object v = invokeNoArg(target, method);
                if (v instanceof String s && !s.isBlank() && !s.contains(".")) {
                    return s;
                }
                if (v instanceof Enum<?> e) {
                    return e.name();
                }
            }
        }
        if (role == null) {
            return null;
        }
        Object support = invokeNoArg(role, "getStateSupport");
        if (support == null) {
            return null;
        }
        for (String method :
            new String[] {"getState", "getCurrentState", "getStateName", "getPrimaryStateName"}) {
            Object v = invokeNoArg(support, method);
            if (v instanceof String s && !s.isBlank()) {
                int dot = s.indexOf('.');
                return dot > 0 ? s.substring(0, dot) : s;
            }
            if (v instanceof Enum<?> e) {
                return e.name();
            }
        }
        return null;
    }

    /**
     * Whether harvest/water/furnace jobs should stop for this tick (whistle Hold, Follow away from home, combat, etc.).
     * {@code Idle} does not pause. Near the Set Home anchor, {@code Follow} still allows jobs.
     */
    public static boolean shouldPauseHomeAssignmentWork(@Nonnull NPCEntity npc) {
        return shouldPauseHomeAssignmentWork(npc, null, null);
    }

    public static boolean shouldPauseHomeAssignmentWork(
            @Nonnull NPCEntity npc,
            @Nullable CommandBuffer<EntityStore> buffer,
            @Nullable Ref<EntityStore> npcRef) {
        String s = getNpcRoleStateName(npc);
        if (s == null || s.isBlank()) {
            return false;
        }
        String u = s.toUpperCase(Locale.ROOT);
        if (u.equals("HOLD")) {
            return true;
        }
        if (u.equals("FOLLOW")
            && buffer != null
            && npcRef != null
            && isFollowWorkAtHomeNotRealFollow(buffer, npcRef, npc)) {
            return false;
        }
        return u.equals("FOLLOW")
            || u.equals("DEFEND")
            || u.equals("AGGRESSIVE");
    }

    private static final double FOLLOW_AT_HOME_MAX_DISTANCE_FROM_ANCHOR = 18.0;

    private static boolean isFollowWorkAtHomeNotRealFollow(
            @Nonnull CommandBuffer<EntityStore> buffer,
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull NPCEntity npc) {
        Vector3d home = getWorkAnchor(buffer, npcRef);
        if (home == null) {
            return false;
        }
        Store<EntityStore> store = buffer.getStore();
        TransformComponent nt = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (nt == null) {
            return false;
        }
        double dNpcHome = snapToBlockCenter(home).distanceTo(nt.getPosition());
        return dNpcHome <= FOLLOW_AT_HOME_MAX_DISTANCE_FROM_ANCHOR;
    }

    public static boolean isHoldState(@Nonnull NPCEntity npc) {
        String s = getNpcRoleStateName(npc);
        return s != null && s.equalsIgnoreCase("Hold");
    }

    /** True when Tamework role state is Follow (TP follow from NPC instructions, not Java seek). */
    public static boolean isFollowState(@Nonnull NPCEntity npc) {
        String s = getNpcRoleStateName(npc);
        return s != null && s.equalsIgnoreCase("Follow");
    }

    /** Only {@code Follow} should use {@link com.hexvane.dragonlings.MarkedEntitySeekBridge} toward the owner when a home is set. */
    public static boolean shouldDriveOwnerFollowWithSeekBridge(@Nonnull NPCEntity npc) {
        String s = getNpcRoleStateName(npc);
        return s != null && s.equalsIgnoreCase("Follow");
    }

    @Nullable
    private static ComponentType<EntityStore, ?> componentType(@Nonnull Store<EntityStore> store, @Nonnull String id) {
        return store.getRegistry().getData().getComponentType(id);
    }

    @Nullable
    private static Object invokeNoArg(@Nonnull Object target, @Nonnull String name) {
        try {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Nullable
    private static Boolean invokeNoArgBoolean(@Nonnull Object target, @Nonnull String name) {
        Object v = invokeNoArg(target, name);
        return v instanceof Boolean b ? b : null;
    }

    private static void invokeVoidBoolean(@Nonnull Object target, @Nonnull String name, boolean arg) {
        try {
            Method m = target.getClass().getMethod(name, boolean.class);
            m.invoke(target, arg);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static void invokeVoidUuid(@Nonnull Object target, @Nonnull String name, @Nullable UUID arg) {
        try {
            Method m = target.getClass().getMethod(name, UUID.class);
            m.invoke(target, arg);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static void invokeVoidString(@Nonnull Object target, @Nonnull String name, @Nonnull String arg) {
        try {
            Method m = target.getClass().getMethod(name, String.class);
            m.invoke(target, arg);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
