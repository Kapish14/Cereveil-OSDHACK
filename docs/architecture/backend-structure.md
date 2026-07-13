# Backend Structure

This document captures the intended Convex backend file shape for Cereveil. ADR-0046 records the decision to structure the backend around function wrappers and feature modules; this document records the concrete layout we intend to implement.

## Directory shape

```text
convex/
  schema.ts
  auth.config.ts
  http.ts

  lib/
    functionWrappers.ts
    requestContext.ts
    actors.ts
    authorize.ts
    errors.ts
    logging.ts
    validators.ts
    time.ts

  modules/
    guardianAuth/
      public.ts
      useCases.ts
      data.ts
      validators.ts

    policies/
      public.ts
      useCases.ts
      data.ts
      validators.ts

    deviceIdentity/
      http.ts
      jwt.ts
      useCases.ts
      data.ts

    notifications/
      internal.ts
      fcmActions.ts
      data.ts

    safety/
    accessRequests/
    screenTime/
    remoteAudio/
```

## Top-level Convex files

### `schema.ts`

Defines Convex tables, indexes, and database validators.

### `auth.config.ts`

Configures authentication providers accepted by Convex:

- Clerk for Guardian Accounts.
- Cereveil custom JWT provider for Child Devices.

This file verifies token providers; it does not replace application authorization.

### `http.ts`

Defines raw HTTP routes. Most app interactions should use Convex queries/mutations/actions, but HTTP routes are appropriate for Device Identity endpoints such as:

- `POST /device/enroll`
- `POST /device/token`
- public JWKS exposure if required by the custom JWT provider setup

## Shared backend infrastructure

### `lib/functionWrappers.ts`

Defines the wrapper functions that act like middleware around Convex functions:

```ts
guardianQuery(...)
guardianMutation(...)
childDeviceQuery(...)
childDeviceMutation(...)
publicHttpAction(...)
internalMutation(...)
internalAction(...)
```

Responsibilities:

- create request context;
- resolve the actor;
- enforce authentication;
- map safe errors;
- emit privacy-safe logs;
- pass the typed app context into the feature handler.

The Guardian auth bootstrap slice should introduce only the shared primitives it needs, such as Clerk identity extraction and safe application errors. The full `guardianMutation` wrapper is introduced with the first post-bootstrap Guardian API that requires an existing active Guardian Account.

### `lib/requestContext.ts`

Builds Cereveil's request context from Convex `ctx`, function metadata, and the resolved actor.

Example shape:

```ts
type RequestContext = {
  requestId: string;
  functionName: string;
  startedAt: number;
  actor: GuardianActor | ChildDeviceActor | SystemActor;
};
```

### `lib/actors.ts`

Resolves authenticated callers into domain actors:

```text
Clerk identity
  → GuardianActor

Child Device JWT
  → ChildDeviceActor
```

Actor resolution loads current backend state, such as Guardian Account, Household, Child Device Credential, and Active Enrollment.

### `lib/authorize.ts`

Contains reusable authorization helpers:

```ts
requireGuardianForHousehold(...)
requireGuardianForChildProfile(...)
requireActiveEnrollmentForChild(...)
requireChildDeviceForEnrollment(...)
```

This prevents ownership and lifecycle checks from being reimplemented inconsistently across feature modules.

### `lib/errors.ts`

Defines application error codes and safe error mapping.

Example codes:

```text
UNAUTHENTICATED
FORBIDDEN
NOT_FOUND
VALIDATION_FAILED
CONFLICT
REVOKED
RATE_LIMITED
INTERNAL
```

### `lib/logging.ts`

Privacy-safe structured logging.

Allowed:

- request ID;
- function name;
- actor kind;
- result code;
- duration;
- policy version;
- non-sensitive IDs.

Forbidden:

- raw scam text;
- screenshots;
- OCR output;
- audio;
- accessibility node contents;
- exact location history;
- full notification contents.

### `lib/validators.ts`

Shared validators used across modules.

Feature-specific validators stay inside the relevant module.

### `lib/time.ts`

Centralizes time helpers:

```ts
now()
addMinutes(...)
addDays(...)
startOfLocalDay(...)
startOfWeek(...)
```

This avoids scattering retention and expiry calculations across feature modules.

## Feature module convention

Most feature modules should follow this shape:

```text
moduleName/
  public.ts
  useCases.ts
  data.ts
  validators.ts
  internal.ts      // only when needed
  actions.ts       // only when external side effects are needed
```

### `public.ts`

Exports Convex functions called by Android clients.

Examples:

```ts
export const updatePolicy = guardianMutation(...);
export const getChildPolicy = childDeviceQuery(...);
```

This is the Convex equivalent of a controller.

### `useCases.ts`

Contains business workflow logic.

Examples:

```ts
updateSupervisionPolicy(...)
applyPolicyAcknowledgement(...)
resolveAccessRequest(...)
```

### `data.ts`

Focused data-access helpers that hide meaningful query complexity.

Examples:

```ts
loadLatestPolicyForChild(...)
insertPolicyVersion(...)
loadActiveEnrollmentForChild(...)
```

Avoid generic pass-through repositories that only wrap `ctx.db` without adding domain value.

### `validators.ts`

Feature-specific argument and embedded-object validators.

## Module notes

### `modules/guardianAuth`

Owns the Guardian auth bootstrap flow after Clerk authentication.

Initial public function:

```ts
export const bootstrapGuardian = mutation(...);
```

Client arguments:

```ts
{
  guardianInstallationId: string,
  deviceLabel?: string,
  appBuild: string,
  timezone?: string,
}
```

The function derives Clerk identity server-side from `ctx.auth.getUserIdentity()`. It must not accept Clerk user ID, email, Guardian Account ID, Household ID, or Guardian Device ID from the client for authorization or ownership.
The function also derives backend environment server-side; the initial implementation uses `dev` only.
Because this function creates the Guardian Account, it is implemented as a direct Convex `mutation` using shared auth and error helpers rather than the later `guardianMutation` wrapper, which requires an existing Guardian actor.
`guardianInstallationId` is an opaque random installation key. `deviceLabel` is optional display metadata and may contain a brand/model-style label such as `Pixel 8`; it must not be used for identity or authorization.
`timezone` is an optional app-provided IANA timezone string used when creating the Household. Bootstrap defaults it to `Asia/Kolkata` when missing and sets `country` to `IN` server-side.

The mutation returns a small internal `GuardianBootstrapState` for app routing and follow-up Convex calls:

```ts
{
  guardianAccountId: Id<"guardianAccounts">,
  householdId: Id<"households">,
  guardianDeviceId: Id<"guardianDevices">,
  guardianDeviceStatus: "active" | "revoked",
  hasChildProfiles: boolean,
  serverNow: number,
}
```

Do not return raw Clerk identity, all Child data, FCM tokens, Supervision Policy state, or home-screen display data from bootstrap. The Guardian app should call focused queries after bootstrap for user-facing screens.
`hasChildProfiles` is `true` only when the Household has at least one active Child Profile; profiles in `deleting` do not count for bootstrap routing.

### `modules/policies`

Owns Supervision Policy versions and policy application acknowledgement.

### `modules/deviceIdentity`

Owns Enrollment Code exchange, Child Device Credential creation/rotation/revocation, and Child Device JWT issuance.

Special files:

- `http.ts`: raw HTTP endpoint handlers for enrollment/token refresh.
- `jwt.ts`: custom JWT signing, key IDs, and public key/JWKS support.

### `modules/notifications`

Owns Guardian Notices, Child Device wake-up commands, FCM delivery, and acknowledgement/delivery state.

Special files:

- `internal.ts`: internal functions scheduled/called by other modules.
- `fcmActions.ts`: Convex actions that call Firebase Cloud Messaging.
- `data.ts`: token lookup, delivery attempts, and notice persistence helpers.

### `modules/safety`

Owns Safety Alerts and weekly Safety Incident Summaries.

### `modules/accessRequests`

Owns Access Requests and Access Grants.

### `modules/appCatalog`

Owns latest-only installed user-launchable app reconciliation for Guardian app selection.

### `modules/location`

Owns latest Location State, Location Heartbeats, one-time Location Refresh Requests, and their cleanup.

### `modules/screenTime`

Owns Screen Time Refresh Requests, bounded snapshot staging, atomic latest-only publication, local-midnight expiry, and policy-controlled deletion.

### `modules/remoteAudio`

Owns Remote Audio session lifecycle, Child Device commands, WebRTC signaling rows, cooldown, and cleanup.

## Request lifecycle

Normal Convex app request:

```text
Android app
  ↓
Convex query/mutation/action
  ↓
Convex argument validator
  ↓
Cereveil function wrapper
  ↓
request context creation
  ↓
actor resolution
  ↓
authentication check
  ↓
authorization check
  ↓
feature use-case
  ↓
focused DB helpers
  ↓
scheduled side effects if needed
  ↓
safe logging
  ↓
sanitized response/error
```

Device Identity HTTP request:

```text
Child Device
  ↓
Convex HTTP action
  ↓
parse and validate request
  ↓
verify Enrollment Code or device-held key proof
  ↓
load credential/enrollment state
  ↓
issue or reject Child Device JWT
  ↓
safe logging
  ↓
sanitized HTTP response
```

## Side-effect rule

Business mutations write authoritative state first. External side effects happen through scheduled/internal actions.

Example:

```text
policy update mutation
  ↓
insert policy version
  ↓
create device command
  ↓
schedule notification action
  ↓
FCM action sends wake-up
```

Do not make Firebase/FCM calls directly from every business mutation.
