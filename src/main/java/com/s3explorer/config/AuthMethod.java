package com.s3explorer.config;

/** How a connection obtains credentials. */
public enum AuthMethod {
    DEFAULT_CHAIN("AWS default credential chain"),
    PROFILE("AWS profile (~/.aws)"),
    ENVIRONMENT("Environment variables"),
    ACCESS_KEY("Access key and secret key"),
    ANONYMOUS("Anonymous (public buckets)");

    private final String label;

    AuthMethod(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
