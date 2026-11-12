# Caller

A lightweight phone dialer and contacts app for Android 7.0 (API 24) through the latest Android.

- Replaces the system phone app (default dialer role): own incoming and in-call screens
- Recents grouped by day, missed calls highlighted
- Contacts with favorites, quick search by name or number
- Keypad with T9 contact search, long-press 0 for +, long-press 1 for voicemail
- In-call: mute, speaker / Bluetooth routing, keypad (DTMF), hold, swap, merge, reply with SMS
- Dual-SIM picker, proximity sensor, lock-screen incoming call
- Light and dark theme
- In-app updates from GitHub Releases
- ~850 KB APK, no third-party libraries beyond AndroidX

## Install

Download `Caller-x.y.z.apk` from [Releases](https://github.com/anees-dahot/caller-app/releases/latest), open it, then tap **Set default** in the app.

## Build

```
./gradlew assembleRelease
```

Release signing reads `keystore.properties` (not committed). Without it, use `assembleDebug`.

## Releasing an update

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`
2. `./gradlew assembleRelease`
3. `gh release create vX.Y.Z app/build/outputs/apk/release/app-release.apk#Caller-X.Y.Z.apk`

Installed apps check the latest release on launch and offer a one-tap update.
