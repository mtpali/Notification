# Notification

A lightweight personal Android app that mirrors notifications between two phones over the Internet.

Current target devices:

- Sender: Samsung J7 Core, Android 9
- Receiver: POCO X3 NFC, Android 10 / MIUI

## Current v0.8 architecture

The recommended transport is Firebase Cloud Messaging because it remains available after the Receiver app is swiped from Recents and while the screen is off, without keeping an app-owned WebSocket or foreground service alive.

```text
Samsung Sender
  -> AES-GCM encrypt on device
  -> short HTTPS request
  -> Cloudflare Worker relay
  -> Firebase Cloud Messaging
  -> POCO Receiver
  -> decrypt on device
  -> mirrored Android notification
```

Reply and Mark as read use the same path in reverse:

```text
POCO Receiver
  -> encrypted command
  -> Cloudflare Worker relay
  -> FCM command topic
  -> Samsung Sender
  -> NotificationListenerService
  -> original RemoteInput / PendingIntent action
```

## Privacy and security

- Notification contents are encrypted on-device with AES-GCM before leaving the phone.
- The six-digit Pair Code is not sent to the relay.
- FCM topics are derived from the Pair Code.
- The Worker sees only the derived topic, message kind, opaque ciphertext and a random message ID.
- The Firebase service-account private key is never stored in the APK or repository.
- `GOOGLE_SERVICE_ACCOUNT_JSON` is stored only as a Cloudflare Worker Secret.
- `RELAY_TOKEN` is stored as a Cloudflare Worker Secret and locally on the two phones.

## Cloudflare relay

Worker URL:

```text
https://notification.mhdvi45.workers.dev
```

Health endpoint:

```text
https://notification.mhdvi45.workers.dev/health
```

The Worker source is in:

```text
relay/src/index.js
```

Required Cloudflare Secrets:

```text
GOOGLE_SERVICE_ACCOUNT_JSON
RELAY_TOKEN
```

`GOOGLE_SERVICE_ACCOUNT_JSON` must contain the complete Firebase service-account JSON. `RELAY_TOKEN` must be a strong private random value of at least 24 characters. Never commit either secret.

The Android app uses the Worker URL as its default Relay URL and appends `/v1/send` itself.

## Device setup

### Samsung Sender

1. Install the current APK.
2. Select **Sender**.
3. Enter/generate the Pair Code.
4. Enter the same private Relay key used for the Cloudflare `RELAY_TOKEN` secret.
5. Save.
6. Enable Notification Access.
7. Choose All apps or selected apps.

The Sender does not need a foreground service or persistent WebSocket. It sends a short HTTPS request only when a notification or test event must be relayed.

### POCO Receiver

1. Install the same APK.
2. Select **Receiver**.
3. Enter the exact same Pair Code.
4. Enter the exact same Relay key.
5. Select **Push (FCM) • lowest battery**.
6. Save and press Start.

Push mode does not require Notification Access, a Receiver WebSocket, a foreground service, or a persistent status notification.

## Validated behavior

FCM delivery has already been validated on the POCO X3 NFC while:

- the app was swiped from Recents
- the screen was off

Android Force Stop is intentionally different: after Force Stop, Android suppresses background/push delivery until the app is opened again.

## Required end-to-end validation before merge

After the Cloudflare Worker is configured:

1. Samsung **Send Test** -> POCO receives it.
2. Swipe POCO from Recents -> Send Test still arrives.
3. Turn screen off for several minutes -> Send Test still arrives.
4. Send a real Telegram/WhatsApp notification -> mirror arrives.
5. Reply on POCO -> original Samsung notification action executes.
6. Mark as read on POCO -> original Samsung action executes.
7. Confirm no duplicate FCM subscriptions, mirrored notifications, or command executions.

Only after these tests pass should the legacy Stable/Hidden WebSocket fallback code be removed and final R8/resource shrinking, APK-size, memory and battery checks be completed.

## Build Android APK

Android build configuration currently uses JDK 17, compileSdk 34, minSdk 28 and targetSdk 29.

```bash
gradle --no-daemon :app:assembleDebug
```

R8 and resource shrinking are enabled for debug and release builds.

## Branch policy

Development for the FCM architecture is on:

```text
agent/fcm-v0.8
```

PR #2 must remain Draft and must not be merged into `main` until Cloudflare relay testing, full device testing, and final cleanup are complete.
