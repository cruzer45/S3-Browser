package com.s3explorer.config;

import com.s3explorer.model.ConflictPolicy;

import java.nio.file.Path;

/** User preferences persisted to {@code settings.json}. Plain mutable fields for Jackson. */
public class AppSettings {

    public General general = new General();
    public Transfers transfers = new Transfers();
    public S3 s3 = new S3();
    public Appearance appearance = new Appearance();
    public WindowState window = new WindowState();

    public enum DoubleClickAction {
        PROPERTIES("Show properties"),
        PREVIEW("Preview"),
        DOWNLOAD("Download");

        private final String label;

        DoubleClickAction(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Theme {
        SYSTEM("System"),
        LIGHT("Light"),
        DARK("Dark");

        private final String label;

        Theme(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum TableDensity {
        COMPACT("Compact", 20),
        NORMAL("Normal", 24),
        COMFORTABLE("Comfortable", 30);

        private final String label;
        private final int rowHeight;

        TableDensity(String label, int rowHeight) {
            this.label = label;
            this.rowHeight = rowHeight;
        }

        public int rowHeight() {
            return rowHeight;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static class General {
        public String defaultDownloadDir = Path.of(System.getProperty("user.home"), "Downloads").toString();
        public DoubleClickAction doubleClickAction = DoubleClickAction.PROPERTIES;
        public boolean showHiddenObjects = true;
        public boolean restoreWindow = true;
        public boolean restoreConnection = true;
        public boolean confirmDragDropUpload = true;
        public boolean allowBucketAdmin = false;
        public boolean persistTransfers = true;
        public ConflictPolicy downloadConflictPolicy = ConflictPolicy.RENAME;
        public long previewMaxKb = 5 * 1024;
        public String lastConnectionId;
    }

    public static class Transfers {
        public int concurrentTransfers = 3;
        public int multipartThresholdMb = 100;
        public int partSizeMb = 16;
        public int parallelParts = 4;
        public int retryCount = 3;
        public int retryDelaySeconds = 2;
        public int connectTimeoutSeconds = 10;
        /** Socket read timeout: how long a request may stall without receiving data. */
        public int requestTimeoutSeconds = 60;
        /** Total bandwidth cap across all transfers in KB/s; 0 means unlimited. */
        public long bandwidthLimitKbps = 0;

        public long multipartThresholdBytes() {
            return Math.max(5, multipartThresholdMb) * 1024L * 1024L;
        }

        public long partSizeBytes() {
            return Math.max(5, partSizeMb) * 1024L * 1024L;
        }
    }

    public static class S3 {
        public String defaultRegion = "us-east-1";
        public String defaultEndpoint = "";
        public boolean pathStyleAccess = false;
        public String defaultConnectionId;
    }

    public static class Appearance {
        public Theme theme = Theme.SYSTEM;
        /** 0 keeps the look-and-feel default. */
        public int fontSize = 0;
        public TableDensity tableDensity = TableDensity.NORMAL;
        public boolean previewPaneVisible = false;
        public boolean dualPane = false;
        public boolean transfersVisible = true;
    }

    public static class WindowState {
        public int x = -1;
        public int y = -1;
        public int width = 1200;
        public int height = 800;
        public boolean maximized = false;
        public int treeDivider = 240;
        /** Height of the transfer queue pane. */
        public int transferDivider = -1;
        public int localDivider = 420;
        /** Width of the preview pane. */
        public int previewDivider = -1;
        public String lastLocalDir;
    }
}
