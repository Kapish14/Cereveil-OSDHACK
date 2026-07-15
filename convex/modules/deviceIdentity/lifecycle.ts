import { v } from "convex/values";
import { internal } from "../../_generated/api";
import { Id } from "../../_generated/dataModel";
import { internalMutation, MutationCtx } from "../../_generated/server";
import { deleteEnrollmentFeatureBatch } from "../featureLifecycle/internal";

const BATCH = 50;

export const expireTokenChallenge = internalMutation({
  args: { challengeId: v.id("childDeviceTokenChallenges") },
  handler: async (ctx, args) => {
    const challenge = await ctx.db.get("childDeviceTokenChallenges", args.challengeId);
    if (challenge !== null) await ctx.db.delete("childDeviceTokenChallenges", challenge._id);
  },
});

export const purgeCredentialChallenges = internalMutation({
  args: { credentialId: v.id("childDeviceCredentials") },
  handler: async (ctx, args) => {
    const challenges = await ctx.db.query("childDeviceTokenChallenges")
      .withIndex("by_credential_id_and_status", (q) => q.eq("credentialId", args.credentialId)).take(BATCH);
    for (const challenge of challenges) await ctx.db.delete("childDeviceTokenChallenges", challenge._id);
    if (challenges.length === BATCH) {
      await ctx.scheduler.runAfter(0, internal.modules.deviceIdentity.lifecycle.purgeCredentialChallenges, args);
    }
    return challenges.length < BATCH;
  },
});

async function drainEnrollment(ctx: MutationCtx, enrollmentId: Id<"activeEnrollments">) {
  const remoteRequest = await ctx.db.query("remoteAudioRequests")
    .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", enrollmentId)).unique();
  if (remoteRequest !== null) {
    const signals = await ctx.db.query("remoteAudioSignals")
      .withIndex("by_remote_audio_request_id", (q) => q.eq("remoteAudioRequestId", remoteRequest._id)).take(70);
    for (const signal of signals) await ctx.db.delete("remoteAudioSignals", signal._id);
    await ctx.db.delete("remoteAudioRequests", remoteRequest._id);
  }
  const enrollment = await ctx.db.get("activeEnrollments", enrollmentId);
  if (enrollment !== null) {
    const cooldown = await ctx.db.query("remoteAudioCooldowns")
      .withIndex("by_child_profile_id", (q) => q.eq("childProfileId", enrollment.childProfileId)).unique();
    if (cooldown !== null) await ctx.db.delete("remoteAudioCooldowns", cooldown._id);
  }
  if (!await deleteEnrollmentFeatureBatch(ctx, enrollmentId)) return false;
  const commands = await ctx.db.query("childDeviceCommands")
    .withIndex("by_active_enrollment_id_and_status", (q) => q.eq("activeEnrollmentId", enrollmentId)).take(BATCH);
  if (commands.length > 0) {
    for (const row of commands) {
      if (row.type === "request_remote_audio") {
        const attempts = await ctx.db.query("fcmDeliveryAttempts")
          .withIndex("by_record_kind_and_record_id", (q) => q.eq("recordKind", "childDeviceCommand").eq("recordId", row._id)).take(BATCH);
        for (const attempt of attempts) await ctx.db.delete("fcmDeliveryAttempts", attempt._id);
      }
      await ctx.db.delete("childDeviceCommands", row._id);
    }
    return false;
  }
  const notice = (await ctx.db.query("guardianNotices")
    .withIndex("by_active_enrollment_id_and_episode_key", (q) => q.eq("activeEnrollmentId", enrollmentId)).take(1)).at(0);
  if (notice !== undefined) {
    const receipts = await ctx.db.query("guardianNoticeReceipts")
      .withIndex("by_guardian_notice_id_and_guardian_device_id", (q) => q.eq("guardianNoticeId", notice._id)).take(BATCH);
    for (const receipt of receipts) await ctx.db.delete("guardianNoticeReceipts", receipt._id);
    if (receipts.length === 0) await ctx.db.delete("guardianNotices", notice._id);
    return false;
  }
  return true;
}

export const purgeRevokedEnrollment = internalMutation({
  args: { activeEnrollmentId: v.id("activeEnrollments") },
  handler: async (ctx, args) => {
    const done = await drainEnrollment(ctx, args.activeEnrollmentId);
    if (!done) await ctx.scheduler.runAfter(0, internal.modules.deviceIdentity.lifecycle.purgeRevokedEnrollment, args);
    return done;
  },
});

export const finalizeEndSupervision = internalMutation({
  args: { childProfileId: v.id("childProfiles") },
  handler: async (ctx, args) => {
    const profile = await ctx.db.get("childProfiles", args.childProfileId);
    if (profile === null || profile.status !== "deleting") return true;
    const enrollment = (await ctx.db.query("activeEnrollments")
      .withIndex("by_child_profile_id_and_status", (q) => q.eq("childProfileId", profile._id)).take(1)).at(0);
    if (enrollment !== undefined) {
      if (!await drainEnrollment(ctx, enrollment._id)) {
        await ctx.scheduler.runAfter(0, internal.modules.deviceIdentity.lifecycle.finalizeEndSupervision, args);
        return false;
      }
      const credentials = await ctx.db.query("childDeviceCredentials")
        .withIndex("by_active_enrollment_id_and_status", (q) => q.eq("activeEnrollmentId", enrollment._id)).take(10);
      for (const credential of credentials) {
        const challenges = await ctx.db.query("childDeviceTokenChallenges")
          .withIndex("by_credential_id_and_status", (q) => q.eq("credentialId", credential._id)).take(BATCH);
        for (const challenge of challenges) await ctx.db.delete("childDeviceTokenChallenges", challenge._id);
        await ctx.db.delete("childDeviceCredentials", credential._id);
      }
      const tokens = await ctx.db.query("fcmTokens")
        .withIndex("by_child_device_id", (q) => q.eq("childDeviceId", enrollment.childDeviceId)).take(BATCH);
      for (const token of tokens) await ctx.db.delete("fcmTokens", token._id);
      const state = await ctx.db.query("policyApplicationStates")
        .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", enrollment._id)).unique();
      if (state !== null) await ctx.db.delete("policyApplicationStates", state._id);
      const health = await ctx.db.query("supervisionHealth")
        .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", enrollment._id)).unique();
      if (health !== null) await ctx.db.delete("supervisionHealth", health._id);
      await ctx.db.delete("activeEnrollments", enrollment._id);
      const device = await ctx.db.get("childDevices", enrollment.childDeviceId);
      if (device !== null) await ctx.db.delete("childDevices", device._id);
      await ctx.scheduler.runAfter(0, internal.modules.deviceIdentity.lifecycle.finalizeEndSupervision, args);
      return false;
    }
    const codes = await ctx.db.query("enrollmentCodes")
      .withIndex("by_child_profile_id_and_status", (q) => q.eq("childProfileId", profile._id)).take(BATCH);
    if (codes.length > 0) {
      for (const row of codes) await ctx.db.delete("enrollmentCodes", row._id);
      await ctx.scheduler.runAfter(0, internal.modules.deviceIdentity.lifecycle.finalizeEndSupervision, args);
      return false;
    }
    const policies = await ctx.db.query("supervisionPolicies")
      .withIndex("by_child_profile_id_and_version", (q) => q.eq("childProfileId", profile._id)).take(BATCH);
    if (policies.length > 0) {
      for (const row of policies) await ctx.db.delete("supervisionPolicies", row._id);
      await ctx.scheduler.runAfter(0, internal.modules.deviceIdentity.lifecycle.finalizeEndSupervision, args);
      return false;
    }
    const operations = await ctx.db.query("policySaveOperations")
      .withIndex("by_child_profile_id", (q) => q.eq("childProfileId", profile._id)).take(BATCH);
    if (operations.length > 0) {
      for (const row of operations) await ctx.db.delete("policySaveOperations", row._id);
      await ctx.scheduler.runAfter(0, internal.modules.deviceIdentity.lifecycle.finalizeEndSupervision, args);
      return false;
    }
    await ctx.db.delete("childProfiles", profile._id);
    return true;
  },
});
