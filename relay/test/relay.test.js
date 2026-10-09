import { test } from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";

globalThis.crypto ||= webcrypto;
const relayToken = "test-relay-key-never-used-in-production";
const env = { RELAY_TOKEN: relayToken };
const topic = `notification-${"a".repeat(32)}`;
const validBody = { topic, kind: "mirror", payload: "a".repeat(100), id: "test-id" };
let sequence = 0;
async function worker() { return (await import(`../src/index.js?test=${sequence++}`)).default; }
function request(body, headers = {}) {
  return new Request("https://relay.example/v1/send", { method: "POST", headers: {
    Authorization: `Bearer ${relayToken}`, "Content-Type": "application/json", ...headers,
  }, body: JSON.stringify(body) });
}
async function account() {
  const pair = await crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
  const bytes = await crypto.subtle.exportKey("pkcs8", pair.privateKey);
  return { ...env, GOOGLE_SERVICE_ACCOUNT_JSON: JSON.stringify({ project_id: "test-project",
    client_email: "test@test-project.iam.gserviceaccount.com", private_key:
      `-----BEGIN PRIVATE KEY-----\n${Buffer.from(bytes).toString("base64")}\n-----END PRIVATE KEY-----` }) };
}

test("health exposes readiness without exposing settings", async () => {
  const w = await worker();
  const response = await w.fetch(new Request("https://relay.example/health"), env);
  assert.deepEqual(await response.json(), { ok: true, configured: false, relayConfigured: true, version: "1.3.0" });
});
test("authentication is required before parsing the request", async () => {
  const response = await (await worker()).fetch(request(validBody, { Authorization: "Bearer wrong" }), env);
  assert.equal(response.status, 401);
});
test("null, arrays and primitives are rejected without a crash", async () => {
  const w = await worker();
  for (const body of [null, [], 42, "text"]) assert.equal((await w.fetch(request(body), env)).status, 400);
});
test("topic must match message direction", async () => {
  const w = await worker();
  assert.equal((await w.fetch(request({ ...validBody, kind: "command" }), env)).status, 400);
  assert.equal((await w.fetch(request({ ...validBody, topic: `notification-cmd-${"a".repeat(32)}` }), env)).status, 400);
});
test("oversize ciphertext and non-Base64 payloads are rejected", async () => {
  const w = await worker();
  for (const payload of ["a".repeat(1801), "plaintext notification", "سلام".repeat(300)]) {
    assert.equal((await w.fetch(request({ ...validBody, payload }), env)).status, 400);
  }
});
test("request stream has a bounded byte budget", async () => {
  const response = await (await worker()).fetch(request({ ...validBody, padding: "x".repeat(9000) }), env);
  assert.equal(response.status, 413);
});
test("unconfigured relay responds with a retryable status", async () => {
  const response = await (await worker()).fetch(request(validBody), env);
  assert.equal(response.status, 503);
  assert.equal(response.headers.get("Retry-After"), "60");
});
test("sends only ciphertext, reuses OAuth token and defaults commands to normal priority", async t => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  const configured = await account();
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return String(url).includes("oauth2") ? Response.json({ access_token: "fake-access-token", expires_in: 3600 }) : Response.json({ name: "test-message" });
  };
  const w = await worker();
  assert.equal((await w.fetch(request(validBody), configured)).status, 200);
  const command = { ...validBody, kind: "command", topic: `notification-cmd-${"a".repeat(32)}`, id: "command-id" };
  assert.equal((await w.fetch(request(command), configured)).status, 200);
  assert.equal(calls.filter(c => c.url.includes("oauth2")).length, 1);
  const bodies = calls.filter(c => c.url.includes("fcm.googleapis")).map(c => JSON.parse(c.options.body));
  assert.deepEqual(bodies[0].message.data, { kind: "mirror", payload: validBody.payload, id: validBody.id });
  assert.equal(bodies[0].message.android.priority, "high");
  assert.equal(bodies[1].message.android.priority, "normal");
  assert.ok(!("notification" in bodies[0].message));
});
test("FCM rate limits propagate Retry-After", async t => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  globalThis.fetch = async url => String(url).includes("oauth2") ? Response.json({ access_token: "test", expires_in: 3600 })
    : new Response("", { status: 429, headers: { "Retry-After": "120" } });
  const response = await (await worker()).fetch(request(validBody), await account());
  assert.equal(response.status, 429);
  assert.equal(response.headers.get("Retry-After"), "120");
});
test("stale OAuth token refreshes once on FCM unauthorized", async t => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  let oauth = 0;
  let fcm = 0;
  globalThis.fetch = async url => {
    if (String(url).includes("oauth2")) { oauth++; return Response.json({ access_token: `test-${oauth}`, expires_in: 3600 }); }
    return ++fcm === 1 ? new Response("", { status: 401 }) : Response.json({ name: "ok" });
  };
  assert.equal((await (await worker()).fetch(request(validBody), await account())).status, 200);
  assert.equal(oauth, 2);
  assert.equal(fcm, 2);
});

function checkRequest(projectId, authorization = `Bearer ${relayToken}`) {
  return new Request("https://relay.example/v1/check", { method: "POST", headers: {
    Authorization: authorization, "Content-Type": "application/json",
  }, body: JSON.stringify({ projectId }) });
}

test("opening the Worker base URL explains its service without claiming delivery", async () => {
  const response = await (await worker()).fetch(new Request("https://relay.example/"), env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { service: "Notification relay", version: "1.3.0", health: "/health" });
});

test("diagnostic endpoint requires the private key and a configured Firebase account", async () => {
  const w = await worker();
  assert.equal((await w.fetch(checkRequest("test-project", "Bearer wrong"), env)).status, 401);
  const unavailable = await w.fetch(checkRequest("test-project"), env);
  assert.equal(unavailable.status, 503);
  assert.equal((await unavailable.json()).error, "relay_not_configured");
});

test("diagnostic detects a Firebase project mismatch before contacting Google", async t => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  globalThis.fetch = () => { throw new Error("must not call Google for mismatched project"); };
  const response = await (await worker()).fetch(checkRequest("different-project"), await account());
  assert.equal(response.status, 409);
  assert.equal((await response.json()).error, "firebase_project_mismatch");
});

test("diagnostic checks FCM permission with validate_only and never delivers a notification", async t => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  let sent;
  globalThis.fetch = async (url, options) => {
    if (String(url).includes("oauth2")) return Response.json({ access_token: "fake-token", expires_in: 3600 });
    sent = JSON.parse(options.body);
    return Response.json({ name: "validated" });
  };
  const response = await (await worker()).fetch(checkRequest("test-project"), await account());
  assert.equal(response.status, 200);
  assert.equal((await response.json()).firebase, "verified");
  assert.equal(sent.validate_only, true);
  assert.equal(sent.message.topic, "notification-diagnostic-check");
  assert.equal(sent.message.android.priority, "normal");
  assert.ok(!("notification" in sent.message));
});

test("Google service-account and FCM permission failures have distinct safe errors", async t => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  const configured = await account();
  globalThis.fetch = async () => new Response("private Google error must never escape", { status: 400 });
  const oauth = await (await worker()).fetch(checkRequest("test-project"), configured);
  assert.deepEqual(await oauth.json(), { error: "oauth_rejected" });
  globalThis.fetch = async url => String(url).includes("oauth2") ? Response.json({ access_token: "fake", expires_in: 3600 })
    : new Response("private credentials must never escape", { status: 403 });
  const fcm = await (await worker()).fetch(checkRequest("test-project"), configured);
  assert.equal(fcm.status, 503);
  assert.deepEqual(await fcm.json(), { error: "fcm_permission_denied" });
});

test("send endpoint accepts the normalized acknowledgement ID used by Android", async t => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  globalThis.fetch = async url => String(url).includes("oauth2") ? Response.json({ access_token: "fake", expires_in: 3600 })
    : Response.json({ name: "sent" });
  const configured = await account();
  const w = await worker();
  assert.equal((await w.fetch(request({ ...validBody, id: "result:command-id" }), configured)).status, 400);
  assert.equal((await w.fetch(request({ ...validBody, id: "0a72fe01c0ffcd817235b1322d98f091" }), configured)).status, 200);
});
