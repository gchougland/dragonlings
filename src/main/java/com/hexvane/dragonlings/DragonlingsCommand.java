package com.hexvane.dragonlings;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.Config;
import javax.annotation.Nonnull;

/**
 * Root command: {@code /dragonlings &lt;subcommand&gt;} (e.g. {@code /dragonlings debug_dump}).
 */
public class DragonlingsCommand extends AbstractCommandCollection {
    public DragonlingsCommand(
            @Nonnull ComponentType<EntityStore, DragonlingData> dragonlingDataType,
            @Nonnull Config<DragonlingsTameCapConfig> tameCapConfig,
            @Nonnull DragonlingsTameCountStore tameCountStore) {
        super("dragonlings", "server.dragonlings.command.desc");
        this.addSubCommand(new DragonlingsDebugDumpCommand(dragonlingDataType));
        this.addSubCommand(new DragonlingsGiveCommand(tameCapConfig, tameCountStore));
    }
}
