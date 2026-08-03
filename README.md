# Outdoor Monitor Android

The Android client displays the latest primary and external sensor readings, supports
Google and password authentication, and checks for new application versions through
`GET /api/v1/app/latest`.

## Development configuration

Copy `local.properties.example` to the Git-ignored `local.properties` file:

```properties
DEBUG_API_BASE_URL=http://your-backend-host:8000
RELEASE_API_BASE_URL=https://your-api.example.com
GOOGLE_WEB_CLIENT_ID=your-google-web-client-id
POLL_INTERVAL_SECONDS=10
```

Use `10.0.2.2` to reach the host from Android Emulator. Use the computer's LAN address
for a physical device. Debug builds allow local HTTP traffic, while release builds
continue to reject cleartext traffic.

Build with Gradle from the `client` directory:

```bash
./gradlew assembleDebug
```

## Local debug build with an explicit version

Run this from the parent project root:

```bash
make apk-local-build \
  APK_LOCAL_VERSION_NAME=1.2.7-debug \
  APK_LOCAL_VERSION_CODE=12007
```

Additional variables:

- `APK_LOCAL_API_BASE_URL` — API URL embedded in the APK;
- `APK_GOOGLE_CLIENT_ID` — Google web client ID, stored only in `docker/.env`;
- `APK_POLL_INTERVAL_SECONDS` — weather polling interval.

When `APK_GOOGLE_CLIENT_ID` is empty in `docker/.env`, the local build attempts to
read `GOOGLE_WEB_CLIENT_ID` from `client/local.properties`. Both files are ignored by
Git.

The build publishes its output into the shared release directory:

```text
build/releases/<version_code>/outdoor-monitor-<version_name>-<version_code>.apk
build/releases/<version_code>/manifest.json
build/releases/latest.json
```

The local API sees these files immediately through its Compose volume. For an emulator
and `API_PORT=8002`, use:

```dotenv
APK_LOCAL_API_BASE_URL=http://10.0.2.2:8002
```

To test the update flow, first build and install an older version:

```bash
make apk-local-build APK_LOCAL_VERSION_NAME=1.2.6-debug APK_LOCAL_VERSION_CODE=12006
```

Then publish a newer version without installing it manually:

```bash
make apk-local-build APK_LOCAL_VERSION_NAME=1.2.7-debug APK_LOCAL_VERSION_CODE=12007
```

On startup, the installed client requests `/api/v1/app/latest`, detects the greater
`version_code`, and displays the download action. The debug keystore is stored in a
persistent BuildKit cache, so subsequent local APKs retain a compatible signature.

## Production release versions

Versions are not entered manually in GitHub Actions. A
`app-vMAJOR.MINOR.PATCH` tag defines the visible version base:

```text
app-v1.2.0  -> tagged commit: 1.2.0
next commit -> 1.2.1
next commit -> 1.2.2
app-v2.0.0  -> tagged commit: 2.0.0
```

The patch component after the tag is derived from the number of commits. Android
`versionCode` is generated separately as `1000 + github.run_number` and must always
increase. The client uses `versionCode`, rather than the visible version, to decide
whether an update is available.

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

After the first tag, ordinary pushes automatically increase the patch component. A
new tag is needed only to change the version base, for example from `1.2.x` to `1.3.0`
or `2.0.0`.

## GitHub Actions configuration

`.github/workflows/android-release.yml` uses a self-hosted Linux runner and the GitHub
environment named `dev`.

Under **Settings → Environments → dev → Environment variables**, configure:

- `RELEASE_API_BASE_URL` — public backend URL;
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
2. The workflow finds the latest reachable `app-v*` tag and calculates `versionName`.
3. It generates a monotonic `versionCode` from the workflow run number.
4. Both values are compared with `$RELEASES_DIR/latest.json`; equal or lower versions
   are rejected.
5. Docker builds and signs the release APK.
6. The build creates the APK, `manifest.json`, and `latest.json`, including SHA-256,
   file size, publication time, and commit SHA.
7. The result is stored as a GitHub Actions artifact.
8. The version directory and `latest.json` are moved atomically into `RELEASES_DIR`.
9. The backend starts returning the new version and APK URL without a restart.

Publishing jobs are serialized through a GitHub Actions concurrency group. Two runner
jobs cannot update `latest.json` simultaneously, and an existing `versionCode`
directory is never overwritten.
