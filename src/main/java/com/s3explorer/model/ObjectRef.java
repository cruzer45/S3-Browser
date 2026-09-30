package com.s3explorer.model;

/** A single object key and its size, used when an operation works on an enumerated set. */
public record ObjectRef(String key, long size) {
}
