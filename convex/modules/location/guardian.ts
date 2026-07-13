import { v } from "convex/values";
import { internal } from "../../_generated/api";
import { guardianMutation, guardianQuery } from "../../lib/functionWrappers";
import { requireGuardianForChildProfile } from "../../lib/authorize";
import { throwAppError } from "../../lib/errors";
import { createFeatureCommand } from "../commands/internal";

const REFRESH_LIFETIME_MS = 60 * 1000;
const REFRESH_COOLDOWN_MS = 2 * 60 * 1000;
const TERMINAL_RETENTION_MS = 15 * 60 * 1000;

export const getLatestLocation = guardianQuery({
  operation: "location.getLatest",
  args: { childProfileId: v.id("childProfiles") },
  handler: async (ctx, actor, args) => {
    await requireGuardianForChildProfile(ctx, actor, args.childProfileId);
    const enrollment = await ctx.db
      .query("activeEnrollments")
      .withIndex("by_child_profile_id_and_status", (q) =>
        q.eq("childProfileId", args.childProfileId).eq("status", "active"),
      )
      .unique();
    if (enrollment === null) return { location: null, refresh: null, serverNow: Date.now() };
    const [location, refreshRows] = await Promise.all([
      ctx.db.query("latestLocationStates")
        .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", enrollment._id))
        .unique(),
      ctx.db.query("locationRefreshRequests")
        .withIndex("by_child_profile_id_and_requested_at", (q) => q.eq("childProfileId", args.childProfileId))
        .order("desc").take(1),
    ]);
    const refresh = refreshRows.at(0);
    return {
      location: location === null ? null : {
        latitude: location.latitude,
        longitude: location.longitude,
        accuracyMeters: location.accuracyMeters,
        capturedAt: location.capturedAt,
      },
      refresh: refresh === undefined ? null : {
        requestId: refresh._id,
        status: refresh.status,
        failureReason: refresh.failureReason ?? null,
        requestedAt: refresh.requestedAt,
        expiresAt: refresh.expiresAt,
      },
      serverNow: Date.now(),
    };
  },
});

export const requestLocationRefresh = guardianMutation({
  operation: "location.requestRefresh",
  args: { childProfileId: v.id("childProfiles") },
  handler: async (ctx, actor, args) => {
    await requireGuardianForChildProfile(ctx, actor, args.childProfileId);
    const enrollment = await ctx.db
      .query("activeEnrollments")
      .withIndex("by_child_profile_id_and_status", (q) =>
        q.eq("childProfileId", args.childProfileId).eq("status", "active"),
      )
      .unique();
    if (enrollment === null) throwAppError("VALIDATION_FAILED");
    const [state, health] = await Promise.all([
      ctx.db.query("policyApplicationStates")
        .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", enrollment._id))
        .unique(),
      ctx.db.query("supervisionHealth")
        .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", enrollment._id))
        .unique(),
    ]);
    if (state === null || state.appliedPolicyVersion === undefined || health === null) {
      throwAppError("VALIDATION_FAILED");
    }
    const [desired, applied] = await Promise.all([
      policyVersion(ctx, args.childProfileId, state.desiredPolicyVersion),
      policyVersion(ctx, args.childProfileId, state.appliedPolicyVersion),
    ]);
    if (
      desired?.locationSharing?.enabled !== true ||
      applied?.locationSharing?.enabled !== true ||
      health.connectivityStatus !== "online" ||
      health.capabilities?.location !== true ||
      health.capabilities?.notificationAccess !== true
    ) throwAppError("VALIDATION_FAILED");
    const serverNow = Date.now();
    const pending = await ctx.db
      .query("locationRefreshRequests")
      .withIndex("by_child_profile_id_and_status", (q) =>
        q.eq("childProfileId", args.childProfileId).eq("status", "pending"),
      )
      .unique();
    if (pending !== null && pending.expiresAt > serverNow) return { requestId: pending._id, expiresAt: pending.expiresAt, reused: true };
    if (pending !== null) {
      await ctx.db.patch("locationRefreshRequests", pending._id, {
        status: "expired",
        completedAt: serverNow,
        purgeAt: serverNow + TERMINAL_RETENTION_MS,
      });
    }
    const latest = await ctx.db
      .query("locationRefreshRequests")
      .withIndex("by_child_profile_id_and_requested_at", (q) => q.eq("childProfileId", args.childProfileId))
      .order("desc")
      .take(1);
    if ((latest.at(0)?.requestedAt ?? 0) + REFRESH_COOLDOWN_MS > serverNow) {
      throwAppError("VALIDATION_FAILED");
    }
    const requestId = await ctx.db.insert("locationRefreshRequests", {
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
      type: "refresh_location",
      referenceId: requestId,
      intentKey: "refresh_location",
      serverNow,
      lifetimeMs: REFRESH_LIFETIME_MS,
    });
    await ctx.scheduler.runAt(serverNow + REFRESH_LIFETIME_MS, internal.modules.location.internal.expireRefresh, {
      requestId,
      serverNow: serverNow + REFRESH_LIFETIME_MS,
    });
    return { requestId, expiresAt: serverNow + REFRESH_LIFETIME_MS, reused: false };
  },
});

async function policyVersion(
  ctx: Parameters<typeof requireGuardianForChildProfile>[0],
  childProfileId: Parameters<typeof requireGuardianForChildProfile>[2],
  version: number,
) {
  return await ctx.db.query("supervisionPolicies")
    .withIndex("by_child_profile_id_and_version", (q) =>
      q.eq("childProfileId", childProfileId).eq("version", version),
    )
    .unique();
}
