package com.cu6.worldhistory.client;

import net.minecraft.nbt.CompoundTag;

/** Lightweight timeline entry retained in memory while the corresponding snapshot stays compressed on disk. */
public record SnapshotMetadata(long id, long gameTime, double x, double y, double z, boolean complete, int chunkCount,
                               int entityCount, PayloadFormat payloadFormat) {
    public SnapshotMetadata {
        if (id < 0L) throw new IllegalArgumentException("Snapshot id must not be negative");
        if (chunkCount < 0) throw new IllegalArgumentException("Snapshot chunk count must not be negative");
        if (entityCount < 0) throw new IllegalArgumentException("Snapshot entity count must not be negative");
        if (payloadFormat == null) throw new NullPointerException("Snapshot payload format must not be null");
    }

    static SnapshotMetadata from(long id, HistorySnapshot snapshot) {
        return new SnapshotMetadata(id, snapshot.gameTime(), snapshot.x(), snapshot.y(), snapshot.z(), snapshot.complete(),
                snapshot.chunks().size(), snapshot.entities().size(), PayloadFormat.CHUNK_MANIFEST_V1);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id); tag.putLong("GameTime", gameTime);
        tag.putDouble("X", x); tag.putDouble("Y", y); tag.putDouble("Z", z);
        tag.putBoolean("Complete", complete); tag.putInt("ChunkCount", chunkCount); tag.putInt("EntityCount", entityCount);
        tag.putInt("PayloadFormat", payloadFormat.id());
        return tag;
    }

    static SnapshotMetadata load(CompoundTag tag, int indexFormatVersion) {
        PayloadFormat payloadFormat;
        if (indexFormatVersion == HistoryArchive.LEGACY_INDEX_FORMAT_VERSION) {
            payloadFormat = PayloadFormat.LEGACY_SNAPSHOT;
        } else {
            if (!tag.contains("PayloadFormat", CompoundTag.TAG_INT)) {
                throw new IllegalStateException("WorldHistory index entry is missing its payload format");
            }
            payloadFormat = PayloadFormat.fromId(tag.getInt("PayloadFormat"));
        }
        return new SnapshotMetadata(tag.getLong("Id"), tag.getLong("GameTime"), tag.getDouble("X"), tag.getDouble("Y"),
                tag.getDouble("Z"), tag.getBoolean("Complete"), tag.getInt("ChunkCount"), tag.getInt("EntityCount"), payloadFormat);
    }

    /** On-disk payload selected by an index entry. */
    public enum PayloadFormat {
        LEGACY_SNAPSHOT(0),
        CHUNK_MANIFEST_V1(1);

        private final int id;

        PayloadFormat(int id) { this.id = id; }

        int id() { return id; }

        static PayloadFormat fromId(int id) {
            for (PayloadFormat format : values()) if (format.id == id) return format;
            throw new IllegalStateException("Unknown WorldHistory snapshot payload format: " + id);
        }
    }
}
