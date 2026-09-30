package com.s3explorer.service;

import com.s3explorer.config.AppSettings;
import com.s3explorer.config.SettingsStore;
import com.s3explorer.model.TransferStatus;
import com.s3explorer.model.TransferTask;
import com.s3explorer.model.TransferType;
import com.s3explorer.util.ErrorTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The shared transfer queue for uploads, downloads, copies, moves and deletes. Tasks run on
 * a bounded worker pool (concurrent file transfers); multipart parts run on a separate pool
 * so a waiting task never starves its own parts.
 */
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    public interface Listener {
        void tasksAdded(List<TransferTask> tasks);

        void tasksRemoved(List<TransferTask> tasks);

        /** A task changed status (not fired for every progress update). */
        void taskChanged(TransferTask task);
    }

    private final SettingsStore settings;
    private final TransferStore store;
    private final List<TransferTask> tasks = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<TransferType, TaskHandler> handlers = new EnumMap<>(TransferType.class);
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor parts;
    private final ExecutorService housekeeping;
    private final com.s3explorer.util.RateLimiter rateLimiter;
    private final TransferContext context;

    public TransferService(SettingsStore settings, ConnectionService connections, TransferStore store) {
        this.settings = settings;
        this.store = store;
        AppSettings.Transfers t = settings.get().transfers;
        int workerCount = Math.max(1, t.concurrentTransfers);
        this.workers = new ThreadPoolExecutor(workerCount, workerCount, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), named("transfer"));
        int partCount = partPoolSize(t);
        this.parts = new ThreadPoolExecutor(partCount, partCount, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), named("transfer-part"));
        this.housekeeping = Executors.newSingleThreadExecutor(named("transfer-cleanup"));
        this.rateLimiter = new com.s3explorer.util.RateLimiter(t.bandwidthLimitKbps * 1024);
        this.context = new TransferContext(connections, settings, rateLimiter, parts);

        handlers.put(TransferType.UPLOAD, new UploadService());
        handlers.put(TransferType.DOWNLOAD, new DownloadService());
        CopyService copy = new CopyService();
        handlers.put(TransferType.COPY, copy);
        handlers.put(TransferType.MOVE, copy);
        handlers.put(TransferType.DELETE, new DeleteService());
    }

    private static int partPoolSize(AppSettings.Transfers t) {
        return Math.max(2, Math.max(1, t.concurrentTransfers) * Math.max(1, t.parallelParts));
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread thread = new Thread(r, prefix + "-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public List<TransferTask> tasks() {
        return List.copyOf(tasks);
    }

    /** Re-applies concurrency and bandwidth settings. */
    public void applySettings() {
        AppSettings.Transfers t = settings.get().transfers;
        resize(workers, Math.max(1, t.concurrentTransfers));
        resize(parts, partPoolSize(t));
        rateLimiter.setBytesPerSecond(t.bandwidthLimitKbps * 1024);
    }

    private static void resize(ThreadPoolExecutor pool, int size) {
        if (size > pool.getMaximumPoolSize()) {
            pool.setMaximumPoolSize(size);
            pool.setCorePoolSize(size);
        } else {
            pool.setCorePoolSize(size);
            pool.setMaximumPoolSize(size);
        }
    }

    public void submit(List<TransferTask> newTasks) {
        if (newTasks.isEmpty()) {
            return;
        }
        tasks.addAll(newTasks);
        listeners.forEach(l -> l.tasksAdded(newTasks));
        for (TransferTask task : newTasks) {
            if (task.status() == TransferStatus.QUEUED) {
                schedule(task);
            }
        }
    }

    /** Adds tasks restored from a previous session without starting them. */
    public void restore(List<TransferTask> restored) {
        if (restored.isEmpty()) {
            return;
        }
        tasks.addAll(restored);
        listeners.forEach(l -> l.tasksAdded(restored));
    }

    private void schedule(TransferTask task) {
        workers.execute(() -> run(task));
    }

    private void run(TransferTask task) {
        if (!task.tryStart()) {
            return;
        }
        changed(task);
        TaskHandler handler = handlers.get(task.type());
        int maxRetries = Math.max(0, settings.get().transfers.retryCount);
        int attempt = 0;
        while (true) {
            try {
                task.setStatus(TransferStatus.RUNNING);
                changed(task);
                if (attempt > 0) {
                    task.setMessage(null);
                }
                handler.execute(task, context);
                task.setStatus(TransferStatus.COMPLETED);
                break;
            } catch (Throwable e) {
                if (task.cancelRequested() || isSignal(e, TransferSignal.Kind.CANCELLED)) {
                    cleanup(handler, task);
                    task.setStatus(TransferStatus.CANCELLED);
                    log.info("Cancelled {} {}", task.type(), task.displayName());
                    break;
                }
                if (task.pauseRequested() || isSignal(e, TransferSignal.Kind.PAUSED)) {
                    task.setStatus(TransferStatus.PAUSED);
                    log.info("Paused {} {}", task.type(), task.displayName());
                    break;
                }
                if (attempt < maxRetries && ErrorTranslator.isRetryable(e)) {
                    attempt++;
                    task.setMessage("Retrying (" + attempt + "/" + maxRetries + ")");
                    log.warn("{} of {} failed ({}); retrying {}/{}", task.type(), task.displayName(), e.toString(),
                            attempt, maxRetries);
                    // If paused or cancelled during the wait, the handler's first checkpoint stops it.
                    sleepUnlessStopped(task, settings.get().transfers.retryDelaySeconds * 1000L * attempt);
                    continue;
                }
                ErrorTranslator.UserMessage message = ErrorTranslator.translate(e, handler.actionDescription(task));
                task.setMessage(message.title() + " " + message.message());
                task.setStatus(TransferStatus.FAILED);
                log.error("{} of {} failed: {}", task.type(), task.displayName(), message.title(), e);
                break;
            }
        }
        changed(task);
    }

    private static boolean isSignal(Throwable e, TransferSignal.Kind kind) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof TransferSignal s && s.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    /** Sleeps in short slices; returns false early if the task was paused or cancelled. */
    private static boolean sleepUnlessStopped(TransferTask task, long millis) {
        long end = System.currentTimeMillis() + Math.max(0, millis);
        while (System.currentTimeMillis() < end) {
            if (task.pauseRequested() || task.cancelRequested()) {
                return false;
            }
            try {
                Thread.sleep(Math.min(200, end - System.currentTimeMillis() + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                task.requestPause();
                return false;
            }
        }
        return true;
    }

    private void cleanup(TaskHandler handler, TransferTask task) {
        try {
            handler.cleanupCancelled(task, context);
        } catch (RuntimeException e) {
            log.warn("Cleanup after cancelling {} failed: {}", task.displayName(), e.toString());
        }
    }

    private void changed(TransferTask task) {
        listeners.forEach(l -> l.taskChanged(task));
    }

    // --- user controls ------------------------------------------------------------------

    public void pause(TransferTask task) {
        synchronized (task) {
            switch (task.status()) {
                case QUEUED -> task.setStatus(TransferStatus.PAUSED);
                case STARTING, RUNNING -> task.requestPause();
                default -> {
                    return;
                }
            }
        }
        changed(task);
    }

    public void resume(TransferTask task) {
        synchronized (task) {
            if (task.status() != TransferStatus.PAUSED && task.status() != TransferStatus.FAILED) {
                return;
            }
            task.requeue();
        }
        changed(task);
        schedule(task);
    }

    /** Restarts a failed or cancelled task; failed tasks keep their resume state. */
    public void retry(TransferTask task) {
        synchronized (task) {
            TransferStatus s = task.status();
            if (s != TransferStatus.FAILED && s != TransferStatus.CANCELLED && s != TransferStatus.PAUSED) {
                return;
            }
            if (s == TransferStatus.CANCELLED) {
                task.resetProgress();
            }
            task.requeue();
        }
        changed(task);
        schedule(task);
    }

    public void cancel(TransferTask task) {
        boolean cleanupNeeded;
        synchronized (task) {
            TransferStatus s = task.status();
            if (s.isActive()) {
                task.requestCancel();
                return;
            }
            if (s == TransferStatus.COMPLETED || s == TransferStatus.CANCELLED) {
                return;
            }
            cleanupNeeded = s == TransferStatus.PAUSED || s == TransferStatus.FAILED;
            task.setStatus(TransferStatus.CANCELLED);
        }
        changed(task);
        if (cleanupNeeded) {
            TaskHandler handler = handlers.get(task.type());
            housekeeping.execute(() -> cleanup(handler, task));
        }
    }

    public void remove(List<TransferTask> toRemove) {
        List<TransferTask> removed = new ArrayList<>();
        for (TransferTask task : toRemove) {
            if (task.status().isActive()) {
                continue;
            }
            if (task.status() == TransferStatus.QUEUED || task.status() == TransferStatus.PAUSED
                    || task.status() == TransferStatus.FAILED) {
                cancel(task);
            }
            if (tasks.remove(task)) {
                removed.add(task);
            }
        }
        if (!removed.isEmpty()) {
            listeners.forEach(l -> l.tasksRemoved(removed));
        }
    }

    public void clearCompleted() {
        remove(tasks.stream().filter(t -> t.status() == TransferStatus.COMPLETED
                || t.status() == TransferStatus.CANCELLED).toList());
    }

    public void pauseAll() {
        tasks.forEach(this::pause);
    }

    public void resumeAll() {
        tasks.stream().filter(t -> t.status() == TransferStatus.PAUSED).forEach(this::resume);
    }

    public long activeCount() {
        return tasks.stream().filter(t -> t.status().isActive()).count();
    }

    public long pendingCount() {
        return tasks.stream().filter(t -> t.status() == TransferStatus.QUEUED || t.status().isActive()).count();
    }

    /**
     * Pauses everything, waits briefly for workers to stop, and persists unfinished tasks
     * so they can be resumed on the next launch.
     */
    public void shutdown(boolean persist) {
        tasks.forEach(t -> {
            if (t.status() == TransferStatus.QUEUED) {
                t.setStatus(TransferStatus.PAUSED);
            } else if (t.status().isActive()) {
                t.requestPause();
            }
        });
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Transfers did not stop within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        workers.shutdownNow();
        parts.shutdownNow();
        housekeeping.shutdown();
        if (persist) {
            store.save(tasks);
        } else {
            store.save(List.of());
        }
    }

    public List<TransferTask> loadPersisted() {
        return store.load();
    }
}
