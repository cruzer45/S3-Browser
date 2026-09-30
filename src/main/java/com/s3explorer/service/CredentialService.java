package com.s3explorer.service;

import software.amazon.awssdk.profiles.ProfileFile;

import java.util.List;
import java.util.TreeSet;

/** Read-only access to locally configured AWS credential profiles (names only, never secrets). */
public class CredentialService {

    public List<String> awsProfileNames() {
        TreeSet<String> names = new TreeSet<>();
        try {
            names.addAll(ProfileFile.defaultProfileFile().profiles().keySet());
        } catch (RuntimeException ignored) {
            // No ~/.aws files, or unreadable ones.
        }
        if (names.isEmpty()) {
            names.add("default");
        }
        return List.copyOf(names);
    }

    public boolean environmentCredentialsPresent() {
        String key = System.getenv("AWS_ACCESS_KEY_ID");
        return key != null && !key.isBlank();
    }
}
