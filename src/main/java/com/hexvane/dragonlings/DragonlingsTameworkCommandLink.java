package com.hexvane.dragonlings;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Mirrors Tamework's command-item link flow without compile-time dependency on Alec's Tamework (it is {@code compileOnly}
 * and not on this mod's runtime classpath). Uses reflection; metadata format matches Tamework's linked-NPC records.
 *
 * <p>
 * The public {@code TameworkApi.commandLinks()} API is <strong>read-only</strong> (detached snapshots: {@code getByNpcUuid},
 * {@code listLinkedToolIds}, etc.); it does not register links. See the
 * <a href="https://wiki.hytalemodding.dev/mod/alecs-tamework/command-links-api-reference">Command Links API Reference</a>
 * on the HytaleModding wiki. Creating a link therefore follows the same ECS + whistle-item metadata path Tamework uses
 * internally when a player links in-game.
 */
final class DragonlingsTameworkCommandLink {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    static final String DRAGON_WHISTLE_ITEM_ID = "Dragon_Whistle";
    /** Same value as {@code TameworkMetadataKeys.COMMAND_LINKED_NPCS}. */
    private static final String META_COMMAND_LINKED_NPCS = "Tamework.Command.LinkedNpcs";

    private static final String CLASS_TAMEWORK = "com.alechilles.alecstamework.Tamework";

    private DragonlingsTameworkCommandLink() {}

    /**
     * Registers the NPC with the Dragon Whistle tool id and merges linked-NPC metadata onto every Dragon Whistle in the
     * player's inventory (including the tools belt; {@link InventoryComponent#EVERYTHING} excludes tools).
     */
    static void linkDragonWhistleToNpc(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull UUID ownerUuid,
            @Nonnull Vector3d npcPosition) {
        if (!tryPutCommandLinksComponent(store, npcRef, ownerUuid)) {
            return;
        }
        tryRefreshTameworkCommandLinkSnapshot(npcRef, store);
        UUID npcUuid = npcEntityUuid(store, npcRef);
        if (npcUuid == null) {
            return;
        }
        updateWhistlesInCombined(
            InventoryComponent.getCombined(store, playerRef, InventoryComponent.EVERYTHING), npcUuid, npcPosition);
        updateWhistlesInCombined(
            InventoryComponent.getCombined(store, playerRef, InventoryComponent.Tool.getComponentType()),
            npcUuid,
            npcPosition);
    }

    /**
     * Uses the {@link EntityStore} registry id {@link DragonlingTamework#ID_COMMAND_LINKS} so we get the same
     * {@link ComponentType} Tamework registered (do not use {@code TameworkCommandLinksComponent.getComponentType()},
     * which can disagree with the store). After {@code putComponent}, notifies Tamework's snapshot service so profiles /
     * linked-panel state match in-game linking ({@code CommandLinkMutationService} does the same refresh).
     *
     * @return false if Tamework never registered command links or reflection failed
     */
    @SuppressWarnings("unchecked")
    private static boolean tryPutCommandLinksComponent(
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> npcRef, @Nonnull UUID ownerUuid) {
        ComponentType<EntityStore, ?> rawType =
            store.getRegistry().getData().getComponentType(DragonlingTamework.ID_COMMAND_LINKS);
        if (rawType == null) {
            LOGGER.atWarning().log(
                "[Dragonlings] Tamework command-links component is not registered (is Alec's Tamework loaded before Dragonlings?)");
            return false;
        }
        ComponentType<EntityStore, Component<EntityStore>> linkType =
            (ComponentType<EntityStore, Component<EntityStore>>) rawType;
        try {
            var reg = store.getRegistry().getData();
            Object existing = store.getComponent(npcRef, linkType);
            Object links;
            Class<?> linkClass;
            if (existing != null) {
                linkClass = existing.getClass();
                Method withToolIdAdded = linkClass.getMethod("withToolIdAdded", String.class);
                Method setOwnerId = linkClass.getMethod("setOwnerId", UUID.class);
                links = withToolIdAdded.invoke(existing, DRAGON_WHISTLE_ITEM_ID);
                setOwnerId.invoke(links, ownerUuid);
            } else {
                links = reg.createComponent(linkType);
                if (links == null) {
                    LOGGER.atWarning().log("[Dragonlings] Registry could not create TameworkCommandLinksComponent instance");
                    return false;
                }
                linkClass = links.getClass();
                Method setOwnerId = linkClass.getMethod("setOwnerId", UUID.class);
                Method setToolIds = linkClass.getMethod("setToolIds", String[].class);
                setOwnerId.invoke(links, ownerUuid);
                setToolIds.invoke(links, new Object[] {new String[] {DRAGON_WHISTLE_ITEM_ID}});
            }
            store.putComponent(npcRef, linkType, (Component<EntityStore>) links);
            return true;
        } catch (ReflectiveOperationException e) {
            LOGGER.atWarning().withCause(e).log("[Dragonlings] Failed to write TameworkCommandLinksComponent");
            return false;
        }
    }

    /**
     * Tamework keeps an in-memory snapshot + SQLite profile rows updated from this service; without it, ECS has links
     * but the whistle UI / API still look empty.
     */
    private static void tryRefreshTameworkCommandLinkSnapshot(
            @Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        try {
            // Same as {@link DragonlingTamework#tameworkHomePositionView}: default Class.forName (not Dragonlings' CL —
            // Tamework lives in another mod JAR).
            Class<?> tameworkClass = Class.forName(CLASS_TAMEWORK);
            Object tamework = tameworkClass.getMethod("getInstance").invoke(null);
            if (tamework == null) {
                return;
            }
            Field field = tameworkClass.getDeclaredField("commandLinkedNpcStateSnapshotService");
            field.setAccessible(true);
            Object snapshotService = field.get(tamework);
            if (snapshotService == null) {
                return;
            }
            Method refresh = snapshotService.getClass().getMethod("refreshFromEntity", Ref.class, Store.class);
            refresh.invoke(snapshotService, npcRef, store);
        } catch (Throwable t) {
            LOGGER.atFine().withCause(t).log("[Dragonlings] Could not refresh Tamework command-link snapshot (non-fatal)");
        }
    }

    @Nullable
    private static UUID npcEntityUuid(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> npcRef) {
        var uuidComp = store.getComponent(npcRef, UUIDComponent.getComponentType());
        return uuidComp != null ? uuidComp.getUuid() : null;
    }

    private static void updateWhistlesInCombined(
            @Nullable CombinedItemContainer combined, @Nonnull UUID npcUuid, @Nonnull Vector3d npcPosition) {
        if (combined == null) {
            return;
        }
        for (short i = 0; i < combined.getCapacity(); i++) {
            ItemStack stack = combined.getItemStack(i);
            if (ItemStack.isEmpty(stack) || stack == null) {
                continue;
            }
            if (!DRAGON_WHISTLE_ITEM_ID.equals(stack.getItemId())) {
                continue;
            }
            ItemStack merged = mergeLinkedNpcRecord(stack, npcUuid, npcPosition);
            if (merged != stack) {
                combined.setItemStackForSlot(i, merged);
            }
        }
    }

    /**
     * Appends a linked-NPC line compatible with Tamework's {@code CommandLinkedNpcRecordStore} format (uuid + optional
     * {@code |x|y|z} last-known position).
     */
    @Nonnull
    static ItemStack mergeLinkedNpcRecord(
            @Nonnull ItemStack stack, @Nonnull UUID npcUuid, @Nonnull Vector3d lastKnown) {
        String existing = stack.getFromMetadataOrNull(META_COMMAND_LINKED_NPCS, Codec.STRING);
        String key = npcUuid.toString().toLowerCase(Locale.ROOT);
        if (existing != null && !existing.isBlank()) {
            for (String line : existing.split("\n", -1)) {
                if (line.isBlank()) {
                    continue;
                }
                String head = line.split("\\|", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (head.equals(key)) {
                    return stack;
                }
            }
        }
        StringBuilder builder = new StringBuilder();
        if (existing != null && !existing.isBlank()) {
            builder.append(existing);
            if (!existing.endsWith("\n")) {
                builder.append('\n');
            }
        }
        builder.append(npcUuid);
        builder.append('|')
            .append(lastKnown.x)
            .append('|')
            .append(lastKnown.y)
            .append('|')
            .append(lastKnown.z);
        return stack.withMetadata(META_COMMAND_LINKED_NPCS, Codec.STRING, builder.toString());
    }
}
