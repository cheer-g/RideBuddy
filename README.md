# RideBuddy Android

A first native Android prototype for planning a ride on open map data.

## What works

- OpenStreetMap map tiles (via Leaflet)
- Place search using OpenStreetMap Nominatim
- Driving route preview using the public OSRM demo service
- Current-location centering and foreground navigation tracking
- Nearby Ride groups using encrypted Bluetooth/Wi-Fi peer connections
- Connected Ride groups using Firebase Realtime Database
- Create/join codes, Android sharing and live rider markers
- Foreground location service for sharing while the screen is off
- Persistent expanded notification that works alongside Google Maps
- Creator-selected trip end time with automatic location-sharing shutdown
- No API key required for maps or Nearby Ride

## Run

1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Select an Android 8.0+ emulator or device and run the `app` configuration.

From PowerShell, with Android Studio's bundled Java:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
```

The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

## Ride group modes

On launch, RideBuddy keeps the map blurred behind a **Create ride / Join ride** choice. Connected riders enter a code and see the destination and organizer cutoff before joining. Each rider can choose an earlier personal sharing cutoff and optionally record a starting odometer value. Ending the ride accepts an ending odometer value and presents a personal duration/distance summary; connected members also store their supplied start/end readings in the trip member record.

Connected active sessions are persisted locally. Firebase location publishing is owned by the foreground service, so it continues while the UI is backgrounded, and reopening RideBuddy restores the trip listener and telemetry dashboard. Nearby sessions still rediscover peers after process loss because their links are device-to-device rather than server-backed.

Connected rides monitor other riders from the foreground service. A rider becomes stale after 60 seconds without an update, stopped-sharing events are reported, and high-priority safety notifications can include a one-tap call action when the rider supplied an optional phone number. The live map also marks poor GPS accuracy and route deviation beyond 500 metres.

The connected-ride organizer has a live management panel for locking new joins, extending the ride cutoff, removing riders, transferring organizer ownership, and ending the ride for everyone. These changes are observed in real time by all participants and enforced by the included Realtime Database rules.

### Nearby Ride

Nearby Ride needs no account, server or internet connection. Create a trip on one phone, share its six-character code, and choose Nearby Ride on the other Android phones. Riders confirm the same pairing digits before the encrypted connection is accepted. Google Nearby Connections uses Bluetooth, BLE and Wi-Fi and is intended for devices within roughly 100 metres of a connected peer.

Before creating either type of trip, search for and select a destination, then choose **Share location until**. The destination and deadline are stored with a connected trip or exchanged during nearby discovery. Every participant receives the same destination and stops publishing when the deadline is reached. While a trip is active, Android displays a persistent expanded notification containing the destination, trip code, remaining time, visible rider count, an **Open map** action, and a **Stop sharing** action. No display-over-other-apps permission is required.

### Connected Ride

Connected Ride works at any distance over mobile internet. It uses anonymous Firebase Authentication and Realtime Database. The app is deliberately shipped without credentials, so add a Firebase project before this mode becomes selectable:

1. Create a Firebase project on the no-cost Spark plan.
2. Register Android app ID `com.ridebuddy.app`.
3. Enable Anonymous Authentication.
4. Create a Realtime Database and publish the rules in `firebase-rules.json`.
5. Place the downloaded `google-services.json` file in the `app` directory.
6. Rebuild the app. The Google Services Gradle plugin reads the app ID, API key, project ID and database URL automatically.

The Firebase identifiers embedded in an Android app are not service-account secrets. Database access is protected by Authentication and the included Security Rules. Before a public launch, add Firebase App Check and replace the short trip code with a separately generated invitation token.

## Join links

The share action sends `https://ridebuddy-107cc.web.app/join?mode=...&code=...`. Android App Links open RideBuddy directly with the mode and code filled in. The `hosting` directory contains the fallback join page and `.well-known/assetlinks.json` association for the current debug signing certificate. Deploy it with `firebase deploy --only hosting`. Before a Play Store release, add the release/app-signing SHA-256 certificate fingerprint to `hosting/.well-known/assetlinks.json` and redeploy.

## Prototype limits

Map search and routing need an internet connection, even when a Nearby Ride group is operating offline. The public Nominatim and OSRM endpoints are suitable for development and low-volume testing, not a production launch. A production version should use hosted or self-managed services, cache searches, and add turn-by-turn guidance. Nearby mode should be tested on at least two physical Android devices because emulators do not reproduce its Bluetooth/Wi-Fi behavior reliably.
