# ChargeWatch

Your main phone is charging in another room and you are using a second phone. ChargeWatch tells the second phone when the first one hits 80% and when it is full.

It is a tiny Android app (under 300 KB) with no account and no backend. Phones pair by scanning a QR code and talk through the free [ntfy.sh](https://ntfy.sh) push relay.

**[Download ChargeWatch.apk](https://github.com/Sowan3k/chargewatch/releases/download/latest/ChargeWatch.apk)** (Android 8.0 or newer)

## What it does

The main screen lists every paired phone with its battery level, whether it is charging, and how long ago it last reported. When another phone in your group is charging you get two notifications:

1. "Pixel 7 is 80% charged" when it reaches your alert level (80% by default, you can change it).
2. "Pixel 7 is fully charged. You can use it now." when it reaches 100%.

Each alert fires once per charge. A phone that hovers at 99/100% or sits at an 80% charge limit does not spam you.

## Install

1. Open the download link above on each phone and open the APK. Android asks you to allow installs from your browser or file manager. Allow it once. If the download sits at 100% and never finishes, get [ChargeWatch.zip](https://github.com/Sowan3k/chargewatch/releases/download/latest/ChargeWatch.zip) instead, open it in your Files app and tap the APK inside.
2. Open ChargeWatch and allow notifications.
3. Tap **Allow** on the battery banner and pick **Allow** (this sets battery usage to Unrestricted). Without it, Android may pause the app in the background.

The APK is signed with a public debug key that lives in this repo, so each new build installs over the old one. Only install ChargeWatch from this repository's releases.

## Pair your phones

1. On one phone, tap **Create group**. It shows a QR code.
2. On the other phone, tap **Scan QR code** and point the camera at it. The scanner comes from Google Play services, so the app needs no camera permission. If the scanner is not available, tap **Enter code** and type the code shown under the QR.
3. Both phones now appear on each other's screen. Add a third phone the same way with **Show QR code**.

To unpair, tap **Leave group**. The other phones remove this one from their list. Long press a phone that no longer exists to remove it by hand.

## Settings

The alert level can be Off, 50, 60, 70, 75, 80, 85, 90 or 95%. Each phone sets its own. You can also rename the phone (the default is the model name) and choose a relay server.

## Keeping it running in the background

ChargeWatch runs a foreground service with a small notification. It restarts after a reboot and after an app update. Most problems come from aggressive battery savers, so if updates stop arriving check these:

- Battery usage for ChargeWatch is set to Unrestricted (Settings, Apps, ChargeWatch, Battery).
- On Samsung, ChargeWatch is not in Sleeping apps or Deep sleeping apps.
- On Xiaomi, Oppo, Vivo, OnePlus and Huawei, Autostart is on and the app is locked in Recents.

[dontkillmyapp.com](https://dontkillmyapp.com) has steps for every brand.

## How it works

A group is 80 random bits. The QR code holds `chargewatch:<group id>` and the ntfy topic is `chargewatch-<group id>`. Each phone has a random device id. When its battery changes it posts a small JSON message to the topic:

```json
{"v":1,"id":"5f2c9a1be07d4c13","name":"Pixel 7","level":82,"charging":true}
```

Every phone also keeps a streaming connection to the same topic (`/json?since=12h` on first connect, so it learns the current state of every phone from ntfy's 12-hour cache). It keeps a map of devices and fires an alert only on a transition (previous level below the mark, new level at or above it), only for messages under 2 minutes old, and never for its own messages. Old and duplicate messages are ignored, so replaying the backlog never repeats an alert.

To stay well under the public relay's limits, a phone sends every 1% change while charging above 50%, every 5% otherwise, and a heartbeat every 30 minutes when nothing changes. The heartbeat is what lets the list say "Last seen 3 h ago" for a phone that has gone quiet. A normal day is roughly 100 to 120 messages per phone.

## Privacy

There is no ChargeWatch server and no account. The messages carry only a random id, the phone name, the battery level and the charging state. Anyone who has your group's QR code or code can read them, and so can the relay operator. If that matters to you, run your own relay.

The QR scanner comes from Google Play services through Google's ML Kit library, which may send anonymous usage statistics about the scanner to Google. The app asks for no camera, location or contacts permission.

## Self-hosted relay

ChargeWatch works with any [ntfy server](https://docs.ntfy.sh/install/). Set it under Settings, Relay server before you create the group. The server URL is then part of the QR code (`chargewatch:<group id>@https://ntfy.example.com`), so the other phones pick it up when they scan. The server must allow anonymous read and write on `chargewatch-*` topics and should keep a message cache of at least 12 hours.

## Build from source

You need JDK 17 and the Android SDK (platform 34).

```sh
./gradlew testReleaseUnitTest assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

The project uses Gradle 8.9, Android Gradle Plugin 8.5.2 and Kotlin 1.9.24. The UI is plain `android.app.Activity` built in code with no AndroidX UI libraries. The only dependencies are ZXing core (to draw the QR code) and the Google code scanner (to read it). Release builds use R8 and resource shrinking.

Every push to `main` runs the GitHub Actions workflow in `.github/workflows/build.yml`. It runs the unit tests, builds the release APK, fails if the APK is over 5 MB, and uploads `ChargeWatch.apk` to the [`latest` release](https://github.com/Sowan3k/chargewatch/releases/tag/latest).

## License

MIT. See [LICENSE](LICENSE).
