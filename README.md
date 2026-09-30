# S3 Explorer

A desktop file browser and manager for Amazon S3 and S3-compatible storage (MinIO, Cloudflare R2,
Wasabi, Backblaze B2, DigitalOcean Spaces, Ceph, SeaweedFS…), built with Java 21, Swing and FlatLaf.
See [`documentation/project_outline.md`](documentation/project_outline.md) for the full specification.
This build implements the **v0.1 (MVP)** and **v0.2** scopes.

## Build and run

The JDK is pinned with SDKMAN in `setupEnv.sh` (21.0.6-zulu):

```sh
source "$HOME/.sdkman/bin/sdkman-init.sh" && source ./setupEnv.sh
./gradlew run                 # start the app
./gradlew installDist         # build/install/s3explorer/bin/s3explorer
./gradlew test                # unit tests (integration tests skip without an endpoint)
```

Application data lives in `~/.s3explorer/` (`settings.json`, `connections.json`, `transfers.json`,
`logs/application.log`). Set `JAVA_OPTS=-Ds3explorer.home=/some/dir` to use a separate profile.

### Integration tests

`S3IntegrationTest` runs end to end against a real S3 API when these variables are set. The
official MinIO images are no longer publicly pullable, so SeaweedFS works well locally:

```sh
echo '{"identities":[{"name":"it","credentials":[{"accessKey":"s3explorer","secretKey":"s3explorer-secret"}],"actions":["Admin","Read","List","Tagging","Write"]}]}' > /tmp/s3.json
docker run -d --rm --name s3explorer-it-s3 -p 127.0.0.1:19000:8333 -v /tmp/s3.json:/etc/s3.json:ro \
  chrislusf/seaweedfs server -s3 -s3.config=/etc/s3.json

S3E_IT_ENDPOINT=http://127.0.0.1:19000 S3E_IT_ACCESS_KEY=s3explorer S3E_IT_SECRET_KEY=s3explorer-secret ./gradlew test
```

They cover single and multipart uploads, pause/resume of multipart uploads and ranged downloads,
cancel (with multipart abort), folder uploads, server-side and cross-connection copies, folder
rename/move, metadata editing, presigned URLs, search, preview and recursive delete.

## Features

| Area | What is there |
|---|---|
| Connections | Multiple saved profiles; provider presets; AWS default chain, `~/.aws` profiles (incl. SSO / assume-role), environment, access key + secret (+ session token), anonymous; custom endpoint, region, path-style, timeouts; pinned buckets for keys that cannot `ListBuckets`; Test Connection |
| Browsing | Bucket tree with lazy folders, breadcrumb bar (click or Ctrl+L to type `s3://bucket/prefix/`), back/forward/up, sortable table (folders first), quick filter, paginated incremental listing, hidden-object toggle |
| Files | Upload files/folders (structure preserved, empty folders kept), drag-and-drop with confirmation, download files/folders with overwrite/skip/rename, create folder, rename (copy → delete, recursive), copy/cut/paste, Copy To/Move To (other buckets and other connections), delete with confirmation (count, size, bucket, prefix, versioning) |
| Transfers | Shared queue for upload/download/copy/move/delete; progress, speed, ETA, elapsed; pause/resume/cancel/retry/remove, pause-all/resume-all/clear; configurable concurrency; multipart upload (threshold, part size, parallel parts) with part-level retries; resumable multipart uploads and ranged downloads; unfinished transfers resume after restart; bandwidth limit |
| Inspect | Properties for files, folders (object count/size) and buckets (region, versioning); metadata editing (system headers + custom `x-amz-meta-*`); Copy S3 URI / object URL; presigned URLs (15 min – 7 days) |
| Search | Name wildcards, extension, modified after/before, size range, storage class; this folder, subfolders or whole bucket |
| Preview | Images, text, pretty JSON/XML, CSV as a table, Markdown; side pane or window; size-limited ranged fetch |
| Dual pane | Local filesystem panel: drag local → S3 to upload, S3 → local to download |
| Appearance | FlatLaf light/dark/system theme, font size, table density |

Architecture follows the spec: `ui` → `controller` → `service` → `aws` → AWS SDK v2. The UI never
calls the SDK directly, and nothing slow runs on the Event Dispatch Thread.

## Security notes

- Secret keys are kept in memory unless you tick *Remember secret key*. Only then are they written,
  to `~/.s3explorer/secrets.json` with owner-only (`rw-------`) permissions. Prefer AWS profiles or
  the default chain where possible.
- Secrets, session tokens, presigned URLs and auth headers are never logged. A Logback converter
  also masks signatures and keys as a safety net, and wire-level HTTP logging is disabled.
- Plain `http://` endpoints are allowed for local servers; remote ones show a warning.
- Deletes always ask for confirmation. Downloaded keys containing `..` or absolute paths cannot
  write outside the chosen folder. XML previews never resolve DTDs or external entities, and
  Markdown previews never load remote images.

## Deviations from the spec

- **"Confirm before deleting" setting omitted:** §36 requires that deletes always ask for confirmation.
- **Data directory:** `~/.s3explorer` rather than `~/.s3manager`, to match the S3 Explorer branding.
- **Version ID column:** Version IDs appear in Properties rather than as a table column, because normal listings do not return them. The version browser is v0.3 scope.
- **Dragging to the OS file manager:** S3 items can be dragged to the built-in local pane, but not out to the OS file manager (Swing would need the files to exist before the drop).
- **Folder keys on delete:** Deleting a folder also removes its folder keys (`dir/`), including implied intermediate ones. On AWS this is a no-op; on filer-backed servers such as SeaweedFS it stops emptied folders from lingering.
