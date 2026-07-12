import { defineSchema, defineTable } from "convex/server";
import { v } from "convex/values";

export default defineSchema({
  guardianAccounts: defineTable({
    clerkTokenIdentifier: v.string(),
    clerkUserId: v.optional(v.string()),
    primaryEmail: v.optional(v.string()),
    status: v.union(
      v.literal("active"),
      v.literal("disabled"),
      v.literal("deleting"),
    ),
    createdAt: v.number(),
    updatedAt: v.number(),
  })
    .index("by_clerk_token_identifier", ["clerkTokenIdentifier"])
    .index("by_status", ["status"]),

  households: defineTable({
    guardianAccountId: v.id("guardianAccounts"),
    status: v.union(v.literal("active"), v.literal("deleting")),
    timezone: v.string(),
    country: v.literal("IN"),
    createdAt: v.number(),
    updatedAt: v.number(),
  })
    .index("by_guardian_account_id", ["guardianAccountId"])
    .index("by_guardian_account_id_and_status", [
      "guardianAccountId",
      "status",
    ])
    .index("by_status", ["status"]),

  guardianDevices: defineTable({
    guardianAccountId: v.id("guardianAccounts"),
    householdId: v.id("households"),
    guardianInstallationId: v.string(),
    deviceLabel: v.optional(v.string()),
    platform: v.literal("android"),
    appBuild: v.string(),
    environment: v.union(v.literal("dev"), v.literal("prod")),
    status: v.union(v.literal("active"), v.literal("revoked")),
    lastSeenAt: v.number(),
    createdAt: v.number(),
    updatedAt: v.number(),
    revokedAt: v.optional(v.number()),
  })
    .index("by_guardian_account_id", ["guardianAccountId"])
    .index("by_guardian_account_id_and_guardian_installation_id", [
      "guardianAccountId",
      "guardianInstallationId",
    ])
    .index("by_guardian_account_id_and_status", [
      "guardianAccountId",
      "status",
    ])
    .index("by_household_id", ["householdId"])
    .index("by_status", ["status"]),

  childProfiles: defineTable({
    householdId: v.id("households"),
    displayName: v.string(),
    birthMonth: v.number(),
    birthYear: v.number(),
    avatarKey: v.optional(v.string()),
    status: v.union(v.literal("active"), v.literal("deleting")),
    createdAt: v.number(),
    updatedAt: v.number(),
  })
    .index("by_household_id", ["householdId"])
    .index("by_household_id_and_status", ["householdId", "status"])
    .index("by_status", ["status"]),

  supervisionPolicies: defineTable({
    householdId: v.id("households"),
    childProfileId: v.id("childProfiles"),
    version: v.number(),
    status: v.union(v.literal("active"), v.literal("superseded")),
    appBlocking: v.object({
      enabled: v.boolean(),
    }),
    safeBrowsing: v.object({
      enabled: v.boolean(),
      safeSearchEnabled: v.boolean(),
    }),
    activeScreenSafety: v.object({
      enabled: v.boolean(),
    }),
    screenTimeSummariesEnabled: v.boolean(),
    createdByGuardianAccountId: v.id("guardianAccounts"),
    createdAt: v.number(),
  })
    .index("by_child_profile_id_and_version", ["childProfileId", "version"])
    .index("by_household_id", ["householdId"])
    .index("by_status", ["status"]),

  enrollmentCodes: defineTable({
    householdId: v.id("households"),
    childProfileId: v.id("childProfiles"),
    codeHash: v.string(),
    status: v.union(v.literal("active"), v.literal("revoked"), v.literal("consumed")),
    expiresAt: v.number(),
    createdByGuardianAccountId: v.id("guardianAccounts"),
    createdByGuardianDeviceId: v.optional(v.id("guardianDevices")),
    revokedByGuardianAccountId: v.optional(v.id("guardianAccounts")),
    revokedByGuardianDeviceId: v.optional(v.id("guardianDevices")),
    consumedByActiveEnrollmentId: v.optional(v.id("activeEnrollments")),
    createdAt: v.number(),
    revokedAt: v.optional(v.number()),
    consumedAt: v.optional(v.number()),
  })
    .index("by_code_hash", ["codeHash"])
    .index("by_child_profile_id_and_status", ["childProfileId", "status"])
    .index("by_household_id", ["householdId"]),

  childDevices: defineTable({
    householdId: v.id("households"),
    childProfileId: v.id("childProfiles"),
    installationId: v.string(),
    deviceLabel: v.optional(v.string()),
    platform: v.literal("android"),
    appBuild: v.string(),
    environment: v.union(v.literal("dev"), v.literal("prod")),
    status: v.union(v.literal("active"), v.literal("revoked")),
    createdAt: v.number(),
    updatedAt: v.number(),
    revokedAt: v.optional(v.number()),
  })
    .index("by_child_profile_id_and_status", ["childProfileId", "status"])
    .index("by_installation_id", ["installationId"])
    .index("by_household_id", ["householdId"]),

  activeEnrollments: defineTable({
    householdId: v.id("households"),
    childProfileId: v.id("childProfiles"),
    childDeviceId: v.id("childDevices"),
    enrollmentCodeId: v.id("enrollmentCodes"),
    status: v.union(v.literal("active"), v.literal("ended"), v.literal("revoked")),
    roleLockActive: v.boolean(),
    enrolledAt: v.number(),
    createdAt: v.number(),
    updatedAt: v.number(),
    endedAt: v.optional(v.number()),
    revokedAt: v.optional(v.number()),
  })
    .index("by_child_profile_id_and_status", ["childProfileId", "status"])
    .index("by_child_device_id", ["childDeviceId"])
    .index("by_household_id", ["householdId"]),

  childDeviceCredentials: defineTable({
    activeEnrollmentId: v.id("activeEnrollments"),
    childDeviceId: v.id("childDevices"),
    publicKeySpki: v.string(),
    algorithm: v.literal("ES256"),
    status: v.union(v.literal("active"), v.literal("revoked")),
    createdAt: v.number(),
    updatedAt: v.number(),
    revokedAt: v.optional(v.number()),
  })
    .index("by_active_enrollment_id_and_status", ["activeEnrollmentId", "status"])
    .index("by_child_device_id", ["childDeviceId"]),

  policyApplicationStates: defineTable({
    householdId: v.id("households"),
    childProfileId: v.id("childProfiles"),
    activeEnrollmentId: v.id("activeEnrollments"),
    childDeviceId: v.id("childDevices"),
    desiredPolicyVersion: v.number(),
    appliedPolicyVersion: v.optional(v.number()),
    status: v.union(v.literal("pending"), v.literal("applied")),
    createdAt: v.number(),
    updatedAt: v.number(),
  })
    .index("by_active_enrollment_id", ["activeEnrollmentId"])
    .index("by_child_profile_id", ["childProfileId"]),

  supervisionHealth: defineTable({
    householdId: v.id("households"),
    childProfileId: v.id("childProfiles"),
    activeEnrollmentId: v.id("activeEnrollments"),
    childDeviceId: v.id("childDevices"),
    connectivityStatus: v.union(
      v.literal("pending"),
      v.literal("online"),
      v.literal("offline"),
    ),
    protectionStatus: v.union(
      v.literal("pending"),
      v.literal("fully_protected"),
      v.literal("protection_degraded"),
    ),
    capabilities: v.optional(
      v.object({
        accessibilityService: v.boolean(),
        usageAccess: v.boolean(),
        location: v.boolean(),
        microphone: v.boolean(),
        notificationAccess: v.boolean(),
        batteryOptimizationExempt: v.boolean(),
      }),
    ),
    lastHeartbeatAt: v.optional(v.number()),
    activeOfflineEpisodeKey: v.optional(v.string()),
    createdAt: v.number(),
    updatedAt: v.number(),
  })
    .index("by_active_enrollment_id", ["activeEnrollmentId"])
    .index("by_child_profile_id", ["childProfileId"]),

  childDeviceTokenChallenges: defineTable({
    credentialId: v.id("childDeviceCredentials"),
    challengeHash: v.string(),
    status: v.union(v.literal("active"), v.literal("used")),
    expiresAt: v.number(),
    createdAt: v.number(),
    usedAt: v.optional(v.number()),
  })
    .index("by_challenge_hash", ["challengeHash"])
    .index("by_credential_id_and_status", ["credentialId", "status"]),

  fcmTokens: defineTable({
    ownerKind: v.union(v.literal("guardianDevice"), v.literal("childDevice")),
    ownerId: v.string(),
    householdId: v.id("households"),
    childProfileId: v.optional(v.id("childProfiles")),
    childDeviceId: v.optional(v.id("childDevices")),
    activeEnrollmentId: v.optional(v.id("activeEnrollments")),
    tokenHash: v.string(),
    encryptedToken: v.string(),
    platform: v.literal("android"),
    environment: v.union(v.literal("dev"), v.literal("prod")),
    status: v.union(v.literal("active"), v.literal("revoked"), v.literal("invalid")),
    registeredAt: v.number(),
    lastSeenAt: v.number(),
    invalidatedAt: v.optional(v.number()),
  })
    .index("by_child_device_id", ["childDeviceId"])
    .index("by_owner_kind_and_owner_id", ["ownerKind", "ownerId"])
    .index("by_token_hash", ["tokenHash"])
    .index("by_environment_and_token_hash", ["environment", "tokenHash"])
    .index("by_status", ["status"]),

  guardianNotices: defineTable({
    householdId: v.id("households"),
    childProfileId: v.id("childProfiles"),
    activeEnrollmentId: v.id("activeEnrollments"),
    type: v.union(v.literal("offline"), v.literal("recovery"), v.literal("tamper")),
    episodeKey: v.string(),
    unavailableCapabilities: v.optional(v.array(v.string())),
    status: v.union(v.literal("active"), v.literal("expired")),
    occurredAt: v.number(),
    expiresAt: v.number(),
  })
    .index("by_household_id_and_occurred_at", ["householdId", "occurredAt"])
    .index("by_active_enrollment_id_and_episode_key", ["activeEnrollmentId", "episodeKey"])
    .index("by_expires_at", ["expiresAt"]),

  guardianNoticeReceipts: defineTable({
    guardianNoticeId: v.id("guardianNotices"),
    guardianDeviceId: v.id("guardianDevices"),
    householdId: v.id("households"),
    status: v.union(v.literal("pending"), v.literal("processed")),
    presentation: v.optional(v.union(v.literal("shown"), v.literal("suppressed"))),
    createdAt: v.number(),
    processedAt: v.optional(v.number()),
    expiresAt: v.number(),
  })
    .index("by_guardian_device_id_and_status", ["guardianDeviceId", "status"])
    .index("by_guardian_notice_id_and_guardian_device_id", ["guardianNoticeId", "guardianDeviceId"])
    .index("by_expires_at", ["expiresAt"]),

  childDeviceCommands: defineTable({
    householdId: v.id("households"),
    childProfileId: v.id("childProfiles"),
    activeEnrollmentId: v.id("activeEnrollments"),
    childDeviceId: v.id("childDevices"),
    type: v.literal("apply_policy_version"),
    policyVersion: v.number(),
    intentKey: v.string(),
    status: v.union(
      v.literal("pending"),
      v.literal("acknowledged"),
      v.literal("rejected"),
      v.literal("superseded"),
      v.literal("cancelled"),
      v.literal("expired"),
    ),
    rejectionReason: v.optional(
      v.union(
        v.literal("unsupported_command"),
        v.literal("invalid_command"),
        v.literal("unable_to_apply"),
      ),
    ),
    createdAt: v.number(),
    updatedAt: v.number(),
    expiresAt: v.number(),
    acknowledgedAt: v.optional(v.number()),
  })
    .index("by_active_enrollment_id_and_status", ["activeEnrollmentId", "status"])
    .index("by_active_enrollment_id_and_intent_key", ["activeEnrollmentId", "intentKey"])
    .index("by_expires_at", ["expiresAt"]),

  fcmDeliveryAttempts: defineTable({
    recordKind: v.union(v.literal("guardianNotice"), v.literal("childDeviceCommand")),
    recordId: v.string(),
    fcmTokenId: v.id("fcmTokens"),
    attempt: v.number(),
    outcome: v.union(v.literal("accepted"), v.literal("transient"), v.literal("invalid"), v.literal("exhausted")),
    attemptedAt: v.number(),
    expiresAt: v.number(),
  })
    .index("by_record_kind_and_record_id", ["recordKind", "recordId"])
    .index("by_expires_at", ["expiresAt"]),

});
