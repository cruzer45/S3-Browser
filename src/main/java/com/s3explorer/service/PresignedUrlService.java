package com.s3explorer.service;

import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.S3Path;
import com.s3explorer.util.UserFacingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.time.Duration;
import java.time.Instant;

/** Generates presigned GET URLs. URLs are returned to the caller and never logged or stored. */
public class PresignedUrlService {

    private static final Logger log = LoggerFactory.getLogger(PresignedUrlService.class);
    /** SigV4 presigned URLs are valid for at most seven days. */
    public static final Duration MAX_DURATION = Duration.ofDays(7);

    private final S3BrowserService browser;

    public PresignedUrlService(S3BrowserService browser) {
        this.browser = browser;
    }

    public record PresignedUrl(String url, Instant expiresAt) {
        @Override
        public String toString() {
            return "PresignedUrl[expiresAt=" + expiresAt + "]";
        }
    }

    public PresignedUrl presign(S3Connection connection, S3Path path, Duration duration) {
        if (path.isDirectory()) {
            throw new UserFacingException("Cannot presign a folder.", "Presigned URLs can only be created for files.");
        }
        if (duration.isZero() || duration.isNegative() || duration.compareTo(MAX_DURATION) > 0) {
            throw new UserFacingException("Invalid expiration.", "The expiration must be between 1 minute and 7 days.");
        }
        String region = connection.isAws() ? browser.bucketRegion(connection, path.bucket()) : null;
        PresignedGetObjectRequest presigned = connection.presigner(region).presignGetObject(
                GetObjectPresignRequest.builder()
                        .signatureDuration(duration)
                        .getObjectRequest(b -> b.bucket(path.bucket()).key(path.key()))
                        .build());
        // Log the fact, never the URL.
        log.info("Generated presigned URL for {} valid for {}", path.toUri(), duration);
        return new PresignedUrl(presigned.url().toExternalForm(), presigned.expiration());
    }
}
