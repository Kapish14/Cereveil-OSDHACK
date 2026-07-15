import { v } from "convex/values";
import { internalMutation, internalQuery } from "../../_generated/server";
import { MutationCtx, QueryCtx } from "../../_generated/server";
import { Id } from "../../_generated/dataModel";
import { internal } from "../../_generated/api";
import { throwAppError } from "../../lib/errors";

const OFFLINE_AFTER_MS = 45 * 60 * 1000;
const MESSAGE_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

export const previewEnrollmentCode = internalQuery({
  args: { codeHash: v.string(), serverNow: v.number() },
  handler: async (ctx, args) => {
    const enrollmentCode = await ctx.db
      .query("enrollmentCodes")
      .withIndex("by_code_hash", (q) => q.eq("codeHash", args.codeHash))
      .unique();
    if (
      enrollmentCode === null ||
      enrollmentCode.status !== "active" ||
      enrollmentCode.expiresAt <= args.serverNow
    ) {
      return null;
    }

    const childProfile = await ctx.db.get("childProfiles", enrollmentCode.childProfileId);
    if (childProfile === null || childProfile.status !== "active") return null;

    const enrollment = await ctx.db
      .query("activeEnrollments")
      .withIndex("by_child_profile_id_and_status", (q) =>
        q.eq("childProfileId", childProfile._id).eq("status", "active"),
      )
      .unique();
    if (enrollment !== null) return null;

    return {
      childDisplayName: childProfile.displayName,
      codeExpiresAt: enrollmentCode.expiresAt,
      serverNow: args.serverNow,
    };
  },
});

export const completeEnrollment = internalMutation({
  args: {
    codeHash: v.string(),
    publicKeySpki: v.string(),
    installationId: v.string(),
    deviceLabel: v.optional(v.string()),
    appBuild: v.string(),
    supportedPolicySchemaVersion: v.number(),
    supportsNsfwScreenDetection: v.boolean(),
    serverNow: v.number(),
  },
  handler: async (ctx, args) => {
    const enrollmentCode = await ctx.db
      .query("enrollmentCodes")
      .withIndex("by_code_hash", (q) => q.eq("codeHash", args.codeHash))
      .unique();
    if (
      enrollmentCode === null ||
      enrollmentCode.status !== "active" ||
      enrollmentCode.expiresAt <= args.serverNow
    ) {
      return { kind: "invalid_code" as const };
    }

    const childProfile = await ctx.db.get("childProfiles", enrollmentCode.childProfileId);
    const household = await ctx.db.get("households", enrollmentCode.householdId);
    if (
      childProfile === null ||
      childProfile.status !== "active" ||
      household === null ||
      household.status !== "active"
    ) {
      return { kind: "invalid_code" as const };
    }

    const existingEnrollment = await ctx.db
      .query("activeEnrollments")
      .withIndex("by_child_profile_id_and_status", (q) =>
        q.eq("childProfileId", childProfile._id).eq("status", "active"),
      )
      .unique();
    if (existingEnrollment !== null) return { kind: "already_enrolled" as const };

    const policies = await ctx.db
      .query("supervisionPolicies")
      .withIndex("by_child_profile_id_and_version", (q) =>
        q.eq("childProfileId", childProfile._id),
      )
      .order("desc")
      .take(1);
    const currentPolicy = policies.at(0);
    if (currentPolicy === undefined || currentPolicy.status !== "active") {
      throw new Error("Active Child Profile is missing its current Supervision Policy.");
    }
    if (
      !Number.isInteger(args.supportedPolicySchemaVersion) ||
      args.supportedPolicySchemaVersion < currentPolicy.schemaVersion
    ) {
      return { kind: "unsupported_policy" as const };
    }

    const childDeviceId = await ctx.db.insert("childDevices", {
      householdId: household._id,
      childProfileId: childProfile._id,
      installationId: args.installationId,
      ...(args.deviceLabel === undefined ? {} : { deviceLabel: args.deviceLabel }),
      platform: "android",
      appBuild: args.appBuild,
      environment: "dev",
      status: "active",
      createdAt: args.serverNow,
      updatedAt: args.serverNow,
    });
    const activeEnrollmentId = await ctx.db.insert("activeEnrollments", {
      householdId: household._id,
      childProfileId: childProfile._id,
      childDeviceId,
      enrollmentCodeId: enrollmentCode._id,
      status: "active",
      roleLockActive: true,
      supportedPolicySchemaVersion: args.supportedPolicySchemaVersion,
      supportsNsfwScreenDetection: args.supportsNsfwScreenDetection,
      enrolledAt: args.serverNow,
      createdAt: args.serverNow,
      updatedAt: args.serverNow,
    });
    const credentialId = await ctx.db.insert("childDeviceCredentials", {
      activeEnrollmentId,
      childDeviceId,
      publicKeySpki: args.publicKeySpki,
      algorithm: "ES256",
      status: "active",
      createdAt: args.serverNow,
      updatedAt: args.serverNow,
    });
    await ctx.db.insert("policyApplicationStates", {
      householdId: household._id,
      childProfileId: childProfile._id,
      activeEnrollmentId,
      childDeviceId,
      desiredPolicyVersion: currentPolicy.version,
      status: "pending",
      createdAt: args.serverNow,
      updatedAt: args.serverNow,
    });
    await ctx.db.insert("childDeviceCommands", {
      householdId: household._id,
      childProfileId: childProfile._id,
      activeEnrollmentId,
      childDeviceId,
      type: "apply_policy_version",
      policyVersion: currentPolicy.version,
      intentKey: "apply_policy_version",
      status: "pending",
      createdAt: args.serverNow,
      updatedAt: args.serverNow,
      expiresAt: args.serverNow + MESSAGE_RETENTION_MS,
    }).then(async (commandId) => {
      await ctx.scheduler.runAfter(0, internal.fcmDelivery.deliver, {
        recordKind: "childDeviceCommand",
        recordId: commandId,
        attempt: 1,
      });
    });
    await ctx.db.insert("supervisionHealth", {
      householdId: household._id,
      childProfileId: childProfile._id,
      activeEnrollmentId,
      childDeviceId,
      connectivityStatus: "pending",
      protectionStatus: "pending",
      createdAt: args.serverNow,
      updatedAt: args.serverNow,
    });
    await ctx.scheduler.runAt(
      args.serverNow + OFFLINE_AFTER_MS,
      internal.modules.deviceIdentity.internal.markOfflineIfStale,
      { activeEnrollmentId, expectedLastHeartbeatAt: null, serverNow: args.serverNow + OFFLINE_AFTER_MS },
    );
    await ctx.db.patch("enrollmentCodes", enrollmentCode._id, {
      status: "consumed",
      consumedAt: args.serverNow,
      consumedByActiveEnrollmentId: activeEnrollmentId,
    });

    return {
      kind: "success" as const,
      childDeviceId,
      activeEnrollmentId,
      credentialId,
      childDisplayName: childProfile.displayName,
      desiredPolicyVersion: currentPolicy.version,
      enrolledAt: args.serverNow,
      environment: "dev" as const,
    };
  },
});

export const childActorArgs = {
  credentialId: v.id("childDeviceCredentials"),
  activeEnrollmentId: v.id("activeEnrollments"),
  childDeviceId: v.id("childDevices"),
};

export const childDeviceActorValidator = v.object({
  credentialId: v.id("childDeviceCredentials"),
  activeEnrollmentId: v.id("activeEnrollments"),
  childDeviceId: v.id("childDevices"),
  childProfileId: v.id("childProfiles"),
  householdId: v.id("households"),
});

export type ChildDeviceActor = {
  credentialId: Id<"childDeviceCredentials">;
  activeEnrollmentId: Id<"activeEnrollments">;
  childDeviceId: Id<"childDevices">;
  childProfileId: Id<"childProfiles">;
  householdId: Id<"households">;
};

export const resolveChildDeviceActor = internalQuery({
  args: { credentialId: v.string(), activeEnrollmentId: v.string(), childDeviceId: v.string() },
  handler: async (ctx, args) => {
    const credentialId = ctx.db.normalizeId("childDeviceCredentials", args.credentialId);
    const activeEnrollmentId = ctx.db.normalizeId("activeEnrollments", args.activeEnrollmentId);
    const childDeviceId = ctx.db.normalizeId("childDevices", args.childDeviceId);
    if (credentialId === null || activeEnrollmentId === null || childDeviceId === null) return null;
    const loaded = await loadActiveChildActor(ctx, { credentialId, activeEnrollmentId, childDeviceId });
    if (loaded === null) return null;
    return actorFromLoaded(loaded);
  },
});

const capabilitiesValidator = v.object({
  accessibilityService: v.boolean(),
  usageAccess: v.boolean(),
  location: v.boolean(),
  microphone: v.boolean(),
  notificationAccess: v.boolean(),
  batteryOptimizationExempt: v.boolean(),
  trustedDeviceTime: v.boolean(),
});

export const recordHeartbeat = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({
      capabilities: capabilitiesValidator,
      supportedPolicySchemaVersion: v.number(),
      supportsNsfwScreenDetection: v.boolean(),
      serverNow: v.number(),
    }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const health = await ctx.db
      .query("supervisionHealth")
      .withIndex("by_active_enrollment_id", (q) =>
        q.eq("activeEnrollmentId", actor.enrollment._id),
      )
      .unique();
    if (health === null) throwAppError("INTERNAL_ERROR");
    if (
      !Number.isInteger(args.input.supportedPolicySchemaVersion) ||
      args.input.supportedPolicySchemaVersion < 1
    ) throwAppError("VALIDATION_FAILED");
    if (
      actor.enrollment.supportedPolicySchemaVersion !== args.input.supportedPolicySchemaVersion ||
      actor.enrollment.supportsNsfwScreenDetection !== args.input.supportsNsfwScreenDetection
    ) {
      await ctx.db.patch("activeEnrollments", actor.enrollment._id, {
        supportedPolicySchemaVersion: args.input.supportedPolicySchemaVersion,
        supportsNsfwScreenDetection: args.input.supportsNsfwScreenDetection,
        updatedAt: args.input.serverNow,
      });
    }
    const wasOffline = health.connectivityStatus === "offline";
    const previousCapabilities = health.capabilities;
    const fullyProtected = Object.values(args.input.capabilities).every(Boolean);
    const status = fullyProtected ? ("fully_protected" as const) : ("protection_degraded" as const);
    await ctx.db.patch("supervisionHealth", health._id, {
      connectivityStatus: "online",
      protectionStatus: status,
      capabilities: args.input.capabilities,
      lastHeartbeatAt: args.input.serverNow,
      updatedAt: args.input.serverNow,
      ...(wasOffline ? { activeOfflineEpisodeKey: undefined } : {}),
    });
    if (wasOffline && health.activeOfflineEpisodeKey !== undefined) {
      await createGuardianNotice(ctx, {
        householdId: actor.enrollment.householdId,
        childProfileId: actor.enrollment.childProfileId,
        activeEnrollmentId: actor.enrollment._id,
        type: "recovery",
        episodeKey: health.activeOfflineEpisodeKey,
        serverNow: args.input.serverNow,
      });
    }
    const newlyUnavailable = Object.entries(args.input.capabilities)
      .filter(([key, available]) => !available && (previousCapabilities?.[key as keyof typeof args.input.capabilities] ?? true))
      .map(([key]) => key);
    if (newlyUnavailable.length > 0) {
      await createGuardianNotice(ctx, {
        householdId: actor.enrollment.householdId,
        childProfileId: actor.enrollment.childProfileId,
        activeEnrollmentId: actor.enrollment._id,
        type: "tamper",
        episodeKey: `tamper:${args.input.serverNow}`,
        unavailableCapabilities: newlyUnavailable,
        serverNow: args.input.serverNow,
      });
    }
    await ctx.scheduler.runAt(
      args.input.serverNow + OFFLINE_AFTER_MS,
      internal.modules.deviceIdentity.internal.markOfflineIfStale,
      {
        activeEnrollmentId: actor.enrollment._id,
        expectedLastHeartbeatAt: args.input.serverNow,
        serverNow: args.input.serverNow + OFFLINE_AFTER_MS,
      },
    );
    return { status, serverNow: args.input.serverNow };
  },
});

export const markOfflineIfStale = internalMutation({
  args: {
    activeEnrollmentId: v.id("activeEnrollments"),
    expectedLastHeartbeatAt: v.union(v.number(), v.null()),
    serverNow: v.number(),
  },
  handler: async (ctx, args) => {
    const health = await ctx.db
      .query("supervisionHealth")
      .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", args.activeEnrollmentId))
      .unique();
    if (health === null) return false;
    if ((health.lastHeartbeatAt ?? null) !== args.expectedLastHeartbeatAt) return false;
    if (health.connectivityStatus === "offline") return false;
    const episodeKey = `offline:${args.serverNow}`;
    await ctx.db.patch("supervisionHealth", health._id, {
      connectivityStatus: "offline",
      activeOfflineEpisodeKey: episodeKey,
      updatedAt: args.serverNow,
    });
    await createGuardianNotice(ctx, {
      householdId: health.householdId,
      childProfileId: health.childProfileId,
      activeEnrollmentId: health.activeEnrollmentId,
      type: "offline",
      episodeKey,
      serverNow: args.serverNow,
    });
    return true;
  },
});

async function createGuardianNotice(
  ctx: MutationCtx,
  args: {
    householdId: Id<"households">;
    childProfileId: Id<"childProfiles">;
    activeEnrollmentId: Id<"activeEnrollments">;
    type: "offline" | "recovery" | "tamper";
    episodeKey: string;
    unavailableCapabilities?: string[];
    serverNow: number;
  },
) {
  const existing = await ctx.db
    .query("guardianNotices")
    .withIndex("by_active_enrollment_id_and_episode_key", (q) =>
      q.eq("activeEnrollmentId", args.activeEnrollmentId).eq("episodeKey", `${args.type}:${args.episodeKey}`),
    )
    .unique();
  if (existing !== null) return existing._id;
  const expiresAt = args.serverNow + MESSAGE_RETENTION_MS;
  const noticeId = await ctx.db.insert("guardianNotices", {
    householdId: args.householdId,
    childProfileId: args.childProfileId,
    activeEnrollmentId: args.activeEnrollmentId,
    type: args.type,
    episodeKey: `${args.type}:${args.episodeKey}`,
    ...(args.unavailableCapabilities === undefined
      ? {}
      : { unavailableCapabilities: args.unavailableCapabilities }),
    status: "active",
    occurredAt: args.serverNow,
    expiresAt,
  });
  const devices = await ctx.db
    .query("guardianDevices")
    .withIndex("by_household_id", (q) => q.eq("householdId", args.householdId))
    .take(3);
  for (const device of devices) {
    if (device.status !== "active") continue;
    await ctx.db.insert("guardianNoticeReceipts", {
      guardianNoticeId: noticeId,
      guardianDeviceId: device._id,
      householdId: args.householdId,
      status: "pending",
      createdAt: args.serverNow,
      expiresAt,
    });
  }
  await ctx.scheduler.runAfter(0, internal.fcmDelivery.deliver, {
    recordKind: "guardianNotice",
    recordId: noticeId,
    attempt: 1,
  });
  return noticeId;
}

export const getTokenChallengeAuthority = internalQuery({
  args: { credentialId: v.id("childDeviceCredentials") },
  handler: async (ctx, args) => {
    const credential = await ctx.db.get("childDeviceCredentials", args.credentialId);
    if (credential !== null) return { publicKeySpki: credential.publicKeySpki };
    const revocationProof = await ctx.db.query("childDeviceRevocationProofs")
      .withIndex("by_credential_id", (q) => q.eq("credentialId", args.credentialId)).unique();
    return revocationProof === null ? null : { publicKeySpki: revocationProof.publicKeySpki };
  },
});

export const createTokenChallenge = internalMutation({
  args: {
    credentialId: v.id("childDeviceCredentials"),
    requestNonceHash: v.string(),
    challengeHash: v.string(),
    expiresAt: v.number(),
    deleteAt: v.number(),
    serverNow: v.number(),
  },
  handler: async (ctx, args) => {
    const credential = await ctx.db.get("childDeviceCredentials", args.credentialId);
    if (credential === null) {
      const revocationProof = await ctx.db.query("childDeviceRevocationProofs")
        .withIndex("by_credential_id", (q) => q.eq("credentialId", args.credentialId)).unique();
      if (revocationProof === null) return null;
    }
    const replay = await ctx.db.query("childDeviceTokenChallenges")
      .withIndex("by_credential_id_and_request_nonce_hash", (q) =>
        q.eq("credentialId", args.credentialId).eq("requestNonceHash", args.requestNonceHash),
      ).unique();
    if (replay !== null) return null;
    const priorChallenges = await ctx.db.query("childDeviceTokenChallenges")
      .withIndex("by_credential_id_and_status", (q) =>
        q.eq("credentialId", args.credentialId).eq("status", "active"),
      ).take(50);
    const unexpiredChallenges = priorChallenges.filter((prior) => prior.expiresAt > args.serverNow);
    for (const prior of priorChallenges) {
      if (prior.expiresAt <= args.serverNow) await ctx.db.delete("childDeviceTokenChallenges", prior._id);
    }
    // Authenticated callers may keep a small concurrent set without invalidating one another.
    if (priorChallenges.length === 50 || unexpiredChallenges.length >= 5) return null;
    const challengeId = await ctx.db.insert("childDeviceTokenChallenges", {
      credentialId: args.credentialId,
      requestNonceHash: args.requestNonceHash,
      challengeHash: args.challengeHash,
      status: "active",
      expiresAt: args.expiresAt,
      createdAt: args.serverNow,
    });
    await ctx.scheduler.runAfter(
      Math.max(0, args.deleteAt - args.serverNow),
      internal.modules.deviceIdentity.lifecycle.expireTokenChallenge,
      { challengeId },
    );
    return true;
  },
});

export const getTokenChallenge = internalQuery({
  args: {
    credentialId: v.id("childDeviceCredentials"),
    challengeHash: v.string(),
    serverNow: v.number(),
  },
  handler: async (ctx, args) => {
    const challenge = await loadActiveChallenge(ctx, args);
    if (challenge === null) return null;
    const credential = await ctx.db.get("childDeviceCredentials", args.credentialId);
    if (credential !== null) return { publicKeySpki: credential.publicKeySpki };
    const revocationProof = await ctx.db.query("childDeviceRevocationProofs")
      .withIndex("by_credential_id", (q) => q.eq("credentialId", args.credentialId)).unique();
    return revocationProof === null ? null : { publicKeySpki: revocationProof.publicKeySpki };
  },
});

export const consumeTokenChallenge = internalMutation({
  args: {
    credentialId: v.id("childDeviceCredentials"),
    challengeHash: v.string(),
    serverNow: v.number(),
  },
  handler: async (ctx, args) => {
    const challenge = await loadActiveChallenge(ctx, args);
    if (challenge === null) return null;
    const credential = await ctx.db.get("childDeviceCredentials", args.credentialId);
    if (credential === null) {
      const revocationProof = await ctx.db.query("childDeviceRevocationProofs")
        .withIndex("by_credential_id", (q) => q.eq("credentialId", args.credentialId)).unique();
      if (revocationProof === null) return null;
      await ctx.db.patch("childDeviceTokenChallenges", challenge._id, {
        status: "used",
        usedAt: args.serverNow,
      });
      return { kind: "revoked" as const };
    }
    await ctx.db.patch("childDeviceTokenChallenges", challenge._id, {
      status: "used",
      usedAt: args.serverNow,
    });
    const activeCredential = await loadActiveCredential(ctx, args.credentialId);
    if (activeCredential === null) return { kind: "revoked" as const };
    return {
      kind: "active" as const,
      credentialId: activeCredential.credential._id,
      activeEnrollmentId: activeCredential.enrollment._id,
      childDeviceId: activeCredential.device._id,
    };
  },
});

export type ChildActorIds = {
  credentialId: Id<"childDeviceCredentials">;
  activeEnrollmentId: Id<"activeEnrollments">;
  childDeviceId: Id<"childDevices">;
};

export async function loadActiveChildActor(ctx: MutationCtx | QueryCtx, ids: ChildActorIds) {
  const [credential, enrollment, device] = await Promise.all([
    ctx.db.get("childDeviceCredentials", ids.credentialId),
    ctx.db.get("activeEnrollments", ids.activeEnrollmentId),
    ctx.db.get("childDevices", ids.childDeviceId),
  ]);
  if (
    credential === null ||
    credential.status !== "active" ||
    credential.activeEnrollmentId !== ids.activeEnrollmentId ||
    credential.childDeviceId !== ids.childDeviceId ||
    enrollment === null ||
    enrollment.status !== "active" ||
    enrollment.childDeviceId !== ids.childDeviceId ||
    device === null ||
    device.status !== "active"
  ) {
    return null;
  }
  const [childProfile, household] = await Promise.all([
    ctx.db.get("childProfiles", enrollment.childProfileId),
    ctx.db.get("households", enrollment.householdId),
  ]);
  if (
    childProfile === null ||
    childProfile.status !== "active" ||
    childProfile.householdId !== enrollment.householdId ||
    device.childProfileId !== childProfile._id ||
    device.householdId !== enrollment.householdId ||
    household === null ||
    household.status !== "active"
  ) return null;
  return { credential, enrollment, device, childProfile, household };
}

export async function requireActiveChildDeviceActor(
  ctx: MutationCtx | QueryCtx,
  actor: ChildDeviceActor,
) {
  const loaded = await loadActiveChildActor(ctx, actor);
  if (loaded === null) throwAppError("CHILD_DEVICE_UNAUTHORIZED");
  const resolved = actorFromLoaded(loaded);
  if (resolved.childProfileId !== actor.childProfileId || resolved.householdId !== actor.householdId) {
    throwAppError("CHILD_DEVICE_UNAUTHORIZED");
  }
  return loaded;
}

function actorFromLoaded(loaded: NonNullable<Awaited<ReturnType<typeof loadActiveChildActor>>>): ChildDeviceActor {
  return {
    credentialId: loaded.credential._id,
    activeEnrollmentId: loaded.enrollment._id,
    childDeviceId: loaded.device._id,
    childProfileId: loaded.childProfile._id,
    householdId: loaded.household._id,
  };
}

async function loadActiveCredential(
  ctx: MutationCtx | QueryCtx,
  credentialId: Id<"childDeviceCredentials">,
) {
  const credential = await ctx.db.get("childDeviceCredentials", credentialId);
  if (credential === null || credential.status !== "active") return null;
  const [enrollment, device] = await Promise.all([
    ctx.db.get("activeEnrollments", credential.activeEnrollmentId),
    ctx.db.get("childDevices", credential.childDeviceId),
  ]);
  if (
    enrollment === null ||
    enrollment.status !== "active" ||
    device === null ||
    device.status !== "active"
  ) {
    return null;
  }
  return { credential, enrollment, device };
}

async function loadActiveChallenge(
  ctx: MutationCtx | QueryCtx,
  args: {
    credentialId: Id<"childDeviceCredentials">;
    challengeHash: string;
    serverNow: number;
  },
) {
  const challenge = await ctx.db
    .query("childDeviceTokenChallenges")
    .withIndex("by_challenge_hash", (q) => q.eq("challengeHash", args.challengeHash))
    .unique();
  if (
    challenge === null ||
    challenge.credentialId !== args.credentialId ||
    challenge.status !== "active" ||
    challenge.expiresAt <= args.serverNow
  ) {
    return null;
  }
  return challenge;
}
