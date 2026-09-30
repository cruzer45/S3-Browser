package com.s3explorer.util;

import java.util.regex.Pattern;

/** Case-insensitive glob matching supporting {@code *} and {@code ?}. */
public final class Wildcard {

    private Wildcard() {
    }

    /**
     * Compiles a pattern. Patterns without wildcards match as a substring, so typing
     * "report" finds "annual-report.pdf".
     */
    public static Pattern compile(String glob) {
        if (glob == null || glob.isBlank()) {
            return null;
        }
        String trimmed = glob.trim();
        boolean hasWildcard = trimmed.indexOf('*') >= 0 || trimmed.indexOf('?') >= 0;
        StringBuilder regex = new StringBuilder();
        if (!hasWildcard) {
            regex.append(".*");
        }
        for (char c : trimmed.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (!hasWildcard) {
            regex.append(".*");
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }

    public static boolean matches(Pattern pattern, String text) {
        return pattern == null || pattern.matcher(text).matches();
    }
}
