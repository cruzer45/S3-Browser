package com.s3explorer.model;

import java.util.List;

/** One page of a delimited listing: folders and files directly under a prefix. */
public record ListingPage(List<S3ObjectItem> items, String nextToken) {

    public boolean hasMore() {
        return nextToken != null;
    }
}
