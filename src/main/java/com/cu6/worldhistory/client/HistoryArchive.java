package com.cu6.worldhistory.client;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Disk-indexed timeline. Only compact metadata is retained in memory; complete snapshots are loaded on demand. */
public final class HistoryArchive {
    private static final int FORMAT_VERSION = 1;
    public static final int MAX_SNAPSHOTS = 600;
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Deque<SnapshotMetadata> snapshots = new ArrayDeque<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "worldhistory-archive");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private int maxSnapshots = MAX_SNAPSHOTS;
    private long nextSnapshotId;
    private Path directory;
    private Path indexFile;

    public void open(Path gameDirectory, String levelId) {
        directory = gameDirectory.resolve("worldhistory").resolve(levelId);
        indexFile = directory.resolve("index.nbt");
        snapshots.clear();
        nextSnapshotId = 0L;
        if (!Files.exists(indexFile)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(indexFile, NbtAccounter.unlimitedHeap());
            if (root.getInt("FormatVersion") != FORMAT_VERSION) {
                LOGGER.warn("Ignoring outdated WorldHistory index: {}", indexFile);
                return;
            }
            nextSnapshotId = root.getLong("NextSnapshotId");
            for (var value : root.getList("Snapshots", CompoundTag.TAG_COMPOUND)) snapshots.addLast(SnapshotMetadata.load((CompoundTag) value));
        } catch (Exception exception) {
            snapshots.clear();
            Path backup = indexFile.resolveSibling("index.corrupt-" + System.currentTimeMillis() + ".nbt");
            try {
                Files.move(indexFile, backup, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.warn("Moved unreadable WorldHistory index to {} and started a new timeline", backup, exception);
            } catch (IOException moveFailure) {
                LOGGER.error("Unable to quarantine unreadable WorldHistory index: {}", indexFile, moveFailure);
            }
        }
    }

    /** Queues one compressed snapshot write and immediately discards its heavy payload from archive memory. */
    public void add(HistorySnapshot snapshot) {
        if (directory == null || indexFile == null) throw new IllegalStateException("WorldHistory archive is not open");
        long id = nextSnapshotId++;
        SnapshotMetadata metadata = SnapshotMetadata.from(id, snapshot);
        snapshots.addLast(metadata);
        List<SnapshotMetadata> removed = new ArrayList<>();
        while (snapshots.size() > maxSnapshots) removed.add(snapshots.removeFirst());
        List<SnapshotMetadata> index = List.copyOf(snapshots);
        Path snapshotFile = snapshotFile(id);
        Path indexDestination = indexFile;
        List<Path> discardedFiles = removed.stream().map(discarded -> snapshotFile(discarded.id())).toList();
        long nextId = nextSnapshotId;
        pendingWrites.incrementAndGet();
        ioExecutor.execute(() -> writeSnapshotAndIndex(id, snapshot, snapshotFile, indexDestination, index, nextId, discardedFiles));
    }

    public void setMaxSnapshots(int maxSnapshots) {
        if (maxSnapshots < 1) throw new IllegalArgumentException("maxSnapshots must be positive");
        this.maxSnapshots = maxSnapshots;
    }

    public List<SnapshotMetadata> snapshots() { return List.copyOf(snapshots); }
    public boolean isEmpty() { return snapshots.isEmpty(); }
    public boolean canStartCapture() { return pendingWrites.get() == 0; }

    /** Loads only the selected snapshot payload, keeping the UI index small and stable. */
    public CompletableFuture<HistorySnapshot> loadAsync(SnapshotMetadata metadata) {
        if (directory == null) throw new IllegalStateException("WorldHistory archive is not open");
        Path snapshotFile = snapshotFile(metadata.id());
        return CompletableFuture.supplyAsync(() -> {
            try {
                return HistorySnapshot.load(NbtIo.readCompressed(snapshotFile, NbtAccounter.unlimitedHeap()));
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to load WorldHistory snapshot: " + snapshotFile, exception);
            }
        }, ioExecutor);
    }

    /** Schedules an index-only flush. Snapshot payloads are always written by {@link #add(HistorySnapshot)}. */
    public void saveAsync() {
        if (indexFile == null) throw new IllegalStateException("WorldHistory archive is not open");
        List<SnapshotMetadata> index = List.copyOf(snapshots);
        long nextId = nextSnapshotId;
        Path indexDestination = indexFile;
        pendingWrites.incrementAndGet();
        ioExecutor.execute(() -> {
            try {
                writeIndex(indexDestination, index, nextId);
            } catch (IOException exception) {
                LOGGER.error("Unable to save WorldHistory index: {}", indexDestination, exception);
            } finally {
                pendingWrites.decrementAndGet();
            }
        });
    }

    private void writeSnapshotAndIndex(long id, HistorySnapshot snapshot, Path snapshotFile, Path indexDestination,
                                       List<SnapshotMetadata> index, long nextId, List<Path> discardedFiles) {
        try {
            writeAtomically(snapshot.save(), snapshotFile);
            for (Path discardedFile : discardedFiles) Files.deleteIfExists(discardedFile);
            writeIndex(indexDestination, index, nextId);
        } catch (IOException exception) {
            LOGGER.error("Unable to save WorldHistory snapshot {}", id, exception);
        } finally {
            pendingWrites.decrementAndGet();
        }
    }

    private void writeIndex(Path destination, List<SnapshotMetadata> index, long nextId) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("FormatVersion", FORMAT_VERSION);
        root.putLong("NextSnapshotId", nextId);
        ListTag tags = new ListTag();
        for (SnapshotMetadata metadata : index) tags.add(metadata.save());
        root.put("Snapshots", tags);
        writeAtomically(root, destination);
    }

    private Path snapshotFile(long id) { return directory.resolve("snapshot-" + id + ".nbt"); }

    private static void writeAtomically(CompoundTag tag, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        NbtIo.writeCompressed(tag, temporary);
        try {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
