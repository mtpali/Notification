# Notification

A lightweight personal Android notification bridge for Android 9 and Android 10.

## Goal

`Notification` mirrors notifications from one Android phone (Sender) to another Android phone (Receiver) over the Internet, even when the two phones are on different networks.

## v0.1 features

- One APK for both phones
- Sender / Receiver mode
- Android 9 (API 28) and Android 10 (API 29)
- Notification access through Android `NotificationListenerService`
- Per-app forwarding filter
- No in-app account
- Pairing with a high-entropy Pair Code
- Internet relay through `ntfy.sh`
- Client-side AES-GCM encryption before data leaves the Sender
- Receiver reconnects after temporary network failures
- Automatic Receiver restart after device boot
- GitHub Actions debug APK build

## Install and pair

1. Download the latest `Notification-debug-apk` artifact from GitHub Actions and extract `app-debug.apk`.
2. Install the same APK on both phones.
3. On the Sender, select **Sender**, generate a Pair Code, save it, and open **Notification Access** to allow Notification to read notifications.
4. Optionally open **Choose apps to forward** and select which apps should be mirrored.
5. Enter the exact same Pair Code on the second phone, select **Receiver**, save settings, and press **Start Receiver**.
6. Keep the Pair Code private. It derives both the relay topic and the encryption key.

## How it works

1. Android calls `NotificationListenerService` when the Sender receives a notification.
2. The Sender serializes the app name, title, text, timestamp, package name and notification key.
3. The payload is encrypted locally with AES-GCM using a key derived from the Pair Code.
4. The encrypted payload is published to a high-entropy `ntfy.sh` topic derived from the same Pair Code.
5. Receiver maintains an HTTP JSON stream to the relay, reconnects after network changes, decrypts messages locally, and creates mirrored Android notifications.
6. The last relay message ID is saved so reconnects can request cached messages after temporary Internet interruptions.

## Privacy model

The relay server can observe network metadata and the derived topic name, but notification contents are encrypted on-device with AES-GCM. The public `ntfy.sh` service may temporarily cache the encrypted messages to survive short network interruptions. The app explicitly tells ntfy not to forward these relay messages through ntfy's Firebase integration because Notification maintains its own receiver stream.

For stronger infrastructure control, a future version can point to a self-hosted ntfy server.

## Current limitations

- Reply actions are not implemented yet.
- Two-way notification dismissal is not implemented yet.
- Images and large notification attachments are not mirrored yet.
- Pairing currently uses a text Pair Code; QR pairing is planned for a later version.
- Receiver uses a foreground service to keep an instant connection on Android 9/10, so Android shows a small persistent status notification while Receiver mode is active.

## Build

GitHub Actions builds a debug APK on every push to `main` and on manual workflow dispatch.

Build configuration:

- JDK 17
- Android SDK platform 34
- Android Build Tools 34.0.0
- Android Gradle Plugin 8.5.2
- Gradle 8.7
- Kotlin 1.9.24
- `minSdk 28`
- `targetSdk 29`

Local build command if Gradle 8.7 is installed:

```bash
gradle --no-daemon :app:assembleDebug
```

The APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Security note

This project is intended for personal use. Do not share your Pair Code. If the code is exposed, generate a new one on both phones.
