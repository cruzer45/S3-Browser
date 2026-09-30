package com.s3explorer.service;

import com.s3explorer.config.AuthMethod;
import com.s3explorer.config.ConnectionProfile;
import com.s3explorer.config.ConnectionStore;
import com.s3explorer.config.SettingsStore;
import com.s3explorer.model.ConflictPolicy;
import com.s3explorer.model.S3Path;
import com.s3explorer.model.TransferStatus;
import com.s3explorer.model.TransferTask;
import com.s3explorer.model.TransferType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerAndStoreTest {

    @TempDir
    Path temp;

    @Test
    void uploadPlanPreservesFolderStructure() throws Exception {
        Path reports = temp.resolve("reports");
        Files.createDirectories(reports.resolve("2025"));
        Files.createDirectories(reports.resolve("2026"));
        Files.createDirectories(reports.resolve("empty"));
        Files.writeString(reports.resolve("2025/annual.pdf"), "a");
        Files.writeString(reports.resolve("2026/january.pdf"), "bb");

        TransferPlanner planner = new TransferPlanner(null, null);
        TransferPlanner.UploadPlan plan = planner.planUpload(List.of(reports), "conn",
                S3Path.folder("company-documents", ""), () -> false, n -> { });

        List<String> keys = plan.tasks().stream().map(t -> t.destination().key()).sorted().toList();
        assertEquals(List.of("reports/2025/annual.pdf", "reports/2026/january.pdf"), keys);
        assertEquals(3, plan.totalBytes());
        assertEquals(List.of("reports/empty/"), plan.emptyFolders().stream().map(S3Path::key).toList());

        TransferPlanner.UploadPlan nested = planner.planUpload(List.of(reports.resolve("2025/annual.pdf")), "conn",
                S3Path.folder("b", "archive/"), () -> false, n -> { });
        assertEquals("archive/annual.pdf", nested.tasks().get(0).destination().key());
    }

    @Test
    void downloadPathsCannotEscapeTheTargetFolder() {
        Path root = temp.toAbsolutePath();
        assertEquals(root.resolve("a/b.txt"), TransferPlanner.safeResolve(root, "a/b.txt"));
        assertEquals(root.resolve("a/b.txt"), TransferPlanner.safeResolve(root, "/a/b.txt"));
        assertNull(TransferPlanner.safeResolve(root, "../etc/passwd"));
        assertNull(TransferPlanner.safeResolve(root, "a/../../x"));
        assertNull(TransferPlanner.safeResolve(root, ""));
        assertNull(TransferPlanner.safeResolve(root, "/"));
    }

    @Test
    void multipartPlanRespectsPartLimits() {
        List<MultipartSupport.Part> parts = MultipartSupport.plan(100L * 1024 * 1024 + 1, 16L * 1024 * 1024);
        assertEquals(7, parts.size());
        assertEquals(4L * 1024 * 1024 + 1, parts.get(6).length());
        assertEquals(100L * 1024 * 1024 + 1, parts.stream().mapToLong(MultipartSupport.Part::length).sum());

        long huge = 5L * 1024 * 1024 * 1024 * 1024; // 5 TiB
        List<MultipartSupport.Part> big = MultipartSupport.plan(huge, 16L * 1024 * 1024);
        assertTrue(big.size() <= MultipartSupport.MAX_PARTS);
        assertEquals(huge, big.stream().mapToLong(MultipartSupport.Part::length).sum());

        assertEquals(MultipartSupport.MIN_PART_SIZE, MultipartSupport.plan(20L * 1024 * 1024, 1024).get(0).length());
    }

    @Test
    void secretsAreOnlyStoredWhenRequestedAndOwnerReadable() throws Exception {
        Path profiles = temp.resolve("connections.json");
        Path secrets = temp.resolve("secrets.json");
        ConnectionStore store = new ConnectionStore(profiles, secrets);

        ConnectionProfile p = new ConnectionProfile();
        p.name = "MinIO";
        p.authMethod = AuthMethod.ACCESS_KEY;
        p.accessKeyId = "AKIAEXAMPLE";
        p.secretAccessKey = "super-secret-value";
        p.rememberSecret = false;
        store.save(p);
        assertFalse(Files.readString(profiles).contains("super-secret-value"));
        assertFalse(Files.exists(secrets));

        p.rememberSecret = true;
        store.save(p);
        assertFalse(Files.readString(profiles).contains("super-secret-value"));
        assertTrue(Files.readString(secrets).contains("super-secret-value"));
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(secrets)));
        }
        ConnectionProfile reloaded = new ConnectionStore(profiles, secrets).find(p.id).orElseThrow();
        assertEquals("super-secret-value", reloaded.secretAccessKey);
        assertFalse(reloaded.toString().contains("secret"));
    }

    @Test
    void unfinishedTransfersRoundTripButDeletesDoNot() {
        Path file = temp.resolve("transfers.json");
        TransferStore store = new TransferStore(file);
        TransferTask upload = TransferTask.upload(temp.resolve("big.iso"), "c1", S3Path.object("b", "big.iso"));
        upload.setUploadId("upload-123");
        upload.setTotal(1000);
        upload.setTransferred(400);
        upload.setStatus(TransferStatus.PAUSED);
        TransferTask download = TransferTask.download("c1", S3Path.object("b", "x.bin"), temp.resolve("x.bin"),
                ConflictPolicy.OVERWRITE);
        download.setRemoteETag("\"etag\"");
        download.setStatus(TransferStatus.FAILED);
        TransferTask delete = TransferTask.delete("c1", S3Path.root("b"), List.of(), "x");
        TransferTask done = TransferTask.upload(temp.resolve("a"), "c1", S3Path.object("b", "a"));
        done.setStatus(TransferStatus.COMPLETED);

        store.save(List.of(upload, download, delete, done));
        List<TransferTask> restored = store.load();
        assertEquals(2, restored.size());
        TransferTask u = restored.stream().filter(t -> t.type() == TransferType.UPLOAD).findFirst().orElseThrow();
        assertEquals("upload-123", u.uploadId());
        assertEquals(400, u.transferred());
        assertEquals(TransferStatus.PAUSED, u.status());
        TransferTask d = restored.stream().filter(t -> t.type() == TransferType.DOWNLOAD).findFirst().orElseThrow();
        assertEquals("\"etag\"", d.remoteETag());
        assertEquals(TransferStatus.FAILED, d.status());
        assertFalse(Files.exists(file), "file is consumed on load");
    }

    @Test
    void settingsPersist() {
        Path file = temp.resolve("settings.json");
        SettingsStore store = new SettingsStore(file);
        store.get().transfers.concurrentTransfers = 7;
        store.get().appearance.dualPane = true;
        store.save();
        SettingsStore reloaded = new SettingsStore(file);
        assertEquals(7, reloaded.get().transfers.concurrentTransfers);
        assertTrue(reloaded.get().appearance.dualPane);
        assertEquals(ConflictPolicy.RENAME, reloaded.get().general.downloadConflictPolicy);
    }
}
