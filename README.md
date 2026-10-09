# Notification 1.3.0

A small personal Android app for mirroring and synchronizing notifications between two phones.
One APK supports both Sender and Receiver. Android 9 and later are supported; the build targets Android 14.

## What synchronizes

- New notifications and subsequent changes replace the same mirrored notification.
- Removing a notification on Sender removes it on Receiver.
- Swiping a mirrored notification on Receiver requests dismissal on Sender.
- Every native notification action is forwarded independently with its original label and order, including Call, Mark as read and Reply. Distinct wearable actions are also retained.
- **Sync now** reconciles the currently active notifications, including stale notifications on Receiver.
- Replayed and out-of-order events are tracked so an older update cannot resurrect a removed notification.
- An encrypted local send queue retries after Internet connectivity returns.
- The main screen shows connection state, pending sends, last successful send/receive, and a short error.

## Reliable notification actions

Version 1.3.0 builds on the latest 1.2.0 reliability branch, not the older `main`.
It uses Android's notification APIs and this app's own transport; no Bridge backend is involved.

- Actions are a list, not two fixed capabilities. Each action carries its own opaque ID, original
  title, semantic metadata, authentication requirement and all RemoteInput keys/labels/choices.
  Equal labels and multiple reply buttons do not make the action list ambiguous.
- Native actions remain in source order. A wearable copy is omitted only if its PendingIntent
  and metadata match a native action. Distinct wearable intents remain reachable.
- Samsung Messages' Call / Mark as read / Reply appear as three separate buttons, even if an OEM
  omits semantic mark-as-read metadata. Calls invoke the original phone app **on Sender**.
- Android's standard notification layout has three action slots. With more than three actions,
  two originals plus **All N actions** are shown. Tapping the notification opens the complete list.
  Multiple text inputs and choice-only inputs can be completed on that screen.
- Single text/choice inputs use inline RemoteInput with the **original result key**. No text is
  guessed from the button label. Missing intents, immutable input intents and attachment-only
  inputs retain a visible action with an explanation; this release cannot forward attachments.
- Each action is bound to a snapshot generation/profile and its precise origin/index/metadata.
  Sender resolves a fresh active notification and its current PendingIntent before dispatch.
  Changed content, labels, inputs, choices or a replaced PendingIntent invalidate old references.
- Receiver durably queues commands; Sender returns encrypted results such as DISPATCHED, STALE,
  EXPIRED, NEEDS_UNLOCK, CANCELLED, UNSUPPORTED, DENIED, BUSY or UNKNOWN. Action status retains the
  original button title. Generic actions do not arbitrarily remove a mirror: source removal drives sync.
- Source authentication requirements are respected. On Android 12+, activity actions require an
  unlocked Sender. Android 14 PendingIntent dispatch explicitly contributes the listener's activity
  launch privileges; OEM/background policies can still prevent the original UI from opening.
- DISPATCHED means PendingIntent dispatch was attempted successfully, not that a call connected,
  a remote service delivered a message, or an activity necessarily became visible.
- The existing durable claim/deduplication rules remain: an interrupted dispatch reports UNKNOWN
  and never automatically repeats the action. Check Sender before retrying UNKNOWN.

Install 1.3.0 on both phones to use generation-checked actions and result acknowledgements.
The existing pair code, delivery choice and relay settings are preserved during signed updates.

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
required in this mode.** Select Compatibility on **both** phones. A compact, title-only persistent
notification is expected; connection state is shown inside the app. Status changes do not rebuild
the service notification. This mode usually consumes more battery than Push.

The service uses Android 14's remote-messaging foreground-service type. Reconnects are serialized,
network aware, and use backoff with jitter. Old socket callbacks cannot reconnect a stopped session.

## Pair and start

1. Install the same 1.3.0 APK on both phones. In-place updates of the previous CI-signed 1.0.0/1.1.0/1.1.1 builds
   preserve settings because the existing CI debug signing key is retained. The 1.2.0 CI build is also an update source.
2. On the first phone choose **Sender**, press **Generate**, and save the generated pair key.
3. Copy the **exact same** pair key to the second phone, choose **Receiver**, and save.
4. Select the same delivery mode in Advanced on both phones.
5. On Sender enable **Notification Access** and choose the apps to forward.
6. On Receiver allow notification permission and press **Start**.
7. Send Test, then test a real notification. Use **Sync now** to request current state.

Generate creates a random six-digit pair code from 100000 to 999999 using `SecureRandom`.
Existing 32-character pair keys remain supported, so previously paired phones can keep their key.
Six-digit codes have a smaller search space than those keys; keep your pairing code private.
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

Worker 1.3.0 responds at the base URL with service/version information. An older Worker
may return `{"error":"not_found"}` there; that alone does not mean it is broken.
Check `https://notification.mhdvi45.workers.dev/health`, then **Send Test** in Push mode on the phones.
Keep the Android Relay URL set to the base URL without `/health` or `/v1/send`; the app adds
`/v1/send` itself. The Android app also normalizes pasted `/health`, `/v1/send` and
`/v1/check` URLs to the correct base, and reports invalid HTTPS URLs before saving. An older deployed Worker may report only `{"ok":true}` at `/health`. The configured default
returned that response on 2026-10-09; this does not establish working Firebase delivery.

In Advanced, **Check Worker** checks reachability, private-key authorization and the Firebase
project against the APK. It calls authenticated `POST /v1/check`, which uses FCM `validate_only`
to verify sending permission **without delivering a notification**. A reachable older deployment
asks you to deploy Worker 1.3.0. **Send Test** is still required to verify the second phone/topic.
See [relay/README.md](relay/README.md) for deployment and interpreting errors.

Android normalizes wire message IDs (including `result:command-id` acknowledgements) to the
Worker's allowed format. The encrypted command ID is preserved for matching results. Push requests
include the APK's Firebase project ID and allow up to 20 seconds for a cold OAuth/FCM request;
Compatibility retains its 4-second read timeout. No periodic health polling was added.

The Worker validates body size, topic direction, ciphertext and FCM's 2 KiB topic data limit.
It caches OAuth tokens, retries one expired token, propagates retry delays, and avoids logging
message contents, Google error response bodies or credentials. Plaintext is decrypted only on phones.

## Lightweight implementation and limits

No new production dependency was added: Kotlin, Android platform APIs, OkHttp and Firebase Messaging
remain the runtime components. The send retry queue uses Android JobScheduler rather than adding
WorkManager. There is no periodic polling in Push mode. R8 and resource shrinking remain enabled.

- Notification capture runs on one background worker with at most 256 pending events. Updates
  coalesce without crossing a removal; overflow triggers active-state reconciliation. Commands
  have a separate durable inbox and reserved work slot.
- Application labels are cached for up to 128 packages. Unchanged snapshots are deduplicated before
  event-time disk writes, encryption and network enqueue. Duplicate network-flush work coalesces.
- Compatibility decrypts/processes received envelopes on a bounded background queue. Queue
  overload reconnects without advancing the replay cursor beyond the gap.
- Up to 256 encrypted sends are kept locally; ordinary updates for one source notification coalesce.
  Action results never coalesce with notification updates or removals.
- The source action inbox stores encrypted commands and at most 32 queued actions. The Receiver
  retains at most 64 action-status records; reply text is not stored in those records.
- Sender retains up to 256 unexpired command IDs. It rejects new actions explicitly when that
  history is full instead of evicting IDs that could allow a duplicate dispatch. Rejected actions
  remain rejected on replay; queued actions are retained in their original order.
- Queued mirror events expire after one hour; commands expire after ten minutes. FCM delivery TTL is five minutes.
- Sync snapshots cover up to 200 active notifications. Incomplete snapshots never clear unseen notifications.
- Receiver retains up to 512 ordering records and the latest 256 message IDs.
- Large notification text is shortened at Unicode code-point boundaries to fit the topic budget.
- If action metadata itself exceeds a single FCM message, the complete snapshot is split into
  individually encrypted parts (each still fits the 2 KiB topic budget). The outbox enqueues a whole
  group atomically. Receiver persists only ciphertext and displays the snapshot only after complete,
  hash-verified assembly, including out-of-order/replayed parts and process restarts.
- Assembled metadata is limited to 64 KiB / 92 parts. Receiver buffers at most 192 parts across eight
  groups for ten minutes. Over-limit metadata is rejected visibly rather than dropping buttons.
  Missing parts require **Sync now**; delivery and notification availability remain best effort.
  Replies are rejected with a visible error if they exceed the byte budget; user text is never silently shortened.
- Notification actions depend on the original app exposing an action and it still being active. Custom buttons drawn only inside RemoteViews are not exposed as Notification.Action and cannot be extracted by this mechanism.
- A queue acknowledgement means saved for sending; the status screen records actual HTTP send success,
  which differs from the source-side action result described below.

## Build and validation

JDK 17, Gradle 8.7, Android SDK 34:

```bash
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
node --test relay/test/relay.test.js
```

GitHub Actions restores the stable CI debug signature, tests Android sync/crypto, action-selection, generation, acknowledgement and bounded-queue rules and Worker
behavior, runs Android lint, builds the signed release APK with R8 obfuscation/resource shrinking,
and uploads it as `Notification-1.3.0-optimized-apk`. CI verifies that the obfuscation mapping exists
and reports the APK byte size and SHA-256.
The extra JUnit/JSON dependencies are test-only and are not included in the APK.

No connected Android phone or emulator is available in the development workspace. CI build, lint
and unit tests verify code and rules; they do not establish real battery consumption. Before
merging, test on both real phones in both delivery modes:

1. New notification, content update, Sender removal and Receiver swipe-to-dismiss.
2. Samsung Messages Call / Mark as read / Reply, duplicate labels and multiple replies; more than three actions, choice-only/multiple inputs and distinct wearable actions. Include stale generation, vanished notification, locked Sender,
   immutable/cancelled PendingIntent and ambiguous actions. Verify the result on Receiver.
3. Receiver offline, Sender offline, restore connectivity, then **Sync now**.
4. Swipe from Recents, screen off/Doze, reboot, and network/VPN changes.
5. Notification permission denied then granted, pair/mode changes, and stopping Compatibility during reconnect.
6. Confirm size, battery usage and duplicate behavior with typical selected apps. Compare
   battery drain, wakeups, transfer latency and network traffic on the same phones and workload.
7. Interrupt the process around dispatch: UNKNOWN must never trigger automatic reply replay.
8. Oversized Persian/emoji replies must show an error without sending a shortened message.

Existing draft PRs #1–#4 are unchanged. This work targets `agent/bridge-reliability-v1.2`. Keep this work in a separate draft PR until real-device
and deployed-Worker validation are complete; do not merge to main without the user's approval.
