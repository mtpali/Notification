const encoder = new TextEncoder();

let cachedAccessToken = "";
let cachedAccessTokenUntil = 0;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true }, 200);
    }

    if (request.method !== "POST" || url.pathname !== "/v1/send") {
      return json({ error: "not_found" }, 404);
    }

    const expected = env.RELAY_TOKEN || "";
    const provided = request.headers.get("Authorization") || "";
    if (expected.length < 24 || provided !== `Bearer ${expected}`) {
      return json({ error: "unauthorized" }, 401);
    }

    const contentType = request.headers.get("Content-Type") || "";
    if (!contentType.toLowerCase().startsWith("application/json")) {
      return json({ error: "invalid_content_type" }, 415);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }

    const topic = typeof body.topic === "string" ? body.topic : "";
    const kind = typeof body.kind === "string" ? body.kind : "";
    const payload = typeof body.payload === "string" ? body.payload : "";
    const id = typeof body.id === "string" && body.id.length <= 128
      ? body.id
      : crypto.randomUUID();

    if (!/^(notification-|notification-cmd-)[A-Za-z0-9_-]{16,64}$/.test(topic)) {
      return json({ error: "invalid_topic" }, 400);
    }
    if (kind !== "mirror" && kind !== "command") {
      return json({ error: "invalid_kind" }, 400);
    }
    if (!payload || payload.length > 3500) {
      return json({ error: "invalid_payload" }, 400);
    }

    let serviceAccount;
    try {
      serviceAccount = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT_JSON || "");
    } catch {
      return json({ error: "relay_not_configured" }, 500);
    }

    const projectId = serviceAccount.project_id || "";
    if (!projectId || !serviceAccount.client_email || !serviceAccount.private_key) {
      return json({ error: "relay_not_configured" }, 500);
    }

    try {
      let accessToken = await getAccessToken(serviceAccount);
      let response = await sendFcm(projectId, accessToken, topic, kind, payload, id);

      if (response.status === 401) {
        cachedAccessToken = "";
        cachedAccessTokenUntil = 0;
        accessToken = await getAccessToken(serviceAccount);
        response = await sendFcm(projectId, accessToken, topic, kind, payload, id);
      }

      if (!response.ok) {
        const responseText = await response.text();
        console.error("FCM send failed", response.status, responseText.slice(0, 300));
        return json({ error: "fcm_error", status: response.status }, 502);
      }

      return json({ ok: true, id }, 200);
    } catch (error) {
      console.error("Relay error", String(error).slice(0, 300));
      return json({ error: "relay_error" }, 500);
    }
  },
};

async function sendFcm(projectId, accessToken, topic, kind, payload, id) {
  return fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Content-Type": "application/json; charset=utf-8",
    },
    body: JSON.stringify({
      message: {
        topic,
        data: { kind, payload, id },
        android: {
          priority: "high",
          ttl: "300s",
        },
      },
    }),
  });
}

async function getAccessToken(serviceAccount) {
  const now = Date.now();
  if (cachedAccessToken && now < cachedAccessTokenUntil - 60_000) {
    return cachedAccessToken;
  }

  const issuedAt = Math.floor(now / 1000);
  const header = base64UrlJson({ alg: "RS256", typ: "JWT" });
  const claims = base64UrlJson({
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: issuedAt,
    exp: issuedAt + 3600,
  });
  const unsigned = `${header}.${claims}`;

  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(serviceAccount.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    { name: "RSASSA-PKCS1-v1_5" },
    key,
    encoder.encode(unsigned),
  );
  const assertion = `${unsigned}.${base64UrlBytes(new Uint8Array(signature))}`;

  const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });

  if (!tokenResponse.ok) {
    throw new Error(`oauth_${tokenResponse.status}`);
  }

  const token = await tokenResponse.json();
  cachedAccessToken = token.access_token || "";
  cachedAccessTokenUntil = now + Number(token.expires_in || 3600) * 1000;
  if (!cachedAccessToken) throw new Error("oauth_missing_access_token");
  return cachedAccessToken;
}

function pemToArrayBuffer(pem) {
  const base64 = pem
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s+/g, "");
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function base64UrlJson(value) {
  return base64UrlBytes(encoder.encode(JSON.stringify(value)));
}

function base64UrlBytes(bytes) {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function json(value, status) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}
