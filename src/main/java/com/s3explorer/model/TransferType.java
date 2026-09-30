package com.s3explorer.model;

public enum TransferType {
    UPLOAD("Upload"),
    DOWNLOAD("Download"),
    COPY("Copy"),
    MOVE("Move"),
    DELETE("Delete");

    private final String label;

    TransferType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
