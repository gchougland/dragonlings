package com.hexvane.dragonlings;

import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.builtin.adventure.farming.states.TilledSoilBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hexvane.dragonlings.behaviors.BlueDragonlingWaterBehavior;
import com.hexvane.dragonlings.behaviors.GreenDragonlingHarvestBehavior;
import com.hexvane.dragonlings.behaviors.PurpleDragonlingCombatBehavior;
import com.hexvane.dragonlings.behaviors.PurpleDragonlingCombatListener;
import com.hexvane.dragonlings.behaviors.RedDragonlingFurnaceBehavior;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One-shot text dump for {@code /dragonlings debug_dump}, written to the server log only (not in-game chat).
 */
public final class DragonlingsDebugDump {
    private static final int MAX_LOG_LINE_CHARS = 8000;
    private static final int MAX_GREEN_CROP_LINES = 128;

    private DragonlingsDebugDump() {}

    /** Emits one INFO log line per text line so the full dump appears in the server console / log file. */
    public static void logToServer(@Nonnull HytaleLogger logger, @Nonnull String text) {
        if (text.isEmpty()) {
            logger.atInfo().log("[Dragonlings debug_dump] (empty)");
            return;
        }
        for (String line : text.split("\n", -1)) {
            if (line.length() > MAX_LOG_LINE_CHARS) {
                logger.atInfo().log("%s... [line truncated]", line.substring(0, MAX_LOG_LINE_CHARS));
            } else {
                logger.atInfo().log("%s", line);
            }
        }
    }

    public static void appendDump(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull ComponentType<EntityStore, NPCEntity> npcComponentType,
            @Nonnull ComponentType<EntityStore, DragonlingData> dragonlingDataType,
            @Nonnull StringBuilder out) {
        out.append("=== Dragonlings debug_dump ===\n");
        out.append("World: ").append(world.getName()).append('\n');
        WorldTimeResource wtr = store.getResource(WorldTimeResource.getResourceType());
        if (wtr != null) {
            out.append("Game time: ").append(wtr.getGameTime()).append('\n');
        }
        out.append('\n');

        Query<EntityStore> npcQuery = Query.and(npcComponentType);
        int[] count = {0};
        store.forEachChunk(
            npcQuery,
            (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> cb) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    NPCEntity npc = chunk.getComponent(i, npcComponentType);
                    if (npc == null) {
                        continue;
                    }
                    String roleName = npc.getRoleName();
                    if (roleName == null || !roleName.contains("Dragonling")) {
                        continue;
                    }
                    Ref<EntityStore> ref = chunk.getReferenceTo(i);
                    DragonlingData data = store.getComponent(ref, dragonlingDataType);
                    appendOneDragonling(world, store, ref, npc, data, roleName, out);
                    count[0]++;
                }
            });
        out.append("Total dragonling NPCs listed: ").append(count[0]).append('\n');
    }

    private static void appendOneDragonling(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull NPCEntity npc,
            @Nullable DragonlingData data,
            @Nonnull String roleName,
            @Nonnull StringBuilder out) {
        out.append("--- ").append(roleName).append(" ---\n");
        UUIDComponent uuidComp = store.getComponent(ref, UUIDComponent.getComponentType());
        out.append("Entity UUID: ").append(uuidComp != null ? uuidComp.getUuid() : "?").append('\n');

        TransformComponent tf = store.getComponent(ref, TransformComponent.getComponentType());
        Vector3d npcPos = tf != null ? tf.getPosition() : null;
        if (npcPos != null) {
            out.append(String.format(Locale.US, "Position: (%.2f, %.2f, %.2f)\n", npcPos.x, npcPos.y, npcPos.z));
        } else {
            out.append("Position: (null)\n");
        }

        out.append("Tamework tamed: ").append(DragonlingTamework.isTamed(store, ref)).append('\n');
        UUID owner = DragonlingTamework.getOwnerId(store, ref);
        out.append("Tamework owner: ").append(owner != null ? owner : "null").append('\n');
        Vector3d home = DragonlingTamework.getHomePosition(store, ref);
        if (home != null) {
            Vector3d anchor = DragonlingTamework.snapToBlockCenter(home);
            out.append(String.format(Locale.US, "Tamework home (raw): (%.3f, %.3f, %.3f)\n", home.x, home.y, home.z));
            out.append(
                String.format(
                    Locale.US,
                    "Work anchor (snapped): (%.3f, %.3f, %.3f)\n",
                    anchor.x,
                    anchor.y,
                    anchor.z));
        } else {
            out.append("Tamework home: null (no StoreHome)\n");
        }

        String state = DragonlingTamework.getNpcRoleStateName(npc);
        out.append("NPC role state: ").append(state != null ? state : "?").append('\n');
        out.append("shouldPauseHomeWork: ").append(DragonlingTamework.shouldPauseHomeAssignmentWork(npc)).append('\n');

        if (data != null) {
            out.append("DragonlingData: aiState=").append(data.getAIState());
            Vector3d tp = data.getTargetPosition();
            out.append(" target=").append(tp != null ? formatVec(tp) : "null");
            out.append(" leashed=").append(data.isLeashed());
            Vector3d lp = data.getLeashPosition();
            out.append(" leashPos=").append(lp != null ? formatVec(lp) : "null");
            out.append(" leashRadius=").append(data.getLeashRadius());
            out.append(" legacyTamed=").append(data.isTamed());
            out.append('\n');
        } else {
            out.append("DragonlingData: (none — not tamed / not bootstrapped yet)\n");
        }

        if (roleName.contains("Green") && npcPos != null && home != null) {
            Vector3d anchor = DragonlingTamework.snapToBlockCenter(home);
            appendGreenSection(world, npcPos, anchor, out);
        }
        if (roleName.contains("Blue") && npcPos != null && home != null) {
            Vector3d anchor = DragonlingTamework.snapToBlockCenter(home);
            appendBlueTilledSection(world, store, anchor, npcPos, out);
        }
        if (roleName.contains("Red") && npcPos != null && home != null) {
            Vector3d anchor = DragonlingTamework.snapToBlockCenter(home);
            appendRedFurnaceSection(world, anchor, npcPos, out);
        }
        if (roleName.contains("Purple")) {
            appendPurpleSection(store, ref, npcPos, out);
        }
        out.append('\n');
    }

    @Nonnull
    private static String formatVec(@Nonnull Vector3d v) {
        return String.format(Locale.US, "(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
    }

    private static void appendGreenSection(
            @Nonnull World world, @Nonnull Vector3d npcPos, @Nonnull Vector3d center, @Nonnull StringBuilder out) {
        out.append("  [Green] harvest radius=").append(GreenDragonlingHarvestBehavior.HARVEST_RADIUS).append('\n');
        int centerX = (int) Math.floor(center.x);
        int centerY = (int) Math.floor(center.y);
        int centerZ = (int) Math.floor(center.z);
        int radius = (int) Math.ceil(GreenDragonlingHarvestBehavior.HARVEST_RADIUS);
        double radiusSq = GreenDragonlingHarvestBehavior.HARVEST_RADIUS * GreenDragonlingHarvestBehavior.HARVEST_RADIUS;
        int logged = 0;
        boolean truncated = false;
        outer:
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double d2 = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (d2 > radiusSq) {
                        continue;
                    }
                    int bx = centerX + dx;
                    int by = centerY + dy;
                    int bz = centerZ + dz;
                    long chunkIdx = ChunkUtil.indexChunkFromBlock(bx, bz);
                    WorldChunk wc = world.getChunkIfInMemory(chunkIdx);
                    if (wc == null) {
                        continue;
                    }
                    int blockId = wc.getBlock(bx, by, bz);
                    if (blockId == 0) {
                        continue;
                    }
                    BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
                    if (blockType == null || !isFarmCropCandidate(blockType)) {
                        continue;
                    }
                    if (logged >= MAX_GREEN_CROP_LINES) {
                        truncated = true;
                        break outer;
                    }
                    out.append("  crop ")
                        .append(formatGreenCropLine(world, bx, by, bz, blockType, centerX, centerY, centerZ, npcPos))
                        .append('\n');
                    logged++;
                }
            }
        }
        if (logged == 0) {
            out.append("  (no farm-crop candidates in loaded chunks)\n");
        }
        if (truncated) {
            out.append("  ... more crop lines omitted (cap=").append(MAX_GREEN_CROP_LINES).append(")\n");
        }
    }

    private static boolean isFarmCropCandidate(@Nonnull BlockType blockType) {
        BlockGathering gathering = blockType.getGathering();
        if (gathering == null || !gathering.isHarvestable()) {
            return false;
        }
        return blockType.getFarming() != null;
    }

    @Nonnull
    private static String formatGreenCropLine(
            @Nonnull World world,
            int bx,
            int by,
            int bz,
            @Nonnull BlockType blockType,
            int centerX,
            int centerY,
            int centerZ,
            @Nonnull Vector3d npcPos) {
        String id = blockType.getId() != null ? blockType.getId() : "?";
        double dxh = bx - centerX;
        double dyh = by - centerY;
        double dzh = bz - centerZ;
        double distHome = Math.sqrt(dxh * dxh + dyh * dyh + dzh * dzh);
        Vector3d blockCenter = new Vector3d(bx + 0.5, by + 0.5, bz + 0.5);
        double distNpc = npcPos.distanceTo(blockCenter);

        boolean mature =
            GreenDragonlingHarvestBehavior.isMatureHarvestableCropForDebug(world, bx, by, bz, blockType);

        WorldChunk wc = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(bx, bz));
        Ref<ChunkStore> blockRef = wc != null ? wc.getBlockComponentEntity(bx, by, bz) : null;
        if (blockRef == null) {
            blockRef = GreenDragonlingHarvestBehavior.resolveFarmingBlockEntityRefPublic(world, bx, by, bz);
        }
        String refPart = blockRef == null ? "blockRef=null" : "blockRef=ok";

        FarmingData farmingData = blockType.getFarming();
        if (farmingData == null || farmingData.getStages() == null) {
            return String.format(
                Locale.US,
                "%s @(%d,%d,%d) distHome=%.2f distNpc=%.2f mature=%s %s (no staged farming)",
                id,
                bx,
                by,
                bz,
                distHome,
                distNpc,
                mature,
                refPart);
        }

        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
        FarmingBlock farmingBlock =
            blockRef != null ? chunkStore.getComponent(blockRef, FarmingBlock.getComponentType()) : null;
        if (farmingBlock == null) {
            String tail = id.contains("StageFinal") ? " (StageFinal block; grown crop often has no FarmingBlock)" : "";
            return String.format(
                Locale.US,
                "%s @(%d,%d,%d) distHome=%.2f distNpc=%.2f mature=%s %s FarmingBlock=null%s",
                id,
                bx,
                by,
                bz,
                distHome,
                distNpc,
                mature,
                refPart,
                tail);
        }

        float gp = farmingBlock.getGrowthProgress();
        String stageSet = farmingBlock.getCurrentStageSet();
        java.util.Map<String, FarmingStageData[]> stageSets = farmingData.getStages();
        FarmingStageData[] stages = stageSet != null ? stageSets.get(stageSet) : null;
        if (stages == null) {
            stageSet = farmingData.getStartingStageSet();
            stages = stageSet != null ? stageSets.get(stageSet) : null;
        }
        int nStages = stages != null ? stages.length : 0;
        int stageInt = (int) gp;
        float frac = gp - stageInt;
        boolean engineMature = nStages > 0 && GreenDragonlingHarvestBehavior.isFarmingGrowthMatureForDebug(gp, nStages);

        return String.format(
            Locale.US,
            "%s @(%d,%d,%d) distHome=%.2f distNpc=%.2f mature=%s %s gp=%.4f stageSet=%s nStages=%d "
                + "stageInt=%d frac=%.4f engineMature=%s",
            id,
            bx,
            by,
            bz,
            distHome,
            distNpc,
            mature,
            refPart,
            gp,
            stageSet != null ? stageSet : "?",
            nStages,
            stageInt,
            frac,
            engineMature);
    }

    private static void appendBlueTilledSection(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull Vector3d center,
            @Nonnull Vector3d npcPos,
            @Nonnull StringBuilder out) {
        out.append("  [Blue] water radius=").append(BlueDragonlingWaterBehavior.WATER_RADIUS).append('\n');
        WorldTimeResource wtr = store.getResource(WorldTimeResource.getResourceType());
        Instant gameTime = wtr != null ? wtr.getGameTime() : null;

        int centerX = (int) Math.floor(center.x);
        int centerY = (int) Math.floor(center.y);
        int centerZ = (int) Math.floor(center.z);
        int radius = (int) Math.ceil(BlueDragonlingWaterBehavior.WATER_RADIUS);
        double radiusSq = BlueDragonlingWaterBehavior.WATER_RADIUS * BlueDragonlingWaterBehavior.WATER_RADIUS;
        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
        int lines = 0;
        final int maxLines = 96;

        for (int dx = -radius; dx <= radius && lines < maxLines; dx++) {
            for (int dz = -radius; dz <= radius && lines < maxLines; dz++) {
                double horizontalDistSq = (double) dx * dx + (double) dz * dz;
                if (horizontalDistSq > radiusSq) {
                    continue;
                }
                int bx = centerX + dx;
                int bz = centerZ + dz;
                long blockChunkIndex = ChunkUtil.indexChunkFromBlock(bx, bz);
                WorldChunk blockChunk = world.getChunkIfInMemory(blockChunkIndex);
                if (blockChunk == null) {
                    continue;
                }
                for (int dy = -radius; dy <= radius && lines < maxLines; dy++) {
                    int by = centerY + dy;
                    double distanceSq = horizontalDistSq + (long) dy * dy;
                    if (distanceSq > radiusSq) {
                        continue;
                    }
                    int blockId = blockChunk.getBlock(bx, by, bz);
                    if (blockId == 0) {
                        continue;
                    }
                    BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
                    if (blockType == null) {
                        continue;
                    }
                    FarmingData farmingData = blockType.getFarming();
                    String blockTypeId = blockType.getId();
                    boolean isTilledSoilType =
                        blockTypeId != null && (blockTypeId.contains("Tilled") || blockTypeId.contains("Farmland"));

                    Ref<ChunkStore> soilRef = blockChunk.getBlockComponentEntity(bx, by, bz);
                    TilledSoilBlock tilledSoil =
                        soilRef != null
                            ? chunkStore.getComponent(soilRef, TilledSoilBlock.getComponentType())
                            : null;
                    int waterX = bx;
                    int waterY = by;
                    int waterZ = bz;
                    if (tilledSoil == null && farmingData != null && by > -60) {
                        Ref<ChunkStore> belowRef = blockChunk.getBlockComponentEntity(bx, by - 1, bz);
                        if (belowRef != null) {
                            tilledSoil =
                                chunkStore.getComponent(belowRef, TilledSoilBlock.getComponentType());
                            waterX = bx;
                            waterY = by - 1;
                            waterZ = bz;
                        }
                    }
                    if (tilledSoil == null && !isTilledSoilType) {
                        continue;
                    }

                    boolean isWatered = false;
                    if (tilledSoil != null) {
                        if (gameTime != null) {
                            Instant wateredUntil = tilledSoil.getWateredUntil();
                            isWatered =
                                tilledSoil.hasExternalWater()
                                    || (wateredUntil != null && wateredUntil.isAfter(gameTime));
                        } else {
                            isWatered = tilledSoil.hasExternalWater() || tilledSoil.getWateredUntil() != null;
                        }
                    }

                    Vector3d sample = new Vector3d(waterX + 0.5, waterY + 1.0, waterZ + 0.5);
                    double distNpc = npcPos.distanceTo(sample);
                    out.append("  soil ");
                    if (tilledSoil == null) {
                        out.append(String.format(Locale.US,
                            "(%d,%d,%d) id=%s tilledSoil=null (fresh/unloaded entity?) distNpc=%.2f\n",
                            bx, by, bz, blockTypeId != null ? blockTypeId : "?",
                            distNpc));
                    } else {
                        Instant wu = tilledSoil.getWateredUntil();
                        out.append(String.format(Locale.US,
                            "waterAt=(%d,%d,%d) block=%s extWater=%s wateredUntil=%s gameTime=%s isWatered=%s distNpc=%.2f\n",
                            waterX,
                            waterY,
                            waterZ,
                            blockTypeId != null ? blockTypeId : "?",
                            tilledSoil.hasExternalWater(),
                            wu,
                            gameTime,
                            isWatered,
                            distNpc));
                    }
                    lines++;
                }
            }
        }
        if (lines == 0) {
            out.append("  (no tilled-soil rows in loaded chunks)\n");
        } else if (lines >= maxLines) {
            out.append("  ... more soil lines omitted (cap=").append(maxLines).append(")\n");
        }
    }

    private static void appendRedFurnaceSection(
            @Nonnull World world, @Nonnull Vector3d center, @Nonnull Vector3d npcPos, @Nonnull StringBuilder out) {
        out.append("  [Red] furnace radius=").append(RedDragonlingFurnaceBehavior.FURNACE_RADIUS).append('\n');
        int centerX = (int) Math.floor(center.x);
        int centerY = (int) Math.floor(center.y);
        int centerZ = (int) Math.floor(center.z);
        int radius = (int) Math.ceil(RedDragonlingFurnaceBehavior.FURNACE_RADIUS);
        double radiusSq = RedDragonlingFurnaceBehavior.FURNACE_RADIUS * RedDragonlingFurnaceBehavior.FURNACE_RADIUS;
        int lines = 0;
        final int maxLines = 48;

        for (int dx = -radius; dx <= radius && lines < maxLines; dx++) {
            for (int dz = -radius; dz <= radius && lines < maxLines; dz++) {
                int bx = centerX + dx;
                int bz = centerZ + dz;
                WorldChunk blockChunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(bx, bz));
                if (blockChunk == null) {
                    continue;
                }
                for (int dy = -radius; dy <= radius && lines < maxLines; dy++) {
                    int by = centerY + dy;
                    double distanceSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (distanceSq > radiusSq) {
                        continue;
                    }
                    int blockId = blockChunk.getBlock(bx, by, bz);
                    if (blockId == 0) {
                        continue;
                    }
                    BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
                    if (blockType == null) {
                        continue;
                    }
                    String bid = blockType.getId();
                    if (bid == null || (!bid.contains("Furnace") && !bid.contains("Smelter"))) {
                        continue;
                    }
                    ProcessingBenchBlock bench =
                        BlockModule.getComponent(ProcessingBenchBlock.getComponentType(), world, bx, by, bz);
                    if (bench == null) {
                        out.append("  furnace ")
                            .append(String.format(Locale.US,
                                "(%d,%d,%d) %s ProcessingBenchBlock=null\n",
                                bx, by, bz, bid));
                        lines++;
                        continue;
                    }
                    Vector3d fp = new Vector3d(bx + 0.5, by + 0.5, bz + 0.5);
                    double distNpc = npcPos.distanceTo(fp);
                    out.append("  furnace ")
                        .append(String.format(Locale.US,
                            "(%d,%d,%d) %s active=%s recipe=%s distNpc=%.2f\n",
                            bx,
                            by,
                            bz,
                            bid,
                            bench.isActive(),
                            bench.getRecipe() != null ? String.valueOf(bench.getRecipe()) : "null",
                            distNpc));
                    lines++;
                }
            }
        }
        if (lines == 0) {
            out.append("  (no furnace/smelter blocks in loaded chunks)\n");
        }
    }

    private static void appendPurpleSection(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> dragonlingRef,
            @Nullable Vector3d npcPos,
            @Nonnull StringBuilder out) {
        out.append("  [Purple] attackRange=").append(PurpleDragonlingCombatBehavior.ATTACK_RANGE).append('\n');
        Ref<EntityStore> targetRef = PurpleDragonlingCombatListener.getCombatTarget(dragonlingRef);
        if (targetRef == null || !targetRef.isValid()) {
            out.append("  combat target: none\n");
            return;
        }
        UUIDComponent tu = store.getComponent(targetRef, UUIDComponent.getComponentType());
        TransformComponent tt = store.getComponent(targetRef, TransformComponent.getComponentType());
        out.append("  combat target ref valid; uuid=").append(tu != null ? tu.getUuid() : "?").append('\n');
        if (tt != null && npcPos != null) {
            Vector3d p = tt.getPosition();
            out.append(String.format(Locale.US,
                "  target pos: (%.2f, %.2f, %.2f) dist=%.2f\n",
                p.x,
                p.y,
                p.z,
                npcPos.distanceTo(p)));
        } else {
            out.append("  target transform: null\n");
        }
    }
}
