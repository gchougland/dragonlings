package com.hexvane.dragonlings;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;

final class DragonlingsDebugDumpCommand extends AbstractPlayerCommand {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Nonnull
    private final ComponentType<EntityStore, DragonlingData> dragonlingDataType;

    DragonlingsDebugDumpCommand(@Nonnull ComponentType<EntityStore, DragonlingData> dragonlingDataType) {
        super("debug_dump", "dragonlings.command.debug_dump.desc");
        this.requirePermission(HytalePermissions.fromCommand("dragonlings", "debug_dump"));
        this.dragonlingDataType = dragonlingDataType;
    }

    @Override
    protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world) {
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        if (npcType == null) {
            LOGGER.atSevere().log(
                "[Dragonlings debug_dump] NPCEntity component type unavailable (NPC module not loaded); skipped");
            return;
        }
        StringBuilder sb = new StringBuilder();
        DragonlingsDebugDump.appendDump(world, store, npcType, this.dragonlingDataType, sb);
        String text = sb.toString();
        LOGGER.atInfo().log(
            "[Dragonlings debug_dump] start (requested by %s, %d chars)", playerRef.getUsername(), text.length());
        DragonlingsDebugDump.logToServer(LOGGER, text);
        LOGGER.atInfo().log("[Dragonlings debug_dump] end");
    }
}
