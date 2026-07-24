package com.cu6.worldhistory.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** GPU-resident terrain meshes for one immutable historical snapshot. */
final class HistoryMesh implements AutoCloseable {
    private static final int SECTION_BUFFER_BYTES = 786_432;
    private final HistoricalBlockAccess blocks;
    private final ArrayDeque<SectionKey> pending = new ArrayDeque<>();
    private final Map<RenderType, List<SectionBuffer>> buffers = new Reference2ObjectArrayMap<>();
    private final java.util.Set<Long> builtChunks = new HashSet<>();

    HistoryMesh(HistorySnapshot snapshot, net.minecraft.client.multiplayer.ClientLevel liveLevel) {
        this.blocks = new HistoricalBlockAccess(snapshot.chunks(), liveLevel);
        List<SectionKey> sections = new ArrayList<>();
        for (ChunkSnapshot chunk : snapshot.chunks()) {
            java.util.Set<Integer> sectionYs = new HashSet<>();
            for (int encoded : chunk.positions()) {
                sectionYs.add(Math.floorDiv(chunk.minY() + (encoded >> 8), 16));
            }
            for (int sectionY : sectionYs) sections.add(new SectionKey(chunk.chunkX(), sectionY, chunk.chunkZ()));
        }
        sections.sort(java.util.Comparator.comparingDouble(section -> distanceSquared(section, snapshot)));
        pending.addAll(sections);
    }

    boolean buildNext() {
        SectionKey key = pending.pollFirst();
        if (key == null) return false;
        buildSection(key);
        return true;
    }

    boolean isChunkReady(double x, double z) {
        return builtChunks.contains(net.minecraft.world.level.ChunkPos.asLong(net.minecraft.util.Mth.floor(x) >> 4, net.minecraft.util.Mth.floor(z) >> 4));
    }

    void render(Matrix4f modelView, Matrix4f projection, double cameraX, double cameraY, double cameraZ,
                double translateX, double translateY, double translateZ, Frustum frustum) {
        for (Map.Entry<RenderType, List<SectionBuffer>> entry : buffers.entrySet()) {
            RenderType renderType = entry.getKey();
            renderType.setupRenderState();
            ShaderInstance shader = RenderSystem.getShader();
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, Minecraft.getInstance().getWindow());
            shader.apply();
            var offset = shader.CHUNK_OFFSET;
            for (SectionBuffer section : entry.getValue()) {
                if (!frustum.isVisible(new AABB(section.x + translateX, section.y + translateY, section.z + translateZ,
                        section.x + translateX + 16.0D, section.y + translateY + 16.0D, section.z + translateZ + 16.0D))) continue;
                if (offset != null) {
                    offset.set((float) (section.x + translateX - cameraX), (float) (section.y + translateY - cameraY),
                            (float) (section.z + translateZ - cameraZ));
                    offset.upload();
                }
                section.buffer.bind();
                section.buffer.draw();
            }
            if (offset != null) offset.set(0.0F, 0.0F, 0.0F);
            shader.clear();
            VertexBuffer.unbind();
            renderType.clearRenderState();
        }
    }

    private void buildSection(SectionKey key) {
        BlockRenderDispatcher renderer = Minecraft.getInstance().getBlockRenderer();
        Map<RenderType, BufferBuilder> builders = new Reference2ObjectArrayMap<>();
        PoseStack pose = new PoseStack();
        RandomSource random = RandomSource.create();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        int baseX = key.x * 16;
        int baseY = key.y * 16;
        int baseZ = key.z * 16;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            position.set(baseX + x, baseY + y, baseZ + z);
            BlockState state = blocks.getBlockState(position);
            if (state.isAir()) continue;
            var fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                renderer.renderLiquid(position, blocks, builderFor(builders, ItemBlockRenderTypes.getRenderLayer(fluid)), state, fluid);
            }
            if (state.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) continue;
            BakedModel model = renderer.getBlockModel(state);
            random.setSeed(state.getSeed(position));
            for (RenderType type : model.getRenderTypes(state, random, ModelData.EMPTY)) {
                pose.pushPose();
                pose.translate(x, y, z);
                renderer.renderBatched(state, position, blocks, pose, builderFor(builders, type), true, random, ModelData.EMPTY, type);
                pose.popPose();
            }
        }
        builders.forEach((type, builder) -> {
            MeshData mesh = builder.build();
            if (mesh == null) return;
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(mesh);
            VertexBuffer.unbind();
            buffers.computeIfAbsent(type, ignored -> new ArrayList<>()).add(new SectionBuffer(baseX, baseY, baseZ, buffer));
        });
        builtChunks.add(net.minecraft.world.level.ChunkPos.asLong(key.x, key.z));
    }

    private static BufferBuilder builderFor(Map<RenderType, BufferBuilder> builders, RenderType type) {
        return builders.computeIfAbsent(type, ignored -> new BufferBuilder(new ByteBufferBuilder(SECTION_BUFFER_BYTES),
                VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK));
    }

    private static double distanceSquared(SectionKey section, HistorySnapshot snapshot) {
        double x = section.x * 16.0 + 8.0 - snapshot.originX();
        double y = section.y * 16.0 + 8.0 - snapshot.originY();
        double z = section.z * 16.0 + 8.0 - snapshot.originZ();
        return x * x + y * y + z * z;
    }

    @Override public void close() {
        buffers.values().forEach(sections -> sections.forEach(section -> section.buffer.close()));
        buffers.clear();
        pending.clear();
        builtChunks.clear();
    }

    private record SectionKey(int x, int y, int z) { }
    private record SectionBuffer(int x, int y, int z, VertexBuffer buffer) { }
    private static final class HistoricalBlockAccess implements BlockAndTintGetter {
        private final Map<Long, ChunkSnapshot> chunks = new HashMap<>();
        private final net.minecraft.client.multiplayer.ClientLevel liveLevel;

        private HistoricalBlockAccess(List<ChunkSnapshot> snapshots, net.minecraft.client.multiplayer.ClientLevel liveLevel) {
            this.liveLevel = liveLevel;
            for (ChunkSnapshot chunk : snapshots) chunks.put(net.minecraft.world.level.ChunkPos.asLong(chunk.chunkX(), chunk.chunkZ()), chunk);
        }

        @Override public BlockEntity getBlockEntity(BlockPos position) { return null; }
        @Override public BlockState getBlockState(BlockPos position) {
            ChunkSnapshot chunk = chunks.get(net.minecraft.world.level.ChunkPos.asLong(position.getX() >> 4, position.getZ() >> 4));
            return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.stateAt(position.getX() & 15, position.getY(), position.getZ() & 15);
        }
        @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos position) { return getBlockState(position).getFluidState(); }
        @Override public float getShade(Direction direction, boolean shade) { return liveLevel.getShade(direction, shade); }
        @Override public LevelLightEngine getLightEngine() { return liveLevel.getLightEngine(); }
        @Override public int getBlockTint(BlockPos position, ColorResolver resolver) { return liveLevel.getBlockTint(position, resolver); }
        @Override public int getHeight() { return liveLevel.getHeight(); }
        @Override public int getMinBuildHeight() { return liveLevel.getMinBuildHeight(); }
    }
}
