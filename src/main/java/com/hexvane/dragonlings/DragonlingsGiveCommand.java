package com.hexvane.dragonlings;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.Config;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.util.NPCPhysicsMath;
import it.unimi.dsi.fastutil.Pair;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * {@code /dragonlings give <player> <type>} — spawns a tamed dragonling in front of the target player and links it to
 * Alec's Tamework Dragon Whistle when that mod is present.
 */
final class DragonlingsGiveCommand extends CommandBase {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double SPAWN_DISTANCE = 3.0;

    @Nonnull
    private final RequiredArg<PlayerRef> playerArg =
        this.withRequiredArg("player", "server.dragonlings.command.give.arg.player", ArgTypes.PLAYER_REF);
    @Nonnull
    private final RequiredArg<String> typeArg =
        this.withRequiredArg("type", "server.dragonlings.command.give.arg.type", ArgTypes.STRING);

    @Nonnull
    private final Config<DragonlingsTameCapConfig> tameCapConfig;
    @Nonnull
    private final DragonlingsTameCountStore tameCountStore;

    DragonlingsGiveCommand(
            @Nonnull Config<DragonlingsTameCapConfig> tameCapConfig,
            @Nonnull DragonlingsTameCountStore tameCountStore) {
        super("give", "server.dragonlings.command.give.desc");
        this.requirePermission(HytalePermissions.fromCommand("dragonlings", "give"));
        this.tameCapConfig = tameCapConfig;
        this.tameCountStore = tameCountStore;
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        PlayerRef targetPlayer = this.playerArg.get(context);
        Ref<EntityStore> targetRef = targetPlayer.getReference();
        if (targetRef == null || !targetRef.isValid()) {
            context.sendMessage(Message.translation("server.commands.errors.playerNotInWorld"));
            return;
        }
        String rawType = this.typeArg.get(context).trim();
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            context.sendMessage(Message.translation("server.dragonlings.command.give.npc_module"));
            return;
        }
        String roleName = resolveDragonlingRole(rawType, npcPlugin);
        if (roleName == null) {
            context.sendMessage(Message.translation("server.dragonlings.command.give.unknown_type").param("type", rawType));
            return;
        }
        int roleIndex = npcPlugin.getIndex(roleName);
        if (roleIndex < 0) {
            context.sendMessage(Message.translation("server.dragonlings.command.give.unknown_type").param("type", rawType));
            return;
        }
        Store<EntityStore> store = targetRef.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(
            () -> {
                UUID ownerUuid = targetPlayer.getUuid();
                DragonlingsTameCapConfig capCfg = this.tameCapConfig.get();
                int max = capCfg.getMaxForRole(roleName);
                int already = DragonlingsTameLimits.getRecordedCount(this.tameCountStore, ownerUuid, roleName);
                if (already >= max) {
                    context.sendMessage(
                        Message.translation("server.dragonlings.tame_cap_reached")
                            .param("variant", friendlyVariantLabel(roleName))
                            .param("max", max));
                    return;
                }
                ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
                if (npcType == null) {
                    context.sendMessage(Message.translation("server.dragonlings.command.give.npc_module"));
                    return;
                }
                TransformComponent transform = store.getComponent(targetRef, TransformComponent.getComponentType());
                if (transform == null) {
                    context.sendMessage(Message.translation("server.commands.errors.playerNotInWorld"));
                    return;
                }
                Vector3d spawnPos = spawnPositionInFront(world, transform);
                Pair<Ref<EntityStore>, NPCEntity> pair = npcPlugin.spawnEntity(store, roleIndex, spawnPos, null, null, null);
                if (pair == null) {
                    context.sendMessage(Message.translation("server.dragonlings.command.give.spawn_failed"));
                    LOGGER.atWarning().log("dragonlings give: spawnEntity returned null for role %s", roleName);
                    return;
                }
                Ref<EntityStore> npcRef = pair.first();
                DragonlingTamework.applyTameToNpc(store, npcRef, ownerUuid);
                this.tameCountStore.recordTame(ownerUuid, roleName);
                Vector3d npcPos = spawnPos;
                TransformComponent nt = store.getComponent(npcRef, TransformComponent.getComponentType());
                if (nt != null) {
                    npcPos = new Vector3d(nt.getPosition());
                }
                DragonlingsTameworkCommandLink.linkDragonWhistleToNpc(store, targetRef, npcRef, ownerUuid, npcPos);
                context.sendMessage(
                    Message.translation("server.dragonlings.command.give.success")
                        .param("player", targetPlayer.getUsername())
                        .param("role", roleName));
            });
    }

    @Nonnull
    private static Vector3d spawnPositionInFront(@Nonnull World world, @Nonnull TransformComponent playerTransform) {
        Vector3d p = playerTransform.getPosition();
        Vector3f rot = playerTransform.getRotation();
        Vector3d dir = Transform.getDirection(rot.getPitch(), rot.getYaw());
        double x = p.x + dir.x * SPAWN_DISTANCE;
        double z = p.z + dir.z * SPAWN_DISTANCE;
        double y = NPCPhysicsMath.heightOverGround(world, x, z);
        if (y < 0.0) {
            y = p.y;
        }
        return new Vector3d(x, y, z);
    }

    /**
     * Accepts short names ({@code green}), full role ids ({@code Dragonling_Green}), or any valid NPC role name
     * registered with {@link NPCPlugin#getIndex(String)}.
     */
    @Nullable
    private static String resolveDragonlingRole(@Nonnull String raw, @Nonnull NPCPlugin npcPlugin) {
        String key = raw.trim();
        if (key.isEmpty()) {
            return null;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        String canonical =
            switch (lower) {
                case "green", "g" -> "Dragonling_Green";
                case "blue", "b" -> "Dragonling_Blue";
                case "red", "r" -> "Dragonling_Red";
                case "purple", "p" -> "Dragonling_Purple";
                case "pilot", "template", "template_pilot", "dragonling_templatepilot" -> "Dragonling_TemplatePilot";
                default -> null;
            };
        if (canonical != null) {
            return canonical;
        }
        if (npcPlugin.getIndex(key) >= 0) {
            return key;
        }
        String normalizedSpaces = key.replace(' ', '_');
        if (!normalizedSpaces.equals(key) && npcPlugin.getIndex(normalizedSpaces) >= 0) {
            return normalizedSpaces;
        }
        return null;
    }

    @Nonnull
    private static String friendlyVariantLabel(@Nonnull String roleName) {
        return switch (roleName) {
            case "Dragonling_Green" -> "Green";
            case "Dragonling_Blue" -> "Blue";
            case "Dragonling_Red" -> "Red";
            case "Dragonling_Purple" -> "Purple";
            case "Dragonling_TemplatePilot" -> "Template pilot";
            default -> roleName;
        };
    }
}
