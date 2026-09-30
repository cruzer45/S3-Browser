package com.s3explorer.config;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A saved S3 connection. Secret material (secret key, session token) is never serialised
 * with the profile; see {@link ConnectionStore} for how it is kept.
 */
public class ConnectionProfile {

    public String id = UUID.randomUUID().toString();
    public String name = "New connection";
    /** Empty for AWS; e.g. {@code http://localhost:9000} for MinIO. */
    public String endpoint = "";
    public String region = "us-east-1";
    public AuthMethod authMethod = AuthMethod.DEFAULT_CHAIN;
    public String awsProfile = "";
    public String accessKeyId = "";
    public boolean rememberSecret = false;
    public boolean pathStyleAccess = false;
    /** 0 uses the application-wide setting. */
    public int connectTimeoutSeconds = 0;
    /** 0 uses the application-wide setting. */
    public int requestTimeoutSeconds = 0;
    /** Buckets to show even when the credentials cannot list all buckets. */
    public List<String> pinnedBuckets = new ArrayList<>();

    @JsonIgnore
    public transient String secretAccessKey;
    @JsonIgnore
    public transient String sessionToken;

    @JsonIgnore
    public boolean isAwsEndpoint() {
        if (endpoint == null || endpoint.isBlank()) {
            return true;
        }
        try {
            String host = URI.create(endpoint.trim()).getHost();
            return host != null && host.toLowerCase(Locale.ROOT).endsWith(".amazonaws.com");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @JsonIgnore
    public boolean hasSecret() {
        return secretAccessKey != null && !secretAccessKey.isEmpty();
    }

    public ConnectionProfile copy() {
        ConnectionProfile c = new ConnectionProfile();
        c.id = id;
        c.name = name;
        c.endpoint = endpoint;
        c.region = region;
        c.authMethod = authMethod;
        c.awsProfile = awsProfile;
        c.accessKeyId = accessKeyId;
        c.rememberSecret = rememberSecret;
        c.pathStyleAccess = pathStyleAccess;
        c.connectTimeoutSeconds = connectTimeoutSeconds;
        c.requestTimeoutSeconds = requestTimeoutSeconds;
        c.pinnedBuckets = new ArrayList<>(pinnedBuckets == null ? List.of() : pinnedBuckets);
        c.secretAccessKey = secretAccessKey;
        c.sessionToken = sessionToken;
        return c;
    }

    /** Never includes secrets. */
    @Override
    public String toString() {
        return name;
    }
}
