import { v } from "convex/values";
import { Id } from "../../_generated/dataModel";
import { internal } from "../../_generated/api";
import { MutationCtx, QueryCtx } from "../../_generated/server";
import { GuardianActor } from "../../lib/actors";
import { requireGuardianForChildProfile } from "../../lib/authorize";
import { throwAppError } from "../../lib/errors";
import { guardianMutation, guardianQuery } from "../../lib/functionWrappers";
import { now } from "../../lib/time";

const POLICY_SCHEMA_VERSION = 1;
const COMMAND_LIFETIME_MS = 7 * 24 * 60 * 60 * 1000;

const updateArgs = {
  childProfileId: v.id("childProfiles"),
  expectedCurrentVersion: v.number(),
  operationId: v.string(),
};

export const getPolicyState = guardianQuery({
  operation: "policies.getState",
  args: { childProfileId: v.id("childProfiles") },
  handler: async (ctx, actor, args) => {
    await requireGuardianForChildProfile(ctx, actor, args.childProfileId);
    return await loadGuardianPolicyState(ctx, args.childProfileId);
  },
});

export const updateScreenTimeSummaries = guardianMutation({
  operation: "policies.updateScreenTimeSummaries",
  args: { ...updateArgs, enabled: v.boolean() },
  handler: async (ctx, actor, args) => await updatePolicy(ctx, actor, args, {
    kind: "screen_time_summaries",
    value: { enabled: args.enabled },
  }),
});

export const updateSafeBrowsing = guardianMutation({
  operation: "policies.updateSafeBrowsing",
  args: { ...updateArgs, enabled: v.boolean(), safeSearchEnabled: v.boolean() },
  handler: async (ctx, actor, args) => {
    if (!args.enabled && args.safeSearchEnabled) throwAppError("VALIDATION_FAILED");
    return await updatePolicy(ctx, actor, args, {
      kind: "safe_browsing",
      value: { enabled: args.enabled, safeSearchEnabled: args.safeSearchEnabled },
    });
  },
});

export const updateAppBlocking = guardianMutation({
  operation: "policies.updateAppBlocking",
  args: { ...updateArgs, enabled: v.boolean() },
  handler: async (ctx, actor, args) => await updatePolicy(ctx, actor, args, {
    kind: "app_blocking",
    value: { enabled: args.enabled },
  }),
});

export const updateActiveScreenSafety = guardianMutation({
  operation: "policies.updateActiveScreenSafety",
  args: { ...updateArgs, enabled: v.boolean() },
  handler: async (ctx, actor, args) => await updatePolicy(ctx, actor, args, {
    kind: "active_screen_safety",
    value: { enabled: args.enabled },
  }),
});

type PolicyChange =
  | { kind: "screen_time_summaries"; value: { enabled: boolean } }
  | { kind: "safe_browsing"; value: { enabled: boolean; safeSearchEnabled: boolean } }
  | { kind: "app_blocking"; value: { enabled: boolean } }
  | { kind: "active_screen_safety"; value: { enabled: boolean } };

async function updatePolicy(
  ctx: MutationCtx,
  actor: GuardianActor,
  args: { childProfileId: Id<"childProfiles">; expectedCurrentVersion: number; operationId: string },
  change: PolicyChange,
) {
  await requireGuardianForChildProfile(ctx, actor, args.childProfileId);
  if (!Number.isInteger(args.expectedCurrentVersion) || args.expectedCurrentVersion < 1) {
    throwAppError("VALIDATION_FAILED");
  }
  if (args.operationId.length < 16 || args.operationId.length > 200) {
    throwAppError("VALIDATION_FAILED");
  }
  const fingerprint = JSON.stringify(change);
  const replay = await ctx.db
    .query("policySaveOperations")
    .withIndex("by_child_profile_id_and_operation_id", (q) =>
      q.eq("childProfileId", args.childProfileId).eq("operationId", args.operationId),
    )
    .unique();
  if (replay !== null) {
    if (replay.fingerprint !== fingerprint) throwAppError("POLICY_OPERATION_REUSED");
    return await loadGuardianPolicyState(ctx, args.childProfileId);
  }

  const enrollment = await activeEnrollment(ctx, args.childProfileId);
  if (enrollment === null) throwAppError("VALIDATION_FAILED");
  if (enrollment.supportedPolicySchemaVersion < POLICY_SCHEMA_VERSION) {
    throwAppError("POLICY_UNSUPPORTED");
  }
  const current = await currentPolicy(ctx, args.childProfileId);
  if (current.version !== args.expectedCurrentVersion) throwAppError("POLICY_CONFLICT");

  const next = {
    appBlocking: change.kind === "app_blocking" ? change.value : current.appBlocking,
    safeBrowsing: change.kind === "safe_browsing" ? change.value : current.safeBrowsing,
    activeScreenSafety: change.kind === "active_screen_safety" ? change.value : current.activeScreenSafety,
    screenTimeSummariesEnabled:
      change.kind === "screen_time_summaries" ? change.value.enabled : current.screenTimeSummariesEnabled,
  };
  if (
    JSON.stringify(next) === JSON.stringify({
      appBlocking: current.appBlocking,
      safeBrowsing: current.safeBrowsing,
      activeScreenSafety: current.activeScreenSafety,
      screenTimeSummariesEnabled: current.screenTimeSummariesEnabled,
    })
  ) {
    await ctx.db.insert("policySaveOperations", {
      householdId: actor.householdId,
      childProfileId: args.childProfileId,
      operationId: args.operationId,
      fingerprint,
      resultPolicyVersion: current.version,
      createdAt: now(),
    });
    return await loadGuardianPolicyState(ctx, args.childProfileId);
  }

  const serverNow = now();
  const nextVersion = current.version + 1;
  await ctx.db.patch("supervisionPolicies", current._id, { status: "superseded" });
  await ctx.db.insert("supervisionPolicies", {
    householdId: actor.householdId,
    childProfileId: args.childProfileId,
    version: nextVersion,
    schemaVersion: POLICY_SCHEMA_VERSION,
    status: "active",
    ...next,
    createdByGuardianAccountId: actor.guardianAccountId,
    createdAt: serverNow,
  });
  await ctx.db.insert("policySaveOperations", {
    householdId: actor.householdId,
    childProfileId: args.childProfileId,
    operationId: args.operationId,
    fingerprint,
    resultPolicyVersion: nextVersion,
    createdAt: serverNow,
  });

  const applicationState = await ctx.db
    .query("policyApplicationStates")
    .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", enrollment._id))
    .unique();
  if (applicationState === null) throwAppError("INTERNAL_ERROR");
  await ctx.db.patch("policyApplicationStates", applicationState._id, {
    desiredPolicyVersion: nextVersion,
    status: "pending",
    failureReason: undefined,
    updatedAt: serverNow,
  });

  const pending = await ctx.db
    .query("childDeviceCommands")
    .withIndex("by_active_enrollment_id_and_status", (q) =>
      q.eq("activeEnrollmentId", enrollment._id).eq("status", "pending"),
    )
    .take(50);
  for (const command of pending) {
    if (command.type === "apply_policy_version") {
      await ctx.db.patch("childDeviceCommands", command._id, {
        status: "superseded",
        updatedAt: serverNow,
      });
    }
  }
  const commandId = await ctx.db.insert("childDeviceCommands", {
    householdId: actor.householdId,
    childProfileId: args.childProfileId,
    activeEnrollmentId: enrollment._id,
    childDeviceId: enrollment.childDeviceId,
    type: "apply_policy_version",
    policyVersion: nextVersion,
    intentKey: "apply_policy_version",
    status: "pending",
    createdAt: serverNow,
    updatedAt: serverNow,
    expiresAt: serverNow + COMMAND_LIFETIME_MS,
  });
  await ctx.scheduler.runAfter(0, internal.fcmDelivery.deliver, {
    recordKind: "childDeviceCommand",
    recordId: commandId,
    attempt: 1,
  });
  return await loadGuardianPolicyState(ctx, args.childProfileId);
}

async function loadGuardianPolicyState(ctx: QueryCtx | MutationCtx, childProfileId: Id<"childProfiles">) {
  const enrollment = await activeEnrollment(ctx, childProfileId);
  if (enrollment === null) throwAppError("VALIDATION_FAILED");
  const applicationState = await ctx.db
    .query("policyApplicationStates")
    .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", enrollment._id))
    .unique();
  if (applicationState === null) throwAppError("INTERNAL_ERROR");
  const desiredPolicy = await policyVersion(ctx, childProfileId, applicationState.desiredPolicyVersion);
  const appliedPolicy = applicationState.appliedPolicyVersion === undefined
    ? null
    : await policyVersion(ctx, childProfileId, applicationState.appliedPolicyVersion);
  return {
    desiredPolicy: publicPolicy(desiredPolicy),
    appliedPolicy: appliedPolicy === null ? null : publicPolicy(appliedPolicy),
    applicationStatus: applicationState.status,
    failureReason: applicationState.failureReason ?? null,
    supportedPolicySchemaVersion: enrollment.supportedPolicySchemaVersion,
  };
}

async function activeEnrollment(ctx: QueryCtx | MutationCtx, childProfileId: Id<"childProfiles">) {
  return await ctx.db
    .query("activeEnrollments")
    .withIndex("by_child_profile_id_and_status", (q) =>
      q.eq("childProfileId", childProfileId).eq("status", "active"),
    )
    .unique();
}

async function currentPolicy(ctx: QueryCtx | MutationCtx, childProfileId: Id<"childProfiles">) {
  const policies = await ctx.db
    .query("supervisionPolicies")
    .withIndex("by_child_profile_id_and_version", (q) => q.eq("childProfileId", childProfileId))
    .order("desc")
    .take(1);
  const policy = policies.at(0);
  if (policy === undefined || policy.status !== "active") throwAppError("INTERNAL_ERROR");
  return policy;
}

async function policyVersion(
  ctx: QueryCtx | MutationCtx,
  childProfileId: Id<"childProfiles">,
  version: number,
) {
  const policy = await ctx.db
    .query("supervisionPolicies")
    .withIndex("by_child_profile_id_and_version", (q) =>
      q.eq("childProfileId", childProfileId).eq("version", version),
    )
    .unique();
  if (policy === null) throwAppError("INTERNAL_ERROR");
  return policy;
}

function publicPolicy(policy: Awaited<ReturnType<typeof currentPolicy>>) {
  return {
    version: policy.version,
    schemaVersion: policy.schemaVersion,
    appBlocking: policy.appBlocking,
    safeBrowsing: policy.safeBrowsing,
    activeScreenSafety: policy.activeScreenSafety,
    screenTimeSummariesEnabled: policy.screenTimeSummariesEnabled,
  };
}
