import { v } from "convex/values";
import { Id } from "../../_generated/dataModel";
import {
  requireGuardianForChildProfile,
  requireUnenrolledChildProfile,
} from "../../lib/authorize";
import { randomBase64Url, sha256Base64Url } from "../../lib/encoding";
import { throwAppError } from "../../lib/errors";
import { guardianMutation, guardianQuery } from "../../lib/functionWrappers";
import { now } from "../../lib/time";

const CODE_LIFETIME_MS = 5 * 60 * 1000;

export const createEnrollmentCode = guardianMutation({
  operation: "enrollmentCodes.create",
  args: { childProfileId: v.id("childProfiles") },
  handler: async (ctx, actor, args: { childProfileId: Id<"childProfiles"> }) => {
    const serverNow = now();
    const childProfile = await requireGuardianForChildProfile(ctx, actor, args.childProfileId);
    await requireUnenrolledChildProfile(ctx, childProfile._id);

    const priorCodes = await ctx.db
      .query("enrollmentCodes")
      .withIndex("by_child_profile_id_and_status", (q) =>
        q.eq("childProfileId", childProfile._id).eq("status", "active"),
      )
      .take(10);
    for (const priorCode of priorCodes) {
      await ctx.db.patch("enrollmentCodes", priorCode._id, {
        status: "revoked",
        revokedAt: serverNow,
        revokedByGuardianAccountId: actor.guardianAccountId,
        revokedByGuardianDeviceId: actor.guardianDeviceId,
      });
    }

    const code = randomBase64Url(16);
    const enrollmentCodeId = await ctx.db.insert("enrollmentCodes", {
      householdId: actor.householdId,
      childProfileId: childProfile._id,
      codeHash: await sha256Base64Url(code),
      status: "active",
      expiresAt: serverNow + CODE_LIFETIME_MS,
      createdByGuardianAccountId: actor.guardianAccountId,
      createdByGuardianDeviceId: actor.guardianDeviceId,
      createdAt: serverNow,
    });

    return {
      enrollmentCodeId,
      code,
      qrPayload: JSON.stringify({
        type: "cereveil.child-enrollment",
        version: 1,
        code,
      }),
      expiresAt: serverNow + CODE_LIFETIME_MS,
      serverNow,
    };
  },
});

export const cancelEnrollmentCode = guardianMutation({
  operation: "enrollmentCodes.cancel",
  args: { enrollmentCodeId: v.id("enrollmentCodes") },
  handler: async (ctx, actor, args: { enrollmentCodeId: Id<"enrollmentCodes"> }) => {
    const enrollmentCode = await ctx.db.get("enrollmentCodes", args.enrollmentCodeId);
    if (enrollmentCode === null || enrollmentCode.householdId !== actor.householdId) {
      throwAppError("UNAUTHENTICATED");
    }
    if (enrollmentCode.status !== "active") return null;

    await ctx.db.patch("enrollmentCodes", enrollmentCode._id, {
      status: "revoked",
      revokedAt: now(),
      revokedByGuardianAccountId: actor.guardianAccountId,
      revokedByGuardianDeviceId: actor.guardianDeviceId,
    });
    return null;
  },
});

export const getEnrollmentSummary = guardianQuery({
  operation: "enrollmentCodes.summary",
  args: { childProfileId: v.id("childProfiles") },
  handler: async (ctx, actor, args: { childProfileId: Id<"childProfiles"> }) => {
    await requireGuardianForChildProfile(ctx, actor, args.childProfileId);

    const enrollment = await ctx.db
      .query("activeEnrollments")
      .withIndex("by_child_profile_id_and_status", (q) =>
        q.eq("childProfileId", args.childProfileId).eq("status", "active"),
      )
      .unique();
    if (enrollment === null) {
      return {
        enrollmentStatus: "unenrolled" as const,
        policyStatus: "not_applicable" as const,
        connectivityStatus: "not_applicable" as const,
        protectionHealthStatus: "not_applicable" as const,
        serverNow: now(),
      };
    }

    const [policyState, health] = await Promise.all([
      ctx.db
        .query("policyApplicationStates")
        .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", enrollment._id))
        .unique(),
      ctx.db
        .query("supervisionHealth")
        .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", enrollment._id))
        .unique(),
    ]);
    return {
      enrollmentStatus: "active" as const,
      policyStatus: policyState?.status ?? ("pending" as const),
      connectivityStatus: health?.connectivityStatus ?? ("pending" as const),
      protectionHealthStatus: health?.protectionStatus ?? ("pending" as const),
      capabilities: health?.capabilities,
      lastHeartbeatAt: health?.lastHeartbeatAt,
      serverNow: now(),
    };
  },
});
