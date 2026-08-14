# Firebase FCM Relay

This Firebase Cloud Function is the only server-side component required by Notification.

It accepts a hashed FCM topic plus opaque AES-GCM ciphertext and forwards it with Firebase Admin SDK. The Function never receives the six-digit Pair Code or plaintext notification contents.

## Why Firebase Functions

- No Cloudflare account or Worker is required.
- No Firebase service-account private key is stored in GitHub or inside the APK.
- The Function uses its Google-managed runtime credentials to call FCM.
- Android still uses FCM for low-battery background delivery.

## Requirements

- Firebase project: `notification-2515e`
- Firebase project on the Blaze plan (required to deploy Cloud Functions)
- Firebase CLI with access to the project
- Node.js 22

## One-time setup

From the repository root:

```bash
npm install -g firebase-tools
firebase login
firebase use notification-2515e
firebase functions:secrets:set RELAY_TOKEN
```

Use a random secret of at least 24 characters for `RELAY_TOKEN`. Never commit it.

Then deploy:

```bash
firebase deploy --only functions:relay
```

The expected base URL for the configured region is:

```text
https://europe-west1-notification-2515e.cloudfunctions.net/relay
```

The Android app appends `/v1/send` automatically.

## Endpoints

- `GET /health` returns `{ "ok": true }`.
- `POST /v1/send` requires `Authorization: Bearer <RELAY_TOKEN>`.

The POST body contains only:

```json
{
  "topic": "notification-...",
  "kind": "mirror",
  "payload": "opaque-aes-gcm-ciphertext",
  "id": "random-message-id"
}
```

`kind` can be `mirror` or `command`. Payloads are size-limited before they are sent to FCM.

## Device setup

Enter the same `RELAY_TOKEN` as the Relay key on both phones. The Firebase Function URL is already the app default, but remains editable for recovery/testing.

Do not Force Stop the Android app during normal use; Android intentionally suppresses FCM delivery after Force Stop until the app is launched again.
