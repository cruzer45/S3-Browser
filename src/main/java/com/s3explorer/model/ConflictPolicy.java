package com.s3explorer.model;

/** What to do when a download target already exists locally. */
public enum ConflictPolicy {
    OVERWRITE("Overwrite existing files"),
    SKIP("Skip existing files"),
    RENAME("Keep both (rename new file)");

    private final String label;

    ConflictPolicy(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
