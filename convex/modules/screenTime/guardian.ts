import { v } from "convex/values";
import { internal } from "../../_generated/api";
import { guardianMutation } from "../../lib/functionWrappers";
import { requireGuardianForChildProfile } from "../../lib/authorize";
import { throwAppError } from "../../lib/errors";
import { createFeatureCommand } from "../commands/internal";

const REFRESH_LIFETIME_MS = 2 * 60 * 1000;
const FRESH_FOR_MS = 2 * 60 * 1000;
const TERMINAL_RETENTION_MS = 15 * 60 * 1000;

export const getOrRequestScreenTime = guardianMutation({
  operation: "screenTime.getOrRequest",
  args: { childProfileId: v.id("childProfiles"), force: v.optional(v.boolean()) },
  handler: async (ctx, actor, args) => {
    await requireGuardianForChildProfile(ctx, actor, args.childProfileId);
    const enrollment = await ctx.db.query("activeEnrollments")
      .withIndex("by_child_profile_id_and_status", (q) =>
        q.eq("childProfileId", args.childProfileId).eq("status", "active"),
      ).unique();
    if (enrollment === null) throwAppError("VALIDATION_FAILED");
    const serverNow = Date.now();
    const [state, health, current] = await Promise.all([
      ctx.db.query("policyApplicationStates")
        .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", enrollment._id)).unique(),
      ctx.db.query("supervisionHealth")
        .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", enrollment._id)).unique(),
      ctx.db.query("screenTimeSnapshots")
        .withIndex("by_active_enrollment_id_and_status", (q) =>
          q.eq("activeEnrollmentId", enrollment._id).eq("status", "current"),
        ).unique(),
    ]);
    if (state === null || state.appliedPolicyVersion === undefined || health === null) throwAppError("VALIDATION_FAILED");
    const [desired, applied] = await Promise.all([
      policyVersion(ctx, args.childProfileId, state.desiredPolicyVersion),
      policyVersion(ctx, args.childProfileId, state.appliedPolicyVersion),
    ]);
    if (
      desired?.screenTime?.enabled !== true || applied?.screenTime?.enabled !== true ||
      health.connectivityStatus !== "online" || health.capabilities?.usageAccess !== true
    ) throwAppError("VALIDATION_FAILED");
    let request = await ctx.db.query("screenTimeRefreshRequests")
      .withIndex("by_child_profile_id_and_status", (q) =>
        q.eq("childProfileId", args.childProfileId).eq("status", "pending"),
      ).unique();
    const needsRefresh = args.force === true || current === null || current.validUntil <= serverNow ||
      current.measuredAt + FRESH_FOR_MS <= serverNow;
    if (needsRefresh && (request === null || request.expiresAt <= serverNow)) {
      if (request !== null) {
        await ctx.db.patch("screenTimeRefreshRequests", request._id, {
          status: "expired",
          completedAt: serverNow,
          purgeAt: serverNow + TERMINAL_RETENTION_MS,
        });
      }
      const requestId = await ctx.db.insert("screenTimeRefreshRequests", {
        householdId: actor.householdId,
        childProfileId: args.childProfileId,
        activeEnrollmentId: enrollment._id,
        childDeviceId: enrollment.childDeviceId,
        status: "pending",
        requestedAt: serverNow,
        expiresAt: serverNow + REFRESH_LIFETIME_MS,
        purgeAt: serverNow + REFRESH_LIFETIME_MS + TERMINAL_RETENTION_MS,
      });
      await createFeatureCommand(ctx, {
        householdId: actor.householdId,
        childProfileId: args.childProfileId,
        activeEnrollmentId: enrollment._id,
        childDeviceId: enrollment.childDeviceId,
        type: "refresh_screen_time",
        referenceId: requestId,
        intentKey: "refresh_screen_time",
        serverNow,
        lifetimeMs: REFRESH_LIFETIME_MS,
      });
      request = await ctx.db.get("screenTimeRefreshRequests", requestId);
      await ctx.scheduler.runAt(serverNow + REFRESH_LIFETIME_MS, internal.modules.screenTime.internal.expireRefresh, {
        requestId,
        serverNow: serverNow + REFRESH_LIFETIME_MS,
      });
    }
    const rows = current === null || current.validUntil <= serverNow
      ? []
      : await ctx.db.query("screenTimeSnapshotRows")
          .withIndex("by_screen_time_snapshot_id", (q) => q.eq("screenTimeSnapshotId", current._id))
          .take(501);
    const generation = await ctx.db.query("appCatalogGenerations")
      .withIndex("by_active_enrollment_id_and_status", (q) =>
        q.eq("activeEnrollmentId", enrollment._id).eq("status", "current"),
      ).unique();
    const apps = [];
    for (const row of rows) {
      const entry = generation === null ? null : await ctx.db.query("appCatalogEntries")
        .withIndex("by_app_catalog_generation_id_and_package_name", (q) =>
          q.eq("appCatalogGenerationId", generation._id).eq("packageName", row.packageName),
        ).unique();
      apps.push({ packageName: row.packageName, label: entry?.label ?? row.packageName, totalTimeInForegroundMs: row.totalTimeInForegroundMs });
    }
    apps.sort((left, right) => right.totalTimeInForegroundMs - left.totalTimeInForegroundMs);
    return {
      snapshot: current === null || current.validUntil <= serverNow ? null : {
        measuredAt: current.measuredAt,
        localDayStart: current.localDayStart,
        validUntil: current.validUntil,
        apps,
      },
      refresh: request === null ? null : { requestId: request._id, requestedAt: request.requestedAt, expiresAt: request.expiresAt },
      serverNow,
    };
  },
});

async function policyVersion(
  ctx: Parameters<typeof requireGuardianForChildProfile>[0],
  childProfileId: Parameters<typeof requireGuardianForChildProfile>[2],
  version: number,
) {
  return await ctx.db.query("supervisionPolicies")
    .withIndex("by_child_profile_id_and_version", (q) => q.eq("childProfileId", childProfileId).eq("version", version))
    .unique();
}
