package com.s3explorer.util;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Logback converter that redacts secrets from log messages as a last line of defence.
 * Code must still never log secrets deliberately; this only catches accidents such as an
 * exception message that embeds a presigned URL.
 */
public class MaskingConverter extends MessageConverter {

    private static final Pattern[] PATTERNS = {
            // Presigned URL query parameters
            Pattern.compile("(?i)(X-Amz-(?:Signature|Credential|Security-Token)=)[^&\\s\"']+"),
            // Authorization headers
            Pattern.compile("(?i)(Authorization\\s*[:=]\\s*)[^\\r\\n,]+"),
            Pattern.compile("(?i)(x-amz-security-token\\s*[:=]\\s*)[^\\r\\n,\\s]+"),
            // key=value style secrets
            Pattern.compile("(?i)((?:aws_)?secret_?(?:access_?)?key\\s*[:=]\\s*)[^\\s,;\"']+"),
            Pattern.compile("(?i)((?:aws_)?session_?token\\s*[:=]\\s*)[^\\s,;\"']+"),
            Pattern.compile("(?i)(password\\s*[:=]\\s*)[^\\s,;\"']+"),
    };

    @Override
    public String convert(ILoggingEvent event) {
        return mask(super.convert(event));
    }

    public static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String result = message;
        for (Pattern pattern : PATTERNS) {
            result = pattern.matcher(result).replaceAll("$1****");
        }
        return result;
    }
}
