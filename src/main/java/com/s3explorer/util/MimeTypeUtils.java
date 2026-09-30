package com.s3explorer.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Content-type detection and friendly type descriptions based on file extensions. */
public final class MimeTypeUtils {

    public static final String DEFAULT_TYPE = "application/octet-stream";

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("txt", "text/plain"),
            Map.entry("log", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("tsv", "text/tab-separated-values"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("css", "text/css"),
            Map.entry("js", "text/javascript"),
            Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("yaml", "application/yaml"),
            Map.entry("yml", "application/yaml"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"),
            Map.entry("gz", "application/gzip"),
            Map.entry("tgz", "application/gzip"),
            Map.entry("tar", "application/x-tar"),
            Map.entry("7z", "application/x-7z-compressed"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("webm", "video/webm"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("jar", "application/java-archive"),
            Map.entry("wasm", "application/wasm"));

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("pdf", "PDF Document"),
            Map.entry("txt", "Text File"),
            Map.entry("log", "Log File"),
            Map.entry("csv", "CSV File"),
            Map.entry("json", "JSON File"),
            Map.entry("xml", "XML File"),
            Map.entry("md", "Markdown File"),
            Map.entry("html", "HTML Document"),
            Map.entry("zip", "ZIP Archive"),
            Map.entry("gz", "GZip Archive"),
            Map.entry("tar", "Tar Archive"),
            Map.entry("png", "PNG Image"),
            Map.entry("jpg", "JPEG Image"),
            Map.entry("jpeg", "JPEG Image"),
            Map.entry("gif", "GIF Image"),
            Map.entry("svg", "SVG Image"),
            Map.entry("mp4", "MP4 Video"),
            Map.entry("mp3", "MP3 Audio"),
            Map.entry("docx", "Word Document"),
            Map.entry("xlsx", "Excel Workbook"),
            Map.entry("pptx", "PowerPoint Presentation"));

    private static final Set<String> IMAGE_PREVIEW = Set.of("png", "jpg", "jpeg", "gif", "bmp");
    private static final Set<String> TEXT_PREVIEW = Set.of(
            "txt", "log", "csv", "tsv", "json", "xml", "md", "markdown", "yaml", "yml", "properties",
            "ini", "conf", "cfg", "toml", "html", "htm", "css", "js", "ts", "java", "groovy", "gradle",
            "kt", "py", "rb", "go", "rs", "c", "h", "cpp", "sh", "bash", "zsh", "sql", "env", "gitignore");

    private MimeTypeUtils() {
    }

    public static String extension(String name) {
        if (name == null) {
            return "";
        }
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = name.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        return dot <= 0 || dot == base.length() - 1 ? "" : base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String contentType(String name) {
        return TYPES.getOrDefault(extension(name), DEFAULT_TYPE);
    }

    public static String contentType(Path file) {
        String known = TYPES.get(extension(file.getFileName().toString()));
        if (known != null) {
            return known;
        }
        try {
            String probed = Files.probeContentType(file);
            return probed != null ? probed : DEFAULT_TYPE;
        } catch (IOException e) {
            return DEFAULT_TYPE;
        }
    }

    public static String describe(String name) {
        String ext = extension(name);
        if (ext.isEmpty()) {
            return "File";
        }
        return DESCRIPTIONS.getOrDefault(ext, ext.toUpperCase(Locale.ROOT) + " File");
    }

    public static boolean isImagePreviewable(String name) {
        return IMAGE_PREVIEW.contains(extension(name));
    }

    public static boolean isTextPreviewable(String name) {
        String ext = extension(name);
        return TEXT_PREVIEW.contains(ext) || (ext.isEmpty() && !name.endsWith("/"));
    }

    public static boolean isPreviewable(String name) {
        return isImagePreviewable(name) || isTextPreviewable(name);
    }
}
