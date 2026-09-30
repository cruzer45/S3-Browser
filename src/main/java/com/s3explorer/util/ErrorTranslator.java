package com.s3explorer.util;

import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.net.ssl.SSLException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Converts exceptions into short, user-facing messages. The raw exception is still logged by
 * the caller; nothing here should ever include credentials or presigned URLs.
 */
public final class ErrorTranslator {

    public record UserMessage(String title, String message) {
        public String full() {
            return title + "\n\n" + message;
        }
    }

    private static final Set<String> AUTH_CODES = Set.of(
            "InvalidAccessKeyId", "SignatureDoesNotMatch", "InvalidToken", "InvalidSecurity",
            "AuthorizationHeaderMalformed_Credentials", "UnrecognizedClientException");
    private static final Set<String> EXPIRED_CODES = Set.of(
            "ExpiredToken", "ExpiredTokenException", "TokenRefreshRequired", "RequestExpired");
    private static final Set<String> QUOTA_CODES = Set.of(
            "QuotaExceeded", "XMinioStorageFull", "StorageFull", "InsufficientStorage",
            "TooManyBuckets", "StorageQuotaExceeded", "QuotaExceededError");
    private static final Set<String> THROTTLE_CODES = Set.of(
            "SlowDown", "Throttling", "ThrottlingException", "RequestLimitExceeded", "TooManyRequests");

    private ErrorTranslator() {
    }

    /**
     * @param action what the user was trying to do, phrased to follow "permission to",
     *               e.g. "delete objects from this bucket". May be null.
     */
    public static UserMessage translate(Throwable error, String action) {
        Throwable t = unwrap(error);
        String permission = action == null || action.isBlank() ? "perform this operation" : action;

        if (t instanceof UserFacingException ufe) {
            return new UserMessage(ufe.title(), ufe.getMessage());
        }
        if (t instanceof S3Exception s3) {
            return translateS3(s3, permission);
        }

        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof S3Exception s3) {
                return translateS3(s3, permission);
            }
            if (c instanceof UnknownHostException uh) {
                return new UserMessage("Host not found.",
                        "Could not resolve \"" + uh.getMessage() + "\" (DNS failure). "
                                + "Check the endpoint URL and your network connection.");
            }
            if (c instanceof ConnectException || c instanceof NoRouteToHostException) {
                return new UserMessage("Cannot connect.",
                        "The storage endpoint refused the connection or is unreachable. "
                                + "Check the endpoint URL, port and your network connection.");
            }
            if (c instanceof SocketTimeoutException || c instanceof ApiCallTimeoutException
                    || c instanceof ApiCallAttemptTimeoutException
                    || c.getClass().getSimpleName().equals("ConnectTimeoutException")) {
                return new UserMessage("Network timeout.",
                        "The storage service did not respond in time. Check your connection, "
                                + "or increase the timeouts in Settings.");
            }
            if (c instanceof SSLException) {
                return new UserMessage("Secure connection failed.",
                        "The TLS handshake with the endpoint failed. The server certificate may be "
                                + "invalid or untrusted, or the endpoint may not support HTTPS.");
            }
            if (c instanceof NoSuchFileException nf) {
                return new UserMessage("File not found.", "The local file no longer exists: " + nf.getFile());
            }
            if (c instanceof AccessDeniedException ad) {
                return new UserMessage("Local permission denied.",
                        "You do not have permission to access " + ad.getFile() + ".");
            }
            if (c instanceof FileSystemException fs && fs.getMessage() != null
                    && fs.getMessage().toLowerCase(Locale.ROOT).contains("no space")) {
                return new UserMessage("Disk full.", "There is not enough free space on the local disk.");
            }
        }

        String msg = t.getMessage() == null ? "" : t.getMessage();
        if (msg.contains("Unable to load credentials") || msg.contains("Unable to load region")
                && msg.contains("credentials")) {
            return new UserMessage("No credentials found.",
                    "No AWS credentials could be found for this connection. Check the AWS profile, "
                            + "environment variables or ~/.aws/credentials.");
        }
        if (msg.contains("Profile file contained no credentials") || msg.contains("profile")
                && msg.contains("not found")) {
            return new UserMessage("AWS profile not usable.", msg);
        }
        if (msg.contains("The SSO session") || msg.contains("Token is expired")
                || msg.contains("token has expired")) {
            return new UserMessage("Credentials expired.",
                    "Your SSO session has expired. Run \"aws sso login\" and reconnect.");
        }
        if (t instanceof IllegalArgumentException && msg.toLowerCase(Locale.ROOT).contains("endpoint")
                || t instanceof java.net.URISyntaxException) {
            return new UserMessage("Invalid endpoint.", "The endpoint URL is not valid: " + msg);
        }
        if (t instanceof AbortedException || t instanceof InterruptedIOException
                || t instanceof InterruptedException) {
            return new UserMessage("Transfer interrupted.", "The operation was interrupted before it finished.");
        }
        if (t instanceof SdkClientException) {
            return new UserMessage("Connection problem.", msg.isEmpty() ? t.getClass().getSimpleName() : msg);
        }
        return new UserMessage("Unexpected error.", msg.isEmpty() ? t.getClass().getSimpleName() : msg);
    }

    private static UserMessage translateS3(S3Exception e, String permission) {
        String code = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null;
        String detail = e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage();
        int status = e.statusCode();
        if (code == null) {
            code = "";
        }

        if (AUTH_CODES.contains(code)) {
            return new UserMessage("Authentication failed.",
                    "The storage service rejected the credentials for this connection. "
                            + "Check the access key and secret key.");
        }
        if (EXPIRED_CODES.contains(code)) {
            return new UserMessage("Credentials expired.",
                    "Your temporary credentials have expired. Refresh them (for example with "
                            + "\"aws sso login\") and reconnect.");
        }
        if ("AccessDenied".equals(code) || "AllAccessDisabled".equals(code) || status == 403) {
            return new UserMessage("Access denied.",
                    "Your credentials do not have permission to " + permission + ".");
        }
        if ("NoSuchBucket".equals(code)) {
            return new UserMessage("Bucket not found.", "The bucket does not exist or has been deleted.");
        }
        if ("NoSuchKey".equals(code) || "NotFound".equals(code) || status == 404) {
            return new UserMessage("Object not found.", "The object does not exist. It may have been moved or deleted.");
        }
        if ("NoSuchUpload".equals(code)) {
            return new UserMessage("Multipart upload failed.",
                    "The multipart upload no longer exists on the server (it was aborted or expired). "
                            + "Retry to start the upload again.");
        }
        if (QUOTA_CODES.contains(code) || status == 507) {
            return new UserMessage("Storage quota exceeded.",
                    "The storage service reports that the quota or available capacity has been exceeded.");
        }
        if (THROTTLE_CODES.contains(code) || status == 429) {
            return new UserMessage("Too many requests.",
                    "The storage service is throttling requests. Try again shortly or reduce concurrent transfers.");
        }
        if ("PermanentRedirect".equals(code) || "AuthorizationHeaderMalformed".equals(code)
                || "IllegalLocationConstraintException".equals(code) || status == 301) {
            return new UserMessage("Wrong region.",
                    "The bucket is in a different region than the connection is configured for. "
                            + "Edit the connection's region and reconnect.");
        }
        if ("BucketNotEmpty".equals(code)) {
            return new UserMessage("Bucket not empty.", "Delete all objects in the bucket before deleting it.");
        }
        if ("BucketAlreadyExists".equals(code) || "BucketAlreadyOwnedByYou".equals(code)) {
            return new UserMessage("Bucket name unavailable.", "A bucket with that name already exists.");
        }
        if ("InvalidBucketName".equals(code)) {
            return new UserMessage("Invalid bucket name.",
                    "Bucket names must be 3-63 characters of lowercase letters, numbers, dots and hyphens.");
        }
        if ("EntityTooLarge".equals(code)) {
            return new UserMessage("Object too large.", "The object exceeds the maximum size allowed for this operation.");
        }
        if ("InvalidObjectState".equals(code)) {
            return new UserMessage("Object archived.",
                    "The object is in an archive storage class (such as Glacier) and must be restored first.");
        }
        if ("PreconditionFailed".equals(code) || status == 412) {
            return new UserMessage("Object changed.", "The object was modified on the server during the operation.");
        }
        if (status >= 500) {
            return new UserMessage("Storage service unavailable.",
                    "The storage service returned an internal error (" + status + "). Try again later.");
        }
        return new UserMessage("Storage service error.",
                (detail == null ? "" : detail) + (code.isEmpty() ? "" : " (" + code + ")"));
    }

    /** Whether an automatic retry has a reasonable chance of succeeding. */
    public static boolean isRetryable(Throwable error) {
        Throwable t = unwrap(error);
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof S3Exception s3) {
                int status = s3.statusCode();
                String code = s3.awsErrorDetails() != null ? s3.awsErrorDetails().errorCode() : "";
                return status >= 500 || status == 429 || status == 408 || THROTTLE_CODES.contains(code)
                        || "RequestTimeout".equals(code);
            }
            if (c instanceof NoSuchFileException || c instanceof AccessDeniedException) {
                return false;
            }
            if (c instanceof java.io.IOException) {
                return !(c instanceof FileSystemException);
            }
        }
        if (t instanceof SdkClientException sce) {
            String msg = sce.getMessage() == null ? "" : sce.getMessage();
            return !msg.contains("Unable to load credentials");
        }
        return false;
    }

    public static Throwable unwrap(Throwable t) {
        Throwable current = t;
        while ((current instanceof CompletionException || current instanceof ExecutionException
                || current instanceof UncheckedIOException
                || current instanceof java.lang.reflect.InvocationTargetException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
