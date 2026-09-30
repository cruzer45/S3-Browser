package com.s3explorer.service;

import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.model.S3Path;
import com.s3explorer.model.SearchCriteria;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Searches by enumerating keys under a prefix and filtering client-side; S3 has no general
 * server-side search.
 */
public class SearchService {

    public static final int MAX_RESULTS = 10_000;

    private final S3BrowserService browser;

    public SearchService(S3BrowserService browser) {
        this.browser = browser;
    }

    public record Progress(long scanned, int matched, boolean limitReached) {
    }

    /**
     * @param onBatch receives matches page by page (on the calling thread)
     * @return final progress figures
     */
    public Progress search(S3Connection connection, S3Path scope, SearchCriteria criteria, BooleanSupplier cancelled,
                           Consumer<List<S3ObjectItem>> onBatch, Consumer<Progress> onProgress) {
        SearchCriteria.Compiled compiled = criteria.compile();
        long[] scanned = {0};
        int[] matched = {0};
        boolean[] limit = {false};
        BooleanSupplier stop = () -> cancelled.getAsBoolean() || limit[0];

        if (criteria.recursive()) {
            browser.listRecursive(connection, scope.bucket(), scope.key(), stop, page -> {
                List<S3ObjectItem> batch = new ArrayList<>();
                for (S3Object o : page) {
                    scanned[0]++;
                    S3ObjectItem item = S3BrowserService.toItem(scope.bucket(), o);
                    if (compiled.matches(item) && matched[0] < MAX_RESULTS) {
                        batch.add(item);
                        matched[0]++;
                    }
                }
                limit[0] = matched[0] >= MAX_RESULTS;
                if (!batch.isEmpty()) {
                    onBatch.accept(batch);
                }
                onProgress.accept(new Progress(scanned[0], matched[0], limit[0]));
            });
        } else {
            String token = null;
            do {
                var page = browser.list(connection, scope, token, true);
                List<S3ObjectItem> batch = new ArrayList<>();
                for (S3ObjectItem item : page.items()) {
                    scanned[0]++;
                    if (compiled.matches(item) && matched[0] < MAX_RESULTS) {
                        batch.add(item);
                        matched[0]++;
                    }
                }
                if (!batch.isEmpty()) {
                    onBatch.accept(batch);
                }
                limit[0] = matched[0] >= MAX_RESULTS;
                onProgress.accept(new Progress(scanned[0], matched[0], limit[0]));
                token = page.nextToken();
            } while (token != null && !stop.getAsBoolean());
        }
        return new Progress(scanned[0], matched[0], limit[0]);
    }
}
