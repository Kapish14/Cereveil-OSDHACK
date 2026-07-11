import { v } from "convex/values";
import { internalMutation } from "../../_generated/server";
import {
  childDeviceActorValidator,
  requireActiveChildDeviceActor,
} from "../deviceIdentity/internal";

export const registerPushToken = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({ tokenHash: v.string(), encryptedToken: v.string(), serverNow: v.number() }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const existing = await ctx.db
      .query("fcmTokens")
      .withIndex("by_child_device_id", (q) => q.eq("childDeviceId", actor.device._id))
      .unique();
    if (existing === null) {
      await ctx.db.insert("fcmTokens", {
        ownerKind: "childDevice",
        ownerId: actor.device._id,
        householdId: actor.enrollment.householdId,
        childProfileId: actor.enrollment.childProfileId,
        activeEnrollmentId: actor.enrollment._id,
        childDeviceId: actor.device._id,
        tokenHash: args.input.tokenHash,
        encryptedToken: args.input.encryptedToken,
        platform: "android",
        environment: actor.device.environment,
        status: "active",
        registeredAt: args.input.serverNow,
        lastSeenAt: args.input.serverNow,
      });
    } else {
      await ctx.db.patch("fcmTokens", existing._id, {
        tokenHash: args.input.tokenHash,
        encryptedToken: args.input.encryptedToken,
        status: "active",
        lastSeenAt: args.input.serverNow,
      });
    }
    return true;
  },
});
