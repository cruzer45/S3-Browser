package com.s3explorer.model;

import java.util.Map;

/** New system and user metadata to apply to an object. Blank values are removed. */
public record MetadataEdit(
        String contentType,
        String contentEncoding,
        String contentDisposition,
        String contentLanguage,
        String cacheControl,
        Map<String, String> userMetadata) {
}
