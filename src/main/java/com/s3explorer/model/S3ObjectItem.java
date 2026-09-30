package com.s3explorer.model;

import com.s3explorer.util.MimeTypeUtils;

import java.time.Instant;

/** A row in the object browser: a bucket, folder, file, or the ".." parent entry. */
public record S3ObjectItem(
        Kind kind,
        S3Path path,
        long size,
        Instant lastModified,
        String eTag,
        String storageClass) {

    public enum Kind {
        PARENT, BUCKET, FOLDER, FILE
    }

    public static S3ObjectItem parent(S3Path path) {
        return new S3ObjectItem(Kind.PARENT, path, -1, null, null, null);
    }

    public static S3ObjectItem bucket(S3BucketInfo info) {
        return new S3ObjectItem(Kind.BUCKET, S3Path.root(info.name()), -1, info.creationDate(), null, null);
    }

    public static S3ObjectItem folder(S3Path path) {
        return new S3ObjectItem(Kind.FOLDER, path, -1, null, null, null);
    }

    public static S3ObjectItem file(S3Path path, long size, Instant lastModified, String eTag, String storageClass) {
        return new S3ObjectItem(Kind.FILE, path, size, lastModified, eTag, storageClass);
    }

    public String name() {
        return kind == Kind.PARENT ? ".." : path.name();
    }

    public boolean isFile() {
        return kind == Kind.FILE;
    }

    /** Folders and buckets: things you can navigate into. */
    public boolean isContainer() {
        return kind == Kind.FOLDER || kind == Kind.BUCKET;
    }

    public boolean isParent() {
        return kind == Kind.PARENT;
    }

    public String typeDescription() {
        return switch (kind) {
            case PARENT -> "";
            case BUCKET -> "Bucket";
            case FOLDER -> "Folder";
            case FILE -> MimeTypeUtils.describe(path.key());
        };
    }

    /** The ETag without the surrounding quotes S3 returns. */
    public String cleanETag() {
        return eTag == null ? "" : eTag.replace("\"", "");
    }
}
