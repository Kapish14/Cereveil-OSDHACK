# Backend Data Model

This document is the working Convex schema design for Cereveil. ADRs capture why the architecture exists; this document captures the current table shapes we intend to implement.

## Modeling rules

- Use `householdId` as the ownership and authorization grouping key for family supervision data.
- Use `childProfileId` for data about a Child, independent of which phone is currently enrolled.
- Use `childDeviceId` for data produced by or delivered to a specific Child Device installation.
- Use `activeEnrollmentId` for data tied to a specific supervision pairing.
- Use `guardianAccountId` only when a Guardian identity owns or initiated a specific action.
- Keep FCM tokens separate from identity. FCM is a delivery endpoint, not proof of identity.
- Keep Child Device Credentials separate from Active Enrollment. Credentials authenticate a device; enrollment represents the active supervision pairing.
- Do not store raw scam text, screenshots, OCR output, audio, transcripts, full notification contents, or location history.

## Lifecycle fields

Common lifecycle fields:

```ts
createdAt: number
updatedAt?: number
expiresAt?: number
revokedAt?: number
endedAt?: number
status?: string
```

Meanings:

- `createdAt`: when the row began.
- `updatedAt`: when mutable state last changed.
- `expiresAt`: when the row should stop being valid or should be deleted by scheduled cleanup.
- `revokedAt`: when access was intentionally revoked.
- `endedAt`: when a session or supervision flow completed.
- `status`: current domain lifecycle state.

Rows such as Safety Alerts, Screen Time Snapshots, refresh requests, Access Requests, Access Grants, commands, and signals should generally have `expiresAt`. Long-lived identity and ownership rows generally use `status`, `revokedAt`, or `endedAt` instead.

## Tables

### `guardianAccounts`

Maps the Convex-authenticated Clerk identity to Cereveil's Guardian Account record.

```ts
{
  clerkTokenIdentifier: string,
  clerkUserId?: string,
  primaryEmail?: string,
  status: "active" | "disabled" | "deleting",
  createdAt: number,
  updatedAt: number,
}
```

Indexes:

```ts
by_clerk_token_identifier
by_status
```

Rules:

- Use Convex `identity.tokenIdentifier` as the stable auth-linked identity key.
- Store Clerk user ID only as an optional provider-specific reference.
- Do not use email as the stable identity.
- Treat `primaryEmail` as non-authoritative profile metadata; bootstrap may update it when Clerk provides a value, but must not use it for lookup, ownership, merging, or authorization.
- If Clerk provides no email during bootstrap, keep the existing `primaryEmail` rather than clearing it.
- Guardian auth bootstrap creates the Guardian Account, its Household, and the current Guardian Device only.
- If an existing Guardian Account is `disabled` or `deleting`, bootstrap must fail with a safe typed error and must not create or update Household or Guardian Device records.
- Do not create Child Profiles, default policies, Enrollment Codes, FCM tokens, or supervision data during auth bootstrap.

### `households`

The family supervision workspace owned by a Guardian Account.

```ts
{
  guardianAccountId: Id<"guardianAccounts">,
  status: "active" | "deleting",
  timezone: string,
  country: "IN",
  createdAt: number,
  updatedAt: number,
}
```

Indexes:

```ts
by_guardian_account_id
by_guardian_account_id_and_status
by_status
```

Rules:

- Do not embed Child Profiles, policies, notices, or summaries inside the Household row.
- Other tables reference `householdId`.
- A new Household is created during Guardian auth bootstrap only when the Guardian Account has no existing active Household.
- Bootstrap accepts an optional app-provided IANA timezone when creating a Household, defaulting to `Asia/Kolkata` when missing.
- Bootstrap sets `country` to `IN` server-side for the initial implementation.
- Do not infer Household timezone or country from GPS/location.
- If an active Guardian Account has no Household, bootstrap may create one to repair incomplete first-run state.
- If the Guardian Account's Household is `deleting`, bootstrap must fail with a safe typed error and must not create a replacement Household.

### `guardianDevices`

Tracks individual Guardian phones. Clerk handles login/session; this table supports device-level push, display, revocation, and last-seen state.

```ts
{
  guardianAccountId: Id<"guardianAccounts">,
  householdId: Id<"households">,
  guardianInstallationId: string,

  deviceLabel?: string,
  platform: "android",
  appBuild: string,
  environment: "dev" | "prod",

  status: "active" | "revoked",
  lastSeenAt: number,

  createdAt: number,
  updatedAt: number,
  revokedAt?: number,
}
```

Indexes:

```ts
by_guardian_account_id
by_guardian_account_id_and_guardian_installation_id
by_guardian_account_id_and_status
by_household_id
by_status
```

Rules:

- Use an app-generated, locally persisted `guardianInstallationId` to recognize the same Guardian app installation across repeated Clerk sign-ins and FCM token rotation.
- Treat `guardianInstallationId` as a device record key, not an authentication credential.
- Validate `guardianInstallationId` as a bounded opaque string, not as a hardware identifier.
- Do not use IMEI, Android ID, advertising ID, serial number, phone number, or other hardware/device identifiers for Guardian Device identity.
- `deviceLabel` is optional user-facing display metadata; the app may suggest it from device brand/model, and the Guardian may rename it later.
- Derive `environment` server-side from the Convex backend configuration; the initial implementation records `dev` only.
- Guardian auth bootstrap allows an existing active Guardian Device for the installation, but creating a new active Guardian Device must fail with `DEVICE_LIMIT_REACHED` when the Guardian Account already has two active Guardian Devices.
- If the same Guardian installation has a revoked Guardian Device row, bootstrap must fail with `DEVICE_REVOKED` and must not reactivate it automatically.
- Every successful bootstrap updates the active Guardian Device's `lastSeenAt` and `updatedAt` to backend `serverNow`.
- Do not auto-revoke an older Guardian Device during bootstrap.
- Store FCM tokens in `fcmTokens`, not directly on this row.

### `childProfiles`

Minimal backend identity for a Child.

```ts
{
  householdId: Id<"households">,

  displayName: string,
  birthMonth: number,
  birthYear: number,
  avatarKey?: string,

  status: "active" | "deleting",

  createdAt: number,
  updatedAt: number,
}
```

Indexes:

```ts
by_household_id
by_household_id_and_status
by_status
```

Rules:

- No legal full name required.
- No exact birth date.
- No email, phone, or login credentials.
- Prefer built-in or local avatar keys initially rather than uploaded child photos.

### `enrollmentCodes`

Stores the short-lived Guardian-created bootstrap authorization for one Unenrolled Child Profile.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  codeHash: string,
  status: "active" | "revoked" | "consumed",
  expiresAt: number,
  createdByGuardianAccountId: Id<"guardianAccounts">,
  createdByGuardianDeviceId?: Id<"guardianDevices">,
  consumedByActiveEnrollmentId?: Id<"activeEnrollments">,
  createdAt: number,
  revokedAt?: number,
  consumedAt?: number,
}
```

Rules:

- Store only the SHA-256 hash; the raw 128-bit code exists only in the creation response and QR payload.
- Codes expire after five minutes and successful completion consumes the code atomically with enrollment.
- Creating a new code revokes other active codes for the Child Profile.

### `childDevices`

Represents a Child Mode app installation / device record.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  installationId: string,

  platform: "android",
  appBuild: string,
  environment: "dev" | "prod",

  deviceLabel?: string,
  status: "active" | "revoked",

  lastSeenAt?: number,

  createdAt: number,
  updatedAt: number,
  revokedAt?: number,
}
```

Indexes:

```ts
by_household_id
by_child_profile_id
by_status
```

Rules:

- Child Device records are created when enrollment succeeds.
- Prepared Child Device is a local Child Mode state, not a backend `childDevices` row.

### `activeEnrollments`

Represents the current supervision pairing between a Child Profile and Child Device.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  childDeviceId: Id<"childDevices">,
  enrollmentCodeId: Id<"enrollmentCodes">,

  status: "active" | "revoked" | "ended",
  roleLockActive: boolean,

  enrolledAt: number,
  createdAt: number,
  updatedAt: number,
  revokedAt?: number,
  endedAt?: number,
}
```

Indexes:

```ts
by_child_profile_id
by_child_device_id
by_status
```

Rules:

- One Child Profile can have at most one active enrollment.
- End Supervision revokes/ends enrollment and deletes child-specific backend data.
- Replace Child Device revokes the old enrollment but keeps the Child Profile.

### `childDeviceCredentials`

Revocable authorization material for an enrolled Child Device.

```ts
{
  childDeviceId: Id<"childDevices">,
  activeEnrollmentId: Id<"activeEnrollments">,

  publicKeySpki: string,
  algorithm: "ES256",

  status: "active" | "revoked",

  createdAt: number,
  updatedAt: number,
  revokedAt?: number,
}
```

Indexes:

```ts
by_child_device_id
by_active_enrollment_id_and_status
```

Rules:

- Do not store private keys.
- Do not store raw refresh tokens.
- Child Device private key material belongs in Android Keystore.
- Backend checks both credential state and Active Enrollment state before issuing or accepting Child Device access.

### `supervisionPolicies`

Immutable versioned policy state for a Child Profile.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,

  version: number,
  schemaVersion: 2,
  status: "active" | "superseded",

  appBlocking: {
    enabled: boolean,
    apps: Array<{
      packageName: string,
      manualBlocked: boolean,
      schedules: Array<{
        scheduleId: string,
        daysOfWeek: Array<number>,
        startLocalMinute: number,
        endLocalMinute: number,
      }>,
    }>,
  },
  safeBrowsing: SafeBrowsingPolicy,
  activeScreenSafety: ActiveScreenSafetyPolicy,
  locationSharing: { enabled: boolean },
  screenTime: { enabled: boolean },

  createdByGuardianAccountId: Id<"guardianAccounts">,
  createdAt: number,
}
```

Indexes:

```ts
by_child_profile_version
by_household_id
by_status
```

Rules:

- Guardian edits create a new policy version.
- Old versions are not overwritten.
- `appBlocking.apps` contains at most 100 package entries and at most eight schedules per package; Convex validates duplicates, local-minute ranges, day sets, and Exempt Apps.
- Manual and Scheduled Blocks remain independent and overlapping schedules compose into continuous effective coverage.
- Creating the first Manual or Scheduled Block while App Blocking is disabled enables the section in the same complete policy version. Disabling the section later preserves its rules, and removing the last rule does not implicitly disable it.
- If Location Sharing is disabled, backend deletes latest location and pending refresh state and Child Mode stops collecting/uploading location.
- If Screen Time is disabled, backend deletes the latest snapshot and pending refresh state and Child Mode stops accepting refresh work.

### `policyApplicationStates`

Tracks what policy the Guardian wants versus what Child Mode has acknowledged.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  activeEnrollmentId: Id<"activeEnrollments">,
  childDeviceId: Id<"childDevices">,

  desiredPolicyVersion: number,
  appliedPolicyVersion?: number,
  status: "pending" | "applied" | "failed",
  failureReason?: "unsupported_schema" | "invalid_policy" | "activation_failed",
  createdAt: number,
  updatedAt: number,
}
```

Indexes:

```ts
by_child_profile_id
by_active_enrollment_id
```

Rules:

- If `desiredPolicyVersion > appliedPolicyVersion`, Guardian Mode can show pending application state.

### `appCatalogEntries`

Latest-only user-launchable app presence reported by the active Child Device.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  childDeviceId: Id<"childDevices">,
  activeEnrollmentId: Id<"activeEnrollments">,
  packageName: string,
  displayLabel: string,
  catalogGeneration: string,
  syncedAt: number,
  createdAt: number,
  updatedAt: number,
}
```

Indexes:

```ts
by_active_enrollment_id_and_package_name
by_active_enrollment_id_and_catalog_generation
by_child_profile_id
```

Rules:

- Accept bounded generation batches, then atomically publish the completed generation and delete rows absent from it in bounded cleanup.
- Upload immediately after enrollment, reconcile after package add/remove events and restart, and show `syncedAt` freshness in Guardian Mode.
- Include only package name and device-resolved label for user-launchable apps; exclude icons, permissions, APK metadata, installation timestamps, system components, and Exempt Apps.
- Removing a catalog entry never removes package-based App Block rules.

### `guardianNotices`

Authoritative Guardian-facing notices. FCM only wakes/delivers; this table is the source of truth.

```ts
{
  householdId: Id<"households">,
  childProfileId?: Id<"childProfiles">,

  type:
    | "safety_alert"
    | "access_request"
    | "tamper_alert"
    | "offline"
    | "recovery"
    | "weekly_safety_summary",

  sourceId?: string,

  severity: "info" | "warning" | "urgent",
  status: "unread" | "read" | "archived",

  createdAt: number,
  expiresAt?: number,
}
```

Rules:

- Payloads remain minimal and type-specific.
- Do not store raw content in notices.

### `childDeviceCommands`

Authoritative command queue for Child Devices. FCM may wake the device, but command authority lives here.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  childDeviceId: Id<"childDevices">,
  activeEnrollmentId: Id<"activeEnrollments">,

  command:
    | { type: "apply_policy_version", policyVersion: number }
    | { type: "refresh_location", locationRefreshRequestId: Id<"locationRefreshRequests"> }
    | { type: "refresh_screen_time", screenTimeRefreshRequestId: Id<"screenTimeRefreshRequests"> }
    | { type: "reconcile_access_grants", accessRequestId: Id<"accessRequests"> },

  intentKey: string,
  status: "pending" | "acknowledged" | "rejected" | "superseded" | "cancelled" | "expired",
  createdAt: number,
  updatedAt: number,
  expiresAt: number,
  acknowledgedAt?: number,
  rejectionReason?: string,
}
```

Rules:

- Do not put command authority inside FCM payloads.
- Devices fetch authoritative command state from Convex.
- Each variant references feature-owned authoritative state and acknowledges only its type-specific success point.
- Policy and Screen Time refresh commands use normal-priority FCM; Location Refresh uses high priority with a Child-visible notification; Access Grant approval is high priority when it changes a visible Block Screen.

### `accessRequests`

Child-initiated temporary access request for a blocked app.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  childDeviceId: Id<"childDevices">,
  activeEnrollmentId: Id<"activeEnrollments">,

  appPackageName: string,
  policyVersion: number,
  blockKind: "manual" | "scheduled",
  scheduledCoverageEndsAt?: number,

  status: "pending" | "approved" | "denied" | "expired",

  requestedAt: number,
  resolvedAt?: number,
  resolvedByGuardianAccountId?: Id<"guardianAccounts">,

  expiresAt: number,
}
```

Indexes:

```ts
by_child_profile_status
by_household_id
by_expires_at
```

Rules:

- If Child Mode is offline, do not queue Access Requests; show the child that Guardian is unreachable.
- Allow one pending request per enrollment, package, and effective block; repeated taps return the existing request without another Guardian Notice.
- Expire after 15 minutes or earlier when the effective block, policy eligibility, or Active Enrollment ends.
- After denial, enforce a five-minute backend-owned cooldown before another request for that app.
- An unrelated policy version does not invalidate the request when the same effective block remains.
- Delete terminal rows 24 hours after resolution or expiry.

### `accessGrants`

Approved temporary exception to an App Block.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  childDeviceId: Id<"childDevices">,
  activeEnrollmentId: Id<"activeEnrollments">,

  accessRequestId: Id<"accessRequests">,

  appPackageName: string,
  startsAt: number,
  expiresAt: number,

  createdByGuardianAccountId: Id<"guardianAccounts">,
  createdAt: number,
}
```

Indexes:

```ts
by_child_profile_id
by_active_enrollment_id
by_expires_at
```

Rules:

- If an Access Request is denied, no Access Grant is created.
- A grant starts at backend approval; delayed FCM, retrieval, process death, and reboot never restart its duration.
- Manual-block grants last for the selected 15, 30, 45, or 60 minutes. Schedule-only grants cannot exceed the current continuous scheduled coverage.
- Active grants are non-revocable in v1 and are persisted by Child Mode for offline enforcement until absolute expiry.
- Delete expired grant rows 24 hours after they cease being valid.

### `safetyAlerts`

Metadata-only Guardian-facing result of a Child-side Safety Incident.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  childDeviceId: Id<"childDevices">,
  activeEnrollmentId: Id<"activeEnrollments">,

  type: "scam_text" | "nsfw_screen",

  appPackageName: string,
  appLabel: string,

  confidenceBand: "low" | "medium" | "high",
  policyVersion: number,

  occurredAt: number,
  createdAt: number,
  expiresAt: number,
}
```

Indexes:

```ts
by_child_profile_created_at
by_household_id
by_expires_at
```

Rules:

- Retain for one week.
- Delete after the weekly Safety Incident Summary is created.
- Never store raw text, screenshots, OCR output, image pixels, audio, or screen recordings.

### `safetyIncidentSummaries`

Weekly aggregate of recent Safety Alerts.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,

  periodStart: number,
  periodEnd: number,

  scamTextCount: number,
  nsfwScreenCount: number,

  topApps: Array<{
    appPackageName: string,
    appLabel: string,
    count: number,
  }>,

  createdAt: number,
  expiresAt: number,
}
```

Indexes:

```ts
by_child_profile_period
by_expires_at
```

Rules:

- Generated by backend scheduled work.
- Retained until the next weekly summary replaces it.
- Summary does not contain individual incident details or raw content.

### `screenTimeRefreshRequests`

Bounded on-demand work that asks Child Mode to replace the latest Screen Time Snapshot.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  childDeviceId: Id<"childDevices">,
  activeEnrollmentId: Id<"activeEnrollments">,

  requestedByGuardianAccountId: Id<"guardianAccounts">,
  status: "requested" | "uploading" | "completed" | "failed" | "expired",
  requestedAt: number,
  completedAt?: number,
  failureReason?: "child_offline" | "usage_access_unavailable" | "upload_failed" | "request_timeout",
  createdAt: number,
  updatedAt: number,
  expiresAt: number,
}
```

Indexes:

```ts
by_child_profile_status
by_household_id
by_expires_at
```

Rules:

- Create only while the desired and applied schema-v2 policies both enable Screen Time, the Child Device is Online, and Usage Access was last reported available.
- Allow one pending request per Child Profile, expire it after two minutes, and deduplicate automatic or manual retries during that interval.
- Use normal-priority FCM through a typed `refresh_screen_time` Child Device Command.
- Do not queue failed responses; expire partial staging rows and measure again on a later request.
- Delete terminal request state 15 minutes after completion, failure, or expiry.

### `screenTimeSnapshots`

Header for staging and atomically publishing the latest today-so-far Screen Time state.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  childDeviceId: Id<"childDevices">,
  activeEnrollmentId: Id<"activeEnrollments">,
  refreshRequestId: Id<"screenTimeRefreshRequests">,

  status: "uploading" | "current" | "superseded",
  localDate: string,
  timezone: string,
  capturedAt: number,
  validUntil: number,
  expectedAppCount: number,

  createdAt: number,
  updatedAt: number,
  expiresAt: number,
}
```

Indexes:

```ts
by_child_profile_status
by_refresh_request_id
by_expires_at
```

### `screenTimeAppUsage`

One positive-duration app row belonging to a staged or current Screen Time Snapshot.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  screenTimeSnapshotId: Id<"screenTimeSnapshots">,
  appPackageName: string,
  foregroundDurationMs: number,
  createdAt: number,
  expiresAt: number,
}
```

Indexes:

```ts
by_screen_time_snapshot_id_and_app_package_name
by_child_profile_id
by_expires_at
```

Rules:

- Child Mode uploads bounded batches containing Android-reported `totalTimeInForeground` only for positive-duration, user-launchable, non-exempt apps.
- Labels resolve through the latest App Catalog; no session count or raw usage event is uploaded.
- A completion mutation verifies the expected rows, marks the new header current, and hides the previous header in one transaction; old rows are then deleted in bounded cleanup.
- The query exposes only the current complete header and rows, never staging or superseded data.
- The first or re-enabled snapshot covers local midnight through `capturedAt`, including same-day usage from before policy enablement, a disabled interval, or enrollment.
- A snapshot becomes invalid at `validUntil`, the Child Device's next local midnight, and is also deleted immediately when Screen Time is disabled or supervision ends.

### `locationState`

Current location state only.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  childDeviceId: Id<"childDevices">,
  activeEnrollmentId: Id<"activeEnrollments">,

  latestLocation?: {
    latitude: number,
    longitude: number,
    accuracyMeters: number,
    capturedAt: number,
  },

  updatedAt: number,
}
```

Rules:

- Store only the latest Location Heartbeat or successful Location Refresh Request measurement.
- Accept a measurement only when its `capturedAt` is newer than the stored latest measurement.
- Do not accept delayed batches or treat backend receipt time as measurement time.
- Do not retain location history or route timelines.
- Treat a location older than 30 minutes as stale while retaining and displaying its measured age and accuracy.
- Delete state immediately when Location Sharing is disabled or supervision ends.

### `locationRefreshRequests`

Transient one-time high-accuracy location refresh state.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  childDeviceId: Id<"childDevices">,
  activeEnrollmentId: Id<"activeEnrollments">,

  status: "requested" | "completed" | "failed" | "expired",

  requestedByGuardianAccountId: Id<"guardianAccounts">,
  requestedAt: number,
  completedAt?: number,
  failureReason?: "child_offline" | "location_unavailable" | "measurement_timeout",
  expiresAt: number,
}
```

Rules:

- A request expires 60 seconds after creation.
- Create only while desired and applied policy enable Location Sharing, the Child Device is Online, and location plus notification capabilities were last reported available.
- Allow one request per Child Profile every two minutes and deduplicate a second Guardian Device or repeated tap.
- Child Mode attempts one fresh high-accuracy measurement and then returns to normal Location Heartbeats.
- A successful result must have been captured at or after the request's backend-owned `requestedAt`; a cached earlier point cannot complete the request.
- Fresh results are retained with their actual `accuracyMeters` rather than rejected or presented as exact solely because accuracy is poor.
- A successful measurement overwrites `locationState`; no request-specific location point is retained.
- A failed or expired request leaves the previous latest location unchanged and visibly stale.
- Use high-priority FCM only with an immediate Child-visible refresh notification.
- Completed, failed, and expired request rows are deleted 15 minutes after becoming terminal.

### `remoteAudioSessions`

Remote Audio session state. Convex stores authorization/signaling/lifecycle state, not audio.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  childDeviceId: Id<"childDevices">,
  activeEnrollmentId: Id<"activeEnrollments">,

  requestedByGuardianAccountId: Id<"guardianAccounts">,

  status:
    | "requested"
    | "connecting"
    | "active"
    | "ended"
    | "stopped_by_child"
    | "expired"
    | "failed",

  requestedAt: number,
  startedAt?: number,
  endedAt?: number,
  expiresAt: number,

  cooldownUntil?: number,

  failureReason?: "child_offline" | "webrtc_failed" | "permission_missing" | "timeout",
}
```

Rules:

- No audio is stored.
- No transcript or recording is stored.
- Initial implementation uses STUN-only WebRTC, so `webrtc_failed` may occur on restrictive networks.
- Session state is transient and should not become long-term history.

### `remoteAudioSignals`

Short-lived WebRTC signaling data.

```ts
{
  remoteAudioSessionId: Id<"remoteAudioSessions">,
  sender: "guardian" | "child",
  type: "offer" | "answer" | "ice_candidate",
  payload: string,
  createdAt: number,
  expiresAt: number,
}
```

Rules:

- Signals expire quickly.
- Payload contains signaling data only, never audio.

### `supervisionHealth`

Backend's current view of Child Device protection status.

```ts
{
  householdId: Id<"households">,
  childProfileId: Id<"childProfiles">,
  childDeviceId: Id<"childDevices">,
  activeEnrollmentId: Id<"activeEnrollments">,

  status: "pending" | "fully_protected" | "protection_degraded" | "offline",

  capabilities?: {
    accessibilityService: boolean,
    usageAccess: boolean,
    location: boolean,
    microphone: boolean,
    vpnService: boolean,
    notificationAccess: boolean,
    batteryOptimizationExempt: boolean,
  },

  lastHeartbeatAt?: number,

  createdAt: number,
  updatedAt: number,
}
```

Rules:

- Child Device reports heartbeat and capability state.
- Enrollment creates `pending` health without a capability snapshot; the first heartbeat reports capabilities.
- Required capability disabled creates a Tamper Alert.
- Two missed expected heartbeats mark the device offline.
- Recovery creates a Guardian Notice.

### `fcmTokens`

Push delivery endpoints for Guardian Devices and Child Devices.

```ts
{
  ownerKind: "guardianDevice" | "childDevice",
  ownerId: string,

  householdId: Id<"households">,
  guardianAccountId?: Id<"guardianAccounts">,
  childProfileId?: Id<"childProfiles">,
  childDeviceId?: Id<"childDevices">,
  activeEnrollmentId?: Id<"activeEnrollments">,

  tokenHash: string,
  encryptedToken: string,

  platform: "android",
  environment: "dev" | "prod",

  status: "active" | "revoked" | "invalid",

  registeredAt: number,
  lastSeenAt: number,
  invalidatedAt?: number,
}
```

Indexes:

```ts
by_owner
by_household_id
by_token_hash
by_status
```

Rules:

- Store encrypted token if avoidable.
- Store token hash for lookup/deduplication.
- FCM token is not identity.

### `auditEvents`

Privacy-safe audit trail for important operational/security actions.

```ts
{
  householdId: Id<"households">,

  actorKind: "guardian" | "childDevice" | "system",
  actorId?: string,

  eventType:
    | "guardian_device_registered"
    | "child_device_enrolled"
    | "child_device_revoked"
    | "policy_changed"
    | "access_request_approved"
    | "access_request_denied"
    | "end_supervision_requested"
    | "end_supervision_completed"
    | "remote_audio_requested"
    | "remote_audio_ended"
    | "screen_time_disabled"
    | "safety_summary_generated",

  targetKind?: string,
  targetId?: string,

  metadata?: Record<string, string | number | boolean>,

  createdAt: number,
  expiresAt?: number,
}
```

Rules:

- No raw child content.
- Child-data-related audit events expire with the Child Profile / End Supervision.
- Account/security events may remain while the Guardian Account exists.

## Follow-up design needed

This document is intentionally a first-pass schema draft. Before implementation, define:

- exact Convex validators for every embedded object;
- exact indexes in `schema.ts`;
- deletion/cleanup scheduled functions;
- encrypted field handling for FCM tokens;
- type-safe ID wrappers shared with Android where useful;
- which functions can read/write each table.
