package com.hexvane.dragonlings.behaviors;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.builtin.adventure.farming.FarmingUtil;
import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.PlaceBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hexvane.dragonlings.DragonlingData;
import com.hexvane.dragonlings.DragonlingTamework;
import com.hexvane.dragonlings.world.ChunkSectionBlockUtil;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * Behavior system for Green dragonlings - harvests crops and deposits them in chest.
 */
public class GreenDragonlingHarvestBehavior extends EntityTickingSystem<EntityStore> {
    public static final double HARVEST_RADIUS = 11.0;
    private static final double HARVEST_COOLDOWN = 2.0; // Seconds between crop harvests
    private static final double APPROACH_DISTANCE = 3.0; // Distance to trigger harvesting
    private static final String TILLED_SOIL_BLOCK_ID = "Soil_Dirt_Tilled";
    private static final String PLANTER_BLOCK_TAG = "SubType=Planter";

    @Nullable
    private static final Field PLACE_BLOCK_TYPE_KEY_FIELD = resolvePlaceBlockTypeKeyField();

    @Nullable
    private static volatile Map<String, CropSeedBinding> cropBlockToSeed;
    
    @Nonnull
    private final ComponentType<EntityStore, NPCEntity> npcComponentType;
    @Nonnull
    private final ComponentType<EntityStore, DragonlingData> dragonlingDataType;
    @Nonnull
    private final Query<EntityStore> query;
    
    // Track harvest cooldowns per dragonling
    private final Map<Ref<EntityStore>, Double> harvestCooldowns = new HashMap<>();
    
    public GreenDragonlingHarvestBehavior(
            @Nonnull ComponentType<EntityStore, NPCEntity> npcComponentType,
            @Nonnull ComponentType<EntityStore, DragonlingData> dragonlingDataType) {
        this.npcComponentType = npcComponentType;
        this.dragonlingDataType = dragonlingDataType;
        this.query = Query.and(npcComponentType, dragonlingDataType);
    }
    
    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        
        NPCEntity npcComponent = archetypeChunk.getComponent(index, this.npcComponentType);
        Ref<EntityStore> npcRef = archetypeChunk.getReferenceTo(index);
        DragonlingData data = commandBuffer.getComponent(npcRef, this.dragonlingDataType);
        
        if (npcComponent == null || data == null) {
            return;
        }
        
        if (!npcComponent.getRoleName().contains("Green")) {
            return;
        }

        Vector3d leashPos = DragonlingTamework.getWorkAnchor(commandBuffer, npcRef);
        if (leashPos == null) {
            return;
        }
        if (DragonlingTamework.isTamed(store, npcRef)
            && DragonlingTamework.shouldPauseHomeAssignmentWork(npcComponent, commandBuffer, npcRef)) {
            return;
        }
        try {
        tickHarvestBody(
            dt,
            archetypeChunk,
            store,
            commandBuffer,
            npcComponent,
            npcRef,
            data,
            leashPos);
        } finally {
            commandBuffer.putComponent(npcRef, this.dragonlingDataType, data);
        }
    }

    private void tickHarvestBody(
            float dt,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull NPCEntity npcComponent,
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull DragonlingData data,
            @Nonnull Vector3d leashPos) {
        
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        
        World world = npcComponent.getWorld();
        if (world == null) {
            return;
        }
        
        double currentTime = System.currentTimeMillis() / 1000.0;
        
        Vector3d npcPos = transform.getPosition();
        Vector3d existingTarget = data.getTargetPosition();
        
        int centerX = (int) Math.floor(leashPos.x);
        int centerY = (int) Math.floor(leashPos.y); // Y is vertical
        int centerZ = (int) Math.floor(leashPos.z); // Z is north/south
        
        if (!ChunkSectionBlockUtil.isChunkInMemory(world, centerX, centerZ)) {
            return;
        }

        ItemContainerBlock chestBlock = findItemContainerNearHome(world, leashPos);
        if (chestBlock == null) {
            return;
        }
        ItemContainer chestInventory = chestBlock.getItemContainer();
        
        // Variables to track the best crop to harvest
        int bestCropX = 0, bestCropY = 0, bestCropZ = 0;
        double bestDistance = Double.MAX_VALUE;
        com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType bestBlockType = null;
        
        // Scan area for crops in a true 3D sphere (not a cylinder or single Y): horizontal + dy filtered by radiusSq.
        int radius = (int) Math.ceil(HARVEST_RADIUS);
        double radiusSq = HARVEST_RADIUS * HARVEST_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int bx = centerX + dx;
                int bz = centerZ + dz; // Z is north/south (horizontal)
                
                // Check 3D distance (sphere) - check X and Z first to skip entire columns outside range
                // We'll check Y in the inner loop
                double horizontalDistSq = dx * dx + dz * dz;
                if (horizontalDistSq > radiusSq) {
                    continue; // Already outside sphere radius even at closest Y
                }
                
                if (!ChunkSectionBlockUtil.isChunkInMemory(world, bx, bz)) {
                    continue; // Chunk not loaded, skip this block
                }
                
                // Check blocks at different Y levels (vertical, full sphere)
                for (int dy = -radius; dy <= radius; dy++) {
                    int by = centerY + dy; // Y is vertical
                    
                    // Check 3D distance (sphere) instead of just 2D (circle)
                    double distanceSq = horizontalDistSq + dy * dy;
                    if (distanceSq > radiusSq) {
                        continue; // Outside sphere radius
                    }
                    
                    int blockId = ChunkSectionBlockUtil.blockId(world, bx, by, bz);
                    if (blockId == 0) {
                        continue; // Air or invalid block
                    }
                    
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType blockType = 
                        com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset(blockId);
                    if (blockType == null) {
                        continue;
                    }

                    // Check if it's a harvestable, mature crop
                    if (!isMatureHarvestableCrop(world, bx, by, bz, blockType)) {
                        continue;
                    }
                    
                    Vector3d approachPos = resolveHarvestApproachPosition(world, bx, by, bz, blockType);
                    double distance = npcPos.distance(approachPos);
                    
                    // Track the nearest mature crop
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestCropX = bx;
                        bestCropY = by;
                        bestCropZ = bz;
                        bestBlockType = blockType;
                    }
                }
            }
        }

        if (existingTarget != null && data.getAIState() == com.hexvane.dragonlings.DragonlingAIState.HARVEST_CROPS) {
            HarvestCropMatch committed =
                findCropMatchingApproachTarget(world, centerX, centerY, centerZ, existingTarget, npcPos);
            if (committed != null) {
                bestCropX = committed.bx;
                bestCropY = committed.by;
                bestCropZ = committed.bz;
                bestBlockType = committed.blockType;
            } else if (npcPos.distance(existingTarget) <= APPROACH_DISTANCE) {
                data.setTargetPosition(null);
                data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                return;
            }
        }
        
        // If we found a mature crop, set AI state to HARVEST_CROPS and set target position
        if (bestBlockType == null) {
            // No mature crop found - reset to WANDER state
            if (data.getAIState() == com.hexvane.dragonlings.DragonlingAIState.HARVEST_CROPS) {
                data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                data.setTargetPosition(null);
            }
            return;
        }
        
        Vector3d approachPos =
            resolveHarvestApproachPosition(world, bestCropX, bestCropY, bestCropZ, bestBlockType);
        Vector3d targetPos = new Vector3d(approachPos);
        
        boolean inHarvestRange = isWithinHarvestRange(npcPos, world, bestCropX, bestCropY, bestCropZ, bestBlockType);
        
        // Check if we're already targeting this crop
        boolean isAlreadyTargeting = (existingTarget != null && 
            Math.abs(existingTarget.x - targetPos.x) < 0.1 && 
            Math.abs(existingTarget.y - targetPos.y) < 0.1 && 
            Math.abs(existingTarget.z - targetPos.z) < 0.1);
        
        if (!isAlreadyTargeting) {
            // Set AI state to HARVEST_CROPS and target position
            // The AI system will handle making the dragonling move towards it
            data.setAIState(com.hexvane.dragonlings.DragonlingAIState.HARVEST_CROPS);
            data.setTargetPosition(targetPos);
        }
        
        if (inHarvestRange) {
            // Check cooldown before harvesting
            Double lastHarvest = harvestCooldowns.get(npcRef);
            if (lastHarvest != null && (currentTime - lastHarvest) < HARVEST_COOLDOWN) {
                return;
            }
            
            // Use the best crop we found for harvesting
            int bx = bestCropX;
            int by = bestCropY;
            int bz = bestCropZ;
            
            // Play Blow animation (harvesting action)
            npcComponent.playAnimation(npcRef, 
                com.hypixel.hytale.protocol.AnimationSlot.Action, 
                "Blow", 
                commandBuffer);
            
            // Spawn harvest particles from the dragonling's mouth/snout (same as blue dragonling water particles)
            TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
            Vector3d mouthPos;
            Rotation3f particleRotation = null;
            
            if (npcTransform != null) {
                Vector3d npcWorldPos = npcTransform.getPosition();
                Rotation3f npcRotation = npcTransform.getRotation();
                particleRotation = npcRotation; // Store rotation for particle spawn
                
                // Use slightly below eye height for mouth position (snout level)
                double headYOffset = 0.45; // Slightly below eye height (0.8) for snout/mouth level
                double mouthForwardOffset = 0.5; // Forward offset for mouth position (in front of head)
                
                // Calculate forward direction from yaw rotation
                // Invert direction since yaw might be 180 degrees off (based on earlier rotation fixes)
                float yaw = npcRotation.yaw();
                double forwardX = -Math.sin(yaw) * mouthForwardOffset;
                double forwardZ = -Math.cos(yaw) * mouthForwardOffset;
                
                // Calculate mouth position: NPC position + eye height + forward direction
                mouthPos = new Vector3d(npcWorldPos);
                mouthPos.y += headYOffset;
                mouthPos.x += forwardX;
                mouthPos.z += forwardZ;
            } else {
                // Fallback: spawn at NPC position + snout height if transform unavailable
                mouthPos = new Vector3d(npcPos);
                mouthPos.y += 0.45; // Snout/mouth level
            }
            
            // Spawn particle effect at mouth position
            // Collect nearby players so they can see the particles
            SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource = 
                commandBuffer.getResource(EntityModule.get().getPlayerSpatialResourceType());
            List<Ref<EntityStore>> playerRefs = SpatialResource.getThreadLocalReferenceList();
            playerSpatialResource.getSpatialStructure().collect(mouthPos, 75.0, playerRefs);
            
            // The particle spawner has a narrow velocity cone (-10 to 10 degrees Yaw/Pitch)
            // Pass the NPC's rotation so particles spawn in the direction the dragonling is facing
            if (particleRotation != null) {
                com.hypixel.hytale.server.core.universe.world.ParticleUtil.spawnParticleEffect(
                    "Dragonling_Green_Harvest",
                    mouthPos,
                    particleRotation,
                    playerRefs,
                    commandBuffer
                );
            } else {
                // Fallback: spawn without rotation if unavailable
                com.hypixel.hytale.server.core.universe.world.ParticleUtil.spawnParticleEffect(
                    "Dragonling_Green_Harvest",
                    mouthPos,
                    playerRefs,
                    commandBuffer
                );
            }
            
            // Same path as HarvestCropInteraction: vanilla handles multiblock regrow, filler, and break logic.
            Vector3i targetBlock = new Vector3i(bx, by, bz);
            BlockType harvestBlockType = ChunkSectionBlockUtil.blockType(world, bx, by, bz);
            if (harvestBlockType == null) {
                harvestCooldowns.put(npcRef, currentTime);
                return;
            }
            BlockGathering harvestGathering = harvestBlockType.getGathering();
            if (harvestGathering == null || harvestGathering.getHarvest() == null) {
                ChunkSectionBlockUtil.setBlockEmpty(world, bx, by, bz);
                harvestCooldowns.put(npcRef, currentTime);
                return;
            }

            FarmingData farmingMeta = harvestBlockType.getFarming();
            String stageAfter = farmingMeta != null ? farmingMeta.getStageSetAfterHarvest() : null;
            boolean regrowsAfterHarvest =
                farmingMeta != null
                    && farmingMeta.getStages() != null
                    && stageAfter != null
                    && !stageAfter.isEmpty();
            boolean eternalCrop = harvestBlockType.getId() != null && harvestBlockType.getId().contains("Eternal");

            CombinedItemContainer npcInv = InventoryComponent.getCombined(commandBuffer, npcRef, InventoryComponent.HOTBAR_FIRST);
            Object2IntOpenHashMap<String> beforeCounts = countQuantitiesByItemId(npcInv);

            Vector3i plantRoot = ChunkSectionBlockUtil.fillerRoot(world, bx, by, bz);
            if (plantRoot == null) {
                plantRoot = new Vector3i(bx, by, bz);
            }

            int rotationIndex = ChunkSectionBlockUtil.rotationIndex(world, bx, by, bz);
            boolean harvested = FarmingUtil.harvest(
                world.getChunkStore().getStore(),
                commandBuffer,
                npcRef,
                harvestBlockType,
                rotationIndex,
                targetBlock);

            if (harvested) {
                Object2IntOpenHashMap<String> afterCounts = countQuantitiesByItemId(npcInv);
                moveNpcHarvestDeltaToChest(
                    npcInv,
                    chestInventory,
                    beforeCounts,
                    afterCounts,
                    eternalCrop,
                    regrowsAfterHarvest,
                    commandBuffer,
                    bx,
                    by,
                    bz);
                tryReplantFromChest(
                    world,
                    chestInventory,
                    harvestBlockType,
                    plantRoot,
                    eternalCrop,
                    regrowsAfterHarvest);
            } else if (ChunkSectionBlockUtil.blockId(world, bx, by, bz) != 0) {
                // Failed harvest (e.g. regrow preconditions); avoid double-break when vanilla already cleared the cell
                ChunkSectionBlockUtil.setBlockEmpty(world, bx, by, bz);
            }
            
            // Update cooldown and return - harvest one crop per cooldown period
            harvestCooldowns.put(npcRef, currentTime);
            return;
        }
    }
    
    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }

    /** Eternal seed bags used for regrow are not deposited (conceptually replanted). */
    private static boolean isEternalSeedItem(@Nonnull ItemStack stack) {
        String id = stack.getItemId();
        if (id == null || !id.contains("Eternal")) {
            return false;
        }
        return id.contains("Seed");
    }

    @Nonnull
    private static Object2IntOpenHashMap<String> countQuantitiesByItemId(@Nonnull CombinedItemContainer inv) {
        Object2IntOpenHashMap<String> counts = new Object2IntOpenHashMap<>();
        for (short slot = 0; slot < inv.getCapacity(); slot++) {
            ItemStack s = inv.getItemStack(slot);
            if (s != null && !s.isEmpty()) {
                counts.addTo(s.getItemId(), s.getQuantity());
            }
        }
        return counts;
    }

    /**
     * {@link FarmingUtil#harvest} gives drops to the NPC via {@code ItemUtils.interactivelyPickupItem}; pull the new stacks
     * off the dragonling and into the linked chest (skipping eternal seeds when regrowing).
     */
    private static void moveNpcHarvestDeltaToChest(
            @Nonnull CombinedItemContainer npcInv,
            @Nonnull ItemContainer chestInventory,
            @Nonnull Object2IntOpenHashMap<String> beforeCounts,
            @Nonnull Object2IntOpenHashMap<String> afterCounts,
            boolean eternalCrop,
            boolean regrowsAfterHarvest,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            int bx,
            int by,
            int bz) {
        for (String itemId : afterCounts.keySet()) {
            int delta = afterCounts.getInt(itemId) - beforeCounts.getOrDefault(itemId, 0);
            if (delta <= 0) {
                continue;
            }
            ItemStack pullRequest = new ItemStack(itemId, delta);
            ItemStackTransaction removed = npcInv.removeItemStack(pullRequest, false, true);
            if (!removed.succeeded()) {
                continue;
            }
            int taken = delta;
            if (removed.getRemainder() != null && !removed.getRemainder().isEmpty()) {
                taken = delta - removed.getRemainder().getQuantity();
            }
            if (taken <= 0) {
                continue;
            }
            ItemStack recovered = new ItemStack(itemId, taken);
            if (eternalCrop && regrowsAfterHarvest && isEternalSeedItem(recovered)) {
                continue;
            }
            ItemStackTransaction addTx = chestInventory.addItemStack(recovered);
            ItemStack remainder = addTx.getRemainder();
            if (remainder != null && !remainder.isEmpty()) {
                com.hypixel.hytale.component.Holder<EntityStore>[] itemDrops =
                    com.hypixel.hytale.server.core.modules.entity.item.ItemComponent.generateItemDrops(
                        commandBuffer,
                        java.util.Collections.singletonList(remainder),
                        new Vector3d(bx + 0.5, by + 0.5, bz + 0.5),
                        Rotation3f.ZERO);
                for (com.hypixel.hytale.component.Holder<EntityStore> itemHolder : itemDrops) {
                    if (itemHolder != null) {
                        commandBuffer.addEntity(itemHolder, com.hypixel.hytale.component.AddReason.SPAWN);
                    }
                }
            }
        }
    }

    /**
     * Tamework {@code StoreHome} may hit the chest hitbox slightly off the block origin; search a small neighborhood.
     */
    @Nullable
    private static ItemContainerBlock findItemContainerNearHome(@Nonnull World world, @Nonnull Vector3d home) {
        int cx = (int) Math.floor(home.x);
        int cy = (int) Math.floor(home.y);
        int cz = (int) Math.floor(home.z);
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    ItemContainerBlock b = findItemContainerBlockAt(world, cx + dx, cy + dy, cz + dz);
                    if (b != null) {
                        return b;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Resolves filler / multi-block root to the block entity ref (same pattern as {@link #findItemContainerBlockAt}).
     */
    @Nullable
    private static Ref<ChunkStore> resolveBlockEntityRefForFarming(@Nonnull World world, int x, int y, int z) {
        return ChunkSectionBlockUtil.blockEntityRefAtFillerRoot(world, x, y, z);
    }

    /**
     * Fully-grown crops often use a block type whose id contains {@code StageFinal} (including eternal crops); the
     * chunk may have no {@link FarmingBlock} then — growth state is implicit in the block type. Intermediate stages
     * keep a block entity with {@link FarmingBlock} and {@code growthProgress} (see {@link FarmingUtil#tickFarming}).
     */
    private static boolean isFinalCropStageBlockType(@Nonnull BlockType blockType) {
        String id = blockType.getId();
        return id != null && id.contains("StageFinal");
    }

    /**
     * Wall apple fruit on trees (seek ground beneath the column; soil apples use {@code Plant_Crop_Apple_Block} without
     * {@code Wall}).
     */
    private static boolean isAppleTreeWallCrop(@Nonnull BlockType blockType) {
        String id = blockType.getId();
        return id != null && id.contains("Crop_Apple") && id.contains("Wall");
    }

    private static final double APPROACH_TARGET_MATCH = 0.85;

    /**
     * First standable surface in column (bx, bz) below {@code appleY}: non-air block with air above, so the NPC can stand
     * on top. Falls back to first solid if no air gap is found.
     */
    @Nullable
    private static Vector3d findGroundStandBelowAppleColumn(@Nonnull World world, int bx, int appleY, int bz) {
        if (!ChunkSectionBlockUtil.isChunkInMemory(world, bx, bz)) {
            return null;
        }
        for (int y = appleY - 1; y >= ChunkUtil.MIN_Y; y--) {
            int blockId = ChunkSectionBlockUtil.blockId(world, bx, y, bz);
            if (blockId == 0) {
                continue;
            }
            int above = y < ChunkUtil.HEIGHT_MINUS_1 ? ChunkSectionBlockUtil.blockId(world, bx, y + 1, bz) : 0;
            if (above == 0) {
                return new Vector3d(bx + 0.5, y + 1.0, bz + 0.5);
            }
        }
        for (int y = appleY - 1; y >= ChunkUtil.MIN_Y; y--) {
            if (ChunkSectionBlockUtil.blockId(world, bx, y, bz) != 0) {
                return new Vector3d(bx + 0.5, y + 1.0, bz + 0.5);
            }
        }
        return null;
    }

    @Nonnull
    private static Vector3d resolveHarvestApproachPosition(
            @Nonnull World world, int bx, int by, int bz, @Nonnull BlockType blockType) {
        if (isAppleTreeWallCrop(blockType)) {
            Vector3d ground = findGroundStandBelowAppleColumn(world, bx, by, bz);
            if (ground != null) {
                return ground;
            }
        }
        return new Vector3d(bx + 0.5, by + 0.5, bz + 0.5);
    }

    private static boolean isWithinHarvestRange(
            @Nonnull Vector3d npcPos,
            @Nonnull World world,
            int bx,
            int by,
            int bz,
            @Nonnull BlockType blockType) {
        Vector3d approach = resolveHarvestApproachPosition(world, bx, by, bz, blockType);
        return npcPos.distance(approach) <= APPROACH_DISTANCE;
    }

    @Nullable
    private static HarvestCropMatch findCropMatchingApproachTarget(
            @Nonnull World world,
            int centerX,
            int centerY,
            int centerZ,
            @Nonnull Vector3d existingTarget,
            @Nonnull Vector3d npcPos) {
        int radius = (int) Math.ceil(HARVEST_RADIUS);
        double radiusSq = HARVEST_RADIUS * HARVEST_RADIUS;
        double bestDist = Double.MAX_VALUE;
        HarvestCropMatch best = null;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double horizontalDistSq = dx * dx + dz * dz;
                if (horizontalDistSq > radiusSq) {
                    continue;
                }
                int bx = centerX + dx;
                int bz = centerZ + dz;
                if (!ChunkSectionBlockUtil.isChunkInMemory(world, bx, bz)) {
                    continue;
                }
                for (int dy = -radius; dy <= radius; dy++) {
                    int by = centerY + dy;
                    double distanceSq = horizontalDistSq + dy * dy;
                    if (distanceSq > radiusSq) {
                        continue;
                    }
                    int blockId = ChunkSectionBlockUtil.blockId(world, bx, by, bz);
                    if (blockId == 0) {
                        continue;
                    }
                    BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
                    if (blockType == null) {
                        continue;
                    }
                    if (!isMatureHarvestableCrop(world, bx, by, bz, blockType)) {
                        continue;
                    }
                    Vector3d approach = resolveHarvestApproachPosition(world, bx, by, bz, blockType);
                    if (approach.distance(existingTarget) > APPROACH_TARGET_MATCH) {
                        continue;
                    }
                    double d = npcPos.distance(approach);
                    if (d < bestDist) {
                        bestDist = d;
                        best = new HarvestCropMatch(bx, by, bz, blockType);
                    }
                }
            }
        }
        return best;
    }

    private static final class HarvestCropMatch {
        final int bx;
        final int by;
        final int bz;
        @Nonnull
        final BlockType blockType;

        HarvestCropMatch(int bx, int by, int bz, @Nonnull BlockType blockType) {
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            this.blockType = blockType;
        }
    }

    /**
     * Harvestable blocks with multi-stage growth: either the final-stage block type ({@link #isFinalCropStageBlockType})
     * or {@link FarmingBlock} on the block entity with growth past the last stage (same idea as {@link FarmingUtil}).
     */
    private static boolean isMatureHarvestableCrop(
            @Nonnull World world,
            int bx,
            int by,
            int bz,
            @Nonnull BlockType blockType) {
        BlockGathering gathering = blockType.getGathering();
        if (gathering == null || !gathering.isHarvestable()) {
            return false;
        }
        FarmingData farmingData = blockType.getFarming();
        if (farmingData == null || farmingData.getStages() == null) {
            return true;
        }
        if (isFinalCropStageBlockType(blockType)) {
            return true;
        }
        Ref<ChunkStore> blockRef = ChunkSectionBlockUtil.blockEntityRefAt(world, bx, by, bz);
        if (blockRef == null) {
            blockRef = resolveBlockEntityRefForFarming(world, bx, by, bz);
        }
        if (blockRef == null) {
            return false;
        }
        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
        FarmingBlock farmingBlock = chunkStore.getComponent(blockRef, FarmingBlock.getComponentType());
        if (farmingBlock == null) {
            return false;
        }
        float growthProgress = farmingBlock.getGrowthProgress();
        String currentStageSet = farmingBlock.getCurrentStageSet();
        java.util.Map<String, FarmingStageData[]> stageSets = farmingData.getStages();
        FarmingStageData[] stages = currentStageSet != null ? stageSets.get(currentStageSet) : null;
        if (stages == null) {
            currentStageSet = farmingData.getStartingStageSet();
            stages = currentStageSet != null ? stageSets.get(currentStageSet) : null;
        }
        if (stages == null || stages.length == 0) {
            return true;
        }
        return isFarmingGrowthMatureForHarvest(growthProgress, stages.length);
    }

    /**
     * Mirrors {@code FarmingUtil.tickFarming}: {@code growthProgress} is {@code stageIndex + fractionWithinStage}
     * ({@code (int) currentProgress} is the stage index; fractional part is progress within that stage — see growth tick
     * loop in Hytale {@code FarmingUtil}). Mature when the final stage has finished growing: either {@code growthProgress}
     * reaches {@code stages.length} or the last stage index has fractional part near 1.0.
     */
    private static boolean isFarmingGrowthMatureForHarvest(float growthProgress, int stageCount) {
        if (stageCount <= 0) {
            return true;
        }
        int lastStageIndex = stageCount - 1;
        int stage = (int) growthProgress;
        float frac = growthProgress - stage;
        if (stage >= stageCount) {
            return true;
        }
        if (stage < lastStageIndex) {
            return false;
        }
        return frac >= 0.99f || growthProgress >= (float) stageCount - 1e-3f;
    }

    /** Exposes maturity check for {@link DragonlingsDebugDump}. */
    public static boolean isMatureHarvestableCropForDebug(
            @Nonnull World world, int bx, int by, int bz, @Nonnull BlockType blockType) {
        return isMatureHarvestableCrop(world, bx, by, bz, blockType);
    }

    @SuppressWarnings("SameParameterValue")
    public static boolean isFarmingGrowthMatureForDebug(float growthProgress, int stageCount) {
        return isFarmingGrowthMatureForHarvest(growthProgress, stageCount);
    }

    @Nullable
    public static Ref<ChunkStore> resolveFarmingBlockEntityRefPublic(
            @Nonnull World world, int x, int y, int z) {
        return resolveBlockEntityRefForFarming(world, x, y, z);
    }

    /**
     * After a non-eternal harvest, consume one matching regular seed from the chest and plant it on the same plot.
     */
    private static void tryReplantFromChest(
            @Nonnull World world,
            @Nonnull ItemContainer chestInventory,
            @Nonnull BlockType harvestedBlockType,
            @Nonnull Vector3i plantRoot,
            boolean eternalCrop,
            boolean regrowsAfterHarvest) {
        if (eternalCrop || regrowsAfterHarvest || isAppleTreeWallCrop(harvestedBlockType)) {
            return;
        }
        CropSeedBinding binding = resolveNonEternalSeed(harvestedBlockType);
        if (binding == null) {
            return;
        }
        int px = plantRoot.x;
        int py = plantRoot.y;
        int pz = plantRoot.z;
        if (ChunkSectionBlockUtil.blockId(world, px, py, pz) != BlockType.EMPTY_ID) {
            return;
        }
        if (!isVanillaSeedPlantableSoil(world, px, py, pz)) {
            return;
        }
        int cropBlockId = BlockType.getAssetMap().getIndex(binding.blockTypeToPlace);
        if (cropBlockId == AssetMapWithIndexes.NOT_FOUND) {
            return;
        }
        BlockType cropBlockType = BlockType.getAssetMap().getAsset(cropBlockId);
        if (cropBlockType == null) {
            return;
        }
        ItemStack seed = new ItemStack(binding.seedItemId, 1);
        if (!chestInventory.canRemoveItemStack(seed)) {
            return;
        }
        ItemStackTransaction removed = chestInventory.removeItemStack(seed, true, true);
        if (!removed.succeeded()) {
            return;
        }
        boolean placed = ChunkSectionBlockUtil.setBlock(world, px, py, pz, cropBlockId, cropBlockType);
        if (!placed) {
            chestInventory.addItemStack(new ItemStack(binding.seedItemId, 1));
        }
    }

    @Nullable
    private static CropSeedBinding resolveNonEternalSeed(@Nonnull BlockType harvestedBlockType) {
        Map<String, CropSeedBinding> map = seedMap();
        String harvestedId = harvestedBlockType.getId();
        if (harvestedId != null) {
            CropSeedBinding binding = map.get(harvestedId);
            if (binding != null) {
                return binding;
            }
        }
        String defaultKey = harvestedBlockType.getDefaultStateKey();
        if (defaultKey != null && !defaultKey.equals(harvestedId)) {
            return map.get(defaultKey);
        }
        return null;
    }

    @Nonnull
    private static Map<String, CropSeedBinding> seedMap() {
        Map<String, CropSeedBinding> map = cropBlockToSeed;
        if (map == null) {
            synchronized (GreenDragonlingHarvestBehavior.class) {
                map = cropBlockToSeed;
                if (map == null) {
                    map = buildSeedMap();
                    cropBlockToSeed = map;
                }
            }
        }
        return map;
    }

    @Nonnull
    private static Map<String, CropSeedBinding> buildSeedMap() {
        Map<String, CropSeedBinding> map = new HashMap<>();
        for (Item item : Item.getAssetMap().getAssetMap().values()) {
            if (item == null) {
                continue;
            }
            String itemId = item.getId();
            if (itemId == null || !itemId.contains("Seed") || itemId.contains("Eternal")) {
                continue;
            }
            String placedBlockKey = placeBlockTypeFromSeedItem(item);
            if (placedBlockKey == null || placedBlockKey.contains("Eternal")) {
                continue;
            }
            indexCropBlockKeys(map, placedBlockKey, new CropSeedBinding(itemId, placedBlockKey));
        }
        return Collections.unmodifiableMap(map);
    }

    private static void indexCropBlockKeys(
            @Nonnull Map<String, CropSeedBinding> map,
            @Nonnull String placedBlockKey,
            @Nonnull CropSeedBinding binding) {
        map.putIfAbsent(placedBlockKey, binding);
        BlockType placed = BlockType.getAssetMap().getAsset(placedBlockKey);
        if (placed == null) {
            return;
        }
        String defaultKey = placed.getDefaultStateKey();
        if (defaultKey != null) {
            map.putIfAbsent(defaultKey, binding);
        }
        StateData state = placed.getState();
        if (state == null || state.getStateNames() == null) {
            return;
        }
        for (String stateName : state.getStateNames()) {
            String stateKey = placed.getBlockKeyForState(stateName);
            if (stateKey != null) {
                map.putIfAbsent(stateKey, binding);
            }
        }
    }

    @Nullable
    private static String placeBlockTypeFromSeedItem(@Nonnull Item item) {
        Map<String, String> vars = item.getInteractionVars();
        if (vars == null) {
            return null;
        }
        String seedRootId = vars.get("SeedId");
        if (seedRootId == null) {
            return null;
        }
        RootInteraction seedRoot = RootInteraction.getAssetMap().getAsset(seedRootId);
        if (seedRoot == null) {
            return null;
        }
        String[] interactionIds = seedRoot.getInteractionIds();
        if (interactionIds == null) {
            return null;
        }
        for (String interactionId : interactionIds) {
            if (interactionId == null) {
                continue;
            }
            Interaction interaction = Interaction.getAssetMap().getAsset(interactionId);
            if (interaction instanceof PlaceBlockInteraction place) {
                String blockTypeKey = readPlaceBlockTypeKey(place);
                if (blockTypeKey != null && !blockTypeKey.isEmpty()) {
                    return blockTypeKey;
                }
            }
        }
        return null;
    }

    @Nullable
    private static Field resolvePlaceBlockTypeKeyField() {
        try {
            Field field = PlaceBlockInteraction.class.getDeclaredField("blockTypeKey");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | SecurityException ignored) {
            return null;
        }
    }

    @Nullable
    private static String readPlaceBlockTypeKey(@Nonnull PlaceBlockInteraction place) {
        if (PLACE_BLOCK_TYPE_KEY_FIELD == null) {
            return null;
        }
        try {
            Object value = PLACE_BLOCK_TYPE_KEY_FIELD.get(place);
            return value instanceof String key ? key : null;
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    /**
     * Matches vanilla {@code Seed_Condition}: tilled soil or a planter block below the plot.
     */
    private static boolean isVanillaSeedPlantableSoil(@Nonnull World world, int plantX, int plantY, int plantZ) {
        BlockType below = ChunkSectionBlockUtil.blockType(world, plantX, plantY - 1, plantZ);
        if (below == null) {
            return false;
        }
        String belowId = below.getId();
        if (belowId != null && belowId.contains(TILLED_SOIL_BLOCK_ID)) {
            return true;
        }
        String defaultKey = below.getDefaultStateKey();
        if (TILLED_SOIL_BLOCK_ID.equals(defaultKey)) {
            return true;
        }
        int planterTag = AssetRegistry.getTagIndex(PLANTER_BLOCK_TAG);
        if (planterTag == AssetRegistry.TAG_NOT_FOUND) {
            return false;
        }
        int belowBlockId = ChunkSectionBlockUtil.blockId(world, plantX, plantY - 1, plantZ);
        return BlockType.getAssetMap().getIndexesForTag(planterTag).contains(belowBlockId);
    }

    private static final class CropSeedBinding {
        @Nonnull
        final String seedItemId;
        @Nonnull
        final String blockTypeToPlace;

        CropSeedBinding(@Nonnull String seedItemId, @Nonnull String blockTypeToPlace) {
            this.seedItemId = seedItemId;
            this.blockTypeToPlace = blockTypeToPlace;
        }
    }

    /**
     * Resolves multi-block roots via filler (same as stash / container commands) then reads {@link ItemContainerBlock}.
     */
    @Nullable
    private static ItemContainerBlock findItemContainerBlockAt(@Nonnull World world, int x, int y, int z) {
        Ref<ChunkStore> blockEntityRef = ChunkSectionBlockUtil.blockEntityRefAtFillerRoot(world, x, y, z);
        if (blockEntityRef == null) {
            return null;
        }
        return world.getChunkStore().getStore().getComponent(blockEntityRef, ItemContainerBlock.getComponentType());
    }
}
