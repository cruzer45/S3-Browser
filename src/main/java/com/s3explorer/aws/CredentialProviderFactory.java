package com.s3explorer.aws;

import com.s3explorer.config.ConnectionProfile;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/** Builds an SDK credentials provider for a connection profile. */
public final class CredentialProviderFactory {

    private CredentialProviderFactory() {
    }

    public static AwsCredentialsProvider create(ConnectionProfile profile) {
        return switch (profile.authMethod) {
            case DEFAULT_CHAIN -> DefaultCredentialsProvider.builder().build();
            case PROFILE -> ProfileCredentialsProvider.builder()
                    .profileName(blankToNull(profile.awsProfile) == null ? "default" : profile.awsProfile.trim())
                    .build();
            case ENVIRONMENT -> EnvironmentVariableCredentialsProvider.create();
            case ACCESS_KEY -> {
                if (profile.accessKeyId == null || profile.accessKeyId.isBlank() || !profile.hasSecret()) {
                    throw new IllegalStateException("The access key and secret key are required for this connection.");
                }
                String token = blankToNull(profile.sessionToken);
                yield StaticCredentialsProvider.create(token == null
                        ? AwsBasicCredentials.create(profile.accessKeyId.trim(), profile.secretAccessKey)
                        : AwsSessionCredentials.create(profile.accessKeyId.trim(), profile.secretAccessKey, token));
            }
            case ANONYMOUS -> AnonymousCredentialsProvider.create();
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
