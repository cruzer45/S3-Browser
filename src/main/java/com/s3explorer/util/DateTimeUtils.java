package com.s3explorer.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Date, time and duration formatting for the UI. */
public final class DateTimeUtils {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private DateTimeUtils() {
    }

    public static String format(Instant instant) {
        return instant == null ? "" : DATE_TIME.format(instant);
    }

    public static String formatPrecise(Instant instant) {
        return instant == null ? "" : DATE_TIME_SECONDS.format(instant);
    }

    /** Formats a duration as h:mm:ss or m:ss. */
    public static String formatDuration(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return "";
        }
        long seconds = duration.toSeconds();
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    public static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
