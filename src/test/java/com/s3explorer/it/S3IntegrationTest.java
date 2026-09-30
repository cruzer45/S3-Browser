package com.s3explorer.it;

import com.s3explorer.aws.S3Connection;
import com.s3explorer.config.AuthMethod;
import com.s3explorer.config.ConnectionProfile;
import com.s3explorer.config.ConnectionStore;
import com.s3explorer.config.SettingsStore;
import com.s3explorer.model.ConflictPolicy;
import com.s3explorer.model.MetadataEdit;
import com.s3explorer.model.ObjectProperties;
import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.model.S3Path;
import com.s3explorer.model.SearchCriteria;
import com.s3explorer.model.TransferStatus;
import com.s3explorer.model.TransferTask;
import com.s3explorer.service.ConnectionService;
import com.s3explorer.service.MetadataService;
import com.s3explorer.service.PresignedUrlService;
import com.s3explorer.service.PreviewService;
import com.s3explorer.service.S3BrowserService;
import com.s3explorer.service.SearchService;
import com.s3explorer.service.TransferPlanner;
import com.s3explorer.service.TransferService;
import com.s3explorer.service.TransferStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end tests against a real S3-compatible endpoint. Skipped unless S3E_IT_ENDPOINT,
 * S3E_IT_ACCESS_KEY and S3E_IT_SECRET_KEY are set, e.g. against a local MinIO container.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3IntegrationTest {

    private static final int MB = 1024 * 1024;

    /** Shared across tests (a @TempDir field would be recreated per test method). */
    private Path temp;

    private SettingsStore settings;
    private ConnectionService connections;
    private S3BrowserService browser;
    private TransferService transfers;
    private TransferPlanner planner;
    private S3Connection conn;
    private String connId;
    private String otherConnId;
    private String bucket;
    private String bucket2;

    @BeforeAll
    void setUp() throws Exception {
        String endpoint = System.getenv("S3E_IT_ENDPOINT");
        assumeTrue(endpoint != null && !endpoint.isBlank(), "S3E_IT_ENDPOINT not set; skipping integration tests");
        Path home = Files.createTempDirectory("s3e-it");
        temp = Files.createTempDirectory("s3e-it-files");
        settings = new SettingsStore(home.resolve("settings.json"));
        settings.get().transfers.multipartThresholdMb = 5;
        settings.get().transfers.partSizeMb = 5;
        settings.get().transfers.parallelParts = 3;
        settings.get().transfers.retryCount = 1;
        settings.get().transfers.retryDelaySeconds = 0;
        connections = new ConnectionService(new ConnectionStore(home.resolve("c.json"), home.resolve("s.json")), settings);
        browser = new S3BrowserService();
        transfers = new TransferService(settings, connections, new TransferStore(home.resolve("t.json")));
        planner = new TransferPlanner(connections, browser);

        connId = saveProfile("it", endpoint);
        otherConnId = saveProfile("it-second", endpoint); // same server, separate client: exercises streaming copy
        conn = connections.get(connId);
        bucket = "s3e-it-" + UUID.randomUUID().toString().substring(0, 8);
        bucket2 = bucket + "-b";
        browser.createBucket(conn, bucket, "us-east-1");
        browser.createBucket(conn, bucket2, "us-east-1");
    }

    private String saveProfile(String name, String endpoint) {
        ConnectionProfile p = new ConnectionProfile();
        p.name = name;
        p.endpoint = endpoint;
        p.region = System.getenv().getOrDefault("S3E_IT_REGION", "us-east-1");
        p.authMethod = AuthMethod.ACCESS_KEY;
        p.accessKeyId = System.getenv("S3E_IT_ACCESS_KEY");
        p.secretAccessKey = System.getenv("S3E_IT_SECRET_KEY");
        p.pathStyleAccess = true;
        connections.saveProfile(p);
        return p.id;
    }

    @AfterAll
    void tearDown() {
        if (conn == null) {
            return;
        }
        for (String b : List.of(bucket, bucket2)) {
            try {
                var refs = browser.collect(conn, b, "", () -> false, null);
                for (var r : refs) {
                    conn.client().deleteObject(x -> x.bucket(b).key(r.key()));
                }
                conn.client().listMultipartUploads(x -> x.bucket(b)).uploads().forEach(u ->
                        conn.client().abortMultipartUpload(x -> x.bucket(b).key(u.key()).uploadId(u.uploadId())));
                browser.deleteBucket(conn, b);
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        transfers.shutdown(false);
        connections.closeAll();
    }

    // --- helpers -----------------------------------------------------------------------------

    private Path randomFile(String name, int size) throws Exception {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        Path file = temp.resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, data);
        return file;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private void runAll(List<TransferTask> tasks) throws InterruptedException {
        transfers.submit(tasks);
        for (TransferTask t : tasks) {
            await(() -> t.status().isFinished(), t.displayName());
            assertEquals(TransferStatus.COMPLETED, t.status(), t.displayName() + ": " + t.message());
        }
    }

    private List<String> keys(String b, String prefix) {
        return browser.collect(conn, b, prefix, () -> false, null).stream().map(r -> r.key()).sorted().toList();
    }

    private S3ObjectItem fileItem(String b, String key) {
        var head = browser.head(conn, S3Path.object(b, key));
        return S3ObjectItem.file(S3Path.object(b, key), head.contentLength(), head.lastModified(), head.eTag(), null);
    }

    // --- tests ---------------------------------------------------------------------------

    @Test
    @Order(1)
    void listsBucketsAndCreatesFolders() {
        assertTrue(browser.listBuckets(conn).stream().anyMatch(b -> b.name().equals(bucket)));
        browser.createFolder(conn, S3Path.folder(bucket, "docs"));
        var page = browser.list(conn, S3Path.root(bucket), null, true);
        assertEquals(List.of("docs"), page.items().stream().map(S3ObjectItem::name).toList());
        assertTrue(page.items().get(0).isContainer());
        assertTrue(browser.list(conn, S3Path.folder(bucket, "docs"), null, true).items().isEmpty(),
                "the folder marker itself is not listed as a child");
    }

    @Test
    @Order(2)
    void uploadsAndDownloadsSingleAndMultipart() throws Exception {
        Path small = randomFile("up/small.bin", 100_000);
        Path large = randomFile("up/large.bin", 13 * MB); // 3 parts at 5 MB
        runAll(List.of(
                TransferTask.upload(small, connId, S3Path.object(bucket, "docs/small.bin")),
                TransferTask.upload(large, connId, S3Path.object(bucket, "docs/large.bin"))));
        assertEquals(13L * MB, browser.head(conn, S3Path.object(bucket, "docs/large.bin")).contentLength());
        assertTrue(browser.head(conn, S3Path.object(bucket, "docs/large.bin")).eTag().contains("-3"),
                "large file used a 3-part multipart upload");

        Path downloads = temp.resolve("down");
        List<TransferTask> tasks = planner.planDownload(connId,
                List.of(S3ObjectItem.folder(S3Path.folder(bucket, "docs"))), downloads, ConflictPolicy.OVERWRITE,
                () -> false, n -> { });
        runAll(tasks);
        assertEquals(sha256(small), sha256(downloads.resolve("docs/small.bin")));
        assertEquals(sha256(large), sha256(downloads.resolve("docs/large.bin")));

        // Conflict policies
        List<TransferTask> again = planner.planDownload(connId, List.of(fileItem(bucket, "docs/small.bin")),
                downloads.resolve("docs"), ConflictPolicy.RENAME, () -> false, n -> { });
        runAll(again);
        assertTrue(Files.exists(downloads.resolve("docs/small (1).bin")));
        List<TransferTask> skip = planner.planDownload(connId, List.of(fileItem(bucket, "docs/small.bin")),
                downloads.resolve("docs"), ConflictPolicy.SKIP, () -> false, n -> { });
        runAll(skip);
        assertTrue(skip.get(0).message().startsWith("Skipped"));
    }

    @Test
    @Order(3)
    void pausesAndResumesMultipartUpload() throws Exception {
        Path big = randomFile("up/pausable.bin", 16 * MB);
        // One part at a time and 4 MB/s, so the first 5 MB part finishes before we pause.
        settings.get().transfers.parallelParts = 1;
        settings.get().transfers.bandwidthLimitKbps = 4 * 1024;
        transfers.applySettings();
        try {
            TransferTask task = TransferTask.upload(big, connId, S3Path.object(bucket, "big/pausable.bin"));
            transfers.submit(List.of(task));
            await(() -> task.transferred() > 6L * MB, "some progress");
            transfers.pause(task);
            await(() -> task.status() == TransferStatus.PAUSED, "pause");
            assertNotNull(task.uploadId(), "multipart upload kept open for resume");
            assertTrue(task.transferred() >= 5L * MB, "progress falls back to completed parts only");
            String uploadId = task.uploadId();
            int partsOnServer = conn.client().listParts(b -> b.bucket(bucket).key("big/pausable.bin")
                    .uploadId(uploadId)).parts().size();
            assertTrue(partsOnServer >= 1, "completed parts stay on the server");

            settings.get().transfers.bandwidthLimitKbps = 0;
            transfers.applySettings();
            transfers.resume(task);
            await(() -> task.status().isFinished(), "resume");
            assertEquals(TransferStatus.COMPLETED, task.status(), task.message());
            assertNull(task.uploadId());
        } finally {
            settings.get().transfers.bandwidthLimitKbps = 0;
            settings.get().transfers.parallelParts = 3;
            transfers.applySettings();
        }
        Path back = temp.resolve("down2/pausable.bin");
        runAll(List.of(TransferTask.download(connId, S3Path.object(bucket, "big/pausable.bin"), back,
                ConflictPolicy.OVERWRITE)));
        assertEquals(sha256(big), sha256(back));
    }

    @Test
    @Order(4)
    void pausesAndResumesDownloadWithRangedGet() throws Exception {
        Path target = temp.resolve("down3/pausable.bin");
        settings.get().transfers.bandwidthLimitKbps = 4 * 1024;
        transfers.applySettings();
        try {
            TransferTask task = TransferTask.download(connId, S3Path.object(bucket, "big/pausable.bin"), target,
                    ConflictPolicy.OVERWRITE);
            transfers.submit(List.of(task));
            await(() -> task.transferred() > 3L * MB, "download progress");
            transfers.pause(task);
            await(() -> task.status() == TransferStatus.PAUSED, "download pause");
            Path part = target.resolveSibling("pausable.bin.s3e-part");
            assertTrue(Files.size(part) > 0, "partial file kept");
            assertFalse(Files.exists(target));

            settings.get().transfers.bandwidthLimitKbps = 0;
            transfers.applySettings();
            transfers.resume(task);
            await(() -> task.status().isFinished(), "download resume");
            assertEquals(TransferStatus.COMPLETED, task.status(), task.message());
            assertFalse(Files.exists(part));
        } finally {
            settings.get().transfers.bandwidthLimitKbps = 0;
            transfers.applySettings();
        }
        assertEquals(sha256(temp.resolve("up/pausable.bin")), sha256(target));
    }

    @Test
    @Order(5)
    void cancelAbortsMultipartUpload() throws Exception {
        Path big = randomFile("up/cancel.bin", 12 * MB);
        settings.get().transfers.bandwidthLimitKbps = 2 * 1024;
        transfers.applySettings();
        try {
            TransferTask task = TransferTask.upload(big, connId, S3Path.object(bucket, "big/cancel.bin"));
            transfers.submit(List.of(task));
            await(() -> task.uploadId() != null && task.transferred() > MB, "upload started");
            transfers.cancel(task);
            await(() -> task.status().isFinished(), "cancel");
            assertEquals(TransferStatus.CANCELLED, task.status());
        } finally {
            settings.get().transfers.bandwidthLimitKbps = 0;
            transfers.applySettings();
        }
        assertTrue(conn.client().listMultipartUploads(b -> b.bucket(bucket)).uploads().stream()
                .noneMatch(u -> u.key().equals("big/cancel.bin")), "multipart upload aborted");
        assertFalse(browser.exists(conn, S3Path.object(bucket, "big/cancel.bin")));
    }

    @Test
    @Order(6)
    void uploadsFolderTreesPreservingStructure() throws Exception {
        randomFile("tree/reports/2025/annual.pdf", 1000);
        randomFile("tree/reports/2026/january.pdf", 2000);
        Files.createDirectories(temp.resolve("tree/reports/empty"));
        TransferPlanner.UploadPlan plan = planner.planUpload(List.of(temp.resolve("tree/reports")), connId,
                S3Path.root(bucket), () -> false, n -> { });
        planner.createEmptyFolders(connId, plan.emptyFolders());
        runAll(plan.tasks());
        assertEquals(List.of("reports/2025/annual.pdf", "reports/2026/january.pdf", "reports/empty/"),
                keys(bucket, "reports/"));
    }

    @Test
    @Order(7)
    void copiesMovesAndRenames() throws Exception {
        // Server-side copy between buckets on the same connection
        runAll(planner.planCopy(connId, List.of(S3ObjectItem.folder(S3Path.folder(bucket, "reports"))), connId,
                S3Path.root(bucket2), false, Map.of(), () -> false, n -> { }));
        assertEquals(List.of("reports/2025/annual.pdf", "reports/2026/january.pdf", "reports/empty/"),
                keys(bucket2, "reports/"));

        // Streaming copy across connections, including a multipart-sized object
        runAll(planner.planCopy(connId, List.of(fileItem(bucket, "docs/large.bin")), otherConnId,
                S3Path.folder(bucket2, "streamed"), false, Map.of(), () -> false, n -> { }));
        assertEquals(13L * MB, browser.head(conn, S3Path.object(bucket2, "streamed/large.bin")).contentLength());

        // Rename a folder: copy + verify + delete for each object
        S3ObjectItem folder = S3ObjectItem.folder(S3Path.folder(bucket2, "reports"));
        runAll(planner.planRename(connId, folder, "archive", () -> false, n -> { }));
        assertEquals(List.of(), keys(bucket2, "reports/"));
        assertEquals(List.of("archive/2025/annual.pdf", "archive/2026/january.pdf", "archive/empty/"),
                keys(bucket2, "archive/"));

        // Rename to an existing name is refused
        browser.createFolder(conn, S3Path.folder(bucket2, "taken"));
        assertThrows(RuntimeException.class, () -> planner.planRename(connId,
                S3ObjectItem.folder(S3Path.folder(bucket2, "archive")), "taken", () -> false, n -> { }));

        // Move a file into another folder
        runAll(planner.planCopy(connId, List.of(fileItem(bucket2, "archive/2025/annual.pdf")), connId,
                S3Path.folder(bucket2, "taken"), true, Map.of(), () -> false, n -> { }));
        assertTrue(browser.exists(conn, S3Path.object(bucket2, "taken/annual.pdf")));
        assertFalse(browser.exists(conn, S3Path.object(bucket2, "archive/2025/annual.pdf")));

        // Moving a folder into itself is refused
        assertThrows(RuntimeException.class, () -> planner.planCopy(connId,
                List.of(S3ObjectItem.folder(S3Path.folder(bucket2, "archive"))), connId,
                S3Path.folder(bucket2, "archive/2026"), true, Map.of(), () -> false, n -> { }));
    }

    @Test
    @Order(8)
    void editsMetadataAndReadsProperties() {
        S3Path path = S3Path.object(bucket, "docs/small.bin");
        new MetadataService().update(conn, path, new MetadataEdit("application/x-test", null, "attachment",
                null, "max-age=60", Map.of("Customer-ID", "74832", "uploaded-by", "administrator")));
        ObjectProperties p = browser.properties(conn, path);
        assertEquals("application/x-test", p.contentType());
        assertEquals("max-age=60", p.cacheControl());
        assertEquals("attachment", p.contentDisposition());
        assertEquals("74832", p.metadata().get("customer-id"));
        assertEquals("administrator", p.metadata().get("uploaded-by"));
        assertEquals(100_000, p.size());
        assertTrue(p.objectUrl().contains(bucket) && p.objectUrl().endsWith("docs/small.bin"));
    }

    @Test
    @Order(9)
    void presignedUrlDownloadsTheObject() throws Exception {
        var presigned = new PresignedUrlService(browser).presign(conn, S3Path.object(bucket, "docs/small.bin"),
                Duration.ofMinutes(15));
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(presigned.url())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(Files.readAllBytes(temp.resolve("up/small.bin")), response.body());
        assertFalse(presigned.toString().contains("Signature"), "toString never exposes the URL");
    }

    @Test
    @Order(10)
    void searchesAndPreviews() throws Exception {
        Path csv = temp.resolve("up/table.csv");
        Files.writeString(csv, "a,b\n1,2\n");
        runAll(List.of(TransferTask.upload(csv, connId, S3Path.object(bucket, "reports/2026/table.csv"))));

        SearchService search = new SearchService(browser);
        List<S3ObjectItem> found = new ArrayList<>();
        var result = search.search(conn, S3Path.root(bucket), new SearchCriteria("*.pdf", null, null, null, null,
                null, null, true), () -> false, found::addAll, p -> { });
        assertEquals(2, result.matched());
        assertEquals(List.of("annual.pdf", "january.pdf"), found.stream().map(S3ObjectItem::name).sorted().toList());

        found.clear();
        search.search(conn, S3Path.folder(bucket, "reports"), new SearchCriteria(null, "csv", null, null, null, null,
                null, false), () -> false, found::addAll, p -> { });
        assertTrue(found.isEmpty(), "non-recursive search only looks at the folder itself");

        var preview = new PreviewService().fetch(conn, S3Path.object(bucket, "reports/2026/table.csv"), 4);
        assertTrue(preview.truncated());
        assertEquals("a,b\n", new String(preview.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    @Order(11)
    void deletesFoldersRecursively() throws Exception {
        TransferPlanner.DeletePlan plan = planner.planDelete(connId, S3Path.root(bucket),
                List.of(S3ObjectItem.folder(S3Path.folder(bucket, "reports"))), () -> false, n -> { });
        assertEquals(4, plan.objectCount());
        assertEquals(3000 + 8, plan.totalBytes());
        runAll(List.of(TransferTask.delete(connId, S3Path.root(bucket), plan.refs(), "reports")));
        assertEquals(List.of(), keys(bucket, "reports/"));
        assertTrue(browser.list(conn, S3Path.root(bucket), null, true).items().stream()
                .noneMatch(i -> i.name().equals("reports")), "deleted folder no longer listed");
    }
}
