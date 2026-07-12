import { v } from "convex/values";
import { internalMutation, internalQuery } from "../../_generated/server";
import {
  childDeviceActorValidator,
  requireActiveChildDeviceActor,
} from "../deviceIdentity/internal";
import { throwAppError } from "../../lib/errors";

export const getCurrentPolicy = internalQuery({
  args: { actor: childDeviceActorValidator, input: v.object({}) },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const policies = await ctx.db
      .query("supervisionPolicies")
      .withIndex("by_child_profile_id_and_version", (q) =>
        q.eq("childProfileId", actor.enrollment.childProfileId),
      )
      .order("desc")
      .take(1);
    const policy = policies.at(0);
    if (policy === undefined || policy.status !== "active") throwAppError("INTERNAL_ERROR");
    return {
      version: policy.version,
      schemaVersion: policy.schemaVersion,
      appBlocking: policy.appBlocking,
      safeBrowsing: policy.safeBrowsing,
      activeScreenSafety: policy.activeScreenSafety,
      screenTimeSummariesEnabled: policy.screenTimeSummariesEnabled,
    };
  },
});

export const acknowledgePolicy = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({ appliedPolicyVersion: v.number(), serverNow: v.number() }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const state = await ctx.db
      .query("policyApplicationStates")
      .withIndex("by_active_enrollment_id", (q) =>
        q.eq("activeEnrollmentId", actor.enrollment._id),
      )
      .unique();
    if (state === null) throwAppError("INTERNAL_ERROR");
    if (state.desiredPolicyVersion !== args.input.appliedPolicyVersion) {
      throwAppError("POLICY_VERSION_MISMATCH");
    }
    await ctx.db.patch("policyApplicationStates", state._id, {
      appliedPolicyVersion: args.input.appliedPolicyVersion,
      status: "applied",
      failureReason: undefined,
      updatedAt: args.input.serverNow,
    });
    const commands = await ctx.db
      .query("childDeviceCommands")
      .withIndex("by_active_enrollment_id_and_status", (q) =>
        q.eq("activeEnrollmentId", actor.enrollment._id).eq("status", "pending"),
      )
      .take(50);
    for (const command of commands) {
      if (command.type === "apply_policy_version" && command.policyVersion === args.input.appliedPolicyVersion) {
        await ctx.db.patch("childDeviceCommands", command._id, {
          status: "acknowledged",
          acknowledgedAt: args.input.serverNow,
          updatedAt: args.input.serverNow,
        });
      }
    }
    return true;
  },
});
