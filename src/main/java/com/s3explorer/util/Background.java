package com.s3explorer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Runs work off the Swing Event Dispatch Thread and delivers the result back on the EDT.
 * Used for short, UI-initiated operations such as listings; long transfers go through
 * {@link com.s3explorer.service.TransferService}.
 */
public final class Background {

    private static final Logger log = LoggerFactory.getLogger(Background.class);
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "background-" + COUNTER.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private Background() {
    }

    public static <T> Future<?> run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        return POOL.submit(() -> {
            try {
                T result = work.call();
                if (onSuccess != null) {
                    SwingUtilities.invokeLater(() -> onSuccess.accept(result));
                }
            } catch (Throwable e) {
                Throwable cause = ErrorTranslator.unwrap(e);
                if (!(cause instanceof InterruptedException)) {
                    log.warn("Background operation failed: {}", cause.toString());
                    log.debug("Background failure detail", cause);
                }
                if (onError != null) {
                    SwingUtilities.invokeLater(() -> onError.accept(cause));
                }
            }
        });
    }

    public static Future<?> run(Runnable work) {
        return POOL.submit(() -> {
            try {
                work.run();
            } catch (Throwable e) {
                log.warn("Background operation failed", e);
            }
        });
    }

    /** Runs {@code r} on the EDT, immediately if already there. */
    public static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }
}
