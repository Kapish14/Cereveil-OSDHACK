import { base64UrlEncode } from "./encoding";
import { env } from "../_generated/server";

export async function encryptPushToken(token: string) {
  const version = env.FCM_TOKEN_ENCRYPTION_ACTIVE_VERSION ?? "legacy";
  const secret = version === "1"
    ? (env.FCM_TOKEN_ENCRYPTION_KEY_V1 ?? "")
    : env.CHILD_PUSH_TOKEN_ENCRYPTION_SECRET;
  if (secret.length < 32) {
    throw new Error("The active FCM token encryption key must contain at least 32 characters.");
  }
  const keyBytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(secret));
  const key = await crypto.subtle.importKey("raw", keyBytes, "AES-GCM", false, ["encrypt"]);
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce },
    key,
    new TextEncoder().encode(token),
  );
  return `${version}.${base64UrlEncode(nonce)}.${base64UrlEncode(new Uint8Array(encrypted))}`;
}
