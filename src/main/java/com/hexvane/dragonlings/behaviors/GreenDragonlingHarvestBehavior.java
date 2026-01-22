package com.hexvane.dragonlings.behaviors;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState; // Still needed for ItemContainerState access
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hexvane.dragonlings.DragonlingData;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Behavior system for Green dragonlings - harvests crops and deposits them in chest.
 */
public class GreenDragonlingHarvestBehavior extends EntityTickingSystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final double HARVEST_RADIUS = 8.0;
    private static final double HARVEST_COOLDOWN = 2.0; // Seconds between crop harvests
    
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
        DragonlingData data = archetypeChunk.getComponent(index, this.dragonlingDataType);
        
        if (npcComponent == null || data == null) {
            return;
        }
        
        // Only process Green dragonlings that are leashed to a chest
        if (!npcComponent.getRoleName().contains("Green") || !data.isLeashed()) {
            return;
        }
        
        String leashBlockType = data.getLeashBlockType();
        if (leashBlockType == null || !leashBlockType.contains("Chest")) {
            return; // Not leashed to a chest
        }
        
        Vector3d leashPos = data.getLeashPosition();
        if (leashPos == null) {
            return;
        }
        
        TransformComponent transform = store.getComponent(archetypeChunk.getReferenceTo(index), TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        
        World world = npcComponent.getWorld();
        if (world == null) {
            return;
        }
        
        Ref<EntityStore> npcRef = archetypeChunk.getReferenceTo(index);
        double currentTime = System.currentTimeMillis() / 1000.0;
        
        // Check cooldown at the start - if still on cooldown, don't harvest anything this tick
        Double lastHarvest = harvestCooldowns.get(npcRef);
        if (lastHarvest != null && (currentTime - lastHarvest) < HARVEST_COOLDOWN) {
            return; // Still on cooldown, wait for next tick
        }
        
        int centerX = (int) Math.floor(leashPos.x);
        int centerY = (int) Math.floor(leashPos.y); // Y is vertical
        int centerZ = (int) Math.floor(leashPos.z); // Z is north/south
        
        // Get chest block state
        // Note: Using deprecated BlockState.getState() because we need ItemContainerState to access chest inventory
        // This is the only way to get ItemContainerState from a block position
        WorldChunk chunk = world.getChunkIfInMemory(com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(centerX, centerZ));
        if (chunk == null) {
            return;
        }
        
        @SuppressWarnings("removal") // BlockState is deprecated but needed for ItemContainerState access
        BlockState chestState = chunk.getState(centerX, centerY, centerZ);
        if (chestState == null || !(chestState instanceof com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState)) {
            return;
        }
        
        com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState chestContainerState = 
            (com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState) chestState;
        com.hypixel.hytale.server.core.inventory.container.ItemContainer chestInventory = chestContainerState.getItemContainer();
        
        // Scan area for crops in a sphere - harvest the first one we find
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
                    
                    // Check if it's a harvestable crop
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering gathering = blockType.getGathering();
                    if (gathering == null || !gathering.isHarvestable()) {
                        continue;
                    }
                    
                    // Check if crop is mature (for farming crops, check if at final stage)
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData farmingData = blockType.getFarming();
                    if (farmingData != null && farmingData.getStages() != null) {
                        // This is a farming crop - check if it's at the final/mature stage
                        // We need to check the FarmingBlock component to see the current stage
                        com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> chunkStore = world.getChunkStore().getStore();
                        long chunkIndex = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(bx, bz);
                        com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> chunkRef = 
                            world.getChunkStore().getChunkReference(chunkIndex);
                        if (chunkRef != null) {
                            com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk blockComponentChunk = 
                                chunkStore.getComponent(chunkRef, com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk.getComponentType());
                            if (blockComponentChunk != null) {
                                int blockIndexColumn = com.hypixel.hytale.math.util.ChunkUtil.indexBlockInColumn(bx, by, bz);
                                com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> blockRef = 
                                    blockComponentChunk.getEntityReference(blockIndexColumn);
                                if (blockRef != null) {
                                    com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock farmingBlock = 
                                        chunkStore.getComponent(blockRef, com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock.getComponentType());
                                    if (farmingBlock != null) {
                                        String currentStageSet = farmingBlock.getCurrentStageSet();
                                        if (currentStageSet == null) {
                                            currentStageSet = farmingData.getStartingStageSet();
                                        }
                                        java.util.Map<String, com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData[]> stageSets = 
                                            farmingData.getStages();
                                        com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData[] stages = 
                                            stageSets.get(currentStageSet);
                                        if (stages != null) {
                                            float growthProgress = farmingBlock.getGrowthProgress();
                                            int currentStage = (int) growthProgress;
                                            // Crop is mature if it's at the final stage
                                            if (currentStage < stages.length - 1) {
                                                // Not mature yet, skip this crop
                                                continue;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Play Blow animation (harvesting action)
                    npcComponent.playAnimation(npcRef, 
                        com.hypixel.hytale.protocol.AnimationSlot.Action, 
                        "Blow", 
                        commandBuffer);
                    
                    // Get drops using the proper drop system (handles drop lists, quantities, etc.)
                    com.hypixel.hytale.server.core.asset.type.blocktype.config.HarvestingDropType harvestDrop = gathering.getHarvest();
                    if (harvestDrop == null) {
                        // No harvest drop configured, just break the block
                        blockChunk.breakBlock(bx, by, bz, 0);
                        harvestCooldowns.put(npcRef, currentTime);
                        return; // Harvest one crop per cooldown period
                    }
                    
                    String itemId = harvestDrop.getItemId();
                    String dropListId = harvestDrop.getDropListId();
                    
                    // Use BlockHarvestUtils.getDrops to get actual drops (handles drop lists, quantities, etc.)
                    java.util.List<com.hypixel.hytale.server.core.inventory.ItemStack> drops = 
                        com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils.getDrops(
                            blockType, 1, itemId, dropListId);
                    
                    // Deposit all drops into chest BEFORE harvesting (so we have them)
                    for (com.hypixel.hytale.server.core.inventory.ItemStack dropStack : drops) {
                        if (dropStack == null || dropStack.isEmpty()) {
                            continue;
                        }
                        
                        // Try to add to chest, drop remainder if chest is full
                        com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction transaction = 
                            chestInventory.addItemStack(dropStack);
                        com.hypixel.hytale.server.core.inventory.ItemStack remainder = transaction.getRemainder();
                        
                        if (remainder != null && !remainder.isEmpty()) {
                            // Drop remainder as item entity
                            com.hypixel.hytale.component.Holder<EntityStore>[] itemDrops = 
                                com.hypixel.hytale.server.core.modules.entity.item.ItemComponent.generateItemDrops(
                                    commandBuffer, 
                                    java.util.Collections.singletonList(remainder),
                                    new com.hypixel.hytale.math.vector.Vector3d(bx + 0.5, by + 0.5, bz + 0.5),
                                    com.hypixel.hytale.math.vector.Vector3f.ZERO
                                );
                            for (com.hypixel.hytale.component.Holder<EntityStore> itemHolder : itemDrops) {
                                if (itemHolder != null) {
                                    commandBuffer.addEntity(itemHolder, com.hypixel.hytale.component.AddReason.SPAWN);
                                }
                            }
                        }
                    }
                    
                    // Handle crop harvesting - reset stage for regrowing crops or break block for one-time crops
                    boolean cropHarvested = false;
                    if (farmingData != null && farmingData.getStages() != null) {
                        String stageSetAfterHarvest = farmingData.getStageSetAfterHarvest();
                        // Only reset if this is truly an eternal/regrowing crop
                        // Check both: has stageSetAfterHarvest AND block type ID contains "Eternal"
                        String blockTypeId = blockType.getId();
                        boolean isEternal = stageSetAfterHarvest != null && !stageSetAfterHarvest.isEmpty() 
                            && blockTypeId != null && blockTypeId.contains("Eternal");
                        
                        if (isEternal) {
                            // Regrowing crop (eternal seed) - reset to starting stage instead of breaking
                            // We can use ChunkStore directly since we're in an EntityTickingSystem (ChunkStore is not being processed)
                            com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> chunkStore = world.getChunkStore().getStore();
                            long chunkIndex = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(bx, bz);
                            com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> chunkRef = 
                                world.getChunkStore().getChunkReference(chunkIndex);
                            if (chunkRef != null) {
                                com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk blockComponentChunk = 
                                    chunkStore.getComponent(chunkRef, com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk.getComponentType());
                                if (blockComponentChunk != null) {
                                    int blockIndexColumn = com.hypixel.hytale.math.util.ChunkUtil.indexBlockInColumn(bx, by, bz);
                                    com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> blockRef = 
                                        blockComponentChunk.getEntityReference(blockIndexColumn);
                                    
                                    java.time.Instant now = world.getEntityStore().getStore().getResource(
                                        com.hypixel.hytale.server.core.modules.time.WorldTimeResource.getResourceType()).getGameTime();
                                    
                                    java.util.Map<String, com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData[]> stageSets = 
                                        farmingData.getStages();
                                    String startingStageSet = farmingData.getStartingStageSet();
                                    com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData[] startingStages = 
                                        startingStageSet != null ? stageSets.get(startingStageSet) : null;
                                    com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData[] newStages = 
                                        stageSets.get(stageSetAfterHarvest);
                                    
                                    if (startingStages != null && newStages != null && newStages.length > 0) {
                                        int currentStageIndex = startingStages.length - 1;
                                        com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData previousStage = 
                                            startingStages[currentStageIndex];
                                        
                                        com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock farmingBlock;
                                        if (blockRef == null) {
                                            // Create new farming block entity
                                            com.hypixel.hytale.component.Holder<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> blockEntity = 
                                                com.hypixel.hytale.server.core.universe.world.storage.ChunkStore.REGISTRY.newHolder();
                                            blockEntity.putComponent(
                                                com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo.getComponentType(), 
                                                new com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo(blockIndexColumn, chunkRef));
                                            farmingBlock = new com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock();
                                            farmingBlock.setLastTickGameTime(now);
                                            farmingBlock.setCurrentStageSet(stageSetAfterHarvest);
                                            blockEntity.addComponent(com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock.getComponentType(), farmingBlock);
                                            blockRef = chunkStore.addEntity(blockEntity, com.hypixel.hytale.component.AddReason.SPAWN);
                                        } else {
                                            // Update existing farming block
                                            farmingBlock = chunkStore.ensureAndGetComponent(
                                                blockRef, 
                                                com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock.getComponentType());
                                        }
                                        
                                        if (farmingBlock != null) {
                                            // Reset farming stage
                                            farmingBlock.setCurrentStageSet(stageSetAfterHarvest);
                                            farmingBlock.setGrowthProgress(0.0F);
                                            farmingBlock.setExecutions(0);
                                            farmingBlock.setGeneration(farmingBlock.getGeneration() + 1);
                                            farmingBlock.setLastTickGameTime(now);
                                            
                                            com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.ChunkStore> sectionRef = 
                                                world.getChunkStore().getChunkSectionReference(
                                                    com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(bx),
                                                    com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(by),
                                                    com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(bz)
                                                );
                                            if (sectionRef != null && blockRef != null) {
                                                com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection section = 
                                                    chunkStore.getComponent(sectionRef, 
                                                        com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection.getComponentType());
                                                if (section != null) {
                                                    section.scheduleTick(
                                                        com.hypixel.hytale.math.util.ChunkUtil.indexBlock(bx, by, bz), 
                                                        now);
                                                }
                                                
                                                // Apply the first stage of the new stage set to reset the crop
                                                newStages[0].apply(
                                                    chunkStore, 
                                                    sectionRef, 
                                                    blockRef, 
                                                    bx, by, bz, 
                                                    previousStage);
                                                cropHarvested = true; // Successfully reset eternal crop
                                            } else {
                                                LOGGER.atWarning().log("[GreenHarvest] %s failed to reset eternal crop at (%d, %d, %d) - sectionRef or blockRef null", 
                                                    npcComponent.getRoleName(), bx, by, bz);
                                            }
                                        } else {
                                            LOGGER.atWarning().log("[GreenHarvest] %s failed to reset eternal crop at (%d, %d, %d) - farmingBlock null", 
                                                npcComponent.getRoleName(), bx, by, bz);
                                        }
                                    } else {
                                        LOGGER.atWarning().log("[GreenHarvest] %s failed to reset eternal crop at (%d, %d, %d) - startingStages or newStages invalid", 
                                            npcComponent.getRoleName(), bx, by, bz);
                                    }
                                } else {
                                    LOGGER.atWarning().log("[GreenHarvest] %s failed to reset eternal crop at (%d, %d, %d) - blockComponentChunk null", 
                                        npcComponent.getRoleName(), bx, by, bz);
                                }
                            } else {
                                LOGGER.atWarning().log("[GreenHarvest] %s failed to reset eternal crop at (%d, %d, %d) - chunkRef null", 
                                    npcComponent.getRoleName(), bx, by, bz);
                            }
                        } else {
                            // One-time farming crop - will be broken below
                        }
                    } else {
                        // Not a farming crop - will be broken below
                    }
                    
                    // If we didn't successfully reset an eternal crop, break the block
                    if (!cropHarvested) {
                        blockChunk.breakBlock(bx, by, bz, 0);
                    }
                    
                    // Update cooldown and return - harvest one crop per cooldown period
                    harvestCooldowns.put(npcRef, currentTime);
                    return;
                }
            }
        }
    }
    
    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return this.query;
    }
}
