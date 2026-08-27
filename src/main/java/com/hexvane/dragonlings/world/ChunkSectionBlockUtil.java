package com.hexvane.dragonlings.world;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.time.Instant;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Chunk column and section block access without deprecated {@link World} chunk helpers or
 * {@link com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk} block APIs.
 */
public final class ChunkSectionBlockUtil {

    private ChunkSectionBlockUtil() {}

    @Nullable
    public static Ref<ChunkStore> chunkRefIfInMemory(@Nonnull World world, long chunkIndex) {
        Ref<ChunkStore> ref = world.getChunkStore().getChunkReference(chunkIndex);
        if (ref == null || !ref.isValid()) {
            return null;
        }
        return ref;
    }

    @Nullable
    public static Ref<ChunkStore> chunkRefIfInMemory(@Nonnull World world, int blockX, int blockZ) {
        return chunkRefIfInMemory(world, ChunkUtil.indexChunkFromBlock(blockX, blockZ));
    }

    public static boolean isChunkInMemory(@Nonnull World world, int blockX, int blockZ) {
        return chunkRefIfInMemory(world, blockX, blockZ) != null;
    }

    @Nullable
    public static Ref<ChunkStore> sectionRefAt(@Nonnull World world, int worldX, int worldY, int worldZ) {
        if (worldY < ChunkUtil.MIN_Y || worldY > ChunkUtil.HEIGHT_MINUS_1) {
            return null;
        }
        Ref<ChunkStore> sectionRef = world.getChunkStore().getChunkSectionReferenceAtBlock(worldX, worldY, worldZ);
        if (sectionRef == null || !sectionRef.isValid()) {
            return null;
        }
        return sectionRef;
    }

    @Nullable
    public static BlockSection blockSectionAt(@Nonnull World world, int worldX, int worldY, int worldZ) {
        Ref<ChunkStore> sectionRef = sectionRefAt(world, worldX, worldY, worldZ);
        if (sectionRef == null) {
            return null;
        }
        return world.getChunkStore().getStore().getComponent(sectionRef, BlockSection.getComponentType());
    }

    @Nullable
    public static BlockComponentSection blockComponentSectionAt(
            @Nonnull World world, int worldX, int worldY, int worldZ) {
        Ref<ChunkStore> sectionRef = sectionRefAt(world, worldX, worldY, worldZ);
        if (sectionRef == null) {
            return null;
        }
        return world.getChunkStore().getStore().getComponent(sectionRef, BlockComponentSection.getComponentType());
    }

    @Nullable
    public static Ref<ChunkStore> blockEntityRefAt(@Nonnull World world, int worldX, int worldY, int worldZ) {
        BlockComponentSection section = blockComponentSectionAt(world, worldX, worldY, worldZ);
        if (section == null) {
            return null;
        }
        return section.getBlockReference(ChunkUtil.indexBlock(worldX, worldY, worldZ));
    }

    /**
     * Block-entity ref at the filler root of the cell (multi-block crops / chests).
     */
    @Nullable
    public static Ref<ChunkStore> blockEntityRefAtFillerRoot(@Nonnull World world, int worldX, int worldY, int worldZ) {
        Vector3i root = fillerRoot(world, worldX, worldY, worldZ);
        if (root == null) {
            return null;
        }
        return blockEntityRefAt(world, root.x, root.y, root.z);
    }

    @Nullable
    public static Vector3i fillerRoot(@Nonnull World world, int worldX, int worldY, int worldZ) {
        BlockSection section = blockSectionAt(world, worldX, worldY, worldZ);
        if (section == null) {
            return null;
        }
        int filler = section.getFiller(worldX, worldY, worldZ);
        if (filler == FillerBlockUtil.NO_FILLER) {
            return new Vector3i(worldX, worldY, worldZ);
        }
        return new Vector3i(
            worldX - FillerBlockUtil.unpackX(filler),
            worldY - FillerBlockUtil.unpackY(filler),
            worldZ - FillerBlockUtil.unpackZ(filler));
    }

    public static int blockId(@Nonnull World world, int x, int y, int z) {
        BlockSection section = blockSectionAt(world, x, y, z);
        if (section == null) {
            return BlockType.EMPTY_ID;
        }
        return section.get(x, y, z);
    }

    @Nullable
    public static BlockType blockType(@Nonnull World world, int x, int y, int z) {
        int id = blockId(world, x, y, z);
        return BlockType.getAssetMap().getAsset(id);
    }

    public static int rotationIndex(@Nonnull World world, int x, int y, int z) {
        BlockSection section = blockSectionAt(world, x, y, z);
        if (section == null) {
            return RotationTuple.NONE_INDEX;
        }
        return section.getRotationIndex(x, y, z);
    }

    public static int filler(@Nonnull World world, int x, int y, int z) {
        BlockSection section = blockSectionAt(world, x, y, z);
        if (section == null) {
            return FillerBlockUtil.NO_FILLER;
        }
        return section.getFiller(x, y, z);
    }

    public static boolean setTicking(@Nonnull World world, int x, int y, int z, boolean ticking) {
        BlockSection section = blockSectionAt(world, x, y, z);
        if (section == null) {
            return false;
        }
        return section.setTicking(x, y, z, ticking);
    }

    public static void scheduleTick(@Nonnull World world, int x, int y, int z, @Nonnull Instant gameTime) {
        BlockSection section = blockSectionAt(world, x, y, z);
        if (section == null) {
            return;
        }
        section.scheduleTick(ChunkUtil.indexBlock(x, y, z), gameTime);
    }

    public static boolean setBlockEmpty(@Nonnull World world, int x, int y, int z, int settings) {
        Ref<ChunkStore> sectionRef = sectionRefAt(world, x, y, z);
        if (sectionRef == null) {
            return false;
        }
        return BlockOperations.setBlock(
            world.getChunkStore(),
            sectionRef,
            x,
            y,
            z,
            BlockType.EMPTY_ID,
            BlockType.EMPTY,
            RotationTuple.NONE_INDEX,
            FillerBlockUtil.NO_FILLER,
            settings);
    }

    public static boolean setBlockEmpty(@Nonnull World world, int x, int y, int z) {
        return setBlockEmpty(world, x, y, z, SetBlockSettings.NONE);
    }

    public static boolean setBlock(
            @Nonnull World world, int x, int y, int z, int blockId, @Nonnull BlockType blockType) {
        Ref<ChunkStore> sectionRef = sectionRefAt(world, x, y, z);
        if (sectionRef == null) {
            return false;
        }
        return BlockOperations.setBlock(
            world.getChunkStore(),
            sectionRef,
            x,
            y,
            z,
            blockId,
            blockType,
            RotationTuple.NONE_INDEX,
            FillerBlockUtil.NO_FILLER,
            SetBlockSettings.NONE);
    }
}
