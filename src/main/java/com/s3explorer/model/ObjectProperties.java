package com.s3explorer.model;

import java.time.Instant;
import java.util.Map;

/** Everything HeadObject reports about an object, plus derived URLs. */
public record ObjectProperties(
        S3Path path,
        String objectUrl,
        long size,
        Instant lastModified,
        String eTag,
        String contentType,
        String contentEncoding,
        String contentDisposition,
        String contentLanguage,
        String cacheControl,
        String storageClass,
        String versionId,
        String serverSideEncryption,
        String kmsKeyId,
        Map<String, String> metadata) {
}
