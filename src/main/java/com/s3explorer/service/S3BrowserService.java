package com.s3explorer.service;

import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.ListingPage;
import com.s3explorer.model.ObjectProperties;
import com.s3explorer.model.ObjectRef;
import com.s3explorer.model.S3BucketInfo;
import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.model.S3Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.BucketLocationConstraint;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Read-side S3 operations for the browser: buckets, listings, properties, folders. */
public class S3BrowserService {

    private static final Logger log = LoggerFactory.getLogger(S3BrowserService.class);
    public static final int PAGE_SIZE = 1000;

    /** Lists buckets, merging in the profile's pinned buckets (used when ListBuckets is denied). */
    public List<S3BucketInfo> listBuckets(S3Connection connection) {
        Map<String, S3BucketInfo> buckets = new LinkedHashMap<>();
        List<String> pinned = connection.profile().pinnedBuckets == null ? List.of() : connection.profile().pinnedBuckets;
        try {
            for (Bucket b : connection.client().listBuckets().buckets()) {
                buckets.put(b.name(), new S3BucketInfo(b.name(), b.creationDate()));
            }
        } catch (S3Exception e) {
            if (e.statusCode() != 403 || pinned.isEmpty()) {
                throw e;
            }
            log.info("ListBuckets denied for '{}'; showing pinned buckets only", connection.profile().name);
        }
        for (String name : pinned) {
            if (!name.isBlank()) {
                buckets.putIfAbsent(name.trim(), new S3BucketInfo(name.trim(), null));
            }
        }
        List<S3BucketInfo> result = new ArrayList<>(buckets.values());
        result.sort(Comparator.comparing(S3BucketInfo::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /** Lists one page of folders and files directly under {@code folder}. */
    public ListingPage list(S3Connection connection, S3Path folder, String continuationToken, boolean showHidden) {
        ListObjectsV2Response response = connection.client().listObjectsV2(ListObjectsV2Request.builder()
                .bucket(folder.bucket())
                .prefix(folder.key().isEmpty() ? null : folder.key())
                .delimiter("/")
                .maxKeys(PAGE_SIZE)
                .continuationToken(continuationToken)
                .build());
        List<S3ObjectItem> items = new ArrayList<>();
        for (CommonPrefix cp : response.commonPrefixes()) {
            S3Path path = S3Path.folder(folder.bucket(), cp.prefix());
            if (showHidden || !path.name().startsWith(".")) {
                items.add(S3ObjectItem.folder(path));
            }
        }
        for (S3Object o : response.contents()) {
            if (o.key().equals(folder.key())) {
                continue; // the folder marker object itself
            }
            S3Path path = S3Path.object(folder.bucket(), o.key());
            if (!showHidden && path.name().startsWith(".")) {
                continue;
            }
            items.add(toItem(folder.bucket(), o));
        }
        return new ListingPage(items, Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null);
    }

    public static S3ObjectItem toItem(String bucket, S3Object o) {
        S3Path path = S3Path.object(bucket, o.key());
        if (path.isDirectory()) {
            return S3ObjectItem.folder(path);
        }
        return S3ObjectItem.file(path, o.size() == null ? 0 : o.size(), o.lastModified(), o.eTag(),
                o.storageClassAsString());
    }

    /** Streams every object under a prefix (no delimiter), page by page. */
    public void listRecursive(S3Connection connection, String bucket, String prefix, BooleanSupplier cancelled,
                              Consumer<List<S3Object>> pageConsumer) {
        String token = null;
        do {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return;
            }
            ListObjectsV2Response response = connection.client().listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix == null || prefix.isEmpty() ? null : prefix)
                    .maxKeys(PAGE_SIZE)
                    .continuationToken(token)
                    .build());
            pageConsumer.accept(response.contents());
            token = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (token != null);
    }

    /** Collects every object under a prefix, including folder markers. */
    public List<ObjectRef> collect(S3Connection connection, String bucket, String prefix, BooleanSupplier cancelled,
                                   Consumer<Integer> progress) {
        List<ObjectRef> refs = new ArrayList<>();
        listRecursive(connection, bucket, prefix, cancelled, page -> {
            for (S3Object o : page) {
                refs.add(new ObjectRef(o.key(), o.size() == null ? 0 : o.size()));
            }
            if (progress != null) {
                progress.accept(refs.size());
            }
        });
        return refs;
    }

    public record FolderStats(long objectCount, long totalSize) {
    }

    public FolderStats folderStats(S3Connection connection, S3Path folder, BooleanSupplier cancelled) {
        long[] totals = new long[2];
        listRecursive(connection, folder.bucket(), folder.key(), cancelled, page -> {
            for (S3Object o : page) {
                if (!o.key().endsWith("/")) {
                    totals[0]++;
                    totals[1] += o.size() == null ? 0 : o.size();
                }
            }
        });
        return new FolderStats(totals[0], totals[1]);
    }

    public ObjectProperties properties(S3Connection connection, S3Path path) {
        HeadObjectResponse head = head(connection, path);
        return new ObjectProperties(
                path,
                objectUrl(connection, path),
                head.contentLength() == null ? 0 : head.contentLength(),
                head.lastModified(),
                head.eTag() == null ? "" : head.eTag().replace("\"", ""),
                head.contentType(),
                head.contentEncoding(),
                head.contentDisposition(),
                head.contentLanguage(),
                head.cacheControl(),
                head.storageClassAsString() == null ? "STANDARD" : head.storageClassAsString(),
                head.versionId(),
                head.serverSideEncryptionAsString(),
                head.ssekmsKeyId(),
                new LinkedHashMap<>(head.metadata()));
    }

    public HeadObjectResponse head(S3Connection connection, S3Path path) {
        return connection.client().headObject(b -> b.bucket(path.bucket()).key(path.key()));
    }

    /** Whether an object exists (for files) or any object exists under the prefix (for folders). */
    public boolean exists(S3Connection connection, S3Path path) {
        if (path.isDirectory()) {
            ListObjectsV2Response r = connection.client().listObjectsV2(b -> b.bucket(path.bucket())
                    .prefix(path.key()).maxKeys(1));
            return !r.contents().isEmpty();
        }
        try {
            head(connection, path);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /** Creates a folder marker: an empty object whose key ends in "/". */
    public void createFolder(S3Connection connection, S3Path folder) {
        if (!folder.isDirectory() || folder.isBucketRoot()) {
            throw new IllegalArgumentException("Not a folder path: " + folder);
        }
        connection.client().putObject(b -> b.bucket(folder.bucket()).key(folder.key()), RequestBody.empty());
        log.info("Created folder {}", folder.toUri());
    }

    public String bucketRegion(S3Connection connection, String bucket) {
        return connection.bucketRegions().computeIfAbsent(bucket, b -> {
            try {
                String constraint = connection.client().getBucketLocation(r -> r.bucket(b)).locationConstraintAsString();
                if (constraint == null || constraint.isEmpty()) {
                    return connection.isAws() ? "us-east-1" : connection.region().id();
                }
                return "EU".equals(constraint) ? "eu-west-1" : constraint;
            } catch (RuntimeException e) {
                log.debug("GetBucketLocation failed for {}: {}", b, e.toString());
                return connection.region().id();
            }
        });
    }

    public String versioningStatus(S3Connection connection, String bucket) {
        try {
            String status = connection.client().getBucketVersioning(r -> r.bucket(bucket)).statusAsString();
            return status == null || status.isEmpty() ? "Not enabled" : status;
        } catch (RuntimeException e) {
            log.debug("GetBucketVersioning failed for {}: {}", bucket, e.toString());
            return "Unknown";
        }
    }

    public String objectUrl(S3Connection connection, S3Path path) {
        GetUrlRequest.Builder request = GetUrlRequest.builder().bucket(path.bucket()).key(path.key());
        if (connection.isAws()) {
            request.region(Region.of(bucketRegion(connection, path.bucket())));
        }
        return connection.client().utilities().getUrl(request.build()).toExternalForm();
    }

    public void createBucket(S3Connection connection, String name, String region) {
        S3Client client = connection.client();
        CreateBucketRequest.Builder request = CreateBucketRequest.builder().bucket(name);
        if (connection.isAws() && region != null && !region.isBlank() && !"us-east-1".equals(region)) {
            request.createBucketConfiguration(c -> c.locationConstraint(BucketLocationConstraint.fromValue(region)));
        }
        client.createBucket(request.build());
        log.info("Created bucket {}", name);
    }

    public void deleteBucket(S3Connection connection, String name) {
        connection.client().deleteBucket(b -> b.bucket(name));
        connection.bucketRegions().remove(name);
        log.info("Deleted bucket {}", name);
    }
}
