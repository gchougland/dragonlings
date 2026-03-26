package com.hexvane.dragonlings;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Root command: {@code /dragonlings &lt;subcommand&gt;} (e.g. {@code /dragonlings debug_dump}).
 */
public class DragonlingsCommand extends AbstractCommandCollection {
    public DragonlingsCommand(ComponentType<EntityStore, DragonlingData> dragonlingDataType) {
        super("dragonlings", "dragonlings.command.desc");
        this.addSubCommand(new DragonlingsDebugDumpCommand(dragonlingDataType));
    }
}
