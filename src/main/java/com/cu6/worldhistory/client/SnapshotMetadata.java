package com.cu6.worldhistory.client;

import net.minecraft.nbt.CompoundTag;

/** Lightweight timeline entry retained in memory while the corresponding snapshot stays compressed on disk. */
public record SnapshotMetadata(long id, long gameTime, double x, double y, double z, boolean complete, int chunkCount, int entityCount) {
    static SnapshotMetadata from(long id, HistorySnapshot snapshot) {
        return new SnapshotMetadata(id, snapshot.gameTime(), snapshot.x(), snapshot.y(), snapshot.z(), snapshot.complete(),
                snapshot.chunks().size(), snapshot.entities().size());
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id); tag.putLong("GameTime", gameTime);
        tag.putDouble("X", x); tag.putDouble("Y", y); tag.putDouble("Z", z);
        tag.putBoolean("Complete", complete); tag.putInt("ChunkCount", chunkCount); tag.putInt("EntityCount", entityCount);
        return tag;
    }

    static SnapshotMetadata load(CompoundTag tag) {
        return new SnapshotMetadata(tag.getLong("Id"), tag.getLong("GameTime"), tag.getDouble("X"), tag.getDouble("Y"),
                tag.getDouble("Z"), tag.getBoolean("Complete"), tag.getInt("ChunkCount"), tag.getInt("EntityCount"));
    }
}
