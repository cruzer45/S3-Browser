package com.s3explorer.util;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.UnknownHostException;
import java.nio.file.NoSuchFileException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilTest {

    @Test
    void formatsSizes() {
        assertEquals("0 B", FileSizeFormatter.format(0));
        assertEquals("980 B", FileSizeFormatter.format(980));
        assertEquals("1.0 KB", FileSizeFormatter.format(1024));
        assertEquals("8.2 MB", FileSizeFormatter.format((long) (8.2 * 1024 * 1024)));
        assertEquals("421 MB", FileSizeFormatter.format(421L * 1024 * 1024));
        assertEquals("1.7 GB", FileSizeFormatter.format((long) (1.7 * 1024 * 1024 * 1024)));
        assertEquals("", FileSizeFormatter.format(-1));
    }

    @Test
    void generatesUniqueNames() {
        Set<String> existing = Set.of("report.pdf", "report (1).pdf", "data/", "archive.tar.gz");
        assertEquals("report (2).pdf", NameConflicts.uniqueName("report.pdf", existing::contains));
        assertEquals("new.pdf", NameConflicts.uniqueName("new.pdf", existing::contains));
        assertEquals("data (1)/", NameConflicts.uniqueName("data/", existing::contains));
        assertEquals("archive.tar (1).gz", NameConflicts.uniqueName("archive.tar.gz", existing::contains));
        assertEquals(".env (1)", NameConflicts.uniqueName(".env", Set.of(".env")::contains));
    }

    @Test
    void matchesWildcards() {
        assertTrue(Wildcard.matches(Wildcard.compile("*.backup"), "db.BACKUP"));
        assertFalse(Wildcard.matches(Wildcard.compile("*.backup"), "db.backup.old"));
        assertTrue(Wildcard.matches(Wildcard.compile("inv-??.pdf"), "inv-01.pdf"));
        assertTrue(Wildcard.matches(Wildcard.compile("port"), "annual-report.pdf"));
        assertTrue(Wildcard.matches(Wildcard.compile("a+b(c)"), "x a+b(c) y"));
        assertTrue(Wildcard.matches(null, "anything"));
    }

    @Test
    void masksSecretsInLogMessages() {
        String url = "https://b.s3.amazonaws.com/k?X-Amz-Algorithm=AWS4&X-Amz-Credential=AKIA123%2F2026&"
                + "X-Amz-Signature=abcdef0123&X-Amz-Security-Token=tok";
        String masked = MaskingConverter.mask("Fetching " + url);
        assertFalse(masked.contains("abcdef0123"));
        assertFalse(masked.contains("AKIA123"));
        assertFalse(masked.contains("=tok"));
        assertTrue(masked.contains("X-Amz-Algorithm=AWS4"));
        assertEquals("Authorization: ****", MaskingConverter.mask("Authorization: AWS4-HMAC-SHA256 Credential=x"));
        assertEquals("aws_secret_access_key=****", MaskingConverter.mask("aws_secret_access_key=wJalrXUtnFEMI"));
    }

    @Test
    void translatesS3Errors() {
        ErrorTranslator.UserMessage denied = ErrorTranslator.translate(s3(403, "AccessDenied"),
                "delete objects from this bucket");
        assertEquals("Access denied.", denied.title());
        assertEquals("Your credentials do not have permission to delete objects from this bucket.", denied.message());
        assertEquals("Bucket not found.", ErrorTranslator.translate(s3(404, "NoSuchBucket"), null).title());
        assertEquals("Object not found.", ErrorTranslator.translate(s3(404, "NoSuchKey"), null).title());
        assertEquals("Credentials expired.", ErrorTranslator.translate(s3(400, "ExpiredToken"), null).title());
        assertEquals("Authentication failed.", ErrorTranslator.translate(s3(403, "SignatureDoesNotMatch"), null).title());
        assertEquals("Storage quota exceeded.", ErrorTranslator.translate(s3(507, "XMinioStorageFull"), null).title());
        assertEquals("Host not found.", ErrorTranslator.translate(
                SdkClientException.create("fail", new UnknownHostException("nope.example")), null).title());
        assertEquals("File not found.", ErrorTranslator.translate(new NoSuchFileException("/tmp/x"), null).title());
        assertEquals("Custom.", ErrorTranslator.translate(new UserFacingException("Custom.", "Body"), null).title());
    }

    @Test
    void classifiesRetryableErrors() {
        assertTrue(ErrorTranslator.isRetryable(s3(503, "SlowDown")));
        assertTrue(ErrorTranslator.isRetryable(s3(500, "InternalError")));
        assertFalse(ErrorTranslator.isRetryable(s3(403, "AccessDenied")));
        assertFalse(ErrorTranslator.isRetryable(s3(404, "NoSuchKey")));
        assertTrue(ErrorTranslator.isRetryable(SdkClientException.create("reset", new java.net.SocketException("reset"))));
        assertFalse(ErrorTranslator.isRetryable(new NoSuchFileException("x")));
    }

    private static S3Exception s3(int status, String code) {
        return (S3Exception) S3Exception.builder().statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(code).build())
                .message(code).build();
    }
}
