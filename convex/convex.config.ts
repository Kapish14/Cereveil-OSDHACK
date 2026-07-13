import { defineApp } from "convex/server";
import { v } from "convex/values";

export default defineApp({
  env: {
    CLERK_JWT_ISSUER_DOMAIN: v.string(),
    CHILD_DEVICE_JWT_PRIVATE_JWK: v.string(),
    CHILD_DEVICE_JWT_PUBLIC_JWK: v.string(),
    CHILD_DEVICE_JWT_KEY_ID: v.string(),
    CHILD_DEVICE_JWT_ISSUER: v.string(),
    CHILD_PUSH_TOKEN_ENCRYPTION_SECRET: v.string(),
    FCM_TOKEN_ENCRYPTION_KEY_V1: v.optional(v.string()),
    FCM_TOKEN_ENCRYPTION_ACTIVE_VERSION: v.optional(v.string()),
    FCM_PROJECT_ID: v.optional(v.string()),
    FCM_CLIENT_EMAIL: v.optional(v.string()),
    FCM_PRIVATE_KEY: v.optional(v.string()),
    REMOTE_AUDIO_STUN_URLS: v.optional(v.string()),
  },
});
