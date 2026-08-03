# Outdoor Monitor Android

Android client for the Outdoor Monitor weather monitoring system. The application
connects to the Outdoor Monitor API and displays the latest temperature and
humidity readings collected from outdoor sensors.

The API is provided by a separate backend repository. The backend reads sensor
metrics from Prometheus, stores periodic snapshots, and
serves them through an authenticated HTTP API.

## Configuration

Copy `local.properties.example` to `local.properties` and provide values for:

- `DEBUG_API_BASE_URL`
- `RELEASE_API_BASE_URL`
- `GOOGLE_WEB_CLIENT_ID`
- `POLL_INTERVAL_SECONDS`

`local.properties` is ignored by Git and must not contain values intended for
committing to the repository.

## Build

Build the debug application:

```bash
./gradlew assembleDebug
```

Build the release application:

```bash
./gradlew assembleRelease
```

## Docker releases

`docker/Dockerfile.apk` builds and signs the application and exports a versioned APK,
`manifest.json`, and `latest.json`. Version values are supplied as `VERSION_NAME` and
`VERSION_CODE`; signing material is passed only as BuildKit secrets.

The GitHub Actions workflow uses a self-hosted Linux runner. Every `master` build uses
`1000 + github.run_number` as Android `versionCode`; an `app-v1.2.0` tag or manual input sets
the visible `versionName`. The runner reads its API URL, publication root, and Google
client ID from the `RELEASE_API_BASE_URL`, `RELEASES_DIR`, `SIGNING_DIR`, and
`GOOGLE_WEB_CLIENT_ID` repository variables. On a single build/application host, use
`/srv/outdoor-monitor/releases` for published artifacts and keep signing material
separately in `/var/lib/outdoor-monitor-builder/signing`.

The workflow uses the GitHub environment named `dev`. Configure these under
**Settings → Environments → dev → Environment variables**:

- `RELEASE_API_BASE_URL` — public backend base URL.
- `RELEASES_DIR` — `/srv/outdoor-monitor/releases` on the self-hosted runner.
- `SIGNING_DIR` — `/var/lib/outdoor-monitor-builder/signing` on the runner.

Configure `GOOGLE_WEB_CLIENT_ID` separately under
**Settings → Environments → dev → Environment secrets**.

Signing passwords are intentionally not stored in GitHub or Compose. Create
`$SIGNING_DIR/release.env` directly on the runner with mode `600`:

```text
SIGNING_STORE_PASSWORD=<value>
SIGNING_KEY_PASSWORD=<value>
SIGNING_KEY_ALIAS=release
```

Store the keystore as `$SIGNING_DIR/release.jks`, also with mode `600`. The runner
service account must be able to read both files and write to `RELEASES_DIR`.
