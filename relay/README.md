# Notification Worker 1.3.0

Deploy this directory to the Cloudflare account that owns `notification.mhdvi45.workers.dev`.
The default URL returned HTTP 200 with `{ "ok": true }` from `/health` on 2026-10-09.
That proves reachability, not Firebase permission, topic subscription or phone delivery.

```sh
cd relay
npm install
npx wrangler login
npx wrangler secret put GOOGLE_SERVICE_ACCOUNT_JSON
npx wrangler secret put RELAY_TOKEN
npm run deploy
```

If both secrets already exist and are correct, keep them and only deploy the new source.
`GOOGLE_SERVICE_ACCOUNT_JSON` is a Google service account for the **same Firebase project**
used by `app/google-services.json`. Enable the Firebase Cloud Messaging HTTP v1 API and
service-account permission to send messages. Paste the JSON into the Cloudflare secret prompt;
never put it in this repository or APK. `RELAY_TOKEN` is a random private value of at least
24 characters, matching Advanced → Private key on both phones.

After deploying, `/health` should report `version: "1.3.0"`, `configured: true` and
`relayConfigured: true`. In Notification 1.3.0 on either phone, Advanced → **Check Worker**
checks the private key, matching Firebase project and Google sending permission using
`validate_only: true`; it does not send a notification. Then use **Send Test** from Sender
and **Sync now** on Receiver to verify real delivery in both directions.

| Result | Next action |
| --- | --- |
| Endpoint missing / old Worker | Deploy this directory; use the actual base HTTPS URL on both phones. |
| Private key rejected | Match Android Private key with the Cloudflare `RELAY_TOKEN` secret. |
| Firebase secret missing/invalid | Correct `GOOGLE_SERVICE_ACCOUNT_JSON` in Cloudflare. |
| Different Firebase project | Use the service account for the APK's Firebase project. |
| Google service account rejected | Check the service account/private key is valid and not revoked. |
| FCM permission denied | Enable the FCM API and grant that service account sending permission. |
| Check succeeds but phone sees nothing | Same pairing code/delivery mode; Start Receiver; notification permission; Firebase topic subscription/Google Play services/network; test both directions. |

Compatibility mode uses ntfy.sh instead of this Worker. Select it on **both** phones if you
want to test the new action forwarding while the Cloudflare configuration is being corrected.

This source update does not itself deploy or modify production secrets. No Cloudflare
account was available in the development session. Worker tests use mock Google responses.
