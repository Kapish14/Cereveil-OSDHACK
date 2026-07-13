import { AuthConfig } from "convex/server";
import { env } from "./_generated/server";

export default {
  providers: [
    {
      domain: env.CLERK_JWT_ISSUER_DOMAIN,
      applicationID: "convex",
    },
    {
      type: "customJwt",
      issuer: env.CHILD_DEVICE_JWT_ISSUER,
      applicationID: "cereveil-child-api",
      jwks: `${env.CHILD_DEVICE_JWT_ISSUER}/.well-known/jwks.json`,
      algorithm: "ES256",
    },
  ],
} satisfies AuthConfig;
