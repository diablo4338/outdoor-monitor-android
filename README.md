# Outdoor Monitor Android

Android client for the Outdoor Monitor weather monitoring system. The application
connects to the Outdoor Monitor API and displays the latest temperature and
humidity readings collected from outdoor sensors.

The API is provided by the
[Outdoor Monitor Backend](https://github.com/diablo4338/outdoor-monitor-backend).
The backend reads sensor metrics from Prometheus, stores periodic snapshots, and
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
