package com.s3explorer.service;

import com.s3explorer.aws.S3Connection;
import com.s3explorer.model.ConflictPolicy;
import com.s3explorer.model.ObjectRef;
import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.model.S3Path;
import com.s3explorer.model.TransferTask;
import com.s3explorer.util.UserFacingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Expands user selections (files, folders, prefixes) into individual transfer tasks. Runs
 * off the EDT: folder operations enumerate the local filesystem or S3.
 */
public class TransferPlanner {

    private static final Logger log = LoggerFactory.getLogger(TransferPlanner.class);

    private final ConnectionService connections;
    private final S3BrowserService browser;

    public TransferPlanner(ConnectionService connections, S3BrowserService browser) {
        this.connections = connections;
        this.browser = browser;
    }

    // --- uploads -------------------------------------------------------------------------

    public record UploadPlan(List<Path> sources, S3Path destination, List<TransferTask> tasks,
                             List<S3Path> emptyFolders, long totalBytes) {
        public int fileCount() {
            return tasks.size();
        }
    }

    /** Walks local files and folders; folder structure is preserved under {@code destination}. */
    public UploadPlan planUpload(List<Path> sources, String connectionId, S3Path destination,
                                 BooleanSupplier cancelled, Consumer<Integer> progress) throws IOException {
        List<TransferTask> tasks = new ArrayList<>();
        List<S3Path> emptyFolders = new ArrayList<>();
        long[] total = {0};
        for (Path source : sources) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            if (Files.isDirectory(source)) {
                Path base = source.getParent() == null ? source : source.getParent();
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (cancelled.getAsBoolean()) {
                            return FileVisitResult.TERMINATE;
                        }
                        try (var children = Files.list(dir)) {
                            if (children.findAny().isEmpty()) {
                                emptyFolders.add(S3Path.folder(destination.bucket(),
                                        destination.key() + relativeKey(base, dir) + "/"));
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()) {
                            tasks.add(TransferTask.upload(file, connectionId,
                                    S3Path.object(destination.bucket(), destination.key() + relativeKey(base, file))));
                            total[0] += attrs.size();
                            if (tasks.size() % 200 == 0) {
                                progress.accept(tasks.size());
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        log.warn("Skipping unreadable path {}: {}", file, e.toString());
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else if (Files.isRegularFile(source)) {
                tasks.add(TransferTask.upload(source, connectionId,
                        S3Path.object(destination.bucket(), destination.key() + source.getFileName())));
                total[0] += Files.size(source);
            }
        }
        progress.accept(tasks.size());
        return new UploadPlan(List.copyOf(sources), destination, tasks, emptyFolders, total[0]);
    }

    /** Relative path from {@code base} using "/" separators, e.g. "reports/2025/annual.pdf". */
    static String relativeKey(Path base, Path path) {
        Path rel = base.relativize(path);
        List<String> parts = new ArrayList<>();
        for (Path p : rel) {
            parts.add(p.toString());
        }
        return String.join("/", parts);
    }

    public void createEmptyFolders(String connectionId, List<S3Path> folders) {
        S3Connection connection = connections.get(connectionId);
        for (S3Path folder : folders) {
            browser.createFolder(connection, folder);
        }
    }

    // --- downloads -----------------------------------------------------------------------

    public List<TransferTask> planDownload(String connectionId, List<S3ObjectItem> items, Path targetDir,
                                           ConflictPolicy policy, BooleanSupplier cancelled,
                                           Consumer<Integer> progress) throws IOException {
        S3Connection connection = connections.get(connectionId);
        Path root = targetDir.toAbsolutePath().normalize();
        List<TransferTask> tasks = new ArrayList<>();
        for (S3ObjectItem item : items) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            if (item.isFile()) {
                Path target = safeResolve(root, item.name());
                if (target != null) {
                    tasks.add(TransferTask.download(connectionId, item.path(), target, policy));
                }
            } else if (item.isContainer()) {
                S3Path folder = item.path();
                String parentPrefix = folder.parent() == null ? "" : folder.parent().key();
                String folderName = item.kind() == S3ObjectItem.Kind.BUCKET ? folder.bucket() + "/" : "";
                browser.listRecursive(connection, folder.bucket(), folder.key(), cancelled, page -> {
                    for (S3Object o : page) {
                        String relative = folderName + o.key().substring(parentPrefix.length());
                        Path target = safeResolve(root, relative);
                        if (target == null) {
                            log.warn("Skipping object with unsafe key {}", o.key());
                            continue;
                        }
                        if (o.key().endsWith("/")) {
                            try {
                                Files.createDirectories(target);
                            } catch (IOException e) {
                                log.warn("Could not create {}: {}", target, e.toString());
                            }
                        } else {
                            tasks.add(TransferTask.download(connectionId, S3Path.object(folder.bucket(), o.key()),
                                    target, policy));
                        }
                    }
                    progress.accept(tasks.size());
                });
            }
        }
        return tasks;
    }

    /**
     * Resolves a key-derived relative path under {@code root}, refusing anything that would
     * escape it (keys can legally contain "..", absolute segments or backslashes).
     */
    static Path safeResolve(Path root, String relative) {
        String cleaned = relative.replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        for (String segment : cleaned.split("/")) {
            if (segment.equals("..") || segment.contains(":") && System.getProperty("os.name").startsWith("Windows")) {
                return null;
            }
        }
        Path resolved = root.resolve(cleaned).normalize();
        return resolved.startsWith(root) && !resolved.equals(root) ? resolved : null;
    }

    // --- copy / move / rename --------------------------------------------------------------

    /**
     * Plans a copy or move of items into {@code destFolder}. {@code renames} maps a top-level
     * item to a different destination name (used for "keep both" and rename).
     */
    public List<TransferTask> planCopy(String sourceConnectionId, List<S3ObjectItem> items, String destConnectionId,
                                       S3Path destFolder, boolean move, Map<S3Path, String> renames,
                                       BooleanSupplier cancelled, Consumer<Integer> progress) {
        S3Connection source = connections.get(sourceConnectionId);
        boolean sameConnection = sourceConnectionId.equals(destConnectionId);
        List<TransferTask> tasks = new ArrayList<>();
        for (S3ObjectItem item : items) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            String name = renames.getOrDefault(item.path(), item.name());
            if (item.isFile()) {
                tasks.add(TransferTask.copy(sourceConnectionId, item.path(), destConnectionId,
                        S3Path.object(destFolder.bucket(), destFolder.key() + name), move));
            } else if (item.kind() == S3ObjectItem.Kind.FOLDER) {
                S3Path folder = item.path();
                String newPrefix = destFolder.key() + name + "/";
                if (sameConnection && folder.bucket().equals(destFolder.bucket())
                        && newPrefix.startsWith(folder.key())) {
                    throw new UserFacingException("Invalid destination.",
                            "A folder cannot be copied or moved into itself.");
                }
                browser.listRecursive(source, folder.bucket(), folder.key(), cancelled, page -> {
                    for (S3Object o : page) {
                        String newKey = newPrefix + o.key().substring(folder.key().length());
                        tasks.add(TransferTask.copy(sourceConnectionId, S3Path.object(folder.bucket(), o.key()),
                                destConnectionId, S3Path.object(destFolder.bucket(), newKey), move));
                    }
                    progress.accept(tasks.size());
                });
            }
        }
        return tasks;
    }

    /** Rename is a move within the same folder. */
    public List<TransferTask> planRename(String connectionId, S3ObjectItem item, String newName,
                                         BooleanSupplier cancelled, Consumer<Integer> progress) {
        validateName(newName);
        S3Path parent = item.path().parent();
        if (parent == null) {
            throw new UserFacingException("Cannot rename.", "Buckets cannot be renamed.");
        }
        S3Connection connection = connections.get(connectionId);
        S3Path target = item.isFile() ? parent.resolveObject(newName) : parent.resolveFolder(newName);
        if (browser.exists(connection, target)) {
            throw new UserFacingException("Name already in use.",
                    "An item named \"" + newName + "\" already exists in this folder.");
        }
        return planCopy(connectionId, List.of(item), connectionId, parent, true,
                Map.of(item.path(), newName), cancelled, progress);
    }

    public static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new UserFacingException("Invalid name.", "The name cannot be empty.");
        }
        if (name.contains("/")) {
            throw new UserFacingException("Invalid name.", "The name cannot contain \"/\".");
        }
        if (name.equals(".") || name.equals("..")) {
            throw new UserFacingException("Invalid name.", "\".\" and \"..\" are not valid names.");
        }
    }

    /** Top-level names from {@code items} that already exist in {@code destFolder}. */
    public Set<S3Path> findConflicts(String destConnectionId, List<S3ObjectItem> items, S3Path destFolder) {
        S3Connection connection = connections.get(destConnectionId);
        Set<S3Path> conflicts = new LinkedHashSet<>();
        for (S3ObjectItem item : items) {
            S3Path target = item.isFile() ? destFolder.resolveObject(item.name()) : destFolder.resolveFolder(item.name());
            if (browser.exists(connection, target)) {
                conflicts.add(item.path());
            }
        }
        return conflicts;
    }

    // --- delete --------------------------------------------------------------------------

    /**
     * @param impliedMarkers folder keys added to {@code refs} without having been listed. Deleting a
     *                       missing key is a no-op on S3, but filer-backed servers (e.g. SeaweedFS)
     *                       keep reporting an emptied folder until its own key is deleted.
     */
    public record DeletePlan(S3Path scope, List<ObjectRef> refs, long totalBytes, int selectedCount,
                             String versioningStatus, int impliedMarkers) {
        /** Objects that actually exist, as shown to the user. */
        public int objectCount() {
            return refs.size() - impliedMarkers;
        }
    }

    public DeletePlan planDelete(String connectionId, S3Path scope, List<S3ObjectItem> items,
                                 BooleanSupplier cancelled, Consumer<Integer> progress) {
        S3Connection connection = connections.get(connectionId);
        List<ObjectRef> refs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int implied = 0;
        for (S3ObjectItem item : items) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            if (item.isFile()) {
                if (seen.add(item.path().key())) {
                    refs.add(new ObjectRef(item.path().key(), Math.max(0, item.size())));
                }
            } else if (item.kind() == S3ObjectItem.Kind.FOLDER) {
                String folderKey = item.path().key();
                Set<String> folders = new java.util.TreeSet<>(
                        java.util.Comparator.comparingInt(String::length).reversed().thenComparing(k -> k));
                folders.add(folderKey);
                for (ObjectRef ref : browser.collect(connection, item.path().bucket(), folderKey, cancelled,
                        n -> progress.accept(refs.size() + n))) {
                    if (seen.add(ref.key())) {
                        refs.add(ref);
                    }
                    // Every intermediate folder between this object and the selected folder.
                    for (int slash = ref.key().lastIndexOf('/', ref.key().length() - 2);
                         slash >= folderKey.length(); slash = ref.key().lastIndexOf('/', slash - 1)) {
                        folders.add(ref.key().substring(0, slash + 1));
                    }
                }
                // Folder keys go last, deepest first, so each folder is empty when it is deleted.
                for (String folder : folders) {
                    if (seen.add(folder)) {
                        refs.add(new ObjectRef(folder, 0));
                        implied++;
                    }
                }
            }
        }
        long total = refs.stream().mapToLong(ObjectRef::size).sum();
        String versioning = browser.versioningStatus(connection, scope.bucket());
        return new DeletePlan(scope, refs, total, items.size(), versioning, implied);
    }
}
