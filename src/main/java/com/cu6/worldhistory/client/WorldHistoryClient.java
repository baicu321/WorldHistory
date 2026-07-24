package com.cu6.worldhistory.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

/** Client lifecycle for recording and inspecting the local timeline. */
@EventBusSubscriber(modid = "worldhistory", value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class WorldHistoryClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HistoryArchive ARCHIVE = new HistoryArchive();
    private static final KeyMapping OPEN = new KeyMapping("key.worldhistory.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_SLASH, "key.categories.worldhistory");
    private static final KeyMapping PREVIEW_FORWARD = new KeyMapping("key.worldhistory.preview_forward", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UP, "key.categories.worldhistory.preview");
    private static final KeyMapping PREVIEW_BACKWARD = new KeyMapping("key.worldhistory.preview_backward", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_DOWN, "key.categories.worldhistory.preview");
    private static final KeyMapping PREVIEW_LEFT = new KeyMapping("key.worldhistory.preview_left", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT, "key.categories.worldhistory.preview");
    private static final KeyMapping PREVIEW_RIGHT = new KeyMapping("key.worldhistory.preview_right", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT, "key.categories.worldhistory.preview");
    private static final KeyMapping PREVIEW_UP = new KeyMapping("key.worldhistory.preview_up", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_PAGE_UP, "key.categories.worldhistory.preview");
    private static final KeyMapping PREVIEW_DOWN = new KeyMapping("key.worldhistory.preview_down", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_PAGE_DOWN, "key.categories.worldhistory.preview");
    private static int ticks;
    private static String openLevel;
    private static HistorySnapshot viewed;
    private static net.minecraft.world.phys.Vec3 viewAnchor;
    private static WorldHistoryConfig config;
    private static CaptureTask captureTask;
    private static int captureCooldown;
    private static List<RenderedEntity> viewedEntities = List.of();
    private static List<RenderedBlockEntity> viewedBlockEntities = List.of();
    private static HistoryMesh historyMesh;
    private static boolean freePreview;
    private static net.minecraft.world.phys.Vec3 previewPosition;
    private static float previewYaw;
    private static float previewPitch;
    private static boolean previewMoving;
    private static boolean snapshotLoadPending;

    private WorldHistoryClient() { }

    public static void register(IEventBus modBus) {
        modBus.addListener(WorldHistoryClient::registerKeys);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
        event.register(PREVIEW_FORWARD);
        event.register(PREVIEW_BACKWARD);
        event.register(PREVIEW_LEFT);
        event.register(PREVIEW_RIGHT);
        event.register(PREVIEW_UP);
        event.register(PREVIEW_DOWN);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        if (config == null) {
            config = WorldHistoryConfig.load(minecraft.gameDirectory.toPath());
            ARCHIVE.setMaxSnapshots(config.maxSnapshots);
        }
        if (viewed != null && !freePreview && minecraft.player.position().distanceToSqr(viewAnchor) > 0.0001D) {
            clearView();
        }
        if (viewed != null && freePreview) updatePreviewCamera(minecraft);
        String levelId = minecraft.level.dimension().location().toString().replace(':', '_');
        if (!levelId.equals(openLevel)) {
            if (openLevel != null && !ARCHIVE.isEmpty()) ARCHIVE.saveAsync();
            openLevel = levelId;
            ARCHIVE.open(minecraft.gameDirectory.toPath(), levelId);
            ticks = 0;
            captureTask = null;
            captureCooldown = 0;
        }
        if (OPEN.consumeClick()) minecraft.setScreen(new HistoryScreen(ARCHIVE));
        if (captureTask != null) {
            captureTask.process(minecraft.level, config.chunksPerTick);
            if (captureTask.complete()) {
                ARCHIVE.add(captureTask.finish(minecraft.level));
                captureTask = null;
                captureCooldown = config.sampleIntervalTicks;
            }
        } else if (captureCooldown > 0) {
            captureCooldown--;
        } else if (ARCHIVE.canStartCapture()) {
            captureTask = CaptureTask.begin(minecraft.level, minecraft.player, config);
        }
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (viewed == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        var camera = event.getCamera().getPosition();
        if (historyMesh == null) throw new IllegalStateException("Historical view has no mesh");
        if (!freePreview || !previewMoving) historyMesh.buildNext();
        double historyOffsetX = viewAnchor.x - viewed.originX();
        double historyOffsetY = viewAnchor.y - viewed.originY();
        double historyOffsetZ = viewAnchor.z - viewed.originZ();
        historyMesh.render(event.getModelViewMatrix(), event.getProjectionMatrix(), camera.x, camera.y, camera.z,
                historyOffsetX, historyOffsetY, historyOffsetZ, event.getFrustum());
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        for (RenderedEntity historical : viewedEntities) {
            net.minecraft.world.entity.Entity entity = historical.entity();
            if (!historyMesh.isChunkReady(entity.getX() - historyOffsetX, entity.getZ() - historyOffsetZ)) continue;
            Minecraft.getInstance().getEntityRenderDispatcher().render(entity,
                    entity.getX() - camera.x, entity.getY() - camera.y, entity.getZ() - camera.z,
                    entity.getYRot(), 0.0F, event.getPoseStack(), buffers, historical.light());
        }
        for (RenderedBlockEntity historical : viewedBlockEntities) {
            net.minecraft.world.level.block.entity.BlockEntity blockEntity = historical.entity();
            if (!historyMesh.isChunkReady(blockEntity.getBlockPos().getX() - historyOffsetX, blockEntity.getBlockPos().getZ() - historyOffsetZ)) continue;
                event.getPoseStack().pushPose();
                event.getPoseStack().translate(blockEntity.getBlockPos().getX() - camera.x, blockEntity.getBlockPos().getY() - camera.y,
                        blockEntity.getBlockPos().getZ() - camera.z);
                renderHistoricalBlockEntity(blockEntity, historical.light(), event.getPoseStack(), buffers);
                event.getPoseStack().popPose();
        }
        // The regular entity pass has already flushed before AFTER_ENTITIES. Historical entities need their own flush.
        buffers.endBatch();
    }

    static void view(HistorySnapshot snapshot) {
        viewed = snapshot;
        viewAnchor = Minecraft.getInstance().player.position();
        freePreview = false;
        previewPosition = null;
        historyMesh = new HistoryMesh(snapshot, Minecraft.getInstance().level);
        double offsetX = viewAnchor.x - snapshot.originX();
        double offsetY = viewAnchor.y - snapshot.originY();
        double offsetZ = viewAnchor.z - snapshot.originZ();
        viewedEntities = snapshot.entities().stream().map(recorded -> {
            net.minecraft.world.entity.Entity entity = recorded.create(Minecraft.getInstance().level);
            entity.setPos(entity.getX() + offsetX, entity.getY() + offsetY, entity.getZ() + offsetZ);
            // Entity renderers interpolate from xo/yo/zo to the current position. Freeze both endpoints after relocating.
            entity.setOldPosAndRot();
            return new RenderedEntity(entity, recorded.light());
        }).toList();
        int blockOffsetX = net.minecraft.util.Mth.floor(viewAnchor.x) - net.minecraft.util.Mth.floor(snapshot.originX());
        int blockOffsetY = net.minecraft.util.Mth.floor(viewAnchor.y) - net.minecraft.util.Mth.floor(snapshot.originY());
        int blockOffsetZ = net.minecraft.util.Mth.floor(viewAnchor.z) - net.minecraft.util.Mth.floor(snapshot.originZ());
        List<RenderedBlockEntity> blockEntities = new ArrayList<>();
        for (ChunkSnapshot chunk : snapshot.chunks()) for (ChunkSnapshot.RecordedBlockEntity recorded : chunk.blockEntities()) {
            net.minecraft.core.BlockPos target = recorded.position().offset(blockOffsetX, blockOffsetY, blockOffsetZ);
            net.minecraft.world.level.block.state.BlockState state = chunk.stateAt(recorded.position().getX() & 15, recorded.position().getY(), recorded.position().getZ() & 15);
            net.minecraft.world.level.block.entity.BlockEntity blockEntity = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                    target, state, recorded.data().copy(), Minecraft.getInstance().level.registryAccess());
            if (blockEntity != null) {
                blockEntity.setLevel(Minecraft.getInstance().level);
                blockEntities.add(new RenderedBlockEntity(blockEntity, recorded.light()));
            }
        }
        viewedBlockEntities = List.copyOf(blockEntities);
    }

    static void requestView(SnapshotMetadata metadata, boolean enableFreePreview) {
        if (snapshotLoadPending) return;
        snapshotLoadPending = true;
        ARCHIVE.loadAsync(metadata).whenComplete((snapshot, failure) -> Minecraft.getInstance().execute(() -> {
            snapshotLoadPending = false;
            if (failure != null) {
                LOGGER.error("Unable to load WorldHistory snapshot {}", metadata.id(), failure);
                return;
            }
            if (enableFreePreview) viewWithFreePreview(snapshot);
            else view(snapshot);
        }));
    }

    static void clearView() {
        viewed = null;
        viewAnchor = null;
        viewedEntities = List.of();
        viewedBlockEntities = List.of();
        if (historyMesh != null) historyMesh.close();
        historyMesh = null;
        freePreview = false;
        previewPosition = null;
    }

    public static boolean isViewingHistory() { return viewed != null; }

    public static boolean isFreePreview() { return freePreview; }

    static void viewWithFreePreview(HistorySnapshot snapshot) {
        view(snapshot);
        Minecraft minecraft = Minecraft.getInstance();
        previewPosition = new net.minecraft.world.phys.Vec3(viewAnchor.x, minecraft.player.getEyeY(), viewAnchor.z);
        previewYaw = minecraft.player.getYRot();
        previewPitch = minecraft.player.getXRot();
        freePreview = true;
    }

    public static net.minecraft.world.phys.Vec3 previewPosition() {
        if (!freePreview || previewPosition == null) throw new IllegalStateException("Free preview camera is not active");
        return previewPosition;
    }

    public static float previewYaw() { return previewYaw; }
    public static float previewPitch() { return previewPitch; }

    private static void updatePreviewCamera(Minecraft minecraft) {
        long window = minecraft.getWindow().getWindow();
        if (minecraft.screen != null) return;
        previewYaw = minecraft.player.getYRot();
        previewPitch = minecraft.player.getXRot();
        double speed = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) ? 2.0D : 0.5D;
        double yawRadians = Math.toRadians(previewYaw);
        net.minecraft.world.phys.Vec3 forward = new net.minecraft.world.phys.Vec3(-Math.sin(yawRadians), 0.0D, Math.cos(yawRadians));
        net.minecraft.world.phys.Vec3 left = new net.minecraft.world.phys.Vec3(-Math.cos(yawRadians), 0.0D, -Math.sin(yawRadians));
        net.minecraft.world.phys.Vec3 movement = net.minecraft.world.phys.Vec3.ZERO;
        if (PREVIEW_FORWARD.isDown()) movement = movement.add(forward);
        if (PREVIEW_BACKWARD.isDown()) movement = movement.subtract(forward);
        if (PREVIEW_LEFT.isDown()) movement = movement.add(left);
        if (PREVIEW_RIGHT.isDown()) movement = movement.subtract(left);
        if (PREVIEW_UP.isDown()) movement = movement.add(0.0D, 1.0D, 0.0D);
        if (PREVIEW_DOWN.isDown()) movement = movement.add(0.0D, -1.0D, 0.0D);
        previewMoving = movement.lengthSqr() > 0.0D;
        if (previewMoving) previewPosition = previewPosition.add(movement.normalize().scale(speed));
    }

    private static final class CaptureTask {
        private final long gameTime;
        private final double x;
        private final double y;
        private final double z;
        private final List<ChunkPos> positions;
        private final List<ChunkSnapshot> chunks = new ArrayList<>();
        private int next;
        private final int radius;
        private final List<HistorySnapshot.RecordedEntity> entities;

        private CaptureTask(long gameTime, double x, double y, double z, List<ChunkPos> positions, int radius,
                            List<HistorySnapshot.RecordedEntity> entities) {
            this.gameTime = gameTime; this.x = x; this.y = y; this.z = z; this.positions = positions; this.radius = radius; this.entities = entities;
        }

        static CaptureTask begin(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.client.player.LocalPlayer player, WorldHistoryConfig config) {
            int centerX = player.blockPosition().getX() >> 4;
            int centerZ = player.blockPosition().getZ() >> 4;
            List<ChunkPos> positions = new ArrayList<>();
            for (int cz = centerZ - config.renderDistanceChunks; cz <= centerZ + config.renderDistanceChunks; cz++) {
                for (int cx = centerX - config.renderDistanceChunks; cx <= centerX + config.renderDistanceChunks; cx++) {
                    if (level.getChunkSource().getChunkNow(cx, cz) != null) positions.add(new ChunkPos(cx, cz));
                }
            }
            return new CaptureTask(level.getDayTime(), player.getX(), player.getY(), player.getZ(), positions, config.renderDistanceChunks,
                    HistorySnapshot.captureEntities(level, player, config.renderDistanceChunks));
        }

        void process(net.minecraft.client.multiplayer.ClientLevel level, int chunksPerTick) {
            for (int i = 0; i < chunksPerTick && next < positions.size(); i++) {
                ChunkPos position = positions.get(next++);
                ChunkSnapshot chunk = ChunkSnapshot.capture(level, position.x, position.z);
                if (chunk != null) chunks.add(chunk);
            }
        }

        boolean complete() { return next >= positions.size(); }

        HistorySnapshot finish(net.minecraft.client.multiplayer.ClientLevel level) {
            return new HistorySnapshot(gameTime, x, y, z, x, y, z, chunks, entities, true);
        }
    }

    private record RenderedEntity(net.minecraft.world.entity.Entity entity, int light) { }
    private record RenderedBlockEntity(net.minecraft.world.level.block.entity.BlockEntity entity, int light) { }

    private static <E extends net.minecraft.world.level.block.entity.BlockEntity> void renderHistoricalBlockEntity(E blockEntity, int light,
                                                                                                                     com.mojang.blaze3d.vertex.PoseStack pose,
                                                                                                                     net.minecraft.client.renderer.MultiBufferSource buffers) {
        var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(blockEntity);
        if (renderer != null) renderer.render(blockEntity, 0.0F, pose, buffers, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
    }
}
