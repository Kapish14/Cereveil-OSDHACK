"use node";

import { createDecipheriv, createHash } from "node:crypto";
import { GoogleAuth } from "google-auth-library";
import { v } from "convex/values";
import { internalAction } from "./_generated/server";
import { env } from "./_generated/server";
import { internal } from "./_generated/api";

const retryDelays = [30_000, 90_000, 240_000, 540_000];

export const deliver = internalAction({
  args: {
    recordKind: v.union(v.literal("guardianNotice"), v.literal("childDeviceCommand")),
    recordId: v.string(),
    attempt: v.number(),
  },
  handler: async (ctx, args) => {
    const delivery = await ctx.runQuery(internal.modules.notifications.internal.getDeliveryTargets, {
      recordKind: args.recordKind,
      recordId: args.recordId,
    });
    if (delivery === null || delivery.targets.length === 0) return null;
    const projectId = env.FCM_PROJECT_ID;
    const clientEmail = env.FCM_CLIENT_EMAIL;
    const privateKey = env.FCM_PRIVATE_KEY;
    const encryptionKey = env.FCM_TOKEN_ENCRYPTION_KEY_V1;
    if (!projectId || !clientEmail || !privateKey || !encryptionKey) throw new Error("FCM development configuration is incomplete.");
    const auth = new GoogleAuth({
      credentials: { client_email: clientEmail, private_key: privateKey },
      scopes: ["https://www.googleapis.com/auth/firebase.messaging"],
    });
    const accessToken = await auth.getAccessToken();
    if (!accessToken) throw new Error("FCM access token unavailable.");
    let shouldRetry = false;
    for (const target of delivery.targets) {
      const response = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
        method: "POST",
        headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
        body: JSON.stringify({
          message: {
            token: decryptToken(target.encryptedToken, encryptionKey),
            data: { schemaVersion: "1", category: delivery.category, recordId: args.recordId },
            android: { priority: delivery.priority },
          },
        }),
      });
      const body = await response.text();
      const invalid = response.status === 404 || (response.status === 400 && /UNREGISTERED|registration-token-not-registered/i.test(body));
      const transient = response.status === 429 || response.status >= 500;
      const outcome = response.ok ? "accepted" : invalid ? "invalid" : transient && args.attempt < 5 ? "transient" : "exhausted";
      await ctx.runMutation(internal.modules.notifications.internal.recordDeliveryOutcome, {
        ...args,
        fcmTokenId: target._id,
        outcome,
        serverNow: Date.now(),
      });
      shouldRetry ||= outcome === "transient";
    }
    if (shouldRetry && args.attempt < 5) {
      const base = retryDelays[Math.min(args.attempt - 1, retryDelays.length - 1)];
      const jitter = Math.floor(base * (0.8 + Math.random() * 0.4));
      await ctx.scheduler.runAfter(jitter, internal.fcmDelivery.deliver, { ...args, attempt: args.attempt + 1 });
    }
    return null;
  },
});

function decryptToken(value: string, secret: string) {
  const [version, nonceValue, ciphertextValue] = value.split(".");
  if (version !== "1" || !nonceValue || !ciphertextValue) throw new Error("Unsupported FCM token ciphertext.");
  const decode = (input: string) => Buffer.from(input.replace(/-/g, "+").replace(/_/g, "/"), "base64");
  const encrypted = decode(ciphertextValue);
  const authTag = encrypted.subarray(encrypted.length - 16);
  const ciphertext = encrypted.subarray(0, encrypted.length - 16);
  const decipher = createDecipheriv("aes-256-gcm", createHash("sha256").update(secret).digest(), decode(nonceValue));
  decipher.setAuthTag(authTag);
  return Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString("utf8");
}
