package com.cu6.worldhistory.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayList;
import java.util.List;

/** Sparse historical representation of one loaded chunk. Air is omitted. */
public record ChunkSnapshot(int chunkX, int chunkZ, int minY, int height, int[] positions, int[] states,
                            List<RecordedBlockEntity> blockEntities) {
    public static ChunkSnapshot capture(ClientLevel level, int chunkX, int chunkZ) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        return chunk == null ? null : capture(chunk, level.getMinBuildHeight(), level.getHeight(), level.registryAccess());
    }

    public static ChunkSnapshot capture(LevelChunk chunk, int minY, int height, HolderLookup.Provider registries) {
        List<Integer> positions = new ArrayList<>();
        List<Integer> states = new ArrayList<>();
        int airState = Block.getId(Blocks.AIR.defaultBlockState());
        LevelChunkSection[] sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section.hasOnlyAir()) continue;
            int sectionMinY = minY + sectionIndex * 16;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int state = Block.getId(section.getBlockState(x, y, z));
                if (state != airState) {
                    positions.add(((sectionMinY + y - minY) * 16 + z) * 16 + x);
                    states.add(state);
                }
            }
        }
        List<RecordedBlockEntity> blockEntities = new ArrayList<>();
        for (BlockPos position : chunk.getBlockEntitiesPos()) {
            BlockEntity blockEntity = chunk.getBlockEntity(position);
            if (blockEntity != null) blockEntities.add(new RecordedBlockEntity(position, blockEntity.saveWithFullMetadata(registries),
                    net.minecraft.client.renderer.LevelRenderer.getLightColor(chunk.getLevel(), position)));
        }
        return new ChunkSnapshot(chunk.getPos().x, chunk.getPos().z, minY, height,
                positions.stream().mapToInt(Integer::intValue).toArray(), states.stream().mapToInt(Integer::intValue).toArray(), List.copyOf(blockEntities));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("ChunkX", chunkX); tag.putInt("ChunkZ", chunkZ); tag.putInt("MinY", minY); tag.putInt("Height", height);
        tag.put("Positions", new IntArrayTag(positions));
        tag.put("States", new IntArrayTag(states));
        ListTag blockEntityTags = new ListTag();
        for (RecordedBlockEntity blockEntity : blockEntities) blockEntityTags.add(blockEntity.save());
        tag.put("BlockEntities", blockEntityTags);
        return tag;
    }

    public static ChunkSnapshot load(CompoundTag tag) {
        int[] positions = tag.getIntArray("Positions");
        int[] states = tag.getIntArray("States");
        if (positions.length != states.length) throw new IllegalStateException("Corrupt WorldHistory chunk snapshot");
        List<RecordedBlockEntity> blockEntities = new ArrayList<>();
        for (var value : tag.getList("BlockEntities", CompoundTag.TAG_COMPOUND)) blockEntities.add(RecordedBlockEntity.load((CompoundTag) value));
        return new ChunkSnapshot(tag.getInt("ChunkX"), tag.getInt("ChunkZ"), tag.getInt("MinY"), tag.getInt("Height"), positions, states, List.copyOf(blockEntities));
    }

    /**
     * Resolves a saved block without consulting the live level.  The positions array is emitted in
     * ascending local-coordinate order by {@link #capture(LevelChunk, int, int)}, so a binary search
     * keeps the historical render view compact while still providing correct neighbour lookups.
     */
    public BlockState stateAt(int localX, int y, int localZ) {
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15 || y < minY || y >= minY + height) {
            return Blocks.AIR.defaultBlockState();
        }
        int encoded = ((y - minY) * 16 + localZ) * 16 + localX;
        int index = java.util.Arrays.binarySearch(positions, encoded);
        return index < 0 ? Blocks.AIR.defaultBlockState() : Block.stateById(states[index]);
    }

    /** NBT needed to faithfully reproduce a block rendered outside the terrain mesh, such as chests. */
    public record RecordedBlockEntity(BlockPos position, CompoundTag data, int light) {
        public RecordedBlockEntity { data = data.copy(); }
        CompoundTag save() {
            CompoundTag tag = data.copy();
            tag.putInt("X", position.getX()); tag.putInt("Y", position.getY()); tag.putInt("Z", position.getZ());
            tag.putInt("WorldHistoryLight", light);
            return tag;
        }
        static RecordedBlockEntity load(CompoundTag tag) {
            return new RecordedBlockEntity(new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")), tag, tag.getInt("WorldHistoryLight"));
        }
    }
}
