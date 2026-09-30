package com.s3explorer.model;

public enum TransferStatus {
    QUEUED("Queued"),
    STARTING("Starting"),
    RUNNING("Running"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled");

    private final String label;

    TransferStatus(String label) {
        this.label = label;
    }

    public boolean isActive() {
        return this == STARTING || this == RUNNING;
    }

    public boolean isFinished() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    @Override
    public String toString() {
        return label;
    }
}
