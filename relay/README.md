# Notification FCM Relay

A tiny Cloudflare Worker that forwards already-encrypted Notification payloads to Firebase Cloud Messaging HTTP v1.

The relay never receives the 6-digit Pair Code or plaintext notification contents. It only sees a hashed FCM topic, message kind, opaque AES-GCM ciphertext and a random message ID.

## Required secrets

Configure these as Cloudflare Worker secrets. Never commit them to GitHub.

- `GOOGLE_SERVICE_ACCOUNT_JSON`: complete Firebase service-account JSON with permission to send FCM messages for project `notification-2515e`.
- `RELAY_TOKEN`: a random high-entropy key (at least 24 characters). Enter the same key locally in the Notification app on both phones.

## Deploy with Wrangler

```bash
cd relay
npm install
npx wrangler login
npx wrangler secret put GOOGLE_SERVICE_ACCOUNT_JSON
npx wrangler secret put RELAY_TOKEN
npx wrangler deploy
```

The deployed URL will look like:

```text
https://notification-fcm-relay.<your-subdomain>.workers.dev
```

Do not add `/v1/send` in the Android app. The app adds that path itself.

## Endpoints

- `GET /health` -> `{ "ok": true }`
- `POST /v1/send` -> authenticated encrypted FCM relay

## Request format

```json
{
  "topic": "notification-...",
  "kind": "mirror",
  "payload": "opaque-aes-gcm-ciphertext",
  "id": "random-message-id"
}
```

The `Authorization` header must be `Bearer <RELAY_TOKEN>`.

## Security notes

- Firebase service-account credentials stay only in Cloudflare Secrets.
- The Relay key is not compiled into the APK and is not stored in this repository.
- Android stores the Relay key locally in app preferences on each device.
- Transport is HTTPS and notification payloads remain AES-GCM encrypted end to end.
