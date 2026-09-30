package com.s3explorer.util;

import java.util.Locale;

/** Formats byte counts for display using binary (1024) units. */
public final class FileSizeFormatter {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB", "EB"};

    private FileSizeFormatter() {
    }

    public static String format(long bytes) {
        if (bytes < 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        String pattern = value >= 100 ? "%.0f %s" : "%.1f %s";
        return String.format(Locale.ROOT, pattern, value, UNITS[unit]);
    }

    public static String formatRate(double bytesPerSecond) {
        if (bytesPerSecond <= 0 || Double.isNaN(bytesPerSecond)) {
            return "";
        }
        return format((long) bytesPerSecond) + "/s";
    }
}
