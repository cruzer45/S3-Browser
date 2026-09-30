package com.s3explorer.aws;

import com.s3explorer.config.AppSettings;
import com.s3explorer.config.AuthMethod;
import com.s3explorer.config.ConnectionProfile;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.profiles.ProfileProperty;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

/** Creates SDK clients from connection profiles. */
public final class S3ClientFactory {

    private S3ClientFactory() {
    }

    public static S3Connection create(ConnectionProfile profile, AppSettings.Transfers transferSettings) {
        AwsCredentialsProvider credentials = CredentialProviderFactory.create(profile);
        Region region = resolveRegion(profile);
        URI endpoint = endpointUri(profile);
        boolean aws = profile.isAwsEndpoint();

        int connectTimeout = profile.connectTimeoutSeconds > 0
                ? profile.connectTimeoutSeconds : transferSettings.connectTimeoutSeconds;
        int socketTimeout = profile.requestTimeoutSeconds > 0
                ? profile.requestTimeoutSeconds : transferSettings.requestTimeoutSeconds;
        int maxConnections = Math.max(50,
                transferSettings.concurrentTransfers * Math.max(1, transferSettings.parallelParts) * 2 + 10);

        S3ClientBuilder builder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentials)
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(Math.max(1, connectTimeout)))
                        .socketTimeout(Duration.ofSeconds(Math.max(1, socketTimeout)))
                        .maxConnections(maxConnections))
                .forcePathStyle(profile.pathStyleAccess);
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        if (aws) {
            // Follow redirects to buckets in other regions transparently.
            builder.crossRegionAccessEnabled(true);
        } else {
            // Many S3-compatible services reject the newer default CRC checksum headers.
            builder.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        }
        S3Client client = builder.build();
        return new S3Connection(profile.copy(), client, credentials, region, endpoint);
    }

    static S3Presigner presigner(ConnectionProfile profile, AwsCredentialsProvider credentials, Region region,
                                 URI endpoint) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(profile.pathStyleAccess)
                        .build());
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        return builder.build();
    }

    static Region resolveRegion(ConnectionProfile profile) {
        if (profile.region != null && !profile.region.isBlank()) {
            return Region.of(profile.region.trim());
        }
        if (profile.authMethod == AuthMethod.PROFILE && profile.awsProfile != null && !profile.awsProfile.isBlank()) {
            try {
                String fromProfile = ProfileFile.defaultProfileFile().profile(profile.awsProfile.trim())
                        .flatMap(p -> p.property(ProfileProperty.REGION))
                        .orElse(null);
                if (fromProfile != null) {
                    return Region.of(fromProfile);
                }
            } catch (RuntimeException ignored) {
                // Fall back to us-east-1.
            }
        }
        return Region.US_EAST_1;
    }

    static URI endpointUri(ConnectionProfile profile) {
        if (profile.endpoint == null || profile.endpoint.isBlank()) {
            return null;
        }
        String text = profile.endpoint.trim();
        if (!text.contains("://")) {
            text = "https://" + text;
        }
        URI uri = URI.create(text);
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid endpoint URL: " + profile.endpoint);
        }
        return uri;
    }
}
