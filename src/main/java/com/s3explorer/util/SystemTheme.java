package com.s3explorer.util;

import com.formdev.flatlaf.util.SystemInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Best-effort detection of the operating system's dark mode preference. */
public final class SystemTheme {

    private SystemTheme() {
    }

    public static boolean isDark() {
        try {
            if (SystemInfo.isMacOS) {
                return run("defaults", "read", "-g", "AppleInterfaceStyle").toLowerCase(Locale.ROOT).contains("dark");
            }
            if (SystemInfo.isWindows) {
                String out = run("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme");
                return out.contains("0x0");
            }
            if (SystemInfo.isLinux) {
                String scheme = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
                if (scheme.contains("dark")) {
                    return true;
                }
                String theme = run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme");
                return theme.toLowerCase(Locale.ROOT).contains("dark");
            }
        } catch (Exception ignored) {
            // Fall through to light.
        }
        return false;
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        return out.toString();
    }
}
