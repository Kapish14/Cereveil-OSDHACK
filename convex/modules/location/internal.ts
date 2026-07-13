import { v } from "convex/values";
import { internalMutation } from "../../_generated/server";
import { throwAppError } from "../../lib/errors";
import {
  childDeviceActorValidator,
  requireActiveChildDeviceActor,
} from "../deviceIdentity/internal";

const TERMINAL_RETENTION_MS = 15 * 60 * 1000;

export const recordMeasurement = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({
      latitude: v.number(),
      longitude: v.number(),
      accuracyMeters: v.number(),
      capturedAt: v.number(),
      refreshRequestId: v.optional(v.id("locationRefreshRequests")),
      serverNow: v.number(),
    }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    if (
      args.input.latitude < -90 || args.input.latitude > 90 ||
      args.input.longitude < -180 || args.input.longitude > 180 ||
      !Number.isFinite(args.input.accuracyMeters) || args.input.accuracyMeters < 0 ||
      args.input.accuracyMeters > 100_000 ||
      args.input.capturedAt < args.input.serverNow - 24 * 60 * 60 * 1000 ||
      args.input.capturedAt > args.input.serverNow + 5 * 60 * 1000
    ) throwAppError("VALIDATION_FAILED");
    const state = await ctx.db
      .query("policyApplicationStates")
      .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", actor.enrollment._id))
      .unique();
    if (state?.appliedPolicyVersion === undefined) throwAppError("VALIDATION_FAILED");
    const applied = await ctx.db
      .query("supervisionPolicies")
      .withIndex("by_child_profile_id_and_version", (q) =>
        q.eq("childProfileId", actor.childProfile._id).eq("version", state.appliedPolicyVersion!),
      )
      .unique();
    const desired = (await ctx.db
      .query("supervisionPolicies")
      .withIndex("by_child_profile_id_and_version", (q) => q.eq("childProfileId", actor.childProfile._id))
      .order("desc")
      .take(1)).at(0);
    if (
      applied?.locationSharing?.enabled !== true ||
      desired?.status !== "active" ||
      desired.locationSharing?.enabled !== true
    ) throwAppError("VALIDATION_FAILED");

    let refresh = null;
    if (args.input.refreshRequestId !== undefined) {
      refresh = await ctx.db.get("locationRefreshRequests", args.input.refreshRequestId);
      if (
        refresh === null ||
        refresh.activeEnrollmentId !== actor.enrollment._id ||
        refresh.status !== "pending" ||
        refresh.expiresAt <= args.input.serverNow ||
        args.input.capturedAt < refresh.requestedAt
      ) throwAppError("VALIDATION_FAILED");
    }

    const current = await ctx.db
      .query("latestLocationStates")
      .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", actor.enrollment._id))
      .unique();
    if (current === null) {
      await ctx.db.insert("latestLocationStates", {
        householdId: actor.household._id,
        childProfileId: actor.childProfile._id,
        activeEnrollmentId: actor.enrollment._id,
        childDeviceId: actor.device._id,
        latitude: args.input.latitude,
        longitude: args.input.longitude,
        accuracyMeters: args.input.accuracyMeters,
        capturedAt: args.input.capturedAt,
        uploadedAt: args.input.serverNow,
      });
    } else if (args.input.capturedAt > current.capturedAt) {
      await ctx.db.patch("latestLocationStates", current._id, {
        latitude: args.input.latitude,
        longitude: args.input.longitude,
        accuracyMeters: args.input.accuracyMeters,
        capturedAt: args.input.capturedAt,
        uploadedAt: args.input.serverNow,
      });
    }
    if (refresh !== null) {
      await ctx.db.patch("locationRefreshRequests", refresh._id, {
        status: "completed",
        completedAt: args.input.serverNow,
        purgeAt: args.input.serverNow + TERMINAL_RETENTION_MS,
      });
    }
    return { accepted: current === null || args.input.capturedAt > current.capturedAt };
  },
});

export const failRefresh = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({
      refreshRequestId: v.id("locationRefreshRequests"),
      reason: v.union(v.literal("measurement_failed"), v.literal("capability_unavailable")),
      serverNow: v.number(),
    }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const request = await ctx.db.get("locationRefreshRequests", args.input.refreshRequestId);
    if (request === null || request.activeEnrollmentId !== actor.enrollment._id) {
      throwAppError("CHILD_DEVICE_UNAUTHORIZED");
    }
    if (request.status === "pending") {
      await ctx.db.patch("locationRefreshRequests", request._id, {
        status: "failed",
        failureReason: args.input.reason,
        completedAt: args.input.serverNow,
        purgeAt: args.input.serverNow + TERMINAL_RETENTION_MS,
      });
    }
    return { ok: true };
  },
});

export const expireRefresh = internalMutation({
  args: { requestId: v.id("locationRefreshRequests"), serverNow: v.number() },
  handler: async (ctx, args) => {
    const request = await ctx.db.get("locationRefreshRequests", args.requestId);
    if (request?.status !== "pending" || request.expiresAt > args.serverNow) return false;
    await ctx.db.patch("locationRefreshRequests", request._id, {
      status: "expired",
      completedAt: args.serverNow,
      purgeAt: args.serverNow + TERMINAL_RETENTION_MS,
    });
    return true;
  },
});
