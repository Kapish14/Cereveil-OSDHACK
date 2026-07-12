import { v } from "convex/values";
import { paginationOptsValidator } from "convex/server";
import { internalMutation, internalQuery } from "../../_generated/server";
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
      commands: result.page.map((command) => ({
        commandId: command._id,
        type: command.type,
        policyVersion: command.policyVersion,
        expiresAt: command.expiresAt,
      })),
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
    return { ok: true };
  },
});
