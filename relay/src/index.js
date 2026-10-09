const encoder = new TextEncoder();
const VERSION = "1.3.0";
class RelayFailure extends Error {}
let cachedAccessToken = "";
let cachedAccessTokenUntil = 0;
let cachedAccount = "";
let tokenRequest = null;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, configured: Boolean(readAccount(env)),
        relayConfigured: (env.RELAY_TOKEN || "").length >= 24, version: VERSION }, 200);
    }
    if (request.method === "GET" && url.pathname === "/")
      return json({ service: "Notification relay", version: VERSION, health: "/health" }, 200);
    const checking = url.pathname === "/v1/check";
    if (request.method !== "POST" || (!checking && url.pathname !== "/v1/send")) return json({ error: "not_found" }, 404);
    const expected = env.RELAY_TOKEN || "";
    if (expected.length < 24 || request.headers.get("Authorization") !== `Bearer ${expected}`) {
      return json({ error: "unauthorized" }, 401);
    }
    if (!(request.headers.get("Content-Type") || "").toLowerCase().startsWith("application/json")) {
      return json({ error: "invalid_content_type" }, 415);
    }
    let body;
    try { body = JSON.parse(await limitedBody(request)); }
    catch (error) { return json({ error: error.message === "body_too_large" ? "body_too_large" : "invalid_json" }, error.message === "body_too_large" ? 413 : 400); }
    if (!body || typeof body !== "object" || Array.isArray(body)) return json({ error: "invalid_json" }, 400);

    if (checking) {
      const account = readAccount(env);
      if (!account) return json({ error: "relay_not_configured" }, 503, 60);
      if (typeof body.projectId !== "string" || !body.projectId || body.projectId !== account.project_id)
        return json({ error: "firebase_project_mismatch" }, 409);
      // FCM validates IAM/API/project configuration without delivering anything to a phone.
      return forward(account, "notification-diagnostic-check", { kind: "mirror", payload: "diagnostic", id: "check" },
        "normal", "check", true);
    }

    const { topic, kind, payload } = body;
    const id = body.id === undefined ? crypto.randomUUID() : body.id;
    const priority = body.priority === undefined ? (kind === "mirror" ? "high" : "normal") : body.priority;
    if (kind !== "mirror" && kind !== "command") return json({ error: "invalid_kind" }, 400);
    const pattern = kind === "command" ? /^notification-cmd-[A-Za-z0-9_-]{16,64}$/ : /^notification-(?!cmd-)[A-Za-z0-9_-]{16,64}$/;
    if (typeof topic !== "string" || !pattern.test(topic)) return json({ error: "invalid_topic" }, 400);
    if (typeof id !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(id)) return json({ error: "invalid_id" }, 400);
    if (priority !== "normal" && priority !== "high") return json({ error: "invalid_priority" }, 400);
    if (typeof payload !== "string" || !/^[A-Za-z0-9_-]{40,1800}$/.test(payload)) return json({ error: "invalid_payload" }, 400);
    const data = { kind, payload, id };
    // Topic messages have a 2 KiB data limit, including keys and values.
    if (encoder.encode(JSON.stringify(data)).length > 2048) return json({ error: "payload_too_large" }, 413);
    const account = readAccount(env);
    if (!account) return json({ error: "relay_not_configured" }, 503, 60);
    if (body.projectId !== undefined && (typeof body.projectId !== "string" || body.projectId !== account.project_id))
      return json({ error: "firebase_project_mismatch" }, 409);
    return forward(account, topic, data, priority, id);

  },
};

async function forward(account, topic, data, priority, id, validateOnly = false) {
  try {
    let accessToken = await getAccessToken(account);
    let response = await sendFcm(account.project_id, accessToken, topic, data, priority, validateOnly);
    if (response.status === 401) {
      cachedAccessToken = "";
      cachedAccessTokenUntil = 0;
      accessToken = await getAccessToken(account);
      response = await sendFcm(account.project_id, accessToken, topic, data, priority, validateOnly);
    }
    if (!response.ok) {
      // Only status, never Google's response body or notification/credential data.
      console.error("FCM status", response.status);
      if (response.status === 400) return json({ error: "fcm_invalid_payload" }, 400);
      if (response.status === 403) return json({ error: "fcm_permission_denied" }, 503, 300);
      return json({ error: "fcm_unavailable", status: response.status },
        response.status === 429 ? 429 : 503, retrySeconds(response.headers.get("Retry-After")));
    }
    return json(validateOnly ? { ok: true, version: VERSION, firebase: "verified" } : { ok: true, id }, 200);
  } catch (error) {
    return json({ error: error instanceof RelayFailure ? error.message : "relay_unavailable" }, 503, 60);
  }
}

function readAccount(env) {
  try {
    const account = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT_JSON || "");
    return account && typeof account.project_id === "string" && account.project_id &&
      typeof account.client_email === "string" && account.client_email &&
      typeof account.private_key === "string" && account.private_key.includes("BEGIN PRIVATE KEY")
      ? account : null;
  } catch { return null; }
}

async function limitedBody(request) {
  if (Number(request.headers.get("Content-Length")) > 8192) throw new Error("body_too_large");
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      length += value.length;
      if (length > 8192) { await reader.cancel(); throw new Error("body_too_large"); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return new TextDecoder().decode(bytes);
}

function sendFcm(projectId, accessToken, topic, data, priority, validateOnly = false) {
  return fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`, {
    method: "POST",
    signal: AbortSignal.timeout(8000),
    headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify({ ...(validateOnly ? { validate_only: true } : {}), message: { topic, data, android: { priority, ttl: "300s" } } }),
  });
}

async function getAccessToken(account) {
  const identity = `${account.project_id}:${account.client_email}`;
  const now = Date.now();
  if (cachedAccount === identity && cachedAccessToken && now < cachedAccessTokenUntil - 60_000) return cachedAccessToken;
  if (tokenRequest && cachedAccount === identity) return tokenRequest;
  cachedAccount = identity;
  tokenRequest = acquireToken(account, now);
  try { return await tokenRequest; } finally { tokenRequest = null; }
}

async function acquireToken(account, now) {
  const issuedAt = Math.floor(now / 1000);
  const unsigned = `${base64UrlJson({ alg: "RS256", typ: "JWT" })}.${base64UrlJson({
    iss: account.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token", iat: issuedAt, exp: issuedAt + 3600,
  })}`;
  const key = await crypto.subtle.importKey("pkcs8", pemToBytes(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, encoder.encode(unsigned));
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", signal: AbortSignal.timeout(8000),
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${unsigned}.${base64UrlBytes(new Uint8Array(signature))}` }),
  });
  if (!response.ok) throw new RelayFailure("oauth_rejected");
  const token = await response.json();
  if (typeof token.access_token !== "string" || !token.access_token) throw new Error("oauth_missing_token");
  const lifetime = Number(token.expires_in || 3600);
  if (!Number.isFinite(lifetime) || lifetime <= 0) throw new Error("oauth_invalid_expiry");
  cachedAccessToken = token.access_token;
  cachedAccessTokenUntil = now + Math.min(lifetime, 3600) * 1000;
  return cachedAccessToken;
}

function pemToBytes(pem) {
  const binary = atob(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace(/\s+/g, ""));
  return Uint8Array.from(binary, char => char.charCodeAt(0));
}

function base64UrlJson(value) { return base64UrlBytes(encoder.encode(JSON.stringify(value))); }
function base64UrlBytes(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function retrySeconds(value) {
  const seconds = Number(value);
  if (value && Number.isFinite(seconds)) return Math.min(3600, Math.max(60, Math.ceil(seconds)));
  const date = value ? Date.parse(value) : NaN;
  return Number.isFinite(date) ? Math.min(3600, Math.max(60, Math.ceil((date - Date.now()) / 1000))) : 60;
}
function json(value, status, retryAfter) {
  const headers = { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" };
  if (retryAfter) headers["Retry-After"] = String(retryAfter);
  return new Response(JSON.stringify(value), { status, headers });
}
