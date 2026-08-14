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

## How it works

1. Install the same APK on both phones.
2. On the Sender, select **Sender**, generate a Pair Code, save it, and enable Notification Access.
3. Copy the same Pair Code to the Receiver, select **Receiver**, save it, then start the receiver service.
4. Sender notifications are encrypted locally and published to a topic derived from the Pair Code.
5. Receiver subscribes to that topic, decrypts the payload locally, and creates a mirrored Android notification.

## Privacy model

The relay server can observe network metadata and the derived topic name, but notification contents are encrypted on-device with AES-GCM. The public `ntfy.sh` service may temporarily cache the encrypted messages to survive short network interruptions. The Pair Code must be kept secret because it derives both the relay topic and encryption key.

For stronger infrastructure control, a future version can point to a self-hosted ntfy server.

## Current limitations

- Reply actions are not implemented yet.
- Two-way notification dismissal is not implemented yet.
- Images and large notification attachments are not mirrored yet.
- Receiver uses a foreground service to keep an instant connection on Android 9/10, so Android shows a small persistent status notification while Receiver mode is active.

## Build

GitHub Actions builds a debug APK on every push to `main` and on manual workflow dispatch.

Local requirements:

- JDK 17
- Android SDK platform 36
- Android Build Tools 36.0.0
- Gradle 9.4.1

Run:

```bash
gradle :app:assembleDebug
```

The APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Security note

This project is intended for personal use. Do not share your Pair Code. If the code is exposed, generate a new one on both phones.
