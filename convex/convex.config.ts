import { defineApp } from "convex/server";
import { v } from "convex/values";

export default defineApp({
  env: {
    CLERK_JWT_ISSUER_DOMAIN: v.string(),
    CHILD_DEVICE_JWT_SECRET: v.string(),
    CHILD_PUSH_TOKEN_ENCRYPTION_SECRET: v.string(),
  },
});
