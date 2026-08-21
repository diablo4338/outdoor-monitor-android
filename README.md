# Outdoor Monitor Android

The Android client displays the latest primary and external sensor readings, supports
Google and password authentication, and checks for new application versions through
`GET /api/v1/app/latest`.

## Development configuration

Copy `local.properties.example` to the Git-ignored `local.properties` file:

```properties
DEBUG_API_BASE_URL=http://your-backend-host:8000
DEBUG_API_FALLBACK_BASE_URL=
RELEASE_API_BASE_URL=https://your-api.example.com
RELEASE_API_FALLBACK_BASE_URL=https://your-fallback-api.example.com
GOOGLE_WEB_CLIENT_ID=your-google-web-client-id
POLL_INTERVAL_SECONDS=10
```

Use `10.0.2.2` to reach the host from Android Emulator. Use the computer's LAN address
for a physical device. Debug builds allow local HTTP traffic, while release builds
continue to reject cleartext traffic.

The fallback URL is optional. Requests retry network failures and HTTP `5xx` responses
with 0.5, 1, and 3 second delays before switching domains. Primary and external sensor
pages poll independently while the application lifecycle is started.

Build with Gradle from the `client` directory:

```bash
./gradlew assembleDebug
```

## Local debug build with an explicit version

Run this from the repository root:

```bash
make apk-test-publish 1.2.3
```

The positional argument is the user-visible version. `VERSION_CODE` is read from
`docker/releases/latest.json` and incremented automatically.

Additional variables:

- `API_PORT` — API port from `docker/.env`, used by both Compose and the APK;
- `APK_API_HOST` — API host embedded in the APK (`10.0.2.2` for an emulator);
- `GOOGLE_WEB_CLIENT_ID` — Google web client ID from `local.properties`;
- `POLL_INTERVAL_SECONDS` — weather polling interval from `local.properties`.

`local.properties` is ignored by Git.

The build publishes its output into the shared release directory:

```text
docker/releases/<version_code>/outdoor-monitor-<version_name>-<version_code>-debug.apk
docker/releases/<version_code>/manifest.json
docker/releases/latest.json
```

The local API sees these files immediately through its Compose volume. The API address
embedded in a local APK comes from `DEBUG_API_BASE_URL` in `local.properties`; use
`http://10.0.2.2:8005` for the emulator. A physical device should use an HTTPS endpoint.

To test the update flow, publish and install the first version:

```bash
make apk-test-publish 1.2.3
```

Then publish a newer version without installing it manually:

```bash
make apk-test-publish 1.2.4
```

On startup, the installed client requests `/api/v1/app/latest`, detects the greater
`version_code`, and displays the download action. Local builds use the standard
Android debug keystore, so subsequent APKs retain the same signature as builds
installed from Android Studio on this machine.

## Production release versions

Versions are not entered manually in GitHub Actions. A
`app-vMAJOR.MINOR.PATCH` tag defines the visible version base:

```text
app-v1.2.0  -> tagged commit: 1.2.0
next successful build -> 1.2.1
next successful build -> 1.2.2
app-v2.0.0  -> tagged commit: 2.0.0
```

Each successful publication increments the patch component from the version stored in
`RELEASES_DIR/latest.json`, including a rerun on the same commit. A newer tag replaces
the base explicitly. Android `versionCode` is generated separately as
`1000 + github.run_number` and must always increase. The client uses `versionCode`,
rather than the visible version, to decide whether an update is available.

## Creating a release tag

Before the first workflow run, and whenever the major or minor base changes, create a
validated tag:

```bash
bash scripts/create-release-tag.sh 1.2.0
git push origin app-v1.2.0
```

The script fetches current tags, validates the format, rejects duplicate or lower
versions, and creates an annotated tag. Even when a tag is created manually, the
workflow repeats the checks on the runner and rejects a downgrade before building.

After the first tag, every successful ordinary or manually restarted workflow run
automatically increases the patch component. A new tag is needed only to change the
version base, for example from `1.2.x` to `1.3.0` or `2.0.0`.

## GitHub Actions configuration

`.github/workflows/android-release.yml` uses a self-hosted Linux runner (x64 or ARM64)
and the GitHub environment named `dev`. Buildx retains its state between runs, while
Gradle user, project, and output directories use locked BuildKit caches.

Under **Settings → Environments → dev → Environment variables**, configure:

- `RELEASE_API_BASE_URL` — public backend URL;
- `RELEASE_API_FALLBACK_BASE_URL` — optional backup backend URL;
- `RELEASES_DIR` — publication directory, such as `/srv/outdoor-monitor/releases`;
- `SIGNING_DIR` — signing directory, such as
  `/var/lib/outdoor-monitor-builder/signing`.

Under **Environment secrets**, configure:

- `GOOGLE_WEB_CLIENT_ID`.

Create `$SIGNING_DIR/release.env` directly on the runner with mode `600`:

```dotenv
SIGNING_STORE_PASSWORD=<secret>
SIGNING_KEY_PASSWORD=<secret>
SIGNING_KEY_ALIAS=release
```

Store the keystore at `$SIGNING_DIR/release.jks` with mode `600`. The runner account
must be able to read the signing directory and write to `RELEASES_DIR`.

The Google client ID is passed to Docker as a BuildKit secret. The keystore and
passwords are also passed only as secrets and are not stored in image history.

## Production release publishing process

1. `checkout` fetches the complete history and all tags.
2. The workflow finds the latest reachable `app-v*` tag and reads the currently
   published version from `latest.json`.
3. It uses a newer tag as the new base or increments the published patch by one.
4. It generates a monotonic `versionCode` from the workflow run number.
5. Both values are compared with `$RELEASES_DIR/latest.json`; lower version bases
   are rejected.
6. Docker builds and signs the release APK.
7. The build creates the APK, `manifest.json`, and `latest.json`, including SHA-256,
   file size, publication time, and commit SHA.
8. The result is stored as a GitHub Actions artifact.
9. The version directory and `latest.json` are moved atomically into `RELEASES_DIR`.
10. The backend starts returning the new version and APK URL without a restart.

Publishing jobs are serialized through a GitHub Actions concurrency group. Two runner
jobs cannot update `latest.json` simultaneously, and an existing `versionCode`
directory is never overwritten.
