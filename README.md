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
  -> Firebase Cloud Function relay
  -> Firebase Cloud Messaging
  -> POCO Receiver
  -> decrypt on device
  -> mirrored Android notification
```

Reply and Mark as read use the same path in reverse:

```text
POCO Receiver
  -> encrypted command
  -> Firebase Cloud Function relay
  -> FCM command topic
  -> Samsung Sender
  -> NotificationListenerService
  -> original RemoteInput / PendingIntent action
```

## Privacy and security

- Notification contents are encrypted on-device with AES-GCM before leaving the Sender.
- The six-digit Pair Code is not sent to the relay.
- FCM topics are derived from the Pair Code.
- The Firebase Function sees only the derived topic, message kind, opaque ciphertext and a random message ID.
- No Firebase service-account private key is stored in the APK or repository.
- The Function uses Google-managed runtime credentials to call FCM.
- `RELAY_TOKEN` is a private Firebase Functions secret and must never be committed.
- The same Relay key is stored locally on both phones.

## Why Firebase Functions instead of Cloudflare

The project uses Firebase for both the Android push transport and the small server-side relay. No Cloudflare Worker or Cloudflare account is required.

Cloud Functions must run on the Firebase Blaze plan. For this personal workload the actual usage is expected to be very small, but billing must be enabled before deployment.

## Firebase relay deployment

Firebase project:

```text
notification-2515e
```

Install the Firebase CLI and authenticate:

```bash
npm install -g firebase-tools
firebase login
firebase use notification-2515e
```

Create the private Relay key as a Firebase secret:

```bash
firebase functions:secrets:set RELAY_TOKEN
```

Use a random value of at least 24 characters and keep it private.

Deploy only the relay:

```bash
firebase deploy --only functions:relay
```

Configured base URL:

```text
https://europe-west1-notification-2515e.cloudfunctions.net/relay
```

The Android app already uses this as its default Relay URL and appends `/v1/send` itself.

Health endpoint after deployment:

```text
https://europe-west1-notification-2515e.cloudfunctions.net/relay/health
```

## Device setup

### Samsung Sender

1. Install the current APK.
2. Select **Sender**.
3. Enter/generate the Pair Code.
4. Enter the same private Relay key used for the Firebase `RELAY_TOKEN` secret.
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

After the Firebase Function is deployed:

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

## Build/check Firebase Function

```bash
cd functions
npm install
npm run check
```

The Function uses Node.js 22, `firebase-functions` and `firebase-admin`.

## Branch policy

Development for the FCM architecture is on:

```text
agent/fcm-v0.8
```

PR #2 must remain Draft and must not be merged into `main` until Firebase relay deployment, full device testing, and final cleanup are complete.
