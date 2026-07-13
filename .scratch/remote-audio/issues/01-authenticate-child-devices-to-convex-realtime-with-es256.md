Status: ready-for-agent

# Authenticate Child Devices to Convex realtime with ES256

## Parent

[Remote Audio](../PRD.md) — user stories 73–76.

## What to build

Make Child Device authentication usable across both the existing HTTP feature boundary and authenticated realtime Convex functions. Replace the undeployed HS256 bearer-token format with a fifteen-minute ES256 custom JWT issued from a dedicated backend P-256 signing key, publish its public verification key through JWKS, and register the issuer with Convex custom authentication.

Keep enrollment and proof-of-possession token refresh on their established HTTP boundary, and preserve every completed Child feature request/response contract. Adapt shared Child HTTP verification and actor resolution to the new token while adding the smallest authenticated Child Convex Android client and function-wrapper seam needed to prove a realtime query. A valid signature is never sufficient by itself: both transports must resolve and validate the current Child Device Credential, Active Enrollment, Child Device, Child Profile, and Household chain on every operation.

There are no deployed users or legacy tokens. Make a clean cutover without dual-token verification or production migration machinery.

## Acceptance criteria

- [ ] Enrollment and proof-of-possession refresh issue fifteen-minute ES256 JWTs with a key ID, subject, URL issuer, audience, issued time, expiry, and credential/enrollment/device claims.
- [ ] The ES256 signing key is backend-held and distinct from every Child Device Keystore proof-of-possession key; only public key material is exposed through a valid JWKS response.
- [ ] Convex accepts the Child issuer through custom JWT authentication and rejects wrong issuer, audience, key ID, algorithm, signature, expiry, and malformed claims.
- [ ] An authenticated Child realtime query proves that the Convex Android client can obtain, refresh, and send the Device Identity token over the realtime connection.
- [ ] Realtime Child wrappers derive identity from authenticated claims and revalidate the full current Child Device authorization chain before returning data.
- [ ] Existing Child HTTP endpoints retain their public contracts while using the ES256 verifier and the same full actor-chain authorization semantics.
- [ ] Revoked credentials, inactive enrollments, revoked or mismatched devices, inactive Child Profiles, and wrong-Household claim combinations fail safely on both HTTP and realtime paths.
- [ ] Existing policy, command, heartbeat, FCM-token, App Catalog, Access Request, Location, Screen Time, and Safety Alert Child integration tests pass with ES256 tokens.
- [ ] Token and authentication failures do not leak private key material, raw tokens, identity claims, or child-specific data into logs or errors.
- [ ] Guardian Clerk authentication remains unchanged and coexists with the Child custom JWT provider.

## Blocked by

None - can start immediately.
