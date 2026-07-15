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
import { MutationCtx } from "../../_generated/server";
import { internal } from "../../_generated/api";

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

export const replaceChildDevice = guardianMutation({
  operation: "supervision.replaceChildDevice",
  args: { childProfileId: v.id("childProfiles") },
  handler: async (ctx, actor, args) => {
    await requireGuardianForChildProfile(ctx, actor, args.childProfileId);
    const enrollment = await ctx.db.query("activeEnrollments")
      .withIndex("by_child_profile_id_and_status", (q) =>
        q.eq("childProfileId", args.childProfileId).eq("status", "active"),
      ).unique();
    if (enrollment === null) return { replaced: false };
    await revokeEnrollmentAuthority(ctx, enrollment._id, enrollment.childDeviceId, now());
    await ctx.scheduler.runAfter(0, internal.modules.deviceIdentity.lifecycle.purgeRevokedEnrollment, {
      activeEnrollmentId: enrollment._id,
    });
    return { replaced: true };
  },
});

export const endSupervision = guardianMutation({
  operation: "supervision.end",
  args: { childProfileId: v.id("childProfiles") },
  handler: async (ctx, actor, args) => {
    const profile = await ctx.db.get("childProfiles", args.childProfileId);
    if (profile === null) return { ended: true };
    if (profile.householdId !== actor.householdId) throwAppError("UNAUTHENTICATED");
    if (profile.status === "deleting") return { ended: true };
    const serverNow = now();
    await ctx.db.patch("childProfiles", profile._id, { status: "deleting", updatedAt: serverNow });
    const enrollment = await ctx.db.query("activeEnrollments")
      .withIndex("by_child_profile_id_and_status", (q) =>
        q.eq("childProfileId", profile._id).eq("status", "active"),
      ).unique();
    if (enrollment !== null) {
      await revokeEnrollmentAuthority(ctx, enrollment._id, enrollment.childDeviceId, serverNow);
    }
    await ctx.scheduler.runAfter(0, internal.modules.deviceIdentity.lifecycle.finalizeEndSupervision, {
      childProfileId: profile._id,
    });
    return { ended: true };
  },
});

async function revokeEnrollmentAuthority(
  ctx: MutationCtx,
  activeEnrollmentId: Id<"activeEnrollments">,
  childDeviceId: Id<"childDevices">,
  serverNow: number,
) {
  const credentials = await ctx.db.query("childDeviceCredentials")
    .withIndex("by_active_enrollment_id_and_status", (q) => q.eq("activeEnrollmentId", activeEnrollmentId)).take(10);
  for (const credential of credentials) {
    await ctx.db.patch("childDeviceCredentials", credential._id, { status: "revoked", revokedAt: serverNow, updatedAt: serverNow });
  }
  const tokens = await ctx.db.query("fcmTokens")
    .withIndex("by_child_device_id", (q) => q.eq("childDeviceId", childDeviceId)).take(20);
  for (const token of tokens) {
    await ctx.db.patch("fcmTokens", token._id, { status: "revoked", invalidatedAt: serverNow });
  }
  const policyState = await ctx.db.query("policyApplicationStates")
    .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", activeEnrollmentId)).unique();
  if (policyState !== null) await ctx.db.delete("policyApplicationStates", policyState._id);
  const health = await ctx.db.query("supervisionHealth")
    .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", activeEnrollmentId)).unique();
  if (health !== null) await ctx.db.delete("supervisionHealth", health._id);
  await ctx.db.patch("activeEnrollments", activeEnrollmentId, {
    status: "revoked", roleLockActive: false, revokedAt: serverNow, updatedAt: serverNow,
  });
  await ctx.db.patch("childDevices", childDeviceId, { status: "revoked", revokedAt: serverNow, updatedAt: serverNow });
}
