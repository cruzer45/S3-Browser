package com.s3explorer.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

/** Generates non-conflicting names such as "report (1).pdf". */
public final class NameConflicts {

    private NameConflicts() {
    }

    public static Path uniquePath(Path target) {
        Path parent = target.getParent();
        String name = target.getFileName().toString();
        String unique = uniqueName(name, candidate -> Files.exists(parent == null ? Path.of(candidate) : parent.resolve(candidate)));
        return parent == null ? Path.of(unique) : parent.resolve(unique);
    }

    /** Returns {@code name} if unused, otherwise "base (n).ext" with the smallest free n. */
    public static String uniqueName(String name, Predicate<String> exists) {
        if (!exists.test(name)) {
            return name;
        }
        boolean folder = name.endsWith("/");
        String bare = folder ? name.substring(0, name.length() - 1) : name;
        int dot = folder ? -1 : bare.lastIndexOf('.');
        String base = dot > 0 ? bare.substring(0, dot) : bare;
        String ext = dot > 0 ? bare.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            String candidate = base + " (" + i + ")" + ext + (folder ? "/" : "");
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find a free name for " + name);
    }
}
