package com.s3explorer.aws;

import com.s3explorer.config.ConnectionProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** An open connection: the SDK client plus per-region presigners and a bucket region cache. */
public final class S3Connection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3Connection.class);

    private final ConnectionProfile profile;
    private final S3Client client;
    private final AwsCredentialsProvider credentials;
    private final Region region;
    private final URI endpoint;
    private final Map<String, S3Presigner> presigners = new ConcurrentHashMap<>();
    private final Map<String, String> bucketRegions = new ConcurrentHashMap<>();

    S3Connection(ConnectionProfile profile, S3Client client, AwsCredentialsProvider credentials, Region region,
                 URI endpoint) {
        this.profile = profile;
        this.client = client;
        this.credentials = credentials;
        this.region = region;
        this.endpoint = endpoint;
    }

    public String id() {
        return profile.id;
    }

    public ConnectionProfile profile() {
        return profile;
    }

    public S3Client client() {
        return client;
    }

    public Region region() {
        return region;
    }

    public boolean isAws() {
        return profile.isAwsEndpoint();
    }

    public Map<String, String> bucketRegions() {
        return bucketRegions;
    }

    public S3Presigner presigner(String regionName) {
        String key = regionName == null || regionName.isBlank() ? region.id() : regionName;
        return presigners.computeIfAbsent(key,
                r -> S3ClientFactory.presigner(profile, credentials, Region.of(r), endpoint));
    }

    @Override
    public void close() {
        try {
            client.close();
            presigners.values().forEach(S3Presigner::close);
            if (credentials instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception e) {
            log.debug("Error closing connection {}", profile.name, e);
        }
    }
}
