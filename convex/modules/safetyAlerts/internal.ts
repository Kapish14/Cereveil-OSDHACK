import { v } from "convex/values";
import { internal } from "../../_generated/api";
import { internalMutation } from "../../_generated/server";
import { throwAppError } from "../../lib/errors";
import {
  childDeviceActorValidator,
  requireActiveChildDeviceActor,
} from "../deviceIdentity/internal";

const RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
const NOTICE_FRESHNESS_MS = 5 * 60 * 1000;
const NOTICE_COOLDOWN_MS = 2 * 60 * 1000;

export const createSafetyAlert = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({
      incidentId: v.string(),
      type: v.union(v.literal("scam_text"), v.literal("nsfw_screen")),
      packageName: v.string(),
      confidenceBand: v.union(v.literal("low"), v.literal("medium"), v.literal("high")),
      policyVersion: v.number(),
      occurredAt: v.number(),
      serverNow: v.number(),
    }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const input = args.input;
    if (
      input.incidentId.length < 16 || input.incidentId.length > 200 ||
      input.packageName.length < 3 || input.packageName.length > 255 ||
      !Number.isInteger(input.policyVersion) || input.policyVersion < 1 ||
      !Number.isFinite(input.occurredAt) ||
      input.occurredAt > input.serverNow + 5 * 60 * 1000 ||
      input.occurredAt < input.serverNow - RETENTION_MS
    ) throwAppError("VALIDATION_FAILED");

    const existing = await ctx.db.query("safetyAlerts")
      .withIndex("by_active_enrollment_id_and_incident_id", (q) =>
        q.eq("activeEnrollmentId", actor.enrollment._id).eq("incidentId", input.incidentId),
      ).unique();
    if (existing !== null) return { safetyAlertId: existing._id, created: false };

    const application = await ctx.db.query("policyApplicationStates")
      .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", actor.enrollment._id))
      .unique();
    if (application?.status !== "applied" || application.appliedPolicyVersion !== input.policyVersion) {
      throwAppError("VALIDATION_FAILED");
    }
    const policy = await ctx.db.query("supervisionPolicies")
      .withIndex("by_child_profile_id_and_version", (q) =>
        q.eq("childProfileId", actor.enrollment.childProfileId).eq("version", input.policyVersion),
      ).unique();
    if (policy === null || !("scamText" in policy.activeScreenSafety)) throwAppError("VALIDATION_FAILED");
    const detector = input.type === "scam_text"
      ? policy.activeScreenSafety.scamText
      : policy.activeScreenSafety.nsfwScreen;
    if (!detector.enabled || !detector.monitoredPackageNames.includes(input.packageName)) {
      throwAppError("VALIDATION_FAILED");
    }

    const generation = await ctx.db.query("appCatalogGenerations")
      .withIndex("by_active_enrollment_id_and_status", (q) =>
        q.eq("activeEnrollmentId", actor.enrollment._id).eq("status", "current"),
      ).unique();
    if (generation === null) throwAppError("VALIDATION_FAILED");
    const entry = await ctx.db.query("appCatalogEntries")
      .withIndex("by_app_catalog_generation_id_and_package_name", (q) =>
        q.eq("appCatalogGenerationId", generation._id).eq("packageName", input.packageName),
      ).unique();
    if (entry === null) throwAppError("VALIDATION_FAILED");

    const expiresAt = input.serverNow + RETENTION_MS;
    const safetyAlertId = await ctx.db.insert("safetyAlerts", {
      householdId: actor.enrollment.householdId,
      childProfileId: actor.enrollment.childProfileId,
      activeEnrollmentId: actor.enrollment._id,
      childDeviceId: actor.device._id,
      incidentId: input.incidentId,
      type: input.type,
      packageName: input.packageName,
      appLabel: entry.label,
      confidenceBand: input.confidenceBand,
      policyVersion: input.policyVersion,
      occurredAt: input.occurredAt,
      createdAt: input.serverNow,
      expiresAt,
    });

    let guardianNoticeId = null;
    if (input.serverNow - input.occurredAt <= NOTICE_FRESHNESS_MS) {
      const cooldown = await ctx.db.query("safetyNotificationCooldowns")
        .withIndex("by_child_profile_id_and_type", (q) =>
          q.eq("childProfileId", actor.enrollment.childProfileId).eq("type", input.type),
        ).unique();
      if (cooldown === null || cooldown.nextAllowedAt <= input.serverNow) {
        if (cooldown === null) {
          await ctx.db.insert("safetyNotificationCooldowns", {
            householdId: actor.enrollment.householdId,
            childProfileId: actor.enrollment.childProfileId,
            activeEnrollmentId: actor.enrollment._id,
            type: input.type,
            nextAllowedAt: input.serverNow + NOTICE_COOLDOWN_MS,
            updatedAt: input.serverNow,
          });
        } else {
          await ctx.db.patch("safetyNotificationCooldowns", cooldown._id, {
            activeEnrollmentId: actor.enrollment._id,
            nextAllowedAt: input.serverNow + NOTICE_COOLDOWN_MS,
            updatedAt: input.serverNow,
          });
        }
        guardianNoticeId = await ctx.db.insert("guardianNotices", {
          householdId: actor.enrollment.householdId,
          childProfileId: actor.enrollment.childProfileId,
          activeEnrollmentId: actor.enrollment._id,
          type: "safety",
          episodeKey: `safety:${safetyAlertId}`,
          safetyAlertId,
          status: "active",
          occurredAt: input.occurredAt,
          expiresAt,
        });
        const devices = await ctx.db.query("guardianDevices")
          .withIndex("by_household_id", (q) => q.eq("householdId", actor.enrollment.householdId))
          .take(10);
        for (const device of devices) {
          if (device.status !== "active") continue;
          await ctx.db.insert("guardianNoticeReceipts", {
            guardianNoticeId,
            guardianDeviceId: device._id,
            householdId: actor.enrollment.householdId,
            status: "pending",
            createdAt: input.serverNow,
            expiresAt,
          });
        }
        await ctx.scheduler.runAfter(0, internal.fcmDelivery.deliver, {
          recordKind: "guardianNotice",
          recordId: guardianNoticeId,
          attempt: 1,
        });
      }
    }
    return { safetyAlertId, guardianNoticeId, created: true };
  },
});
