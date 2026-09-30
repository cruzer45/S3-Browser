package com.s3explorer.service;

import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.MetadataEdit;
import com.s3explorer.model.S3Path;
import com.s3explorer.util.UserFacingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Edits object metadata. S3 metadata is immutable, so this copies the object onto itself
 * with {@code MetadataDirective.REPLACE}, preserving storage class and KMS encryption.
 */
public class MetadataService {

    private static final Logger log = LoggerFactory.getLogger(MetadataService.class);
    private static final Pattern META_KEY = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]+");

    public void update(S3Connection connection, S3Path path, MetadataEdit edit) {
        HeadObjectResponse head = connection.client().headObject(b -> b.bucket(path.bucket()).key(path.key()));
        long size = head.contentLength() == null ? 0 : head.contentLength();
        if (size > CopyService.MAX_SINGLE_COPY) {
            throw new UserFacingException("Object too large.",
                    "Metadata can only be edited on objects up to 5 GB.");
        }
        Map<String, String> userMetadata = new LinkedHashMap<>();
        edit.userMetadata().forEach((k, v) -> {
            String key = k == null ? "" : k.trim().toLowerCase(Locale.ROOT);
            if (key.startsWith("x-amz-meta-")) {
                key = key.substring("x-amz-meta-".length());
            }
            if (key.isEmpty()) {
                return;
            }
            if (!META_KEY.matcher(key).matches()) {
                throw new UserFacingException("Invalid metadata key.",
                        "\"" + k + "\" is not a valid metadata key. Use letters, digits and hyphens.");
            }
            userMetadata.put(key, v == null ? "" : v);
        });

        CopyObjectRequest.Builder request = CopyObjectRequest.builder()
                .sourceBucket(path.bucket()).sourceKey(path.key())
                .destinationBucket(path.bucket()).destinationKey(path.key())
                .metadataDirective(MetadataDirective.REPLACE)
                .contentType(blankToNull(edit.contentType()))
                .contentEncoding(blankToNull(edit.contentEncoding()))
                .contentDisposition(blankToNull(edit.contentDisposition()))
                .contentLanguage(blankToNull(edit.contentLanguage()))
                .cacheControl(blankToNull(edit.cacheControl()))
                .metadata(userMetadata);
        String storageClass = head.storageClassAsString();
        if (storageClass != null && !storageClass.isEmpty() && !"STANDARD".equals(storageClass)) {
            request.storageClass(storageClass);
        }
        if ("aws:kms".equals(head.serverSideEncryptionAsString()) && head.ssekmsKeyId() != null) {
            request.serverSideEncryption(head.serverSideEncryptionAsString()).ssekmsKeyId(head.ssekmsKeyId());
        }
        connection.client().copyObject(request.build());
        log.info("Updated metadata of {}", path.toUri());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
