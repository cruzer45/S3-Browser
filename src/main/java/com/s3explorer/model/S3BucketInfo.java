package com.s3explorer.model;

import java.time.Instant;

public record S3BucketInfo(String name, Instant creationDate) {
}
