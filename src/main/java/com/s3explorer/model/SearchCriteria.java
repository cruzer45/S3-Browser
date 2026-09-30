package com.s3explorer.model;

import com.s3explorer.util.DateTimeUtils;
import com.s3explorer.util.MimeTypeUtils;
import com.s3explorer.util.Wildcard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Filters applied while enumerating keys. S3 has no server-side search, so every criterion
 * here is evaluated client-side against listing results.
 */
public record SearchCriteria(
        String namePattern,
        String extension,
        LocalDate modifiedAfter,
        LocalDate modifiedBefore,
        Long minSize,
        Long maxSize,
        String storageClass,
        boolean recursive) {

    public Compiled compile() {
        return new Compiled(this);
    }

    public static final class Compiled {
        private final Pattern name;
        private final String ext;
        private final Instant after;
        private final Instant before;
        private final SearchCriteria c;

        private Compiled(SearchCriteria c) {
            this.c = c;
            this.name = Wildcard.compile(c.namePattern());
            String e = c.extension() == null ? "" : c.extension().trim().toLowerCase(Locale.ROOT);
            this.ext = e.startsWith(".") ? e.substring(1) : e;
            this.after = c.modifiedAfter() == null ? null : DateTimeUtils.startOfDay(c.modifiedAfter());
            // "Modified before" is exclusive of that day.
            this.before = c.modifiedBefore() == null ? null : DateTimeUtils.startOfDay(c.modifiedBefore());
        }

        public boolean matches(S3ObjectItem item) {
            if (!item.isFile()) {
                return false;
            }
            String fileName = item.name();
            if (!Wildcard.matches(name, fileName)) {
                return false;
            }
            if (!ext.isEmpty() && !MimeTypeUtils.extension(fileName).equals(ext)) {
                return false;
            }
            Instant modified = item.lastModified();
            if (after != null && (modified == null || modified.isBefore(after))) {
                return false;
            }
            if (before != null && (modified == null || !modified.isBefore(before))) {
                return false;
            }
            if (c.minSize() != null && item.size() < c.minSize()) {
                return false;
            }
            if (c.maxSize() != null && item.size() > c.maxSize()) {
                return false;
            }
            if (c.storageClass() != null && !c.storageClass().isBlank()) {
                String sc = item.storageClass() == null ? "STANDARD" : item.storageClass();
                return sc.equalsIgnoreCase(c.storageClass());
            }
            return true;
        }
    }
}
