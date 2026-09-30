package com.s3explorer.service;

import com.s3explorer.aws.S3ClientFactory;
import com.s3explorer.aws.S3Connection;
import com.s3explorer.config.AuthMethod;
import com.s3explorer.config.ConnectionProfile;
import com.s3explorer.config.ConnectionStore;
import com.s3explorer.config.SettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns connection profiles and open SDK clients. Several connections can be open at once
 * (the active one in the browser plus any used by transfers or cross-connection copies).
 */
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);

    public interface Listener {
        default void activeConnectionChanged(S3Connection connection) {
        }

        default void profilesChanged() {
        }
    }

    private record SessionSecret(String secret, String token) {
    }

    private final ConnectionStore store;
    private final SettingsStore settings;
    private final Map<String, S3Connection> open = new ConcurrentHashMap<>();
    private final Map<String, SessionSecret> sessionSecrets = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile S3Connection active;

    public ConnectionService(ConnectionStore store, SettingsStore settings) {
        this.store = store;
        this.settings = settings;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public List<ConnectionProfile> profiles() {
        return store.list().stream().map(this::withSessionSecret).toList();
    }

    public Optional<ConnectionProfile> profile(String id) {
        return id == null ? Optional.empty() : store.find(id).map(this::withSessionSecret);
    }

    public String displayName(String id) {
        return profile(id).map(p -> p.name).orElse("(deleted connection)");
    }

    private ConnectionProfile withSessionSecret(ConnectionProfile p) {
        SessionSecret s = sessionSecrets.get(p.id);
        if (s != null && !p.hasSecret()) {
            p.secretAccessKey = s.secret();
            p.sessionToken = s.token();
        }
        return p;
    }

    /** Saves a profile. Any open client for it is closed so the next use picks up the changes. */
    public void saveProfile(ConnectionProfile profile) {
        if (profile.authMethod == AuthMethod.ACCESS_KEY && profile.hasSecret()) {
            sessionSecrets.put(profile.id, new SessionSecret(profile.secretAccessKey, profile.sessionToken));
        } else {
            sessionSecrets.remove(profile.id);
        }
        store.save(profile);
        S3Connection existing = open.remove(profile.id);
        if (existing != null && existing != active) {
            existing.close();
        }
        listeners.forEach(Listener::profilesChanged);
    }

    public void deleteProfile(String id) {
        store.delete(id);
        sessionSecrets.remove(id);
        S3Connection existing = open.remove(id);
        if (existing != null) {
            if (existing == active) {
                setActive(null);
            }
            existing.close();
        }
        listeners.forEach(Listener::profilesChanged);
    }

    /** True when the profile needs a secret key that is not available yet. */
    public boolean needsSecret(ConnectionProfile profile) {
        return profile.authMethod == AuthMethod.ACCESS_KEY && !profile.hasSecret();
    }

    public void setSessionSecret(String id, String secret, String token) {
        sessionSecrets.put(id, new SessionSecret(secret, token));
    }

    /** Returns an open connection for the profile, creating the client if needed. */
    public S3Connection get(String id) {
        S3Connection existing = open.get(id);
        if (existing != null) {
            return existing;
        }
        ConnectionProfile profile = profile(id)
                .orElseThrow(() -> new IllegalStateException("The connection no longer exists."));
        synchronized (this) {
            existing = open.get(id);
            if (existing != null) {
                return existing;
            }
            S3Connection connection = S3ClientFactory.create(profile, settings.get().transfers);
            open.put(id, connection);
            log.info("Opened connection '{}' (endpoint={}, region={}, auth={})", profile.name,
                    profile.endpoint == null || profile.endpoint.isBlank() ? "AWS" : profile.endpoint,
                    connection.region().id(), profile.authMethod.name());
            return connection;
        }
    }

    /** Builds a throwaway client for testing a profile that may not be saved yet. */
    public S3Connection openTemporary(ConnectionProfile profile) {
        return S3ClientFactory.create(profile, settings.get().transfers);
    }

    public S3Connection active() {
        return active;
    }

    public void setActive(S3Connection connection) {
        this.active = connection;
        if (connection != null) {
            settings.get().general.lastConnectionId = connection.id();
            settings.save();
        }
        listeners.forEach(l -> l.activeConnectionChanged(connection));
    }

    /** Drops the cached client so the next {@link #get} rebuilds it (e.g. after settings change). */
    public void reset(String id) {
        S3Connection existing = open.remove(id);
        if (existing != null && existing != active) {
            existing.close();
        }
    }

    public void closeAll() {
        open.values().forEach(S3Connection::close);
        open.clear();
    }
}
