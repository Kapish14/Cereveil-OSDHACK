Status: ready-for-agent

# Authenticated Function Wrapper Hardening PRD

## Problem Statement

Cereveil now has Guardian Account bootstrap, Guardian Device registration, Child Profile onboarding, and Child Device enrollment, but its authenticated backend boundaries remain inconsistent. Guardian functions can resolve a Guardian Account and Household without proving which active Guardian Device made the request, Child Profile APIs duplicate Clerk identity and lifecycle resolution rather than using the shared Guardian wrappers, and authenticated Child HTTP endpoints repeat partial JWT checks and inconsistent error responses. The current Child actor helper validates only the credential, Active Enrollment, and Child Device, leaving Child Profile and Household lifecycle and relationship checks outside the common boundary.

This makes it too easy for future endpoints to omit an authorization or lifecycle check, return an unsafe exception, misclassify malformed input as authentication failure, or log privacy-sensitive request data. Guardian Device revocation is not reliably enforceable when the calling installation is not resolved, and Child Device revocation can race protected work when actor state is checked outside the operation's transaction.

## Solution

Harden the shared authenticated function boundaries so that post-bootstrap Guardian operations and authenticated Child Device HTTP operations receive complete, server-resolved actors before application work begins. Guardian wrappers will own Convex function registration, Clerk identity extraction, active Guardian Account, Household, and Guardian Device resolution, safe application-error mapping, request correlation, and privacy-safe logging. Child Profile APIs will migrate to these wrappers, while Guardian bootstrap will remain direct because it creates or restores the Guardian Device binding.

Introduce `childDeviceHttpAction` as the single authenticated Child HTTP boundary. It will verify the Child Device JWT, resolve and validate the current credential, Active Enrollment, Child Device, Child Profile, and Household chain, create server-owned request correlation, map stable HTTP errors, and emit typed allowlisted logs. Protected internal queries and mutations will receive a server-derived ChildDeviceActor separately from application input and revalidate that actor chain within their own transaction. Policy fetch and acknowledgement, Supervision Heartbeat, and child push-token registration will migrate to this wrapper. Enrollment Preview, enrollment completion, token challenge, and token exchange will remain separate credential-bootstrap boundaries.

Android Guardian clients will consistently attach the locally persisted Guardian installation identity to post-bootstrap calls, and Android Child clients will map stable backend codes into typed recovery behavior rather than parsing error messages.

## User Stories

1. As a Guardian, I want every post-bootstrap backend request to identify my active Guardian Device, so that a revoked installation cannot continue using Guardian APIs.
2. As a Guardian, I want Clerk to remain the authority proving my Guardian Account identity, so that a local installation identifier is never treated as a credential.
3. As a Guardian, I want unknown or foreign Guardian installation identifiers to fail generically, so that another account's device records cannot be discovered.
4. As a Guardian, I want a known revoked Guardian Device to receive an actionable safe error, so that Guardian Mode can explain that the installation no longer has access.
5. As a Guardian, I want disabled or deleting Guardian Account states to be enforced consistently, so that no feature can bypass account lifecycle rules.
6. As a Guardian, I want a deleting Household state to be enforced consistently, so that new supervision work cannot race Household deletion.
7. As a Guardian, I want missing local installation state to route through Guardian bootstrap, so that feature clients do not create conflicting installation identities.
8. As a Guardian, I want Guardian bootstrap to remain usable before a GuardianActor exists, so that a new installation can create or restore its server-side Guardian Device binding.
9. As a Guardian, I want Child Profile creation to use the common authenticated Guardian boundary, so that it receives the same device and lifecycle protections as other Guardian operations.
10. As a Guardian, I want Child Profile listing to use the common authenticated Guardian boundary, so that reads cannot drift from mutation authorization behavior.
11. As a Guardian, I want existing Enrollment Code operations to use the hardened Guardian contract, so that all post-bootstrap Guardian APIs enforce the same device identity.
12. As a Guardian, I want application validation failures to remain distinguishable from authentication and lifecycle failures, so that Guardian Mode can present the right recovery path.
13. As a Guardian, I want unexpected backend failures to return a generic safe error, so that internal database or implementation details are never exposed.
14. As a Guardian, I want a correlation identifier for an unexpected failure, so that a reported problem can be matched to privacy-safe backend diagnostics.
15. As a Child using a Child Device, I want authenticated HTTP endpoints to verify the JWT signature, issuer, audience, lifetime, and required claims, so that forged or malformed credentials are rejected.
16. As a Child using a Child Device, I want the backend to check current credential state on every authenticated request, so that revocation takes effect without waiting for JWT expiry.
17. As a Child using a Child Device, I want the backend to check the current Active Enrollment on every authenticated request, so that End Supervision or Replace Child Device immediately removes access.
18. As a Child using a Child Device, I want the backend to check current Child Device state on every authenticated request, so that an inactive device cannot continue reporting or fetching policy.
19. As a Child using a Child Device, I want the backend to check current Child Profile and Household lifecycle state, so that deletion cannot be bypassed through an otherwise valid JWT.
20. As a Child using a Child Device, I want Child Profile and Household identity to be derived from authoritative records, so that request or JWT claims cannot redirect access to another supervision relationship.
21. As a Child using a Child Device, I want all authorization-chain inconsistencies to return one generic unauthorized error, so that internal record state is not exposed.
22. As a Child using a Child Device, I want protected mutations to revalidate authorization in their transaction, so that revocation cannot race the protected write.
23. As a Child using a Child Device, I want policy fetch to use the common authenticated HTTP boundary, so that policy is delivered only to the currently authorized Active Enrollment.
24. As a Child using a Child Device, I want policy acknowledgement to use the common authenticated HTTP boundary, so that applied state cannot be written by an inactive device.
25. As a Child using a Child Device, I want a stale policy acknowledgement to return a stable version-mismatch error, so that Child Mode can fetch and apply the latest Supervision Policy.
26. As a Child using a Child Device, I want Supervision Heartbeats to use the common authenticated HTTP boundary, so that health cannot be reported for another or inactive enrollment.
27. As a Child using a Child Device, I want push-token registration to use the common authenticated HTTP boundary, so that FCM delivery state remains bound to the authorized Child Device.
28. As a Child using a Child Device, I want malformed request bodies to return validation failure rather than unauthorized, so that Child Mode can distinguish a client defect from an expired credential.
29. As a Child using a Child Device, I want all authenticated HTTP responses to carry a server-generated request ID, so that failures can be correlated safely.
30. As a Child using a Child Device, I want an unauthorized response to trigger at most one credential refresh and request retry, so that Child Mode can recover without entering an infinite retry loop.
31. As a Child using a Child Device, I want internal failures to use bounded retry with backoff, so that temporary server failures do not cause aggressive network use.
32. As a Child using a Child Device, I want normal network failure to remain distinct from backend application errors, so that offline behavior remains reliable.
33. As a Cereveil operator, I want request logs to use a strict allowlist, so that request bodies, tokens, claims, identifiers, Child identity, policy content, and capability payloads cannot enter routine observability.
34. As a Cereveil operator, I want every authenticated failure and successful state change logged with a correlation ID and safe outcome, so that operational failures remain diagnosable without sensitive content.
35. As a Cereveil operator, I want successful reads excluded from per-request persistent logs, so that observability does not create an unnecessary access trail.
36. As a developer, I want Guardian wrappers to own function registration and installation validation, so that an endpoint cannot adopt the wrapper while forgetting Guardian Device enforcement.
37. As a developer, I want Child endpoint callbacks to return application data rather than raw HTTP responses, so that response headers and safe error mapping remain consistent.
38. As a developer, I want ChildDeviceActor and endpoint input represented separately in internal calls, so that server-derived authorization context cannot collide with untrusted input.
39. As a developer, I want shared runtime validators for actor shape, so that internal functions receive consistent typed arguments.
40. As a developer, I want authorization helpers to remain distinct from shape validators, so that structurally valid actor IDs are never mistaken for authorized records.
41. As a developer, I want wrapper behavior tested at the registered public boundary, so that tests prove externally visible authorization and error contracts without coupling to helper implementation.
42. As a developer, I want credential-bootstrap endpoints to remain separate from authenticated Child endpoints, so that pre-authentication operations do not manufacture a ChildDeviceActor prematurely.

## Implementation Decisions

- `guardianQuery` and `guardianMutation` will own complete Convex query and mutation registration rather than wrapping only a handler callback.
- Each Guardian wrapper definition will declare an operation name, endpoint-specific validators, and an application handler. The wrapper will inject and consume the required Guardian installation argument before invoking that handler.
- Every post-bootstrap Guardian request will include the locally persisted `guardianInstallationId`. The value identifies an installation but does not authenticate it.
- Clerk identity will be extracted server-side, and its stable token identifier will resolve the Guardian Account. Client-supplied Guardian Account, Household, or Guardian Device database IDs will not be accepted for actor resolution.
- GuardianActor will always contain a server-resolved Guardian Account ID, Household ID, and Guardian Device ID. Guardian Device ID will no longer be optional.
- Guardian Device resolution will match the supplied installation identifier only within the Clerk-authenticated Guardian Account and require the device to be active.
- Missing Clerk identity, missing domain state, unknown installation identity, foreign installation identity, and other unproven bindings will map to `UNAUTHENTICATED`.
- Proven lifecycle states will retain actionable safe codes: `DEVICE_REVOKED`, `ACCOUNT_DISABLED`, `ACCOUNT_DELETING`, and `HOUSEHOLD_DELETING`.
- Guardian bootstrap will remain a directly registered Clerk-authenticated mutation because it creates or restores the Guardian Device record required by GuardianActor.
- Child Profile use cases will receive GuardianActor rather than raw Clerk identity and will stop duplicating Guardian Account and Household resolution.
- Resource authorization remains an application/use-case responsibility after actor resolution. Guardian operations will scope work to the actor's Household and continue using focused authorization helpers for targeted resources.
- Existing post-bootstrap Guardian Enrollment Code functions already using Guardian wrappers will adopt the installation argument and mandatory Guardian Device actor contract.
- Guardian Android feature clients will use one shared installation-ID provider. Missing state maps locally to `BootstrapRequired`; only Guardian bootstrap may create and persist an installation identity, after which the original operation may be retried once.
- `childDeviceHttpAction` will be a higher-order function returning a registered authenticated HTTP action. It will not be an internal Convex action.
- Authenticated Child endpoint callbacks will return application data or typed application errors. Only the wrapper will create `Response` objects, serialize JSON, assign status codes, and set common headers.
- The wrapper will extract the Bearer token and strictly verify signature, supported algorithm and token type, issuer, audience, expiration, required temporal claims, and required identity claim types.
- JWT claims will provide only credential, Active Enrollment, and Child Device identity candidates. They will not be treated as proof that current records remain active.
- Because HTTP actions do not have direct database access, the wrapper will call a private actor-resolution query that safely normalizes claimed IDs and validates the complete current authorization chain.
- ChildDeviceActor will contain credential, Active Enrollment, Child Device, Child Profile, and Household IDs. Child Profile and Household identity will be derived from authoritative database relationships rather than accepted from the HTTP request or JWT.
- Complete Child authorization requires active and mutually consistent Child Device Credential, Active Enrollment, Child Device, Child Profile, and Household records.
- Any missing, inactive, revoked, ended, deleting, malformed, or inconsistent authorization-chain state will collapse to `CHILD_DEVICE_UNAUTHORIZED` without identifying the failed record.
- Internal authenticated Child functions will accept a nested actor object separately from a nested application input object. A shared actor validator will enforce the runtime shape and generated types.
- Shape validation is not authorization. Each protected internal query or mutation will reload and validate the complete Child authorization chain within its own transaction before reading or writing protected data.
- This transactional revalidation is intentionally retained even after boundary actor resolution because separate Convex function calls have separate transaction boundaries and revocation may occur between them.
- Policy fetch, policy acknowledgement, Supervision Heartbeat, and child push-token registration will migrate to `childDeviceHttpAction` and the nested actor/input internal contract.
- Enrollment Preview, enrollment completion, token challenge, and token exchange will remain direct Device Identity HTTP actions because they occur before authentication or establish/refresh the Child Device Credential.
- Safe application errors will add `INTERNAL_ERROR` with a generic request-failure message and `POLICY_VERSION_MISMATCH` for acknowledgement of a version other than the currently desired policy.
- Child HTTP error mapping will use `400 VALIDATION_FAILED`, `401 CHILD_DEVICE_UNAUTHORIZED`, `409 POLICY_VERSION_MISMATCH`, and `500 INTERNAL_ERROR`.
- A valid Child Device encountering missing expected backend application state will receive `INTERNAL_ERROR`, not an authentication error. Authorization-chain failure remains `CHILD_DEVICE_UNAUTHORIZED`.
- Unknown exceptions will be caught at authenticated boundaries and converted into `INTERNAL_ERROR`. Stack traces, exception messages, database failures, crypto/configuration details, and other raw implementation information will not be returned to clients.
- Android Child clients will branch on stable error codes rather than message text. Unauthorized responses trigger at most one token challenge/exchange and one retry; policy mismatch triggers latest-policy fetch/apply/acknowledge; internal errors use bounded backoff; network failures remain offline failures.
- Every wrapper invocation will create server-owned request metadata containing a random request ID, operation name, and start time. Application handlers receive the resolved actor but not the request metadata.
- Inbound request IDs will be ignored. Child HTTP responses will return the server-generated value in `X-Request-Id`. Guardian generic internal-error data may include the request ID for correlation.
- Logging will be available only through typed allowlisted functions. The general logging API will accept request ID, operation, actor kind, outcome, safe error code, and duration, not arbitrary records or free-form request metadata.
- Logs will exclude request and response bodies, Clerk claims, JWTs, authorization headers, installation IDs, database IDs, Child names, push tokens or hashes, policy bodies, capability payloads, and raw exception objects.
- Persistent boundary logs will cover every authenticated failure and successful state-changing operation. Successful reads such as Child Profile listing and policy fetch will not create per-request access logs; aggregate metrics may be added later if needed.
- Existing domain-oriented lifecycle fields and tables are sufficient for this work; no new domain identity table is planned.
- The implementation will conform to ADR-0031, ADR-0034, ADR-0044, ADR-0046, ADR-0050, ADR-0051, ADR-0065, ADR-0066, ADR-0067, ADR-0068, and ADR-0069.

## Testing Decisions

- The principal test seam is the registered wrapper contract: invoke public Guardian queries/mutations with Convex identity and arguments, and invoke authenticated Child HTTP routes with real request headers and bodies.
- Tests will assert externally visible results, safe error codes, HTTP statuses, response headers, and resulting database state. They will not assert the order or private composition of helper calls.
- Guardian wrapper tests will cover missing Clerk identity, missing Guardian installation argument, unknown installation, installation belonging to another Guardian Account, revoked installation, disabled and deleting Guardian Accounts, missing and deleting Households, successful complete GuardianActor resolution, and generic mapping of unexpected failures.
- Guardian tests will prove that `guardianDeviceId` is always present for a successful post-bootstrap wrapped operation.
- Child wrapper tests will cover missing Bearer tokens, malformed tokens, invalid signatures, wrong issuer or audience, expired tokens, malformed identity claims, and valid JWTs.
- Child wrapper tests will cover missing, inactive, or inconsistent credential, Active Enrollment, Child Device, Child Profile, and Household records and require all such cases to return the same unauthorized contract.
- Child wrapper tests will cover successful derivation of Child Profile and Household IDs rather than trusting request-supplied ownership data.
- Transactional internal-operation tests will prove that a previously resolved actor is rejected when its authorization chain is revoked or changed before the protected operation.
- Safe-error tests will prove that malformed endpoint input maps to validation failure, stale policy acknowledgement maps to policy-version mismatch, and unexpected exceptions map to internal error without exposing their messages.
- HTTP response tests will require a server-generated `X-Request-Id` on both success and failure and will prove that inbound request IDs are not reused.
- Logging tests will capture structured entries through the logging seam and assert the allowlisted shape. Known tokens, installation IDs, database IDs, names, policy content, capability values, request bodies, and raw exception messages must not appear in serialized log output.
- Logging tests will prove that failures and successful state changes are logged while successful reads are not persisted as per-request logs.
- Android Guardian tests will cover shared installation-ID injection, local `BootstrapRequired`, bootstrap ownership of ID generation, and the bounded retry after bootstrap.
- Android Child client tests will cover typed mapping for validation, unauthorized, policy mismatch, internal error, and network failure, including the one-refresh/one-retry bound.
- Existing enrollment and Guardian/Child client tests provide prior art for Convex identity setup, HTTP route execution, fake clients, local state repositories, and coordinator recovery behavior.
- This PRD does not require a new endpoint-level cross-Household or cross-enrollment authorization test matrix. Existing endpoint tests remain, while new coverage concentrates on the wrapper contract as explicitly agreed.

## Out of Scope

- Changing Guardian bootstrap to use `guardianMutation`.
- Migrating Enrollment Preview, enrollment completion, token challenge, or token exchange to `childDeviceHttpAction`.
- Replacing Clerk as Guardian Account authentication.
- Changing Child Device JWT signing algorithms, the fifteen-minute lifetime, or the Keystore challenge/exchange design beyond stricter verification of the existing contract.
- Adding reusable Child refresh tokens.
- Adding a new cross-Household or cross-enrollment endpoint authorization test matrix beyond existing coverage.
- Logging successful reads individually or building a general audit-history product.
- Logging sensitive Child content, raw location, policy bodies, capability payloads, push tokens, or authentication material.
- Adding new domain identity tables or changing the one-Guardian-Account/one-Household model.
- Redesigning Enrollment Code lifecycle, Active Enrollment creation, Policy Application State, Supervision Health, or FCM delivery semantics.
- Implementing the wrappers or migrations as part of this PRD-writing task.

## Further Notes

- A normal authenticated Child mutation remains one client-visible HTTPS round trip. Internally, the HTTP action performs an actor-resolution query followed by the protected query or mutation, creating two Convex transaction boundaries. The protected operation's second authorization check is deliberate revocation-race protection.
- If a Child Device request first discovers an expired JWT, recovery may require the failed request, token challenge, token exchange, and one retry. Child Mode should refresh before known expiry where practical to avoid the initial failure.
- ADR-0068 records the requirement for an active Guardian Device on every post-bootstrap Guardian request. ADR-0069 records full Child authorization-chain validation and transactional revalidation.
- Request context is intentionally wrapper-local. If a future feature needs richer operational events, it should add a narrow typed logger rather than passing a general-purpose request context through application handlers.
