package com.s3explorer.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locations of application data. Everything lives under {@code ~/.s3explorer} unless the
 * {@code s3explorer.home} system property points elsewhere (used by tests).
 */
public final class AppPaths {

    public static final String HOME_PROPERTY = "s3explorer.home";

    private AppPaths() {
    }

    public static Path baseDir() {
        String override = System.getProperty(HOME_PROPERTY);
        return override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".s3explorer");
    }

    public static Path settingsFile() {
        return baseDir().resolve("settings.json");
    }

    public static Path connectionsFile() {
        return baseDir().resolve("connections.json");
    }

    public static Path secretsFile() {
        return baseDir().resolve("secrets.json");
    }

    public static Path transfersFile() {
        return baseDir().resolve("transfers.json");
    }

    public static Path logsDir() {
        return baseDir().resolve("logs");
    }

    public static void ensureDirectories() {
        try {
            Files.createDirectories(baseDir());
            Files.createDirectories(logsDir());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create application directory " + baseDir(), e);
        }
    }
}
