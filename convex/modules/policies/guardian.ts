import { v } from "convex/values";
import { Id } from "../../_generated/dataModel";
import { internal } from "../../_generated/api";
import { MutationCtx, QueryCtx } from "../../_generated/server";
import { GuardianActor } from "../../lib/actors";
import { requireGuardianForChildProfile } from "../../lib/authorize";
import { throwAppError } from "../../lib/errors";
import { guardianMutation, guardianQuery } from "../../lib/functionWrappers";
import { now } from "../../lib/time";

const POLICY_SCHEMA_VERSION = 2;
const COMMAND_LIFETIME_MS = 7 * 24 * 60 * 60 * 1000;

const updateArgs = {
  childProfileId: v.id("childProfiles"),
  expectedCurrentVersion: v.number(),
  operationId: v.string(),
};

const scheduleValidator = v.object({
  scheduleId: v.string(),
  weekdays: v.array(v.number()),
  startMinute: v.number(),
  endMinute: v.number(),
});

const appBlockRuleValidator = v.object({
  packageName: v.string(),
  manualBlocked: v.boolean(),
  schedules: v.array(scheduleValidator),
});

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
    kind: "screen_time",
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

export const updateAppBlockingRules = guardianMutation({
  operation: "policies.updateAppBlockingRules",
  args: { ...updateArgs, rules: v.array(appBlockRuleValidator) },
  handler: async (ctx, actor, args) => {
    validateAppBlockRules(args.rules);
    return await updatePolicy(ctx, actor, args, {
      kind: "app_blocking_rules",
      value: { rules: args.rules },
    });
  },
});

export const updateLocationSharing = guardianMutation({
  operation: "policies.updateLocationSharing",
  args: { ...updateArgs, enabled: v.boolean() },
  handler: async (ctx, actor, args) => await updatePolicy(ctx, actor, args, {
    kind: "location_sharing",
    value: { enabled: args.enabled },
  }),
});

export const updateScreenTime = guardianMutation({
  operation: "policies.updateScreenTime",
  args: { ...updateArgs, enabled: v.boolean() },
  handler: async (ctx, actor, args) => await updatePolicy(ctx, actor, args, {
    kind: "screen_time",
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
  | { kind: "screen_time"; value: { enabled: boolean } }
  | { kind: "safe_browsing"; value: { enabled: boolean; safeSearchEnabled: boolean } }
  | { kind: "app_blocking"; value: { enabled: boolean } }
  | { kind: "app_blocking_rules"; value: { rules: AppBlockRule[] } }
  | { kind: "location_sharing"; value: { enabled: boolean } }
  | { kind: "active_screen_safety"; value: { enabled: boolean } };

type AppBlockRule = {
  packageName: string;
  manualBlocked: boolean;
  schedules: Array<{
    scheduleId: string;
    weekdays: number[];
    startMinute: number;
    endMinute: number;
  }>;
};

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
    appBlocking: change.kind === "app_blocking"
      ? { ...current.appBlocking, ...change.value, rules: current.appBlocking.rules ?? [] }
      : change.kind === "app_blocking_rules"
        ? {
            enabled: current.appBlocking.enabled || change.value.rules.length > 0,
            rules: change.value.rules,
          }
        : { ...current.appBlocking, rules: current.appBlocking.rules ?? [] },
    safeBrowsing: change.kind === "safe_browsing" ? change.value : current.safeBrowsing,
    activeScreenSafety: change.kind === "active_screen_safety" ? change.value : current.activeScreenSafety,
    locationSharing: change.kind === "location_sharing"
      ? change.value
      : current.locationSharing ?? { enabled: false },
    screenTime: change.kind === "screen_time"
      ? change.value
      : current.screenTime ?? { enabled: current.screenTimeSummariesEnabled ?? false },
  };
  if (
    JSON.stringify(next) === JSON.stringify({
      appBlocking: current.appBlocking,
      safeBrowsing: current.safeBrowsing,
      activeScreenSafety: current.activeScreenSafety,
      locationSharing: current.locationSharing ?? { enabled: false },
      screenTime: current.screenTime ?? { enabled: current.screenTimeSummariesEnabled ?? false },
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
  await applyImmediatePrivacyDisablement(ctx, args.childProfileId, enrollment._id, next);
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

function validateAppBlockRules(rules: AppBlockRule[]) {
  if (rules.length > 100) throwAppError("VALIDATION_FAILED");
  const packages = new Set<string>();
  const packagePattern = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;
  for (const rule of rules) {
    if (
      !packagePattern.test(rule.packageName) ||
      rule.packageName.length > 255 ||
      rule.packageName === "com.cereveil" ||
      rule.packageName.startsWith("com.cereveil.") ||
      packages.has(rule.packageName) ||
      rule.schedules.length > 8 ||
      (!rule.manualBlocked && rule.schedules.length === 0)
    ) throwAppError("VALIDATION_FAILED");
    packages.add(rule.packageName);
    const scheduleIds = new Set<string>();
    for (const schedule of rule.schedules) {
      const weekdays = new Set(schedule.weekdays);
      if (
        schedule.scheduleId.length < 1 ||
        schedule.scheduleId.length > 100 ||
        scheduleIds.has(schedule.scheduleId) ||
        weekdays.size !== schedule.weekdays.length ||
        weekdays.size < 1 ||
        [...weekdays].some((day) => !Number.isInteger(day) || day < 1 || day > 7) ||
        !Number.isInteger(schedule.startMinute) ||
        !Number.isInteger(schedule.endMinute) ||
        schedule.startMinute < 0 || schedule.startMinute > 1439 ||
        schedule.endMinute < 0 || schedule.endMinute > 1439 ||
        schedule.startMinute === schedule.endMinute
      ) throwAppError("VALIDATION_FAILED");
      scheduleIds.add(schedule.scheduleId);
    }
  }
}

async function applyImmediatePrivacyDisablement(
  ctx: MutationCtx,
  childProfileId: Id<"childProfiles">,
  activeEnrollmentId: Id<"activeEnrollments">,
  policy: {
    locationSharing: { enabled: boolean };
    screenTime: { enabled: boolean };
  },
) {
  if (!policy.locationSharing.enabled) {
    const latest = await ctx.db.query("latestLocationStates")
      .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", activeEnrollmentId))
      .unique();
    if (latest !== null) await ctx.db.delete("latestLocationStates", latest._id);
    const requests = await ctx.db.query("locationRefreshRequests")
      .withIndex("by_child_profile_id_and_requested_at", (q) => q.eq("childProfileId", childProfileId))
      .take(50);
    for (const request of requests) await ctx.db.delete("locationRefreshRequests", request._id);
  }
  if (!policy.screenTime.enabled) {
    const requests = await ctx.db.query("screenTimeRefreshRequests")
      .withIndex("by_child_profile_id_and_requested_at", (q) => q.eq("childProfileId", childProfileId))
      .take(50);
    for (const request of requests) await ctx.db.delete("screenTimeRefreshRequests", request._id);
    const snapshots = await ctx.db.query("screenTimeSnapshots")
      .withIndex("by_active_enrollment_id_and_status", (q) => q.eq("activeEnrollmentId", activeEnrollmentId))
      .take(50);
    for (const snapshot of snapshots) {
      const rows = await ctx.db.query("screenTimeSnapshotRows")
        .withIndex("by_screen_time_snapshot_id", (q) => q.eq("screenTimeSnapshotId", snapshot._id))
        .take(501);
      for (const row of rows) await ctx.db.delete("screenTimeSnapshotRows", row._id);
      await ctx.db.delete("screenTimeSnapshots", snapshot._id);
    }
  }
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
    appBlocking: { ...policy.appBlocking, rules: policy.appBlocking.rules ?? [] },
    safeBrowsing: policy.safeBrowsing,
    activeScreenSafety: policy.activeScreenSafety,
    locationSharing: policy.locationSharing ?? { enabled: false },
    screenTime: policy.screenTime ?? { enabled: policy.screenTimeSummariesEnabled ?? false },
  };
}
