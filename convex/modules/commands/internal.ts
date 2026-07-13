import { v } from "convex/values";
import { paginationOptsValidator } from "convex/server";
import { internalMutation, internalQuery, MutationCtx } from "../../_generated/server";
import { Id } from "../../_generated/dataModel";
import { internal } from "../../_generated/api";
import { throwAppError } from "../../lib/errors";
import { childDeviceActorValidator, requireActiveChildDeviceActor } from "../deviceIdentity/internal";

export const reconcileCommands = internalQuery({
  args: { actor: childDeviceActorValidator, input: v.object({ paginationOpts: paginationOptsValidator }) },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    if (!Number.isInteger(args.input.paginationOpts.numItems) || args.input.paginationOpts.numItems < 1 || args.input.paginationOpts.numItems > 50) {
      throwAppError("VALIDATION_FAILED");
    }
    const result = await ctx.db
      .query("childDeviceCommands")
      .withIndex("by_active_enrollment_id_and_status", (q) =>
        q.eq("activeEnrollmentId", actor.enrollment._id).eq("status", "pending"),
      )
      .paginate(args.input.paginationOpts);
    return {
      commands: result.page.filter((command) => command.expiresAt > Date.now() && validCommandPayload(command)).map((command) => command.type === "apply_policy_version"
        ? {
            commandId: command._id,
            type: command.type,
            policyVersion: command.policyVersion,
            expiresAt: command.expiresAt,
          }
        : {
            commandId: command._id,
            type: command.type,
            referenceId: command.referenceId,
            expiresAt: command.expiresAt,
          }),
      continueCursor: result.continueCursor,
      isDone: result.isDone,
    };
  },
});

export const rejectCommand = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({
      commandId: v.id("childDeviceCommands"),
      reason: v.union(
        v.literal("unsupported_command"),
        v.literal("invalid_command"),
        v.literal("unable_to_apply"),
        v.literal("unsupported_schema"),
      ),
      serverNow: v.number(),
    }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const command = await ctx.db.get("childDeviceCommands", args.input.commandId);
    if (command === null || command.activeEnrollmentId !== actor.enrollment._id) {
      throwAppError("CHILD_DEVICE_UNAUTHORIZED");
    }
    if (command.status !== "pending") return { ok: true };
    await ctx.db.patch("childDeviceCommands", command._id, {
      status: "rejected",
      rejectionReason: args.input.reason,
      updatedAt: args.input.serverNow,
    });
    if (command.type === "apply_policy_version") {
      const state = await ctx.db
        .query("policyApplicationStates")
        .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", actor.enrollment._id))
        .unique();
      if (
        state !== null &&
        state.desiredPolicyVersion === command.policyVersion &&
        args.input.reason !== "unsupported_command"
      ) {
        await ctx.db.patch("policyApplicationStates", state._id, {
          status: "failed",
          failureReason: args.input.reason === "unsupported_schema"
            ? "unsupported_schema"
            : args.input.reason === "invalid_command"
              ? "invalid_policy"
              : "activation_failed",
          updatedAt: args.input.serverNow,
        });
      }
    }
    return { ok: true };
  },
});

export async function createFeatureCommand(
  ctx: MutationCtx,
  args: {
    householdId: Id<"households">;
    childProfileId: Id<"childProfiles">;
    activeEnrollmentId: Id<"activeEnrollments">;
    childDeviceId: Id<"childDevices">;
    type: "refresh_location" | "refresh_screen_time" | "reconcile_access_grants";
    referenceId: string;
    intentKey: string;
    serverNow: number;
    lifetimeMs: number;
  },
) {
  const existing = await ctx.db
    .query("childDeviceCommands")
    .withIndex("by_active_enrollment_id_and_intent_key", (q) =>
      q.eq("activeEnrollmentId", args.activeEnrollmentId).eq("intentKey", args.intentKey),
    )
    .order("desc")
    .take(1);
  const current = existing.at(0);
  if (
    current?.status === "pending" &&
    current.expiresAt > args.serverNow &&
    current.type === args.type &&
    current.referenceId === args.referenceId
  ) return current._id;
  if (current?.status === "pending") {
    await ctx.db.patch("childDeviceCommands", current._id, {
      status: current.expiresAt <= args.serverNow ? "expired" : "superseded",
      updatedAt: args.serverNow,
    });
  }
  const commandId = await ctx.db.insert("childDeviceCommands", {
    householdId: args.householdId,
    childProfileId: args.childProfileId,
    activeEnrollmentId: args.activeEnrollmentId,
    childDeviceId: args.childDeviceId,
    type: args.type,
    referenceId: args.referenceId,
    intentKey: args.intentKey,
    status: "pending",
    createdAt: args.serverNow,
    updatedAt: args.serverNow,
    expiresAt: args.serverNow + args.lifetimeMs,
  });
  await ctx.scheduler.runAfter(0, internal.fcmDelivery.deliver, {
    recordKind: "childDeviceCommand",
    recordId: commandId,
    attempt: 1,
  });
  return commandId;
}

export const acknowledgeFeatureCommand = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({
      commandId: v.id("childDeviceCommands"),
      serverNow: v.number(),
    }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const command = await ctx.db.get("childDeviceCommands", args.input.commandId);
    if (command === null || command.activeEnrollmentId !== actor.enrollment._id) {
      throwAppError("CHILD_DEVICE_UNAUTHORIZED");
    }
    if (command.status === "pending") {
      if (command.type === "apply_policy_version" || !validCommandPayload(command)) {
        throwAppError("VALIDATION_FAILED");
      }
      if (!await featureResultIsDurable(ctx, command)) throwAppError("VALIDATION_FAILED");
      await ctx.db.patch("childDeviceCommands", command._id, {
        status: "acknowledged",
        acknowledgedAt: args.input.serverNow,
        updatedAt: args.input.serverNow,
      });
    }
    return { ok: true };
  },
});

function validCommandPayload(command: {
  type: "apply_policy_version" | "refresh_location" | "refresh_screen_time" | "reconcile_access_grants";
  policyVersion?: number;
  referenceId?: string;
}) {
  return command.type === "apply_policy_version"
    ? Number.isInteger(command.policyVersion) && command.referenceId === undefined
    : command.policyVersion === undefined && typeof command.referenceId === "string" && command.referenceId.length > 0;
}

async function featureResultIsDurable(
  ctx: MutationCtx,
  command: { type: string; referenceId?: string },
) {
  const referenceId = command.referenceId!;
  if (command.type === "refresh_location") {
    const id = ctx.db.normalizeId("locationRefreshRequests", referenceId);
    const request = id === null ? null : await ctx.db.get("locationRefreshRequests", id);
    return request !== null && request.status !== "pending";
  }
  if (command.type === "refresh_screen_time") {
    const id = ctx.db.normalizeId("screenTimeRefreshRequests", referenceId);
    const request = id === null ? null : await ctx.db.get("screenTimeRefreshRequests", id);
    return request?.status === "completed";
  }
  if (command.type === "reconcile_access_grants") {
    const requestId = ctx.db.normalizeId("accessRequests", referenceId);
    const request = requestId === null ? null : await ctx.db.get("accessRequests", requestId);
    return request !== null && request.status !== "pending";
  }
  return false;
}
