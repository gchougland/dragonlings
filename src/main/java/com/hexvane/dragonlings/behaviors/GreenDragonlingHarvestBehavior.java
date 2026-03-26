package com.hexvane.dragonlings.behaviors;

import com.hypixel.hytale.builtin.adventure.farming.FarmingUtil;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hexvane.dragonlings.DragonlingData;
import com.hexvane.dragonlings.DragonlingTamework;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Behavior system for Green dragonlings - harvests crops and deposits them in chest.
 */
public class GreenDragonlingHarvestBehavior extends EntityTickingSystem<EntityStore> {
    public static final double HARVEST_RADIUS = 11.0;
    private static final double HARVEST_COOLDOWN = 2.0; // Seconds between crop harvests
    private static final double APPROACH_DISTANCE = 3.0; // Distance to trigger harvesting
    
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
        
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(centerX, centerZ));
        if (chunk == null) {
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
        com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk bestChunk = null;
        
        // Scan area for crops in a true 3D sphere (not a cylinder or single Y): horizontal + dy filtered by radiusSq.
        // Note: In Hytale, getBlock(x, y, z) where y is vertical and z is north/south
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
                
                // Get the chunk for this block position (might be different from chest chunk)
                long blockChunkIndex = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(bx, bz);
                WorldChunk blockChunk = world.getChunkIfInMemory(blockChunkIndex);
                if (blockChunk == null) {
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
                    
                    // Use getBlock() - parameters are (x, y, z) where y is vertical, z is north/south
                    int blockId = blockChunk.getBlock(bx, by, bz);
                    if (blockId == 0) {
                        continue; // Air or invalid block
                    }
                    
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType blockType = 
                        com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset(blockId);
                    if (blockType == null) {
                        continue;
                    }

                    // Check if it's a harvestable, mature crop (uses WorldChunk block entity — same as watering can)
                    if (!isMatureHarvestableCrop(world, bx, by, bz, blockType)) {
                        continue;
                    }
                    
                    Vector3d approachPos = resolveHarvestApproachPosition(world, bx, by, bz, blockType);
                    double distance = npcPos.distanceTo(approachPos);
                    
                    // Track the nearest mature crop
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestCropX = bx;
                        bestCropY = by;
                        bestCropZ = bz;
                        bestBlockType = blockType;
                        bestChunk = blockChunk;
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
                bestChunk = committed.chunk;
            } else if (npcPos.distanceTo(existingTarget) <= APPROACH_DISTANCE) {
                data.setTargetPosition(null);
                data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                return;
            }
        }
        
        // If we found a mature crop, set AI state to HARVEST_CROPS and set target position
        if (bestBlockType == null || bestChunk == null) {
            // No mature crop found - reset to WANDER state
            if (data.getAIState() == com.hexvane.dragonlings.DragonlingAIState.HARVEST_CROPS) {
                data.setAIState(com.hexvane.dragonlings.DragonlingAIState.WANDER);
                data.setTargetPosition(null);
            }
            return;
        }
        
        Vector3d approachPos =
            resolveHarvestApproachPosition(world, bestCropX, bestCropY, bestCropZ, bestBlockType);
        Vector3d targetPos = approachPos.clone();
        
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
            WorldChunk blockChunk = bestChunk;
            
            // Play Blow animation (harvesting action)
            npcComponent.playAnimation(npcRef, 
                com.hypixel.hytale.protocol.AnimationSlot.Action, 
                "Blow", 
                commandBuffer);
            
            // Spawn harvest particles from the dragonling's mouth/snout (same as blue dragonling water particles)
            TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
            Vector3d mouthPos;
            Vector3f particleRotation = null;
            
            if (npcTransform != null) {
                Vector3d npcWorldPos = npcTransform.getPosition();
                Vector3f npcRotation = npcTransform.getRotation();
                particleRotation = npcRotation; // Store rotation for particle spawn
                
                // Use slightly below eye height for mouth position (snout level)
                double headYOffset = 0.45; // Slightly below eye height (0.8) for snout/mouth level
                double mouthForwardOffset = 0.5; // Forward offset for mouth position (in front of head)
                
                // Calculate forward direction from yaw rotation
                // Invert direction since yaw might be 180 degrees off (based on earlier rotation fixes)
                float yaw = npcRotation.getYaw();
                double forwardX = -Math.sin(yaw) * mouthForwardOffset;
                double forwardZ = -Math.cos(yaw) * mouthForwardOffset;
                
                // Calculate mouth position: NPC position + eye height + forward direction
                mouthPos = npcWorldPos.clone();
                mouthPos.y += headYOffset;
                mouthPos.x += forwardX;
                mouthPos.z += forwardZ;
            } else {
                // Fallback: spawn at NPC position + snout height if transform unavailable
                mouthPos = npcPos.clone();
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
            ChunkStore chunkStoreApi = world.getChunkStore();
            Ref<ChunkStore> columnRef = chunkStoreApi.getChunkReference(ChunkUtil.indexChunkFromBlock(bx, bz));
            if (columnRef == null || !columnRef.isValid()) {
                harvestCooldowns.put(npcRef, currentTime);
                return;
            }
            Store<ChunkStore> columnStore = chunkStoreApi.getStore();
            BlockChunk harvestBlockChunk = columnStore.getComponent(columnRef, BlockChunk.getComponentType());
            if (harvestBlockChunk == null) {
                harvestCooldowns.put(npcRef, currentTime);
                return;
            }
            BlockSection harvestSection = harvestBlockChunk.getSectionAtBlockY(by);
            if (harvestSection == null) {
                harvestCooldowns.put(npcRef, currentTime);
                return;
            }
            WorldChunk harvestWorldChunk = columnStore.getComponent(columnRef, WorldChunk.getComponentType());
            if (harvestWorldChunk == null) {
                harvestCooldowns.put(npcRef, currentTime);
                return;
            }
            BlockType harvestBlockType = harvestWorldChunk.getBlockType(targetBlock);
            if (harvestBlockType == null) {
                harvestCooldowns.put(npcRef, currentTime);
                return;
            }
            BlockGathering harvestGathering = harvestBlockType.getGathering();
            if (harvestGathering == null || harvestGathering.getHarvest() == null) {
                blockChunk.breakBlock(bx, by, bz, 0);
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

            int rotationIndex = harvestSection.getRotationIndex(bx, by, bz);
            boolean harvested = FarmingUtil.harvest(world, commandBuffer, npcRef, harvestBlockType, rotationIndex, targetBlock);

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
            } else if (blockChunk.getBlock(bx, by, bz) != 0) {
                // Failed harvest (e.g. regrow preconditions); avoid double-break when vanilla already cleared the cell
                blockChunk.breakBlock(bx, by, bz, 0);
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
                        Vector3f.ZERO);
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
        ChunkStore chunkStoreApi = world.getChunkStore();
        Ref<ChunkStore> chunkRef = chunkStoreApi.getChunkReference(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunkRef == null || !chunkRef.isValid()) {
            return null;
        }
        Store<ChunkStore> chunkStore = chunkStoreApi.getStore();
        BlockChunk blockChunk = chunkStore.getComponent(chunkRef, BlockChunk.getComponentType());
        BlockComponentChunk blockComponentChunk = chunkStore.getComponent(chunkRef, BlockComponentChunk.getComponentType());
        if (blockChunk == null || blockComponentChunk == null) {
            return null;
        }
        Vector3i block = new Vector3i(x, y, z);
        BlockSection section = blockChunk.getSectionAtBlockY(block.y);
        if (section == null) {
            return null;
        }
        int filler = section.getFiller(block.x, block.y, block.z);
        if (filler != 0) {
            block.x = block.x - FillerBlockUtil.unpackX(filler);
            block.y = block.y - FillerBlockUtil.unpackY(filler);
            block.z = block.z - FillerBlockUtil.unpackZ(filler);
        }
        return blockComponentChunk.getEntityReference(ChunkUtil.indexBlockInColumn(block.x, block.y, block.z));
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
        long chunkIndex = ChunkUtil.indexChunkFromBlock(bx, bz);
        WorldChunk wc = world.getChunkIfInMemory(chunkIndex);
        if (wc == null) {
            return null;
        }
        for (int y = appleY - 1; y >= 0; y--) {
            int blockId = wc.getBlock(bx, y, bz);
            if (blockId == 0) {
                continue;
            }
            int above = y < 319 ? wc.getBlock(bx, y + 1, bz) : 0;
            if (above == 0) {
                return new Vector3d(bx + 0.5, y + 1.0, bz + 0.5);
            }
        }
        for (int y = appleY - 1; y >= 0; y--) {
            if (wc.getBlock(bx, y, bz) != 0) {
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
        return npcPos.distanceTo(approach) <= APPROACH_DISTANCE;
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
                WorldChunk blockChunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(bx, bz));
                if (blockChunk == null) {
                    continue;
                }
                for (int dy = -radius; dy <= radius; dy++) {
                    int by = centerY + dy;
                    double distanceSq = horizontalDistSq + dy * dy;
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
                    if (!isMatureHarvestableCrop(world, bx, by, bz, blockType)) {
                        continue;
                    }
                    Vector3d approach = resolveHarvestApproachPosition(world, bx, by, bz, blockType);
                    if (approach.distanceTo(existingTarget) > APPROACH_TARGET_MATCH) {
                        continue;
                    }
                    double d = npcPos.distanceTo(approach);
                    if (d < bestDist) {
                        bestDist = d;
                        best = new HarvestCropMatch(bx, by, bz, blockType, blockChunk);
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
        @Nonnull
        final WorldChunk chunk;

        HarvestCropMatch(int bx, int by, int bz, @Nonnull BlockType blockType, @Nonnull WorldChunk chunk) {
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            this.blockType = blockType;
            this.chunk = chunk;
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
        WorldChunk wc =
            world.getChunkIfInMemory(com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(bx, bz));
        Ref<ChunkStore> blockRef =
            wc != null ? wc.getBlockComponentEntity(bx, by, bz) : null;
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
     * Resolves multi-block roots via filler (same as stash / container commands) then reads {@link ItemContainerBlock}.
     */
    @Nullable
    private static ItemContainerBlock findItemContainerBlockAt(@Nonnull World world, int x, int y, int z) {
        ChunkStore chunkStoreApi = world.getChunkStore();
        Ref<ChunkStore> chunkRef = chunkStoreApi.getChunkReference(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunkRef == null || !chunkRef.isValid()) {
            return null;
        }
        Store<ChunkStore> chunkStore = chunkStoreApi.getStore();
        BlockChunk blockChunk = chunkStore.getComponent(chunkRef, BlockChunk.getComponentType());
        BlockComponentChunk blockComponentChunk = chunkStore.getComponent(chunkRef, BlockComponentChunk.getComponentType());
        if (blockChunk == null || blockComponentChunk == null) {
            return null;
        }
        Vector3i block = new Vector3i(x, y, z);
        BlockSection section = blockChunk.getSectionAtBlockY(block.y);
        int filler = section.getFiller(block.x, block.y, block.z);
        if (filler != 0) {
            block.x = block.x - FillerBlockUtil.unpackX(filler);
            block.y = block.y - FillerBlockUtil.unpackY(filler);
            block.z = block.z - FillerBlockUtil.unpackZ(filler);
        }
        Ref<ChunkStore> blockEntityRef = blockComponentChunk.getEntityReference(ChunkUtil.indexBlockInColumn(block.x, block.y, block.z));
        if (blockEntityRef == null) {
            return null;
        }
        return chunkStore.getComponent(blockEntityRef, ItemContainerBlock.getComponentType());
    }
}
