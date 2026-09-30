# Notification 1.1.0

A small personal Android app for mirroring and synchronizing notifications between two phones.
One APK supports both Sender and Receiver. Android 9 and later are supported; the build targets Android 14.

## What synchronizes

- New notifications and subsequent changes replace the same mirrored notification.
- Removing a notification on Sender removes it on Receiver.
- Swiping a mirrored notification on Receiver requests dismissal on Sender.
- Reply and Mark as read use the original app's notification actions, when available.
- **Sync now** reconciles the currently active notifications, including stale notifications on Receiver.
- Replayed and out-of-order events are tracked so an older update cannot resurrect a removed notification.
- An encrypted local send queue retries after Internet connectivity returns.
- The main screen shows connection state, pending sends, last successful send/receive, and a short error.

## Two delivery choices

### Push (FCM)

Sender encrypts a notification with AES-GCM, sends a short HTTPS request to a Cloudflare Worker,
and Receiver decrypts the FCM data message. Commands use the same path in reverse.
No app-owned persistent socket or foreground service is used in this mode.

Visible notification events request high-priority FCM delivery. Commands, removals and snapshot
control messages use normal priority; Android Doze can delay them. Delivery is best effort,
and **Sync now** restores active state after missed messages.

### Compatibility

Both phones use encrypted ntfy.sh messages. Receiver keeps one foreground WebSocket for mirrors;
Sender keeps one foreground WebSocket for commands. **No Cloudflare Worker or Firebase relay key is
required in this mode.** Select Compatibility on **both** phones. A persistent status notification
is expected; this mode usually consumes more battery than Push.

The service uses Android 14's remote-messaging foreground-service type. Reconnects are serialized,
network aware, and use backoff with jitter. Old socket callbacks cannot reconnect a stopped session.

## Pair and start

1. Install the same 1.1.0 APK on both phones. In-place updates of the previous CI-signed 1.0.0 build
   preserve settings because the existing CI debug signing key is retained.
2. On the first phone choose **Sender**, press **Generate**, and save the generated pair key.
3. Copy the **exact same** pair key to the second phone, choose **Receiver**, and save.
4. Select the same delivery mode in Advanced on both phones.
5. On Sender enable **Notification Access** and choose the apps to forward.
6. On Receiver allow notification permission and press **Start**.
7. Send Test, then test a real notification. Use **Sync now** to request current state.

Generate creates a random 128-bit pair key. Existing six-digit pair codes remain supported for
updates, but their small search space is weak protection; use a generated key for a new pairing.
Changing the pair key clears queued messages and mirrored state for the previous pairing.
Update both phones before using the new sync protocol.

On restrictive firmware such as MIUI, enable the app's Autostart setting and adjust its battery
restriction as needed. **Battery settings** opens Android's settings; the app does not silently
disable battery optimization. Android **Force Stop** suppresses background delivery until reopening
the app, and differs from swiping it out of Recents.

## Configure the Cloudflare Worker for Push

Worker source: `relay/src/index.js`. The Worker name in `relay/wrangler.jsonc` is `notification`.
The existing default Android Relay URL is `https://notification.mhdvi45.workers.dev`; if deploying
to another account, set the actual HTTPS Worker URL in Advanced on both phones.

Enable the Firebase Cloud Messaging HTTP v1 API for the Firebase project used by
`app/google-services.json`. Create a service account authorized to send FCM messages for that project.
Use its service-account JSON **only as a Cloudflare secret**, never as an APK resource or Git file.

```bash
cd relay
npm install
npx wrangler login
npx wrangler secret put GOOGLE_SERVICE_ACCOUNT_JSON
npx wrangler secret put RELAY_TOKEN
npm run deploy
```

`RELAY_TOKEN` must be a private random value of at least 24 characters. Store the same token in
Advanced → Private key on both phones. `/health` reports a version and whether service-account
configuration is present; it does not validate Google permissions or guarantee FCM delivery.

The Worker validates body size, topic direction, ciphertext and FCM's 2 KiB topic data limit.
It caches OAuth tokens, retries one expired token, propagates retry delays, and avoids logging
message contents, Google error response bodies or credentials. Plaintext is decrypted only on phones.

## Lightweight implementation and limits

No new production dependency was added: Kotlin, Android platform APIs, OkHttp and Firebase Messaging
remain the runtime components. The send retry queue uses Android JobScheduler rather than adding
WorkManager. There is no periodic polling in Push mode. R8 and resource shrinking remain enabled.

- Up to 256 encrypted sends are kept locally; ordinary updates for one source notification coalesce.
- Queued mirror events expire after one hour; commands expire after ten minutes. FCM delivery TTL is five minutes.
- Sync snapshots cover up to 200 active notifications. Incomplete snapshots never clear unseen notifications.
- Receiver retains up to 512 ordering records and the latest 256 message IDs.
- Large notification/reply text is shortened at Unicode code-point boundaries to fit the topic budget.
- Notification actions depend on the original app exposing a supported action and it still being active.
- A queue acknowledgement means saved for sending; the status screen records actual HTTP send success,
  which is not an acknowledgement from the receiving phone.

## Build and validation

JDK 17, Gradle 8.7, Android SDK 34:

```bash
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
node --test relay/test/relay.test.js
```

GitHub Actions restores the stable CI debug signature, tests Android sync/crypto rules and Worker
behavior, runs Android lint, builds the shrunk APK and uploads it as `Notification-debug-apk`.
The extra JUnit/JSON dependencies are test-only and are not included in the APK.

Before merging, test on both real phones in both delivery modes:

1. New notification, content update, Sender removal and Receiver swipe-to-dismiss.
2. Reply and Mark as read, including an action whose original notification has already disappeared.
3. Receiver offline, Sender offline, restore connectivity, then **Sync now**.
4. Swipe from Recents, screen off/Doze, reboot, and network/VPN changes.
5. Notification permission denied then granted, pair/mode changes, and stopping Compatibility during reconnect.
6. Confirm size, battery usage and duplicate behavior with typical selected apps.

Existing draft PRs #1 and #2 are unchanged. Keep this work in a separate draft PR until real-device
and deployed-Worker validation are complete; do not merge to main without the user's approval.
