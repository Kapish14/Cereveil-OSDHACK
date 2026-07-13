import { base64UrlDecode, base64UrlEncode } from "../../lib/encoding";
import { env } from "../../_generated/server";

export const CHILD_DEVICE_JWT_LIFETIME_MS = 15 * 60 * 1000;

export type ChildDeviceJwtClaims = {
  credentialId: string;
  activeEnrollmentId: string;
  childDeviceId: string;
  sub: string;
  iat: number;
  exp: number;
  iss: string;
  aud: "cereveil-child-api";
};

export async function issueChildDeviceJwt(
  identity: Pick<ChildDeviceJwtClaims, "credentialId" | "activeEnrollmentId" | "childDeviceId">,
  serverNow: number,
) {
  const header = base64UrlJson({ alg: "ES256", kid: env.CHILD_DEVICE_JWT_KEY_ID, typ: "JWT" });
  const claims: ChildDeviceJwtClaims = {
    ...identity,
    sub: `child-device:${identity.childDeviceId}`,
    iat: Math.floor(serverNow / 1000),
    exp: Math.floor((serverNow + CHILD_DEVICE_JWT_LIFETIME_MS) / 1000),
    iss: env.CHILD_DEVICE_JWT_ISSUER,
    aud: "cereveil-child-api",
  };
  const payload = base64UrlJson(claims);
  const signingInput = `${header}.${payload}`;
  const signature = await sign(signingInput);
  return `${signingInput}.${base64UrlEncode(signature)}`;
}

export async function verifyChildDeviceJwt(token: string, serverNow: number) {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  const [header, payload, signature] = parts;
  try {
    const decodedHeader = JSON.parse(new TextDecoder().decode(base64UrlDecode(header))) as {
      alg?: string;
      kid?: string;
      typ?: string;
    };
    if (
      decodedHeader.alg !== "ES256" ||
      decodedHeader.kid !== env.CHILD_DEVICE_JWT_KEY_ID ||
      decodedHeader.typ !== "JWT"
    ) return null;
    if (!await verify(`${header}.${payload}`, base64UrlDecode(signature))) return null;
    const claims = JSON.parse(new TextDecoder().decode(base64UrlDecode(payload))) as ChildDeviceJwtClaims;
    if (
      claims.iss !== env.CHILD_DEVICE_JWT_ISSUER ||
      claims.aud !== "cereveil-child-api" ||
      claims.sub !== `child-device:${claims.childDeviceId}` ||
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
    ) return null;
    return claims;
  } catch {
    return null;
  }
}

export function childDeviceJwks() {
  const publicJwk = parseJwk(env.CHILD_DEVICE_JWT_PUBLIC_JWK, false);
  return {
    keys: [{
      ...publicJwk,
      alg: "ES256",
      kid: env.CHILD_DEVICE_JWT_KEY_ID,
      use: "sig",
    }],
  };
}

export function validateChildDeviceJwtConfiguration() {
  parseJwk(env.CHILD_DEVICE_JWT_PRIVATE_JWK, true);
  parseJwk(env.CHILD_DEVICE_JWT_PUBLIC_JWK, false);
  if (env.CHILD_DEVICE_JWT_KEY_ID.length < 1) throw new Error("Child Device JWT key ID is missing.");
  const issuer = new URL(env.CHILD_DEVICE_JWT_ISSUER);
  if (issuer.protocol !== "https:") throw new Error("Child Device JWT issuer must use HTTPS.");
}

function base64UrlJson(value: unknown) {
  return base64UrlEncode(new TextEncoder().encode(JSON.stringify(value)));
}

async function sign(value: string) {
  const key = await crypto.subtle.importKey(
    "jwk",
    parseJwk(env.CHILD_DEVICE_JWT_PRIVATE_JWK, true),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  );
  return new Uint8Array(await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    new TextEncoder().encode(value),
  ));
}

async function verify(value: string, signature: Uint8Array) {
  const key = await crypto.subtle.importKey(
    "jwk",
    parseJwk(env.CHILD_DEVICE_JWT_PUBLIC_JWK, false),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );
  return await crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    new Uint8Array(signature).buffer as ArrayBuffer,
    new TextEncoder().encode(value),
  );
}

function parseJwk(value: string, requirePrivate: boolean): JsonWebKey {
  const jwk = JSON.parse(value) as JsonWebKey;
  if (
    jwk.kty !== "EC" || jwk.crv !== "P-256" ||
    typeof jwk.x !== "string" || typeof jwk.y !== "string" ||
    (requirePrivate && typeof jwk.d !== "string")
  ) throw new Error("Child Device JWT key configuration is invalid.");
  return jwk;
}
