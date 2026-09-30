package com.s3explorer.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A location in S3. Keys ending in {@code /} (and the empty key, the bucket root) are
 * treated as folders; S3 itself has no directories, so this is purely a naming convention.
 */
public record S3Path(String bucket, String key) {

    public S3Path {
        Objects.requireNonNull(bucket, "bucket");
        if (bucket.isEmpty()) {
            throw new IllegalArgumentException("bucket must not be empty");
        }
        key = key == null ? "" : key;
    }

    public static S3Path root(String bucket) {
        return new S3Path(bucket, "");
    }

    public static S3Path folder(String bucket, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return root(bucket);
        }
        return new S3Path(bucket, prefix.endsWith("/") ? prefix : prefix + "/");
    }

    public static S3Path object(String bucket, String key) {
        return new S3Path(bucket, key);
    }

    /** Parses {@code s3://bucket/key}, {@code /bucket/key} or {@code bucket/key}. */
    public static S3Path parse(String text) {
        String s = text == null ? "" : text.trim();
        if (s.regionMatches(true, 0, "s3://", 0, 5)) {
            s = s.substring(5);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("No bucket in path: " + text);
        }
        int slash = s.indexOf('/');
        return slash < 0 ? root(s) : new S3Path(s.substring(0, slash), s.substring(slash + 1));
    }

    public boolean isDirectory() {
        return key.isEmpty() || key.endsWith("/");
    }

    public boolean isBucketRoot() {
        return key.isEmpty();
    }

    /** The last path segment without trailing slash; the bucket name for the root. */
    public String name() {
        if (key.isEmpty()) {
            return bucket;
        }
        String trimmed = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    /** The containing folder, or null for the bucket root. */
    public S3Path parent() {
        if (key.isEmpty()) {
            return null;
        }
        String trimmed = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? root(bucket) : new S3Path(bucket, trimmed.substring(0, slash + 1));
    }

    /** The folder prefix used for listing: the key itself for folders, the parent for objects. */
    public String prefix() {
        if (isDirectory()) {
            return key;
        }
        S3Path parent = parent();
        return parent == null ? "" : parent.key();
    }

    public S3Path resolveFolder(String name) {
        requireDirectory();
        return folder(bucket, key + stripSlashes(name));
    }

    public S3Path resolveObject(String name) {
        requireDirectory();
        return object(bucket, key + name);
    }

    /** Ancestors from the bucket root down to and including this path. */
    public List<S3Path> ancestry() {
        List<S3Path> chain = new ArrayList<>();
        for (S3Path p = this; p != null; p = p.parent()) {
            chain.add(0, p);
        }
        return chain;
    }

    public String toUri() {
        return "s3://" + bucket + "/" + key;
    }

    private void requireDirectory() {
        if (!isDirectory()) {
            throw new IllegalStateException("Not a folder: " + toUri());
        }
    }

    private static String stripSlashes(String name) {
        String n = name;
        while (n.endsWith("/")) {
            n = n.substring(0, n.length() - 1);
        }
        return n;
    }

    @Override
    public String toString() {
        return toUri();
    }
}
