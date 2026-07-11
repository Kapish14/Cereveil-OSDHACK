Status: ready-for-agent

# Introduce childDeviceHttpAction Through Policy Fetch and Acknowledgement

## Parent

.scratch/authenticated-function-wrapper-hardening/PRD.md

## What to build

Introduce the common authenticated Child Device HTTP boundary and prove it end-to-end by migrating Supervision Policy fetch and acknowledgement. The boundary must strictly verify the Child Device JWT, resolve the complete current authorization chain, derive Child Profile and Household identity from authoritative records, and give endpoint callbacks a validated ChildDeviceActor. Protected policy operations must revalidate that actor inside their own transaction so revocation cannot race a read or write.

The wrapper will exclusively construct HTTP success and error responses, add server-owned correlation, and use the shared privacy-safe logging infrastructure. Child Mode will consume stable error codes through typed client failures, refresh credentials and retry at most once after unauthorized responses, and recover from policy-version mismatch by fetching and applying the latest Supervision Policy.

## Acceptance criteria

- [ ] `childDeviceHttpAction` is a higher-order authenticated HTTP wrapper, not an internal Convex action.
- [ ] Endpoint callbacks return application data or typed application errors and cannot bypass common JSON serialization, status mapping, headers, or logging with raw responses.
- [ ] The wrapper accepts only a Bearer Child Device JWT and verifies its structure, signature, supported algorithm and token type, issuer, audience, expiration, temporal claim types, and required identity claim types.
- [ ] Malformed IDs and claims fail safely as `CHILD_DEVICE_UNAUTHORIZED` rather than leaking Convex validator or database details.
- [ ] JWT claims supply only credential, Active Enrollment, and Child Device identity candidates and are never treated as proof of current authorization.
- [ ] A private actor-resolution query validates active and mutually consistent Child Device Credential, Active Enrollment, Child Device, Child Profile, and Household records.
- [ ] Child Profile and Household IDs are derived from authoritative records rather than accepted from the request or JWT.
- [ ] ChildDeviceActor contains credential, Active Enrollment, Child Device, Child Profile, and Household IDs.
- [ ] Missing, inactive, revoked, ended, deleting, malformed, or inconsistent authorization-chain states all map to the same `401 CHILD_DEVICE_UNAUTHORIZED` response.
- [ ] A shared runtime validator represents ChildDeviceActor shape, while a separate authorization helper reloads and proves the complete chain.
- [ ] Internal authenticated Child functions accept nested actor and application-input objects rather than mixing server-derived IDs with untrusted fields.
- [ ] Protected internal policy queries and mutations revalidate the complete actor chain in their own transaction.
- [ ] Policy fetch uses `childDeviceHttpAction` and returns the current active Supervision Policy only to the valid Active Enrollment.
- [ ] Policy acknowledgement uses `childDeviceHttpAction` and updates Policy Application State only after transactional actor revalidation.
- [ ] Acknowledging a version other than the currently desired policy returns `409 POLICY_VERSION_MISMATCH`.
- [ ] Malformed authenticated endpoint input returns `400 VALIDATION_FAILED` rather than an authentication error.
- [ ] Unexpected failures return `500 INTERNAL_ERROR` without raw exception, crypto, configuration, or database details.
- [ ] Every success and error response includes a server-generated `X-Request-Id`, and an inbound request ID is never reused.
- [ ] All authenticated failures and successful policy acknowledgements are logged through the typed allowlist; successful policy fetches do not create persistent per-request logs.
- [ ] Logs exclude authorization headers, JWTs, claims, actor/database IDs, policy bodies, request bodies, and raw exceptions.
- [ ] Child Mode maps validation, unauthorized, policy mismatch, internal failure, and network failure into typed client results without parsing message text.
- [ ] Unauthorized recovery performs at most one token challenge/exchange and one retry of the original request.
- [ ] Policy-version mismatch recovery fetches, stores, applies, and acknowledges the latest policy using the established policy ordering rules.
- [ ] Internal failures use bounded retry with backoff, while network failure remains normal offline behavior.
- [ ] Enrollment Preview, enrollment completion, token challenge, and token exchange remain direct credential-bootstrap HTTP endpoints.
- [ ] Public HTTP wrapper contract tests cover JWT verification, every authorization-chain lifecycle and consistency class, derived ownership identity, safe errors, request IDs, logging privacy, and transactional revocation revalidation.
- [ ] Android client tests cover typed mappings, bounded unauthorized recovery, policy mismatch recovery, internal backoff, and network failure.

## Blocked by

- .scratch/authenticated-function-wrapper-hardening/issues/01-harden-guardian-wrappers-and-migrate-post-bootstrap-apis.md
