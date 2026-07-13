import { v } from "convex/values";
import { internal } from "../../_generated/api";
import { internalMutation, internalQuery } from "../../_generated/server";
import { throwAppError } from "../../lib/errors";
import {
  childDeviceActorValidator,
  requireActiveChildDeviceActor,
} from "../deviceIdentity/internal";

const REQUEST_LIFETIME_MS = 15 * 60 * 1000;
const DENIAL_COOLDOWN_MS = 5 * 60 * 1000;
const TERMINAL_RETENTION_MS = 24 * 60 * 60 * 1000;

export const createAccessRequest = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({
      packageName: v.string(),
      appliedPolicyVersion: v.number(),
      blockKind: v.union(v.literal("manual"), v.literal("scheduled")),
      scheduledCoverageEnd: v.optional(v.number()),
      serverNow: v.number(),
    }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const state = await ctx.db
      .query("policyApplicationStates")
      .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", actor.enrollment._id))
      .unique();
    if (state?.appliedPolicyVersion !== args.input.appliedPolicyVersion) {
      throwAppError("VALIDATION_FAILED");
    }
    const policy = await ctx.db
      .query("supervisionPolicies")
      .withIndex("by_child_profile_id_and_version", (q) =>
        q.eq("childProfileId", actor.childProfile._id).eq("version", args.input.appliedPolicyVersion),
      )
      .unique();
    const rule = policy?.appBlocking.rules?.find((candidate) =>
      candidate.packageName === args.input.packageName,
    );
    if (
      policy === null ||
      !policy.appBlocking.enabled ||
      rule === undefined ||
      (args.input.blockKind === "manual" && !rule.manualBlocked) ||
      (args.input.blockKind === "scheduled" && rule.schedules.length === 0)
    ) throwAppError("VALIDATION_FAILED");
    const desiredPolicy = (await ctx.db.query("supervisionPolicies")
      .withIndex("by_child_profile_id_and_version", (q) => q.eq("childProfileId", actor.childProfile._id))
      .order("desc").take(1)).at(0);
    const desiredRule = desiredPolicy?.appBlocking.rules?.find((candidate) =>
      candidate.packageName === args.input.packageName,
    );
    const fingerprint = effectiveBlockFingerprint(args.input.blockKind, rule);
    if (
      desiredPolicy?.status !== "active" || !desiredPolicy.appBlocking.enabled ||
      desiredRule === undefined || effectiveBlockFingerprint(args.input.blockKind, desiredRule) !== fingerprint
    ) throwAppError("VALIDATION_FAILED");

    const pending = await ctx.db
      .query("accessRequests")
      .withIndex("by_active_enrollment_id_and_package_name_and_status", (q) =>
        q
          .eq("activeEnrollmentId", actor.enrollment._id)
          .eq("packageName", args.input.packageName)
          .eq("status", "pending"),
      )
      .unique();
    if (pending !== null && pending.expiresAt > args.input.serverNow) {
      return { requestId: pending._id, expiresAt: pending.expiresAt, reused: true };
    }
    if (pending !== null) {
      await ctx.db.patch("accessRequests", pending._id, {
        status: "expired",
        resolvedAt: args.input.serverNow,
        purgeAt: args.input.serverNow + TERMINAL_RETENTION_MS,
      });
    }
    const denied = await ctx.db
      .query("accessRequests")
      .withIndex("by_active_enrollment_id_and_package_name_and_status", (q) =>
        q
          .eq("activeEnrollmentId", actor.enrollment._id)
          .eq("packageName", args.input.packageName)
          .eq("status", "denied"),
      )
      .order("desc")
      .take(1);
    const latestDenial = denied.at(0);
    if (latestDenial?.resolvedAt !== undefined && latestDenial.resolvedAt + DENIAL_COOLDOWN_MS > args.input.serverNow) {
      throwAppError("VALIDATION_FAILED");
    }
    const scheduleEnd = args.input.blockKind === "scheduled"
      ? args.input.scheduledCoverageEnd
      : undefined;
    if (
      args.input.blockKind === "scheduled" &&
      (scheduleEnd === undefined || scheduleEnd <= args.input.serverNow || scheduleEnd > args.input.serverNow + 8 * 24 * 60 * 60 * 1000)
    ) {
      throwAppError("VALIDATION_FAILED");
    }
    const expiresAt = Math.min(
      args.input.serverNow + REQUEST_LIFETIME_MS,
      scheduleEnd ?? Number.MAX_SAFE_INTEGER,
    );
    const requestId = await ctx.db.insert("accessRequests", {
      householdId: actor.household._id,
      childProfileId: actor.childProfile._id,
      activeEnrollmentId: actor.enrollment._id,
      childDeviceId: actor.device._id,
      packageName: args.input.packageName,
      appliedPolicyVersion: args.input.appliedPolicyVersion,
      blockKind: args.input.blockKind,
      ruleFingerprint: fingerprint,
      ...(scheduleEnd === undefined ? {} : { scheduledCoverageEnd: scheduleEnd }),
      status: "pending",
      createdAt: args.input.serverNow,
      expiresAt,
      purgeAt: expiresAt + TERMINAL_RETENTION_MS,
    });
    const noticeExpiresAt = args.input.serverNow + 7 * 24 * 60 * 60 * 1000;
    const noticeId = await ctx.db.insert("guardianNotices", {
      householdId: actor.household._id,
      childProfileId: actor.childProfile._id,
      activeEnrollmentId: actor.enrollment._id,
      type: "access_request",
      episodeKey: `access:${requestId}`,
      accessRequestId: requestId,
      status: "active",
      occurredAt: args.input.serverNow,
      expiresAt: noticeExpiresAt,
    });
    const devices = await ctx.db
      .query("guardianDevices")
      .withIndex("by_household_id", (q) => q.eq("householdId", actor.household._id))
      .take(3);
    for (const device of devices) {
      if (device.status !== "active") continue;
      await ctx.db.insert("guardianNoticeReceipts", {
        guardianNoticeId: noticeId,
        guardianDeviceId: device._id,
        householdId: actor.household._id,
        status: "pending",
        createdAt: args.input.serverNow,
        expiresAt: noticeExpiresAt,
      });
    }
    await ctx.scheduler.runAfter(0, internal.fcmDelivery.deliver, {
      recordKind: "guardianNotice",
      recordId: noticeId,
      attempt: 1,
    });
    await ctx.scheduler.runAt(expiresAt, internal.modules.access.internal.expireAccessRequest, {
      requestId,
      serverNow: expiresAt,
    });
    return { requestId, expiresAt, reused: false };
  },
});

export function effectiveBlockFingerprint(
  kind: "manual" | "scheduled",
  rule: { manualBlocked: boolean; schedules: Array<{ scheduleId: string; weekdays: number[]; startMinute: number; endMinute: number }> },
) {
  return kind === "manual"
    ? JSON.stringify({ kind, manualBlocked: rule.manualBlocked })
    : JSON.stringify({ kind, schedules: rule.schedules });
}

export const expireAccessRequest = internalMutation({
  args: { requestId: v.id("accessRequests"), serverNow: v.number() },
  handler: async (ctx, args) => {
    const request = await ctx.db.get("accessRequests", args.requestId);
    if (request?.status !== "pending" || request.expiresAt > args.serverNow) return false;
    await ctx.db.patch("accessRequests", request._id, {
      status: "expired",
      resolvedAt: args.serverNow,
      purgeAt: args.serverNow + TERMINAL_RETENTION_MS,
    });
    return true;
  },
});

export const reconcileAccessGrants = internalQuery({
  args: { actor: childDeviceActorValidator, input: v.object({ serverNow: v.number() }) },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const grants = await ctx.db
      .query("accessGrants")
      .withIndex("by_active_enrollment_id_and_expires_at", (q) =>
        q.eq("activeEnrollmentId", actor.enrollment._id).gt("expiresAt", args.input.serverNow),
      )
      .take(100);
    return {
      serverNow: args.input.serverNow,
      grants: grants.map((grant) => ({
        grantId: grant._id,
        packageName: grant.packageName,
        startsAt: grant.startsAt,
        expiresAt: grant.expiresAt,
      })),
    };
  },
});

export const getAccessRequestOutcome = internalQuery({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({ requestId: v.id("accessRequests"), serverNow: v.number() }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const request = await ctx.db.get("accessRequests", args.input.requestId);
    if (request === null || request.activeEnrollmentId !== actor.enrollment._id) {
      throwAppError("CHILD_DEVICE_UNAUTHORIZED");
    }
    return {
      status: request.status,
      retryAt: request.status === "denied" && request.resolvedAt !== undefined
        ? request.resolvedAt + DENIAL_COOLDOWN_MS
        : null,
    };
  },
});
