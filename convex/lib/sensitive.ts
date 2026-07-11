import { base64UrlEncode } from "./encoding";
import { env } from "../_generated/server";

export async function encryptPushToken(token: string) {
  const secret = env.CHILD_PUSH_TOKEN_ENCRYPTION_SECRET;
  if (secret.length < 32) {
    throw new Error("CHILD_PUSH_TOKEN_ENCRYPTION_SECRET must contain at least 32 characters.");
  }
  const keyBytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(secret));
  const key = await crypto.subtle.importKey("raw", keyBytes, "AES-GCM", false, ["encrypt"]);
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce },
    key,
    new TextEncoder().encode(token),
  );
  return `${base64UrlEncode(nonce)}.${base64UrlEncode(new Uint8Array(encrypted))}`;
}
