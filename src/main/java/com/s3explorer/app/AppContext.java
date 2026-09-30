package com.s3explorer.app;

import com.s3explorer.config.AppPaths;
import com.s3explorer.config.ConnectionStore;
import com.s3explorer.config.SettingsStore;
import com.s3explorer.service.ConnectionService;
import com.s3explorer.service.CredentialService;
import com.s3explorer.service.MetadataService;
import com.s3explorer.service.PresignedUrlService;
import com.s3explorer.service.PreviewService;
import com.s3explorer.service.S3BrowserService;
import com.s3explorer.service.SearchService;
import com.s3explorer.service.TransferPlanner;
import com.s3explorer.service.TransferService;
import com.s3explorer.service.TransferStore;

/** Wires the application's services together. */
public final class AppContext {

    public static final String APP_NAME = "S3 Explorer";
    public static final String VERSION = "0.2.0";

    public final SettingsStore settings;
    public final ConnectionService connections;
    public final CredentialService credentials;
    public final S3BrowserService browser;
    public final TransferService transfers;
    public final TransferPlanner planner;
    public final PresignedUrlService presigner;
    public final SearchService search;
    public final MetadataService metadata;
    public final PreviewService preview;

    public AppContext() {
        settings = new SettingsStore(AppPaths.settingsFile());
        connections = new ConnectionService(
                new ConnectionStore(AppPaths.connectionsFile(), AppPaths.secretsFile()), settings);
        credentials = new CredentialService();
        browser = new S3BrowserService();
        transfers = new TransferService(settings, connections, new TransferStore(AppPaths.transfersFile()));
        planner = new TransferPlanner(connections, browser);
        presigner = new PresignedUrlService(browser);
        search = new SearchService(browser);
        metadata = new MetadataService();
        preview = new PreviewService();
    }
}
