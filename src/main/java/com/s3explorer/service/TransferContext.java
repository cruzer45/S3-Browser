package com.s3explorer.service;

import com.s3explorer.aws.S3Connection;
import com.s3explorer.config.AppSettings;
import com.s3explorer.config.SettingsStore;
import com.s3explorer.model.TransferTask;
import com.s3explorer.util.RateLimiter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutorService;

/** Shared facilities handed to task handlers by the {@link TransferService}. */
public final class TransferContext {

    public static final int BUFFER_SIZE = 64 * 1024;

    private final ConnectionService connections;
    private final SettingsStore settings;
    private final RateLimiter rateLimiter;
    private final ExecutorService partExecutor;

    TransferContext(ConnectionService connections, SettingsStore settings, RateLimiter rateLimiter,
                    ExecutorService partExecutor) {
        this.connections = connections;
        this.settings = settings;
        this.rateLimiter = rateLimiter;
        this.partExecutor = partExecutor;
    }

    public S3Connection connection(String id) {
        return connections.get(id);
    }

    public AppSettings.Transfers transferSettings() {
        return settings.get().transfers;
    }

    public ExecutorService partExecutor() {
        return partExecutor;
    }

    /** Throws a {@link TransferSignal} if the user paused or cancelled the task. */
    public void checkpoint(TransferTask task) {
        if (task.cancelRequested()) {
            throw new TransferSignal(TransferSignal.Kind.CANCELLED);
        }
        if (task.pauseRequested()) {
            throw new TransferSignal(TransferSignal.Kind.PAUSED);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new TransferSignal(TransferSignal.Kind.PAUSED);
        }
    }

    public void throttle(long bytes) throws InterruptedIOException {
        try {
            rateLimiter.acquire(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while throttled");
        }
    }

    /** A stream wrapper that throttles, honours pause/cancel and reports bytes read so far. */
    public InputStream monitor(InputStream in, TransferTask task, java.util.function.LongConsumer bytesSoFar) {
        return new MonitoredInputStream(in, task, bytesSoFar);
    }

    private final class MonitoredInputStream extends InputStream {
        private final InputStream in;
        private final TransferTask task;
        private final java.util.function.LongConsumer bytesSoFar;
        private long count;

        MonitoredInputStream(InputStream in, TransferTask task, java.util.function.LongConsumer bytesSoFar) {
            this.in = in;
            this.task = task;
            this.bytesSoFar = bytesSoFar;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            checkpoint(task);
            int n = in.read(b, off, Math.min(len, BUFFER_SIZE));
            if (n > 0) {
                throttle(n);
                count += n;
                bytesSoFar.accept(count);
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
