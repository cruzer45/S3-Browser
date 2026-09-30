package com.s3explorer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Loads, holds and saves {@link AppSettings}. */
public class SettingsStore {

    private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);

    private final Path file;
    private final List<Consumer<AppSettings>> listeners = new CopyOnWriteArrayList<>();
    private volatile AppSettings settings;

    public SettingsStore(Path file) {
        this.file = file;
        this.settings = load();
    }

    private AppSettings load() {
        try {
            AppSettings loaded = JsonSupport.read(file, AppSettings.class, new AppSettings());
            if (loaded.general == null) loaded.general = new AppSettings.General();
            if (loaded.transfers == null) loaded.transfers = new AppSettings.Transfers();
            if (loaded.s3 == null) loaded.s3 = new AppSettings.S3();
            if (loaded.appearance == null) loaded.appearance = new AppSettings.Appearance();
            if (loaded.window == null) loaded.window = new AppSettings.WindowState();
            return loaded;
        } catch (IOException e) {
            log.warn("Could not read settings from {}; using defaults: {}", file, e.getMessage());
            return new AppSettings();
        }
    }

    public AppSettings get() {
        return settings;
    }

    public void save() {
        try {
            JsonSupport.write(file, settings, false);
        } catch (IOException e) {
            log.error("Could not save settings to {}", file, e);
        }
    }

    /** Saves and notifies listeners that settings changed. */
    public void commit() {
        save();
        listeners.forEach(l -> l.accept(settings));
    }

    public void addListener(Consumer<AppSettings> listener) {
        listeners.add(listener);
    }
}
