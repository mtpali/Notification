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
  assert.deepEqual(await response.json(), { ok: true, configured: false, version: "1.1.0" });
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
