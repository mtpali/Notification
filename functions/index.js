"use strict";

const crypto = require("node:crypto");
const {onRequest} = require("firebase-functions/v2/https");
const {defineSecret} = require("firebase-functions/params");
const {requiresRole} = require("firebase-functions/v2");
const {initializeApp} = require("firebase-admin/app");
const {getMessaging} = require("firebase-admin/messaging");

requiresRole("roles/firebasecloudmessaging.admin");
initializeApp();

const RELAY_TOKEN = defineSecret("RELAY_TOKEN");
const TOPIC_PATTERN = /^(notification-|notification-cmd-)[A-Za-z0-9_-]{16,64}$/;
const MAX_ENCRYPTED_PAYLOAD = 3500;

exports.relay = onRequest(
  {
    region: "europe-west1",
    memory: "256MiB",
    timeoutSeconds: 15,
    minInstances: 0,
    maxInstances: 3,
    invoker: "public",
    cors: false,
    secrets: [RELAY_TOKEN],
  },
  async (request, response) => {
    response.set("Cache-Control", "no-store");

    if (request.method === "GET" && request.path === "/health") {
      return response.status(200).json({ok: true});
    }

    if (request.method !== "POST" || request.path !== "/v1/send") {
      return response.status(404).json({error: "not_found"});
    }

    const expected = RELAY_TOKEN.value() || "";
    const authorization = request.get("Authorization") || "";
    const provided = authorization.startsWith("Bearer ")
      ? authorization.slice("Bearer ".length)
      : "";

    if (expected.length < 24 || !safeTokenEquals(provided, expected)) {
      return response.status(401).json({error: "unauthorized"});
    }

    if (!request.is("application/json")) {
      return response.status(415).json({error: "invalid_content_type"});
    }

    const body = request.body && typeof request.body === "object"
      ? request.body
      : {};
    const topic = typeof body.topic === "string" ? body.topic : "";
    const kind = typeof body.kind === "string" ? body.kind : "";
    const payload = typeof body.payload === "string" ? body.payload : "";
    const id = typeof body.id === "string" && body.id.length <= 128
      ? body.id
      : crypto.randomUUID();

    if (!TOPIC_PATTERN.test(topic)) {
      return response.status(400).json({error: "invalid_topic"});
    }
    if (kind !== "mirror" && kind !== "command") {
      return response.status(400).json({error: "invalid_kind"});
    }
    if (!payload || payload.length > MAX_ENCRYPTED_PAYLOAD) {
      return response.status(400).json({error: "invalid_payload"});
    }

    try {
      const messageId = await getMessaging().send({
        topic,
        data: {kind, payload, id},
        android: {
          priority: "high",
          ttl: 300000,
        },
      });

      return response.status(200).json({ok: true, id, messageId});
    } catch (error) {
      console.error("FCM relay error", safeError(error));
      return response.status(502).json({error: "fcm_error"});
    }
  },
);

function safeTokenEquals(provided, expected) {
  if (!provided || provided.length !== expected.length) return false;
  const a = Buffer.from(provided, "utf8");
  const b = Buffer.from(expected, "utf8");
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function safeError(error) {
  const message = error instanceof Error ? error.message : String(error);
  return message.slice(0, 300);
}
