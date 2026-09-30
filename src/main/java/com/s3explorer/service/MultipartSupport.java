package com.s3explorer.service;

import com.s3explorer.model.TransferTask;
import com.s3explorer.util.ErrorTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Runs a multipart upload (or multipart copy) with parallel parts, part-level retries and
 * resume support. The upload ID is stored on the task, so a paused or failed task continues
 * with the parts the server already has.
 */
final class MultipartSupport {

    private static final Logger log = LoggerFactory.getLogger(MultipartSupport.class);

    static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    static final int MAX_PARTS = 10_000;

    record Part(int number, long offset, long length) {
    }

    /** Uploads one part and returns its ETag. {@code progress} receives bytes sent so far. */
    @FunctionalInterface
    interface PartWorker {
        String upload(Part part, LongConsumer progress) throws Exception;
    }

    private MultipartSupport() {
    }

    static List<Part> plan(long size, long requestedPartSize) {
        long partSize = Math.max(MIN_PART_SIZE, requestedPartSize);
        partSize = Math.max(partSize, (size + MAX_PARTS - 1) / MAX_PARTS);
        List<Part> parts = new ArrayList<>();
        long offset = 0;
        int number = 1;
        while (offset < size) {
            long length = Math.min(partSize, size - offset);
            parts.add(new Part(number++, offset, length));
            offset += length;
        }
        if (parts.isEmpty()) {
            parts.add(new Part(1, 0, 0));
        }
        return parts;
    }

    static void execute(TransferTask task, TransferContext ctx, S3Client client, String bucket, String key,
                        long size, long partSize, Supplier<String> createUpload, PartWorker worker)
            throws Exception {
        List<Part> parts = plan(size, partSize);
        Map<Integer, CompletedPart> done = new ConcurrentHashMap<>();

        if (task.uploadId() != null) {
            try {
                for (software.amazon.awssdk.services.s3.model.Part existing : client.listPartsPaginator(b -> b
                        .bucket(bucket).key(key).uploadId(task.uploadId())).parts()) {
                    int n = existing.partNumber();
                    if (n >= 1 && n <= parts.size() && existing.size() != null
                            && existing.size() == parts.get(n - 1).length()) {
                        done.put(n, CompletedPart.builder().partNumber(n).eTag(existing.eTag()).build());
                    }
                }
                log.info("Resuming multipart upload of {} with {}/{} parts already uploaded",
                        key, done.size(), parts.size());
            } catch (NoSuchUploadException e) {
                task.setUploadId(null);
                done.clear();
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    throw e;
                }
                task.setUploadId(null);
                done.clear();
            }
        }
        if (task.uploadId() == null) {
            task.setUploadId(createUpload.get());
        }
        String uploadId = task.uploadId();

        long base = parts.stream().filter(p -> done.containsKey(p.number())).mapToLong(Part::length).sum();
        AtomicLong completedBytes = new AtomicLong(base);
        Map<Integer, Long> inflight = new ConcurrentHashMap<>();
        Runnable updateProgress = () -> task.setTransferred(
                completedBytes.get() + inflight.values().stream().mapToLong(Long::longValue).sum());
        updateProgress.run();

        int parallel = Math.max(1, ctx.transferSettings().parallelParts);
        Semaphore permits = new Semaphore(parallel);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();

        for (Part part : parts) {
            if (done.containsKey(part.number())) {
                continue;
            }
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure.compareAndSet(null, new TransferSignal(TransferSignal.Kind.PAUSED));
                break;
            }
            if (failure.get() != null) {
                permits.release();
                break;
            }
            try {
                ctx.checkpoint(task);
            } catch (TransferSignal s) {
                failure.compareAndSet(null, s);
                permits.release();
                break;
            }
            futures.add(ctx.partExecutor().submit(() -> {
                try {
                    String eTag = uploadWithRetry(task, ctx, part, worker, bytes -> {
                        inflight.put(part.number(), bytes);
                        updateProgress.run();
                    });
                    done.put(part.number(), CompletedPart.builder().partNumber(part.number()).eTag(eTag).build());
                    inflight.remove(part.number());
                    completedBytes.addAndGet(part.length());
                    updateProgress.run();
                } catch (Throwable t) {
                    inflight.remove(part.number());
                    updateProgress.run();
                    failure.compareAndSet(null, t);
                } finally {
                    permits.release();
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException ignored) {
                // Failures are recorded in 'failure'.
            }
        }
        Throwable error = failure.get();
        if (error != null) {
            if (error instanceof Exception e) {
                throw e;
            }
            throw new RuntimeException(error);
        }

        List<CompletedPart> completed = new ArrayList<>(done.values());
        completed.sort(Comparator.comparingInt(CompletedPart::partNumber));
        client.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload(m -> m.parts(completed)));
        task.setUploadId(null);
        task.setTransferred(size);
    }

    private static String uploadWithRetry(TransferTask task, TransferContext ctx, Part part, PartWorker worker,
                                          LongConsumer progress) throws Exception {
        int maxRetries = Math.max(0, ctx.transferSettings().retryCount);
        for (int attempt = 0; ; attempt++) {
            ctx.checkpoint(task);
            try {
                return worker.upload(part, progress);
            } catch (TransferSignal s) {
                throw s;
            } catch (Exception e) {
                ctx.checkpoint(task);
                if (attempt >= maxRetries || !ErrorTranslator.isRetryable(e)) {
                    throw e;
                }
                progress.accept(0);
                log.warn("Part {} of {} failed ({}); retrying {}/{}", part.number(), task.displayName(),
                        e.toString(), attempt + 1, maxRetries);
                Thread.sleep(Math.max(0, ctx.transferSettings().retryDelaySeconds) * 1000L * (attempt + 1));
            }
        }
    }

    static void abortQuietly(S3Client client, String bucket, String key, String uploadId) {
        if (uploadId == null) {
            return;
        }
        try {
            client.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId));
            log.info("Aborted multipart upload for {}", key);
        } catch (RuntimeException e) {
            log.warn("Could not abort multipart upload for {}: {}", key, e.toString());
        }
    }
}
