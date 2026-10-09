<img src="docs/icon.png" width="96" align="right" alt="">

# Overland for Android

A GPS logger for Android that sends your location to a server of your choice, using the same
request format as [Overland for iOS](https://github.com/aaronpk/Overland-iOS). If your server already
accepts Overland, Android phones can now post to it too.

This is a from-scratch Kotlin remake of the deprecated
[Overland for Android](https://github.com/OpenHumans/Overland_android) by OpenHumans, which is no longer
maintained, no longer on Google Play, and depended on a paid location library. This version has no
paid dependencies and targets current Android versions (Android 8.0 and newer).

## Features

- Background tracking in a foreground service, resumed automatically after a reboot or app update.
- Battery-aware: high-accuracy GPS (~10 s) while moving, a low-power fix every ~2 min while the
  phone is still (detected with Google's activity recognition). Can also be set to always high accuracy
  or always low power. While in low power, fixes requested by other apps (e.g. navigation) are picked up
  for free, and a fast one switches straight back to GPS.
- **Bluetooth trigger** (optional): high-accuracy GPS while a chosen paired device, such as your car, is
  connected, so trips are recorded from the first metre instead of when motion detection catches up.
- **Quiet zones** (optional): inside a zone you set (home, office…), points aren't sent unless you're on
  a trip, so walking around inside sends nothing. Location keeps running as usual, so trip starts aren't
  delayed, and the last point held back is sent first so a trip starts where you were parked.
- `motion` (`driving`, `cycling`, `walking`, `running`, `stationary`) on every point.
- Offline queue: points are stored on the phone and uploaded in batches; nothing is lost without
  a connection.
- "Trip in progress" notification with a live timer and distance while you're driving; a minimized,
  silent notification the rest of the time.
- Diagnostics screen that checks permissions, battery optimization and manufacturer limits, with a
  button to fix each problem.

## Server API

Points are POSTed to the configured URL every upload interval (default 1 min), in batches of up to 200
(both can be changed in Settings). Nothing is sent when no new points were recorded:

```http
POST /your/endpoint HTTP/1.1
Authorization: Bearer <access token>
Content-Type: application/json

{
  "locations": [
    {
      "type": "Feature",
      "geometry": { "type": "Point", "coordinates": [-73.4834, 45.5826] },
      "properties": {
        "timestamp": "2026-10-07T13:35:22Z",
        "altitude": 30,
        "speed": 13.9,
        "horizontal_accuracy": 5,
        "vertical_accuracy": -1,
        "motion": ["driving"],
        "battery_state": "unplugged",
        "battery_level": 0.85,
        "device_id": "android-pixel"
      }
    }
  ]
}
```

- Coordinates are `[longitude, latitude]`; `timestamp` is UTC; `speed` is in m/s; accuracies are in metres.
  Unknown values are `-1`, as in Overland iOS.
- The `Authorization` header is only sent when an access token is set.
- The server must reply with `{"result": "ok"}`. Any other response keeps the batch on the phone to be
  sent again later, so the endpoint should ignore duplicates (e.g. unique on device + timestamp).
- **Test connection** in Settings (or **Send now** on the main screen) with an empty queue sends
  `{"locations": []}`, handy for checking the URL and token.

The trip notification is computed on the phone and is not sent to the server; the server gets the raw
points and can build trips however it likes.

## Install

Download the APK from [Releases](../../releases) and open it on your phone (allow installing from your
browser or file manager when asked). Then:

1. Open **Settings** (gear icon) and enter the **receiver endpoint URL**, the **access token** if your
   server needs one, and a **device ID**. Settings are saved as you type.
2. Tap **Test connection**: it should say `OK`.
3. Go back and tap **Start tracking**, then allow location (**Allow all the time**), physical activity and
   notifications.
4. If the banner at the top says something may stop tracking, tap it to open **Diagnostics** and use the
   button next to each problem (battery optimization, manufacturer limits from
   [dontkillmyapp.com](https://dontkillmyapp.com), …).

## Build

Requires JDK 17+ and the Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`).

```sh
./gradlew testDebugUnitTest assembleDebug   # app/build/outputs/apk/debug/
```

For a release build, create `keystore.properties` in the project root (it is git-ignored):

```properties
storeFile=my-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

and run `./gradlew assembleRelease`. Updates only install over an existing copy when signed with the
same key, so keep the keystore safe.

## Credits & license

- Original [Overland for Android](https://github.com/OpenHumans/Overland_android) by Nicolas Eberlé and
  OpenHumans contributors. This remake shares no code with it; the icon is redrawn after its icon.
- [Overland for iOS](https://github.com/aaronpk/Overland-iOS) by Aaron Parecki, which defines the request format.

Licensed under the [Apache License 2.0](LICENSE), like both projects above. See [NOTICE](NOTICE).
