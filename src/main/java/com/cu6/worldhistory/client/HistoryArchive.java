package com.cu6.worldhistory.client;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Disk-indexed timeline backed by immutable manifests and content-addressed chunk blobs. */
public final class HistoryArchive {
    static final int LEGACY_INDEX_FORMAT_VERSION = 1;
    private static final int INDEX_FORMAT_VERSION = 2;
    private static final int MANIFEST_FORMAT_VERSION = 1;
    private static final long INDEX_NBT_QUOTA = 16L * 1024L * 1024L;
    private static final long MANIFEST_NBT_QUOTA = 128L * 1024L * 1024L;
    private static final long BLOB_NBT_QUOTA = 128L * 1024L * 1024L;
    private static final long LEGACY_SNAPSHOT_NBT_QUOTA = 1024L * 1024L * 1024L;
    private static final int SHA_256_HEX_LENGTH = 64;
    public static final int MAX_SNAPSHOTS = 600;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Object stateLock = new Object();
    private final Deque<SnapshotMetadata> snapshots = new ArrayDeque<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "worldhistory-archive");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private int maxSnapshots = MAX_SNAPSHOTS;
    private long nextSnapshotId;
    private ArchiveSession session;

    public void open(Path gameDirectory, String levelId) {
        ArchiveSession nextSession = new ArchiveSession(gameDirectory.resolve("worldhistory").resolve(levelId));
        Deque<SnapshotMetadata> loaded = new ArrayDeque<>();
        long loadedNextId = 0L;
        boolean maintenanceAllowed = true;
        if (Files.exists(nextSession.indexFile())) {
            try {
                CompoundTag root = readCompressed(nextSession.indexFile(), INDEX_NBT_QUOTA);
                if (!root.contains("FormatVersion", Tag.TAG_INT)
                        || !root.contains("NextSnapshotId", Tag.TAG_LONG)
                        || !root.contains("Snapshots", Tag.TAG_LIST)) {
                    throw new IllegalStateException("WorldHistory index is missing required fields: " + nextSession.indexFile());
                }
                int indexVersion = root.getInt("FormatVersion");
                if (indexVersion != LEGACY_INDEX_FORMAT_VERSION && indexVersion != INDEX_FORMAT_VERSION) {
                    throw new IllegalStateException("Unsupported WorldHistory index format: " + indexVersion);
                }
                loadedNextId = root.getLong("NextSnapshotId");
                for (var value : root.getList("Snapshots", Tag.TAG_COMPOUND)) {
                    loaded.addLast(SnapshotMetadata.load((CompoundTag) value, indexVersion));
                }
                validateIndex(loaded, loadedNextId);
            } catch (Exception exception) {
                loaded.clear();
                loadedNextId = 0L;
                maintenanceAllowed = false;
                quarantineUnreadableIndex(nextSession.indexFile(), exception);
            }
        }
        synchronized (stateLock) {
            session = nextSession;
            snapshots.clear();
            snapshots.addAll(loaded);
            nextSnapshotId = loadedNextId;
        }
        if (maintenanceAllowed) scheduleMaintenance(nextSession, List.copyOf(loaded));
    }

    /** Queues a manifest transaction; only content-addressed chunk blobs are written for new snapshots. */
    public void add(HistorySnapshot snapshot) {
        ArchiveSession destination;
        SnapshotMetadata metadata;
        List<SnapshotMetadata> removed = new ArrayList<>();
        List<SnapshotMetadata> index;
        long nextId;
        synchronized (stateLock) {
            destination = requireSession();
            long id = nextSnapshotId++;
            metadata = SnapshotMetadata.from(id, snapshot);
            snapshots.addLast(metadata);
            while (snapshots.size() > maxSnapshots) removed.add(snapshots.removeFirst());
            index = List.copyOf(snapshots);
            nextId = nextSnapshotId;
        }
        pendingWrites.incrementAndGet();
        ioExecutor.execute(() -> writeSnapshotAndIndex(destination, metadata, snapshot, index, nextId, List.copyOf(removed)));
    }

    public void setMaxSnapshots(int maxSnapshots) {
        if (maxSnapshots < 1) throw new IllegalArgumentException("maxSnapshots must be positive");
        synchronized (stateLock) {
            this.maxSnapshots = maxSnapshots;
        }
    }

    public List<SnapshotMetadata> snapshots() {
        synchronized (stateLock) {
            return List.copyOf(snapshots);
        }
    }

    public boolean isEmpty() {
        synchronized (stateLock) {
            return snapshots.isEmpty();
        }
    }

    public boolean canStartCapture() { return pendingWrites.get() == 0; }

    /** Loads either a v2 manifest or a legacy snapshot selected explicitly by its index metadata. */
    public CompletableFuture<HistorySnapshot> loadAsync(SnapshotMetadata metadata) {
        ArchiveSession source;
        synchronized (stateLock) {
            source = requireSession();
            if (!snapshots.contains(metadata)) throw new IllegalArgumentException("Snapshot does not belong to the open archive");
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return switch (metadata.payloadFormat()) {
                    case LEGACY_SNAPSHOT -> loadLegacySnapshot(source, metadata);
                    case CHUNK_MANIFEST_V1 -> loadManifestSnapshot(source, metadata);
                };
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to load WorldHistory snapshot " + metadata.id(), exception);
            }
        }, ioExecutor);
    }

    /** Schedules an index-only flush without converting or rewriting legacy payloads. */
    public void saveAsync() {
        ArchiveSession destination;
        List<SnapshotMetadata> index;
        long nextId;
        synchronized (stateLock) {
            destination = requireSession();
            index = List.copyOf(snapshots);
            nextId = nextSnapshotId;
        }
        pendingWrites.incrementAndGet();
        ioExecutor.execute(() -> {
            try {
                writeIndex(destination.indexFile(), index, nextId);
            } catch (IOException exception) {
                LOGGER.error("Unable to save WorldHistory index: {}", destination.indexFile(), exception);
            } finally {
                pendingWrites.decrementAndGet();
            }
        });
    }

    private void writeSnapshotAndIndex(ArchiveSession destination, SnapshotMetadata metadata, HistorySnapshot snapshot,
                                       List<SnapshotMetadata> index, long nextId, List<SnapshotMetadata> removed) {
        List<Path> createdBlobs = new ArrayList<>();
        Path manifestFile = manifestFile(destination, metadata.id());
        boolean indexCommitted = false;
        try {
            List<String> chunkHashes = writeChunkBlobs(destination, snapshot, createdBlobs);
            CompoundTag manifest = snapshot.saveManifest(chunkHashes);
            manifest.putInt("FormatVersion", MANIFEST_FORMAT_VERSION);
            writeAtomically(manifest, manifestFile);
            writeIndex(destination.indexFile(), index, nextId);
            indexCommitted = true;
            try {
                garbageCollectRemovedPayloads(destination, index, removed);
            } catch (IOException | RuntimeException exception) {
                LOGGER.error("WorldHistory snapshot {} committed, but payload GC failed", metadata.id(), exception);
            }
        } catch (IOException | RuntimeException exception) {
            if (!indexCommitted) {
                cleanupFailedTransaction(manifestFile, createdBlobs);
                rollbackFailedSnapshot(destination, metadata, removed);
            }
            LOGGER.error("Unable to save WorldHistory snapshot {}", metadata.id(), exception);
        } finally {
            pendingWrites.decrementAndGet();
        }
    }

    private List<String> writeChunkBlobs(ArchiveSession destination, HistorySnapshot snapshot, List<Path> createdBlobs)
            throws IOException {
        List<String> chunkHashes = new ArrayList<>(snapshot.chunks().size());
        Map<String, CompoundTag> uniqueBlobs = new LinkedHashMap<>();
        for (ChunkSnapshot chunk : snapshot.chunks()) {
            CompoundTag chunkTag = chunk.save();
            String hash = NbtContentHash.sha256(chunkTag);
            CompoundTag previous = uniqueBlobs.putIfAbsent(hash, chunkTag);
            if (previous != null && !previous.equals(chunkTag)) {
                throw new IllegalStateException("SHA-256 collision while saving WorldHistory chunk blob " + hash);
            }
            chunkHashes.add(hash);
        }
        for (Map.Entry<String, CompoundTag> entry : uniqueBlobs.entrySet()) {
            Path destinationFile = blobFile(destination, entry.getKey());
            if (Files.exists(destinationFile)) {
                if (!destination.verifiedBlobs().contains(entry.getKey())) readChunkBlob(destination, entry.getKey());
                continue;
            }
            writeAtomically(entry.getValue(), destinationFile);
            destination.verifiedBlobs().add(entry.getKey());
            createdBlobs.add(destinationFile);
        }
        return List.copyOf(chunkHashes);
    }

    private HistorySnapshot loadLegacySnapshot(ArchiveSession source, SnapshotMetadata metadata) throws IOException {
        Path file = legacySnapshotFile(source, metadata.id());
        HistorySnapshot snapshot = HistorySnapshot.load(readCompressed(file, LEGACY_SNAPSHOT_NBT_QUOTA));
        validateLoadedSnapshot(metadata, snapshot);
        return snapshot;
    }

    private HistorySnapshot loadManifestSnapshot(ArchiveSession source, SnapshotMetadata metadata) throws IOException {
        Manifest manifest = readManifest(source, metadata);
        List<ChunkSnapshot> chunks = new ArrayList<>(manifest.chunkHashes().size());
        for (String hash : manifest.chunkHashes()) chunks.add(readChunkBlob(source, hash));
        HistorySnapshot snapshot = HistorySnapshot.loadManifest(manifest.tag(), chunks);
        validateLoadedSnapshot(metadata, snapshot);
        return snapshot;
    }

    private Manifest readManifest(ArchiveSession source, SnapshotMetadata metadata) throws IOException {
        Path file = manifestFile(source, metadata.id());
        CompoundTag tag = readCompressed(file, MANIFEST_NBT_QUOTA);
        if (!tag.contains("FormatVersion", Tag.TAG_INT) || tag.getInt("FormatVersion") != MANIFEST_FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported WorldHistory manifest format in " + file);
        }
        if (!tag.contains("ChunkHashes", Tag.TAG_LIST)) {
            throw new IllegalStateException("WorldHistory manifest has no chunk hash list: " + file);
        }
        ListTag hashTags = tag.getList("ChunkHashes", Tag.TAG_STRING);
        if (hashTags.size() != metadata.chunkCount()) {
            throw new IllegalStateException("WorldHistory manifest chunk count differs from index: " + file);
        }
        List<String> hashes = new ArrayList<>(hashTags.size());
        for (Tag hashTag : hashTags) {
            String hash = hashTag.getAsString();
            validateHash(hash);
            hashes.add(hash);
        }
        return new Manifest(tag, List.copyOf(hashes));
    }

    private ChunkSnapshot readChunkBlob(ArchiveSession source, String expectedHash) throws IOException {
        validateHash(expectedHash);
        Path file = blobFile(source, expectedHash);
        CompoundTag tag = readCompressed(file, BLOB_NBT_QUOTA);
        String actualHash = NbtContentHash.sha256(tag);
        if (!expectedHash.equals(actualHash)) {
            throw new IllegalStateException("WorldHistory chunk blob hash mismatch: " + file);
        }
        source.verifiedBlobs().add(expectedHash);
        return ChunkSnapshot.load(tag);
    }

    private void garbageCollectRemovedPayloads(ArchiveSession source, List<SnapshotMetadata> retained,
                                               List<SnapshotMetadata> removed) throws IOException {
        Set<String> candidates = new HashSet<>();
        List<Path> removedManifests = new ArrayList<>();
        for (SnapshotMetadata metadata : removed) {
            if (metadata.payloadFormat() != SnapshotMetadata.PayloadFormat.CHUNK_MANIFEST_V1) continue;
            candidates.addAll(readManifest(source, metadata).chunkHashes());
            removedManifests.add(manifestFile(source, metadata.id()));
        }
        if (!candidates.isEmpty()) {
            for (SnapshotMetadata metadata : retained) {
                if (metadata.payloadFormat() != SnapshotMetadata.PayloadFormat.CHUNK_MANIFEST_V1) continue;
                for (String retainedHash : readManifest(source, metadata).chunkHashes()) candidates.remove(retainedHash);
                if (candidates.isEmpty()) break;
            }
            for (String hash : candidates) {
                Files.deleteIfExists(blobFile(source, hash));
                source.verifiedBlobs().remove(hash);
            }
        }
        for (Path removedManifest : removedManifests) Files.deleteIfExists(removedManifest);
    }

    private void scheduleMaintenance(ArchiveSession source, List<SnapshotMetadata> retained) {
        pendingWrites.incrementAndGet();
        ioExecutor.execute(() -> {
            try {
                garbageCollectOrphans(source, retained);
            } catch (IOException | RuntimeException exception) {
                LOGGER.error("Unable to maintain WorldHistory archive: {}", source.directory(), exception);
            } finally {
                pendingWrites.decrementAndGet();
            }
        });
    }

    private void garbageCollectOrphans(ArchiveSession source, List<SnapshotMetadata> retained) throws IOException {
        Set<String> referencedBlobs = new HashSet<>();
        Set<Path> referencedManifests = new HashSet<>();
        for (SnapshotMetadata metadata : retained) {
            if (metadata.payloadFormat() != SnapshotMetadata.PayloadFormat.CHUNK_MANIFEST_V1) continue;
            referencedManifests.add(manifestFile(source, metadata.id()));
            referencedBlobs.addAll(readManifest(source, metadata).chunkHashes());
        }
        deleteUnreferencedManifests(source, referencedManifests);
        deleteUnreferencedBlobs(source, referencedBlobs);
    }

    private void deleteUnreferencedManifests(ArchiveSession source, Set<Path> referenced) throws IOException {
        Path manifests = source.manifestsDirectory();
        if (!Files.exists(manifests)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(manifests, "snapshot-*.nbt")) {
            for (Path file : files) if (!referenced.contains(file)) Files.deleteIfExists(file);
        }
    }

    private void deleteUnreferencedBlobs(ArchiveSession source, Set<String> referenced) throws IOException {
        Path blobs = source.blobsDirectory();
        if (!Files.exists(blobs)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(blobs, "*.nbt")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String hash = name.substring(0, name.length() - ".nbt".length());
                validateHash(hash);
                if (!referenced.contains(hash)) {
                    Files.deleteIfExists(file);
                    source.verifiedBlobs().remove(hash);
                }
            }
        }
    }

    private void rollbackFailedSnapshot(ArchiveSession destination, SnapshotMetadata failed, List<SnapshotMetadata> removed) {
        synchronized (stateLock) {
            if (session != destination) return;
            if (!snapshots.removeLastOccurrence(failed)) {
                LOGGER.error("Unable to roll back failed WorldHistory snapshot {} because it is absent from memory", failed.id());
                return;
            }
            for (int index = removed.size() - 1; index >= 0; index--) snapshots.addFirst(removed.get(index));
            if (nextSnapshotId == failed.id() + 1L) nextSnapshotId = failed.id();
        }
    }

    private static void cleanupFailedTransaction(Path manifestFile, List<Path> createdBlobs) {
        try {
            Files.deleteIfExists(manifestFile);
            for (Path createdBlob : createdBlobs) Files.deleteIfExists(createdBlob);
        } catch (IOException exception) {
            LOGGER.error("Unable to clean failed WorldHistory transaction for {}", manifestFile, exception);
        }
    }

    private static void validateLoadedSnapshot(SnapshotMetadata metadata, HistorySnapshot snapshot) {
        boolean matches = metadata.gameTime() == snapshot.gameTime()
                && Double.compare(metadata.x(), snapshot.x()) == 0
                && Double.compare(metadata.y(), snapshot.y()) == 0
                && Double.compare(metadata.z(), snapshot.z()) == 0
                && metadata.complete() == snapshot.complete()
                && metadata.chunkCount() == snapshot.chunks().size()
                && metadata.entityCount() == snapshot.entities().size();
        if (!matches) throw new IllegalStateException("WorldHistory snapshot payload differs from index entry " + metadata.id());
    }

    private static void validateIndex(Deque<SnapshotMetadata> entries, long nextId) {
        long previousId = -1L;
        for (SnapshotMetadata metadata : entries) {
            if (metadata.id() <= previousId) throw new IllegalStateException("WorldHistory snapshot ids are not strictly increasing");
            previousId = metadata.id();
        }
        if (nextId < 0L || (!entries.isEmpty() && nextId <= previousId)) {
            throw new IllegalStateException("WorldHistory next snapshot id is invalid: " + nextId);
        }
    }

    private static void validateHash(String hash) {
        if (hash.length() != SHA_256_HEX_LENGTH) throw new IllegalStateException("Invalid WorldHistory chunk hash: " + hash);
        for (int index = 0; index < hash.length(); index++) {
            char value = hash.charAt(index);
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                throw new IllegalStateException("Invalid WorldHistory chunk hash: " + hash);
            }
        }
    }

    private static CompoundTag readCompressed(Path file, long quota) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("WorldHistory archive file does not exist: " + file);
        return NbtIo.readCompressed(file, NbtAccounter.create(quota));
    }

    private static void writeIndex(Path destination, List<SnapshotMetadata> index, long nextId) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("FormatVersion", INDEX_FORMAT_VERSION);
        root.putLong("NextSnapshotId", nextId);
        ListTag tags = new ListTag();
        for (SnapshotMetadata metadata : index) tags.add(metadata.save());
        root.put("Snapshots", tags);
        writeAtomically(root, destination);
    }

    private static void writeAtomically(CompoundTag tag, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        try {
            NbtIo.writeCompressed(tag, temporary);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                LOGGER.debug("Atomic move is unavailable for {}; using a same-directory replacement", destination, exception);
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private static void quarantineUnreadableIndex(Path indexFile, Exception failure) {
        Path backup = indexFile.resolveSibling("index.corrupt-" + System.currentTimeMillis() + ".nbt");
        try {
            Files.move(indexFile, backup, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("Moved unreadable WorldHistory index to {} and started a new timeline", backup, failure);
        } catch (IOException moveFailure) {
            moveFailure.addSuppressed(failure);
            throw new IllegalStateException("Unable to quarantine unreadable WorldHistory index: " + indexFile, moveFailure);
        }
    }

    private ArchiveSession requireSession() {
        if (session == null) throw new IllegalStateException("WorldHistory archive is not open");
        return session;
    }

    private static Path legacySnapshotFile(ArchiveSession source, long id) {
        return source.directory().resolve("snapshot-" + id + ".nbt");
    }

    private static Path manifestFile(ArchiveSession source, long id) {
        return source.manifestsDirectory().resolve("snapshot-" + id + ".nbt");
    }

    private static Path blobFile(ArchiveSession source, String hash) {
        validateHash(hash);
        return source.blobsDirectory().resolve(hash + ".nbt");
    }

    private record Manifest(CompoundTag tag, List<String> chunkHashes) { }

    private static final class ArchiveSession {
        private final Path directory;
        private final Set<String> verifiedBlobs = new HashSet<>();

        private ArchiveSession(Path directory) { this.directory = directory; }

        private Path directory() { return directory; }
        private Path indexFile() { return directory.resolve("index.nbt"); }
        private Path manifestsDirectory() { return directory.resolve("manifests"); }
        private Path blobsDirectory() { return directory.resolve("blobs"); }
        private Set<String> verifiedBlobs() { return verifiedBlobs; }
    }
}
