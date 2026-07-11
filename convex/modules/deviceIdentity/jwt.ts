import { base64UrlDecode, base64UrlEncode } from "../../lib/encoding";
import { env } from "../../_generated/server";

export const CHILD_DEVICE_JWT_LIFETIME_MS = 15 * 60 * 1000;

export type ChildDeviceJwtClaims = {
  credentialId: string;
  activeEnrollmentId: string;
  childDeviceId: string;
  iat: number;
  exp: number;
  iss: "cereveil-device-identity";
  aud: "cereveil-child-api";
};

export async function issueChildDeviceJwt(
  identity: Pick<ChildDeviceJwtClaims, "credentialId" | "activeEnrollmentId" | "childDeviceId">,
  serverNow: number,
) {
  const header = base64UrlJson({ alg: "HS256", typ: "JWT" });
  const claims: ChildDeviceJwtClaims = {
    ...identity,
    iat: Math.floor(serverNow / 1000),
    exp: Math.floor((serverNow + CHILD_DEVICE_JWT_LIFETIME_MS) / 1000),
    iss: "cereveil-device-identity",
    aud: "cereveil-child-api",
  };
  const payload = base64UrlJson(claims);
  const signingInput = `${header}.${payload}`;
  const signature = await hmac(signingInput);
  return `${signingInput}.${base64UrlEncode(signature)}`;
}

export async function verifyChildDeviceJwt(token: string, serverNow: number) {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  const [header, payload, signature] = parts;
  const expected = await hmac(`${header}.${payload}`);
  if (!constantTimeEqual(expected, base64UrlDecode(signature))) return null;
  try {
    const decodedHeader = JSON.parse(new TextDecoder().decode(base64UrlDecode(header))) as {
      alg?: string;
      typ?: string;
    };
    if (decodedHeader.alg !== "HS256" || decodedHeader.typ !== "JWT") return null;
    const claims = JSON.parse(new TextDecoder().decode(base64UrlDecode(payload))) as ChildDeviceJwtClaims;
    if (
      claims.iss !== "cereveil-device-identity" ||
      claims.aud !== "cereveil-child-api" ||
      typeof claims.credentialId !== "string" ||
      typeof claims.activeEnrollmentId !== "string" ||
      typeof claims.childDeviceId !== "string" ||
      typeof claims.iat !== "number" ||
      !Number.isInteger(claims.iat) ||
      typeof claims.exp !== "number" ||
      !Number.isInteger(claims.exp) ||
      claims.iat > Math.floor(serverNow / 1000) + 60 ||
      claims.exp - claims.iat > CHILD_DEVICE_JWT_LIFETIME_MS / 1000 ||
      claims.exp <= Math.floor(serverNow / 1000)
    ) {
      return null;
    }
    return claims;
  } catch {
    return null;
  }
}

function base64UrlJson(value: unknown) {
  return base64UrlEncode(new TextEncoder().encode(JSON.stringify(value)));
}

async function hmac(value: string) {
  const secret = env.CHILD_DEVICE_JWT_SECRET;
  if (secret.length < 32) {
    throw new Error("CHILD_DEVICE_JWT_SECRET must contain at least 32 characters.");
  }
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value)));
}

function constantTimeEqual(left: Uint8Array, right: Uint8Array) {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let index = 0; index < left.length; index += 1) difference |= left[index] ^ right[index];
  return difference === 0;
}
