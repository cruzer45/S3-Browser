package com.s3explorer.config;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists connection profiles to {@code connections.json}. Secrets are only written when the
 * user explicitly opts in ("remember secret"), and then to a separate {@code secrets.json}
 * readable only by the owner. Profiles using AWS profiles or the default chain never store
 * secrets at all.
 */
public class ConnectionStore {

    private static final Logger log = LoggerFactory.getLogger(ConnectionStore.class);

    private final Path profilesFile;
    private final Path secretsFile;
    private final List<ConnectionProfile> profiles = new ArrayList<>();

    record Secret(String secretAccessKey, String sessionToken) {
    }

    public ConnectionStore(Path profilesFile, Path secretsFile) {
        this.profilesFile = profilesFile;
        this.secretsFile = secretsFile;
        load();
    }

    private void load() {
        try {
            if (Files.isRegularFile(profilesFile)) {
                profiles.addAll(JsonSupport.MAPPER.readValue(profilesFile.toFile(),
                        new TypeReference<List<ConnectionProfile>>() { }));
            }
            Map<String, Secret> secrets = readSecrets();
            for (ConnectionProfile p : profiles) {
                Secret s = secrets.get(p.id);
                if (s != null && p.rememberSecret) {
                    p.secretAccessKey = s.secretAccessKey();
                    p.sessionToken = s.sessionToken();
                }
            }
        } catch (IOException e) {
            log.error("Could not read connection profiles from {}", profilesFile, e);
        }
    }

    private Map<String, Secret> readSecrets() throws IOException {
        if (!Files.isRegularFile(secretsFile)) {
            return new LinkedHashMap<>();
        }
        return JsonSupport.MAPPER.readValue(secretsFile.toFile(), new TypeReference<LinkedHashMap<String, Secret>>() { });
    }

    public synchronized List<ConnectionProfile> list() {
        return profiles.stream().map(ConnectionProfile::copy).toList();
    }

    public synchronized Optional<ConnectionProfile> find(String id) {
        return profiles.stream().filter(p -> p.id.equals(id)).findFirst().map(ConnectionProfile::copy);
    }

    public synchronized void save(ConnectionProfile profile) {
        ConnectionProfile stored = profile.copy();
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(stored.id)) {
                profiles.set(i, stored);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            profiles.add(stored);
        }
        persist();
    }

    public synchronized void delete(String id) {
        profiles.removeIf(p -> p.id.equals(id));
        persist();
    }

    private void persist() {
        try {
            JsonSupport.write(profilesFile, profiles, false);
            Map<String, Secret> secrets = new LinkedHashMap<>();
            for (ConnectionProfile p : profiles) {
                if (p.rememberSecret && p.authMethod == AuthMethod.ACCESS_KEY && p.hasSecret()) {
                    secrets.put(p.id, new Secret(p.secretAccessKey, p.sessionToken));
                }
            }
            if (secrets.isEmpty()) {
                Files.deleteIfExists(secretsFile);
            } else {
                JsonSupport.write(secretsFile, secrets, true);
            }
        } catch (IOException e) {
            log.error("Could not save connection profiles", e);
        }
    }
}
