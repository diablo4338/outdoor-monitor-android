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
client ID from the `RELEASE_API_BASE_URL`, `RELEASE_ROOT`, and `GOOGLE_WEB_CLIENT_ID`
repository variables. Signing material stays under `$RELEASE_ROOT/signing`, and
releases are atomically published under `$RELEASE_ROOT/releases`.
