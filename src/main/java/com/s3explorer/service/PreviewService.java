package com.s3explorer.service;

import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.S3Path;
import com.s3explorer.util.MimeTypeUtils;
import com.s3explorer.util.UserFacingException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * Fetches object content for preview, never more than the configured limit. Text previews
 * of larger files are truncated with a ranged GET; images over the limit are refused.
 */
public class PreviewService {

    public record PreviewData(S3Path path, byte[] bytes, long totalSize, boolean truncated, String contentType) {
    }

    public PreviewData fetch(S3Connection connection, S3Path path, long maxBytes) {
        String name = path.name();
        if (!MimeTypeUtils.isPreviewable(name)) {
            throw new UserFacingException("No preview available.", "Preview is not supported for this file type.");
        }
        HeadObjectResponse head = connection.client().headObject(b -> b.bucket(path.bucket()).key(path.key()));
        long size = head.contentLength() == null ? 0 : head.contentLength();
        boolean image = MimeTypeUtils.isImagePreviewable(name);
        if (image && size > maxBytes) {
            throw new UserFacingException("File too large to preview.",
                    "Images larger than the preview limit are not downloaded automatically. "
                            + "Increase the limit in Settings or download the file.");
        }
        boolean truncated = size > maxBytes;
        ResponseBytes<GetObjectResponse> bytes = connection.client().getObjectAsBytes(b -> {
            b.bucket(path.bucket()).key(path.key());
            if (truncated) {
                b.range("bytes=0-" + (maxBytes - 1));
            }
        });
        return new PreviewData(path, bytes.asByteArray(), size, truncated, head.contentType());
    }
}
