package com.s3explorer.service;

import com.s3explorer.aws.S3Connection;
import com.s3explorer.config.AppSettings;
import com.s3explorer.model.S3Path;
import com.s3explorer.model.TransferTask;
import com.s3explorer.util.BoundedInputStream;
import com.s3explorer.util.MimeTypeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** Uploads a local file, using multipart upload above the configured threshold. */
public class UploadService implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    @Override
    public void execute(TransferTask task, TransferContext ctx) throws Exception {
        Path file = task.localPath();
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(file.toString());
        }
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        S3Connection connection = ctx.connection(task.destConnectionId());
        S3Client client = connection.client();
        S3Path dest = task.destination();

        if (task.uploadId() != null && (size != task.localSize() || modified != task.localModified())) {
            log.info("{} changed since the upload started; restarting", file);
            MultipartSupport.abortQuietly(client, dest.bucket(), dest.key(), task.uploadId());
            task.setUploadId(null);
        }
        task.setLocalFileState(size, modified);
        task.setTotal(size);

        String contentType = MimeTypeUtils.contentType(file);
        AppSettings.Transfers settings = ctx.transferSettings();

        if (size < settings.multipartThresholdBytes()) {
            task.setTransferred(0);
            client.putObject(PutObjectRequest.builder()
                            .bucket(dest.bucket())
                            .key(dest.key())
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromContentProvider(
                            () -> ctx.monitor(BoundedInputStream.openRange(file, 0, size), task, task::setTransferred),
                            size, contentType));
            task.setTransferred(size);
        } else {
            MultipartSupport.execute(task, ctx, client, dest.bucket(), dest.key(), size, settings.partSizeBytes(),
                    () -> client.createMultipartUpload(b -> b.bucket(dest.bucket()).key(dest.key())
                            .contentType(contentType)).uploadId(),
                    (part, progress) -> client.uploadPart(UploadPartRequest.builder()
                                    .bucket(dest.bucket())
                                    .key(dest.key())
                                    .uploadId(task.uploadId())
                                    .partNumber(part.number())
                                    .contentLength(part.length())
                                    .build(),
                            RequestBody.fromContentProvider(
                                    () -> ctx.monitor(BoundedInputStream.openRange(file, part.offset(), part.length()),
                                            task, progress),
                                    part.length(), "application/octet-stream")).eTag());
        }
        log.info("Uploaded {} to {} ({} bytes)", file, dest.toUri(), size);
    }

    @Override
    public void cleanupCancelled(TransferTask task, TransferContext ctx) {
        if (task.uploadId() != null) {
            S3Path dest = task.destination();
            MultipartSupport.abortQuietly(ctx.connection(task.destConnectionId()).client(),
                    dest.bucket(), dest.key(), task.uploadId());
            task.setUploadId(null);
        }
    }

    @Override
    public String actionDescription(TransferTask task) {
        return "upload to " + task.destination().bucket();
    }
}
