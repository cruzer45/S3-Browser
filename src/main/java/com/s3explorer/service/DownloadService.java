package com.s3explorer.service;

import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.S3Path;
import com.s3explorer.model.TransferTask;
import com.s3explorer.util.NameConflicts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

/**
 * Downloads an object into a temporary ".s3e-part" file next to the target and renames it
 * when complete. A paused or failed download resumes with a ranged GET, guarded by the
 * original ETag so a changed object restarts from the beginning.
 */
public class DownloadService implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);
    static final String PART_SUFFIX = ".s3e-part";

    @Override
    public void execute(TransferTask task, TransferContext ctx) throws Exception {
        S3Connection connection = ctx.connection(task.sourceConnectionId());
        S3Path source = task.source();
        Path target = task.localPath();

        if (!task.targetResolved()) {
            Files.createDirectories(target.toAbsolutePath().getParent());
            if (Files.exists(target)) {
                switch (task.conflictPolicy()) {
                    case SKIP -> {
                        task.setTotal(0);
                        task.setMessage("Skipped: file already exists");
                        log.info("Skipped download of {}: {} exists", source.toUri(), target);
                        return;
                    }
                    case RENAME -> {
                        target = NameConflicts.uniquePath(target);
                        task.setLocalPath(target);
                    }
                    case OVERWRITE -> {
                        // Replaced when the download completes.
                    }
                }
            }
            task.setTargetResolved(true);
        }
        Path partFile = partFile(target);

        for (int attempt = 0; attempt < 2; attempt++) {
            long existing = task.remoteETag() != null && Files.isRegularFile(partFile) ? Files.size(partFile) : 0;
            if (existing == 0) {
                task.setRemoteETag(null);
            }
            if (existing > 0 && task.total() > 0 && existing >= task.total()) {
                finish(partFile, target, null);
                task.setTransferred(task.total());
                return;
            }

            GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(source.bucket()).key(source.key());
            if (existing > 0) {
                request.range("bytes=" + existing + "-").ifMatch(task.remoteETag());
            }
            ResponseInputStream<GetObjectResponse> in;
            try {
                in = connection.client().getObject(request.build());
            } catch (S3Exception e) {
                if (existing > 0 && (e.statusCode() == 412 || e.statusCode() == 416)) {
                    log.info("Object {} changed since the download started; restarting", source.toUri());
                    Files.deleteIfExists(partFile);
                    task.setRemoteETag(null);
                    continue;
                }
                throw e;
            }

            Instant lastModified;
            boolean complete = false;
            try (OutputStream out = existing > 0
                    ? Files.newOutputStream(partFile, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
                    : Files.newOutputStream(partFile, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                GetObjectResponse response = in.response();
                lastModified = response.lastModified();
                if (existing == 0) {
                    task.setRemoteETag(response.eTag());
                }
                Long length = response.contentLength();
                task.setTotal(length == null ? -1 : existing + length);
                task.setTransferred(existing);

                byte[] buffer = new byte[TransferContext.BUFFER_SIZE];
                long written = existing;
                int n;
                while (true) {
                    ctx.checkpoint(task);
                    n = in.read(buffer);
                    if (n < 0) {
                        break;
                    }
                    ctx.throttle(n);
                    out.write(buffer, 0, n);
                    written += n;
                    task.setTransferred(written);
                }
                complete = true;
            } finally {
                if (!complete) {
                    in.abort();
                }
                try {
                    in.close();
                } catch (IOException | RuntimeException ignored) {
                    // Already aborted or drained.
                }
            }
            finish(partFile, target, lastModified);
            log.info("Downloaded {} to {}", source.toUri(), target);
            return;
        }
        throw new IOException("The object kept changing during the download.");
    }

    private void finish(Path partFile, Path target, Instant lastModified) throws IOException {
        try {
            Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (lastModified != null) {
            try {
                Files.setLastModifiedTime(target, FileTime.from(lastModified));
            } catch (IOException ignored) {
                // Not important.
            }
        }
    }

    static Path partFile(Path target) {
        return target.resolveSibling(target.getFileName() + PART_SUFFIX);
    }

    @Override
    public void cleanupCancelled(TransferTask task, TransferContext ctx) {
        try {
            if (task.localPath() != null) {
                Files.deleteIfExists(partFile(task.localPath()));
            }
        } catch (IOException e) {
            log.warn("Could not delete partial download {}", task.localPath(), e);
        }
        task.setRemoteETag(null);
    }

    @Override
    public String actionDescription(TransferTask task) {
        return "download from " + task.source().bucket();
    }
}
