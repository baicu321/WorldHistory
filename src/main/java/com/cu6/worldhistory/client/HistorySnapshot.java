package com.cu6.worldhistory.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;

/** Historical state captured from loaded chunks at one game time. */
public final class HistorySnapshot {
    private final long gameTime;
    private final double x;
    private final double y;
    private final double z;
    private final double originX;
    private final double originY;
    private final double originZ;
    private final boolean complete;
    private final List<ChunkSnapshot> chunks;
    private final List<RecordedEntity> entities;

    public HistorySnapshot(long gameTime, double x, double y, double z, double originX, double originY, double originZ,
                           List<ChunkSnapshot> chunks, List<RecordedEntity> entities, boolean complete) {
        this.gameTime = gameTime;
        this.x = x;
        this.y = y;
        this.z = z;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.complete = complete;
        this.chunks = List.copyOf(chunks);
        this.entities = List.copyOf(entities);
    }

    public long gameTime() { return gameTime; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public boolean complete() { return complete; }
    public double originX() { return originX; }
    public double originY() { return originY; }
    public double originZ() { return originZ; }
    public List<ChunkSnapshot> chunks() { return chunks; }
    public List<RecordedEntity> entities() { return entities; }

    public static HistorySnapshot capture(ClientLevel level, LocalPlayer player, WorldHistoryConfig config) {
        int centerX = player.blockPosition().getX() >> 4;
        int centerZ = player.blockPosition().getZ() >> 4;
        List<ChunkSnapshot> chunks = new ArrayList<>();
        int radius = config.renderDistanceChunks;
        for (int chunkZ = centerZ - radius; chunkZ <= centerZ + radius; chunkZ++) {
            for (int chunkX = centerX - radius; chunkX <= centerX + radius; chunkX++) {
                if (!level.hasChunk(chunkX, chunkZ)) continue;
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk != null) chunks.add(ChunkSnapshot.capture(chunk, level.getMinBuildHeight(), level.getHeight(), level.registryAccess()));
            }
        }
        List<RecordedEntity> entities = new ArrayList<>();
        level.getEntities(player, player.getBoundingBox().inflate(radius * 16.0), entity -> {
            if (entity.isAlive()) entities.add(RecordedEntity.capture(entity));
            return true;
        });
        return new HistorySnapshot(level.getDayTime(), player.getX(), player.getY(), player.getZ(), player.getX(), player.getY(), player.getZ(), chunks, entities, true);
    }

    static List<RecordedEntity> captureEntities(ClientLevel level, LocalPlayer player, int radiusChunks) {
        List<RecordedEntity> entities = new ArrayList<>();
        level.getEntities(player, player.getBoundingBox().inflate(radiusChunks * 16.0), entity -> {
            if (entity.isAlive()) entities.add(RecordedEntity.capture(entity));
            return true;
        });
        return List.copyOf(entities);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("GameTime", gameTime);
        tag.putDouble("X", x); tag.putDouble("Y", y); tag.putDouble("Z", z);
        tag.putDouble("OriginX", originX); tag.putDouble("OriginY", originY); tag.putDouble("OriginZ", originZ);
        tag.putBoolean("Complete", complete);
        ListTag chunkTags = new ListTag();
        for (ChunkSnapshot chunk : chunks) chunkTags.add(chunk.save());
        tag.put("Chunks", chunkTags);
        ListTag entityTags = new ListTag();
        for (RecordedEntity entity : entities) entityTags.add(entity.save());
        tag.put("Entities", entityTags);
        return tag;
    }

    public static HistorySnapshot load(CompoundTag tag) {
        List<ChunkSnapshot> chunks = new ArrayList<>();
        for (var value : tag.getList("Chunks", CompoundTag.TAG_COMPOUND)) chunks.add(ChunkSnapshot.load((CompoundTag) value));
        List<RecordedEntity> entities = new ArrayList<>();
        for (var value : tag.getList("Entities", CompoundTag.TAG_COMPOUND)) entities.add(RecordedEntity.load((CompoundTag) value));
        double x = tag.getDouble("X");
        double y = tag.getDouble("Y");
        double z = tag.getDouble("Z");
        return new HistorySnapshot(tag.getLong("GameTime"), x, y, z, tag.contains("OriginX") ? tag.getDouble("OriginX") : x,
                tag.contains("OriginY") ? tag.getDouble("OriginY") : y, tag.contains("OriginZ") ? tag.getDouble("OriginZ") : z, chunks, entities,
                tag.contains("Complete") ? tag.getBoolean("Complete") : !chunks.isEmpty());
    }

    /** Immutable entity data, including the original NBT and packed light used by the renderer. */
    public record RecordedEntity(String type, CompoundTag data, int light) {
        public RecordedEntity {
            data = data.copy();
        }
        static RecordedEntity capture(Entity entity) {
            CompoundTag data = entity.saveWithoutId(new CompoundTag());
            int light = net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().getPackedLightCoords(entity, 1.0F);
            return new RecordedEntity(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), data, light);
        }
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Type", type); tag.put("Data", data.copy()); tag.putInt("Light", light); return tag;
        }
        static RecordedEntity load(CompoundTag tag) {
            if (!tag.contains("Data", CompoundTag.TAG_COMPOUND)) throw new IllegalStateException("Historical entity NBT is missing");
            return new RecordedEntity(tag.getString("Type"), tag.getCompound("Data"), tag.getInt("Light"));
        }
        Entity create(ClientLevel level) {
            var entityType = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(type));
            if (entityType == null) throw new IllegalStateException("Unknown historical entity type: " + type);
            Entity entity = entityType.create(level);
            if (entity == null) throw new IllegalStateException("Unable to create historical entity: " + type);
            entity.load(data.copy());
            return entity;
        }
    }
}
