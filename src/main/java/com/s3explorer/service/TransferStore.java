package com.s3explorer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.s3explorer.config.JsonSupport;
import com.s3explorer.model.ConflictPolicy;
import com.s3explorer.model.S3Path;
import com.s3explorer.model.TransferStatus;
import com.s3explorer.model.TransferTask;
import com.s3explorer.model.TransferType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists unfinished transfers across restarts. Multipart uploads resume from their upload
 * ID and downloads from their partial file. Deletes are never persisted: a delete should only
 * ever run straight after the user confirmed it.
 */
public class TransferStore {

    private static final Logger log = LoggerFactory.getLogger(TransferStore.class);

    private final Path file;

    public record Record(
            UUID id,
            TransferType type,
            TransferStatus status,
            String localPath,
            String sourceConnectionId,
            String sourceBucket,
            String sourceKey,
            String destConnectionId,
            String destBucket,
            String destKey,
            ConflictPolicy conflictPolicy,
            String uploadId,
            String remoteETag,
            long localSize,
            long localModified,
            boolean targetResolved,
            long total,
            long transferred,
            String message,
            Instant created) {
    }

    public TransferStore(Path file) {
        this.file = file;
    }

    public void save(List<TransferTask> tasks) {
        List<Record> records = new ArrayList<>();
        for (TransferTask t : tasks) {
            if (t.type() == TransferType.DELETE || t.status() == TransferStatus.COMPLETED
                    || t.status() == TransferStatus.CANCELLED) {
                continue;
            }
            TransferStatus status = t.status() == TransferStatus.FAILED ? TransferStatus.FAILED : TransferStatus.PAUSED;
            records.add(new Record(t.id(), t.type(), status,
                    t.localPath() == null ? null : t.localPath().toString(),
                    t.sourceConnectionId(),
                    t.source() == null ? null : t.source().bucket(),
                    t.source() == null ? null : t.source().key(),
                    t.destConnectionId(),
                    t.destination() == null ? null : t.destination().bucket(),
                    t.destination() == null ? null : t.destination().key(),
                    t.conflictPolicy(), t.uploadId(), t.remoteETag(), t.localSize(), t.localModified(),
                    t.targetResolved(), t.total(), t.transferred(), t.message(), t.created()));
        }
        try {
            if (records.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                JsonSupport.write(file, records, true);
                log.info("Saved {} unfinished transfer(s) for resume", records.size());
            }
        } catch (IOException e) {
            log.error("Could not save unfinished transfers", e);
        }
    }

    public List<TransferTask> load() {
        List<TransferTask> tasks = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return tasks;
        }
        try {
            List<Record> records = JsonSupport.MAPPER.readValue(file.toFile(), new TypeReference<List<Record>>() { });
            for (Record r : records) {
                TransferTask t = TransferTask.restore(r.id(), r.type(),
                        r.localPath() == null ? null : Path.of(r.localPath()),
                        r.sourceConnectionId(),
                        r.sourceBucket() == null ? null : new S3Path(r.sourceBucket(), r.sourceKey()),
                        r.destConnectionId(),
                        r.destBucket() == null ? null : new S3Path(r.destBucket(), r.destKey()),
                        r.conflictPolicy(), r.created() == null ? Instant.now() : r.created());
                t.setUploadId(r.uploadId());
                t.setRemoteETag(r.remoteETag());
                t.setLocalFileState(r.localSize(), r.localModified());
                t.setTargetResolved(r.targetResolved());
                t.setTotal(r.total());
                t.setTransferred(r.transferred());
                t.setMessage(r.message());
                t.setStatus(r.status() == TransferStatus.FAILED ? TransferStatus.FAILED : TransferStatus.PAUSED);
                tasks.add(t);
            }
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException e) {
            log.error("Could not restore unfinished transfers from {}", file, e);
        }
        return tasks;
    }
}
