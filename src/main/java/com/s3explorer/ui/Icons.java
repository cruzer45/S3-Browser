package com.s3explorer.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.s3explorer.model.S3ObjectItem;
import com.s3explorer.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** SVG icons from {@code /icons}; FlatLaf recolours the grey palette for dark themes. */
public final class Icons {

    private static final Map<String, FlatSVGIcon> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> ARCHIVES = Set.of("zip", "gz", "tgz", "tar", "7z", "rar", "bz2", "xz", "jar");

    private Icons() {
    }

    public static Icon get(String name) {
        return get(name, 16);
    }

    public static Icon get(String name, int size) {
        return CACHE.computeIfAbsent(name + "@" + size, k -> new FlatSVGIcon("icons/" + name + ".svg", size, size));
    }

    public static Icon forItem(S3ObjectItem item) {
        return switch (item.kind()) {
            case PARENT -> get("up");
            case BUCKET -> get("bucket");
            case FOLDER -> get("folder");
            case FILE -> forFileName(item.name());
        };
    }

    public static Icon forFileName(String name) {
        String ext = MimeTypeUtils.extension(name);
        if (MimeTypeUtils.isImagePreviewable(name) || "svg".equals(ext) || "webp".equals(ext)) {
            return get("file-image");
        }
        if (ARCHIVES.contains(ext)) {
            return get("file-archive");
        }
        if (!ext.isEmpty() && MimeTypeUtils.isTextPreviewable(name)) {
            return get("file-text");
        }
        return get("file");
    }

    public static List<Image> appImages() {
        List<Image> images = new ArrayList<>();
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            Image image = image("images/app-icon-" + size + ".png");
            if (image != null) {
                images.add(image);
            }
        }
        return images;
    }

    public static ImageIcon logo() {
        Image image = image("images/logo-horizontal.png");
        return image == null ? null : new ImageIcon(image);
    }

    public static ImageIcon appIcon(int size) {
        Image image = image("images/app-icon-" + size + ".png");
        return image == null ? null : new ImageIcon(image);
    }

    private static Image image(String resource) {
        try (InputStream in = Icons.class.getClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }
}
