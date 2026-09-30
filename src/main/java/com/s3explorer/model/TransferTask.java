package com.s3explorer.model;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One queued operation. Uploads and downloads move one file; copy/move handle one object;
 * delete removes an enumerated set of objects. Fields written by worker threads are volatile
 * so the UI can poll them for progress.
 */
public final class TransferTask {

    public enum Unit { BYTES, OBJECTS }

    private final UUID id;
    private final TransferType type;
    private final Instant created;

    private volatile Path localPath;
    private final String sourceConnectionId;
    private final S3Path source;
    private final String destConnectionId;
    private final S3Path destination;
    private final ConflictPolicy conflictPolicy;
    private final List<ObjectRef> deleteRefs;
    private final String label;

    private final AtomicLong transferred = new AtomicLong();
    private volatile long total = -1;
    private volatile Unit unit = Unit.BYTES;
    private volatile TransferStatus status = TransferStatus.QUEUED;
    private volatile String message;

    // Resume state
    private volatile String uploadId;
    private volatile String remoteETag;
    private volatile long localSize = -1;
    private volatile long localModified = -1;
    private volatile boolean targetResolved;
    private volatile int deleteIndex;

    // Control flags
    private volatile boolean pauseRequested;
    private volatile boolean cancelRequested;

    // Timing
    private volatile long accumulatedMillis;
    private volatile long runStartedMillis;

    // Speed sampling (UI thread only)
    private long sampleBytes;
    private long sampleNanos;
    private double speed;

    private TransferTask(UUID id, TransferType type, Path localPath, String sourceConnectionId, S3Path source,
                         String destConnectionId, S3Path destination, ConflictPolicy conflictPolicy,
                         List<ObjectRef> deleteRefs, String label, Instant created) {
        this.id = id;
        this.type = type;
        this.localPath = localPath;
        this.sourceConnectionId = sourceConnectionId;
        this.source = source;
        this.destConnectionId = destConnectionId;
        this.destination = destination;
        this.conflictPolicy = conflictPolicy;
        this.deleteRefs = deleteRefs;
        this.label = label;
        this.created = created;
    }

    public static TransferTask upload(Path file, String connectionId, S3Path destination) {
        return new TransferTask(UUID.randomUUID(), TransferType.UPLOAD, file, null, null,
                connectionId, destination, null, null, null, Instant.now());
    }

    public static TransferTask download(String connectionId, S3Path source, Path target, ConflictPolicy policy) {
        return new TransferTask(UUID.randomUUID(), TransferType.DOWNLOAD, target, connectionId, source,
                null, null, policy, null, null, Instant.now());
    }

    public static TransferTask copy(String sourceConnectionId, S3Path source, String destConnectionId,
                                    S3Path destination, boolean move) {
        return new TransferTask(UUID.randomUUID(), move ? TransferType.MOVE : TransferType.COPY, null,
                sourceConnectionId, source, destConnectionId, destination, null, null, null, Instant.now());
    }

    public static TransferTask delete(String connectionId, S3Path scope, List<ObjectRef> refs, String label) {
        TransferTask task = new TransferTask(UUID.randomUUID(), TransferType.DELETE, null, connectionId, scope,
                null, null, null, List.copyOf(refs), label, Instant.now());
        task.unit = Unit.OBJECTS;
        task.total = refs.size();
        return task;
    }

    /** Recreates a task from persisted state. */
    public static TransferTask restore(UUID id, TransferType type, Path localPath, String sourceConnectionId,
                                       S3Path source, String destConnectionId, S3Path destination,
                                       ConflictPolicy policy, Instant created) {
        return new TransferTask(id, type, localPath, sourceConnectionId, source, destConnectionId, destination,
                policy, null, null, created);
    }

    /** File or object name shown in the queue. */
    public String displayName() {
        if (label != null) {
            return label;
        }
        return switch (type) {
            case UPLOAD -> localPath.getFileName().toString();
            case DOWNLOAD, COPY, MOVE -> source.name();
            case DELETE -> source.name();
        };
    }

    // --- state transitions -------------------------------------------------------------

    /** QUEUED -> STARTING. Returns false if the task was paused or cancelled while queued. */
    public synchronized boolean tryStart() {
        if (status != TransferStatus.QUEUED) {
            return false;
        }
        status = TransferStatus.STARTING;
        message = null;
        runStartedMillis = System.currentTimeMillis();
        return true;
    }

    public synchronized void setStatus(TransferStatus newStatus) {
        if (status.isActive() && !newStatus.isActive()) {
            accumulatedMillis += System.currentTimeMillis() - runStartedMillis;
            runStartedMillis = 0;
        }
        status = newStatus;
    }

    /** Resets control flags and re-queues. */
    public synchronized void requeue() {
        pauseRequested = false;
        cancelRequested = false;
        message = null;
        status = TransferStatus.QUEUED;
    }

    /** Clears progress and resume state so the task starts from scratch. */
    public synchronized void resetProgress() {
        transferred.set(type == TransferType.DELETE ? deleteIndexProgress() : 0);
        uploadId = null;
        remoteETag = null;
        targetResolved = false;
    }

    private long deleteIndexProgress() {
        return deleteIndex;
    }

    // --- getters and setters -------------------------------------------------------------

    public UUID id() { return id; }
    public TransferType type() { return type; }
    public Instant created() { return created; }
    public Path localPath() { return localPath; }
    public void setLocalPath(Path localPath) { this.localPath = localPath; }
    public String sourceConnectionId() { return sourceConnectionId; }
    public S3Path source() { return source; }
    public String destConnectionId() { return destConnectionId; }
    public S3Path destination() { return destination; }
    public ConflictPolicy conflictPolicy() { return conflictPolicy; }
    public List<ObjectRef> deleteRefs() { return deleteRefs; }

    public long total() { return total; }
    public void setTotal(long total) { this.total = total; }
    public long transferred() { return transferred.get(); }
    public void setTransferred(long value) { transferred.set(value); }
    public void addTransferred(long delta) { transferred.addAndGet(delta); }
    public Unit unit() { return unit; }

    public TransferStatus status() { return status; }
    public String message() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String uploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }
    public String remoteETag() { return remoteETag; }
    public void setRemoteETag(String remoteETag) { this.remoteETag = remoteETag; }
    public long localSize() { return localSize; }
    public long localModified() { return localModified; }
    public void setLocalFileState(long size, long modified) { this.localSize = size; this.localModified = modified; }
    public boolean targetResolved() { return targetResolved; }
    public void setTargetResolved(boolean targetResolved) { this.targetResolved = targetResolved; }
    public int deleteIndex() { return deleteIndex; }
    public void setDeleteIndex(int deleteIndex) { this.deleteIndex = deleteIndex; }

    public boolean pauseRequested() { return pauseRequested; }
    public void requestPause() { this.pauseRequested = true; }
    public boolean cancelRequested() { return cancelRequested; }
    public void requestCancel() { this.cancelRequested = true; }

    public double progressFraction() {
        long t = total;
        if (t <= 0) {
            return status == TransferStatus.COMPLETED ? 1.0 : 0.0;
        }
        return Math.min(1.0, (double) transferred.get() / t);
    }

    public Duration elapsed() {
        long running = runStartedMillis > 0 ? System.currentTimeMillis() - runStartedMillis : 0;
        return Duration.ofMillis(accumulatedMillis + running);
    }

    /** Updates the smoothed transfer rate; call periodically from a single thread. */
    public double sampleSpeed() {
        long now = System.nanoTime();
        long bytes = transferred.get();
        if (!status.isActive() || unit != Unit.BYTES) {
            speed = 0;
        } else if (sampleNanos != 0 && now > sampleNanos) {
            double instant = (bytes - sampleBytes) / ((now - sampleNanos) / 1_000_000_000.0);
            speed = speed == 0 ? instant : speed * 0.7 + instant * 0.3;
        }
        sampleBytes = bytes;
        sampleNanos = now;
        return speed;
    }

    public double speed() { return speed; }

    public Duration remaining() {
        if (speed <= 0 || total <= 0 || !status.isActive()) {
            return null;
        }
        return Duration.ofSeconds((long) Math.ceil((total - transferred.get()) / speed));
    }
}
