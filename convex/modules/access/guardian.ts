import { v } from "convex/values";
import { internal } from "../../_generated/api";
import { guardianMutation, guardianQuery } from "../../lib/functionWrappers";
import { requireGuardianForChildProfile } from "../../lib/authorize";
import { throwAppError } from "../../lib/errors";
import { createFeatureCommand } from "../commands/internal";
import { effectiveBlockFingerprint } from "./internal";

const TERMINAL_RETENTION_MS = 24 * 60 * 60 * 1000;
const GRANT_DURATIONS = new Set([15, 30, 45, 60]);

export const listPendingAccessRequests = guardianQuery({
  operation: "access.listPending",
  args: { childProfileId: v.id("childProfiles") },
  handler: async (ctx, actor, args) => {
    await requireGuardianForChildProfile(ctx, actor, args.childProfileId);
    const requests = await ctx.db
      .query("accessRequests")
      .withIndex("by_child_profile_id_and_status", (q) =>
        q.eq("childProfileId", args.childProfileId).eq("status", "pending"),
      )
      .take(50);
    return requests.map((request) => ({
      requestId: request._id,
      packageName: request.packageName,
      blockKind: request.blockKind,
      scheduledCoverageEnd: request.scheduledCoverageEnd ?? null,
      createdAt: request.createdAt,
      expiresAt: request.expiresAt,
    }));
  },
});

export const resolveAccessRequest = guardianMutation({
  operation: "access.resolve",
  args: {
    requestId: v.id("accessRequests"),
    decision: v.union(v.literal("approve"), v.literal("deny")),
    durationMinutes: v.optional(v.number()),
    untilBlockEnds: v.optional(v.boolean()),
  },
  handler: async (ctx, actor, args) => {
    const request = await ctx.db.get("accessRequests", args.requestId);
    if (request === null || request.householdId !== actor.householdId) throwAppError("UNAUTHENTICATED");
    if (request.status !== "pending") return { status: request.status, grant: null };
    const serverNow = Date.now();
    if (request.expiresAt <= serverNow) {
      await ctx.db.patch("accessRequests", request._id, {
        status: "expired",
        resolvedAt: serverNow,
        purgeAt: serverNow + TERMINAL_RETENTION_MS,
      });
      return { status: "expired" as const, grant: null };
    }
    const activePolicy = await ctx.db
      .query("supervisionPolicies")
      .withIndex("by_child_profile_id_and_version", (q) => q.eq("childProfileId", request.childProfileId))
      .order("desc")
      .take(1);
    const current = activePolicy.at(0);
    const rule = current?.appBlocking.rules?.find((candidate) => candidate.packageName === request.packageName);
    const fingerprint = rule === undefined ? null : effectiveBlockFingerprint(request.blockKind, rule);
    if (
      current?.status !== "active" || !current.appBlocking.enabled || rule === undefined ||
      (request.blockKind === "manual" && !rule.manualBlocked) ||
      (request.blockKind === "scheduled" && rule.schedules.length === 0) ||
      (request.ruleFingerprint !== undefined && fingerprint !== request.ruleFingerprint) ||
      (request.blockKind === "scheduled" && (request.scheduledCoverageEnd ?? 0) <= serverNow)
    ) {
      await ctx.db.patch("accessRequests", request._id, {
        status: "expired",
        resolvedAt: serverNow,
        purgeAt: serverNow + TERMINAL_RETENTION_MS,
      });
      return { status: "expired" as const, grant: null };
    }
    if (args.decision === "deny") {
      await ctx.db.patch("accessRequests", request._id, {
        status: "denied",
        resolvedAt: serverNow,
        purgeAt: serverNow + TERMINAL_RETENTION_MS,
      });
      await createFeatureCommand(ctx, {
        householdId: request.householdId,
        childProfileId: request.childProfileId,
        activeEnrollmentId: request.activeEnrollmentId,
        childDeviceId: request.childDeviceId,
        type: "reconcile_access_grants",
        referenceId: request._id,
        intentKey: `access_outcome:${request._id}`,
        serverNow,
        lifetimeMs: 15 * 60 * 1000,
      });
      return { status: "denied" as const, grant: null };
    }
    const durationMinutes = args.durationMinutes;
    if (durationMinutes === undefined || !GRANT_DURATIONS.has(durationMinutes)) {
      throwAppError("VALIDATION_FAILED");
    }
    let expiresAt = serverNow + durationMinutes * 60 * 1000;
    if (request.blockKind === "scheduled") {
      const coverageEnd = request.scheduledCoverageEnd;
      if (coverageEnd === undefined || coverageEnd <= serverNow) throwAppError("VALIDATION_FAILED");
      expiresAt = args.untilBlockEnds === true ? coverageEnd : Math.min(expiresAt, coverageEnd);
    }
    const grantId = await ctx.db.insert("accessGrants", {
      householdId: request.householdId,
      childProfileId: request.childProfileId,
      activeEnrollmentId: request.activeEnrollmentId,
      childDeviceId: request.childDeviceId,
      accessRequestId: request._id,
      packageName: request.packageName,
      startsAt: serverNow,
      expiresAt,
      createdAt: serverNow,
      purgeAt: expiresAt + TERMINAL_RETENTION_MS,
    });
    await ctx.db.patch("accessRequests", request._id, {
      status: "approved",
      resolvedAt: serverNow,
      purgeAt: serverNow + TERMINAL_RETENTION_MS,
    });
    await createFeatureCommand(ctx, {
      householdId: request.householdId,
      childProfileId: request.childProfileId,
      activeEnrollmentId: request.activeEnrollmentId,
      childDeviceId: request.childDeviceId,
      type: "reconcile_access_grants",
      referenceId: request._id,
      intentKey: "reconcile_access_grants",
      serverNow,
      lifetimeMs: 7 * 24 * 60 * 60 * 1000,
    });
    return { status: "approved" as const, grant: { grantId, startsAt: serverNow, expiresAt } };
  },
});
