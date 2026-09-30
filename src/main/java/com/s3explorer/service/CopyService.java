package com.s3explorer.service;

import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.S3Path;
import com.s3explorer.model.TransferTask;
import com.s3explorer.model.TransferType;
import com.s3explorer.util.UserFacingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * Copies or moves a single object. Within one connection this is a server-side copy
 * (multipart copy above 5 GB); between connections the data is streamed through this
 * machine, part by part, without buffering whole objects in memory.
 *
 * <p>Moves are COPY, VERIFY (size check on the destination), then DELETE of the source.
 */
public class CopyService implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(CopyService.class);

    static final long MAX_SINGLE_COPY = 5L * 1024 * 1024 * 1024;
    private static final long COPY_PART_SIZE = 256L * 1024 * 1024;

    @Override
    public void execute(TransferTask task, TransferContext ctx) throws Exception {
        S3Connection src = ctx.connection(task.sourceConnectionId());
        S3Connection dst = ctx.connection(task.destConnectionId());
        S3Path from = task.source();
        S3Path to = task.destination();
        boolean sameConnection = task.sourceConnectionId().equals(task.destConnectionId());

        if (sameConnection && from.equals(to)) {
            task.setTotal(0);
            task.setMessage("Skipped: source and destination are the same");
            return;
        }
        if (from.isDirectory()) {
            // Folder markers are empty by definition; recreate rather than copy, since some
            // S3-compatible servers do not support HeadObject/CopyObject on "dir/" keys.
            task.setTotal(0);
            dst.client().putObject(b -> b.bucket(to.bucket()).key(to.key()), RequestBody.empty());
            if (task.type() == TransferType.MOVE) {
                src.client().deleteObject(b -> b.bucket(from.bucket()).key(from.key()));
            }
            return;
        }

        HeadObjectResponse head = src.client().headObject(b -> b.bucket(from.bucket()).key(from.key()));
        long size = head.contentLength() == null ? 0 : head.contentLength();
        task.setTotal(size);
        ctx.checkpoint(task);

        if (sameConnection) {
            serverSideCopy(task, ctx, src.client(), from, to, head, size);
        } else {
            streamCopy(task, ctx, src.client(), dst.client(), from, to, head, size);
        }
        task.setTransferred(size);

        if (task.type() == TransferType.MOVE) {
            HeadObjectResponse copied = dst.client().headObject(b -> b.bucket(to.bucket()).key(to.key()));
            long copiedSize = copied.contentLength() == null ? 0 : copied.contentLength();
            if (copiedSize != size) {
                throw new UserFacingException("Move verification failed.",
                        "The copy of " + from.name() + " has a different size than the original, so the original "
                                + "was not deleted.");
            }
            src.client().deleteObject(b -> b.bucket(from.bucket()).key(from.key()));
            log.info("Moved {} to {}", from.toUri(), to.toUri());
        } else {
            log.info("Copied {} to {}", from.toUri(), to.toUri());
        }
    }

    private void serverSideCopy(TransferTask task, TransferContext ctx, S3Client client, S3Path from, S3Path to,
                                HeadObjectResponse head, long size) throws Exception {
        String storageClass = nonStandardStorageClass(head);
        if (size <= MAX_SINGLE_COPY) {
            CopyObjectRequest.Builder request = CopyObjectRequest.builder()
                    .sourceBucket(from.bucket()).sourceKey(from.key())
                    .destinationBucket(to.bucket()).destinationKey(to.key())
                    .metadataDirective(MetadataDirective.COPY);
            if (storageClass != null) {
                request.storageClass(storageClass);
            }
            client.copyObject(request.build());
            return;
        }
        long partSize = Math.max(COPY_PART_SIZE, ctx.transferSettings().partSizeBytes());
        MultipartSupport.execute(task, ctx, client, to.bucket(), to.key(), size, partSize,
                () -> client.createMultipartUpload(createRequest(to, head, storageClass)).uploadId(),
                (part, progress) -> {
                    String eTag = client.uploadPartCopy(b -> b
                            .sourceBucket(from.bucket()).sourceKey(from.key())
                            .destinationBucket(to.bucket()).destinationKey(to.key())
                            .uploadId(task.uploadId())
                            .partNumber(part.number())
                            .copySourceRange("bytes=" + part.offset() + "-" + (part.offset() + part.length() - 1)))
                            .copyPartResult().eTag();
                    progress.accept(part.length());
                    return eTag;
                });
    }

    private void streamCopy(TransferTask task, TransferContext ctx, S3Client source, S3Client dest, S3Path from,
                            S3Path to, HeadObjectResponse head, long size) throws Exception {
        String contentType = head.contentType() == null ? "application/octet-stream" : head.contentType();
        String storageClass = nonStandardStorageClass(head);
        if (size < ctx.transferSettings().multipartThresholdBytes()) {
            PutObjectRequest.Builder request = PutObjectRequest.builder()
                    .bucket(to.bucket()).key(to.key())
                    .contentType(contentType)
                    .cacheControl(head.cacheControl())
                    .contentEncoding(head.contentEncoding())
                    .contentDisposition(head.contentDisposition())
                    .contentLanguage(head.contentLanguage())
                    .metadata(head.metadata());
            if (storageClass != null) {
                request.storageClass(storageClass);
            }
            dest.putObject(request.build(), RequestBody.fromContentProvider(
                    () -> ctx.monitor(source.getObject(b -> b.bucket(from.bucket()).key(from.key())
                            .ifMatch(head.eTag())), task, task::setTransferred),
                    size, contentType));
            return;
        }
        MultipartSupport.execute(task, ctx, dest, to.bucket(), to.key(), size, ctx.transferSettings().partSizeBytes(),
                () -> dest.createMultipartUpload(createRequest(to, head, storageClass)).uploadId(),
                (part, progress) -> dest.uploadPart(UploadPartRequest.builder()
                                .bucket(to.bucket()).key(to.key())
                                .uploadId(task.uploadId())
                                .partNumber(part.number())
                                .contentLength(part.length())
                                .build(),
                        RequestBody.fromContentProvider(
                                () -> ctx.monitor(source.getObject(b -> b.bucket(from.bucket()).key(from.key())
                                        .ifMatch(head.eTag())
                                        .range("bytes=" + part.offset() + "-" + (part.offset() + part.length() - 1))),
                                        task, progress),
                                part.length(), "application/octet-stream")).eTag());
    }

    private static CreateMultipartUploadRequest createRequest(S3Path to, HeadObjectResponse head, String storageClass) {
        CreateMultipartUploadRequest.Builder request = CreateMultipartUploadRequest.builder()
                .bucket(to.bucket()).key(to.key())
                .contentType(head.contentType())
                .cacheControl(head.cacheControl())
                .contentEncoding(head.contentEncoding())
                .contentDisposition(head.contentDisposition())
                .contentLanguage(head.contentLanguage())
                .metadata(head.metadata());
        if (storageClass != null) {
            request.storageClass(storageClass);
        }
        return request.build();
    }

    private static String nonStandardStorageClass(HeadObjectResponse head) {
        String sc = head.storageClassAsString();
        return sc == null || sc.isEmpty() || "STANDARD".equals(sc) ? null : sc;
    }

    @Override
    public void cleanupCancelled(TransferTask task, TransferContext ctx) {
        if (task.uploadId() != null) {
            S3Path to = task.destination();
            MultipartSupport.abortQuietly(ctx.connection(task.destConnectionId()).client(), to.bucket(), to.key(),
                    task.uploadId());
            task.setUploadId(null);
        }
    }

    @Override
    public String actionDescription(TransferTask task) {
        return task.type() == TransferType.MOVE
                ? "move objects from " + task.source().bucket() + " to " + task.destination().bucket()
                : "copy objects from " + task.source().bucket() + " to " + task.destination().bucket();
    }
}
