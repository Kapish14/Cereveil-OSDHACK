import { v } from "convex/values";
import { internalMutation } from "../../_generated/server";
import { cooldownForChild, terminateRequest } from "./model";

export const expireRemoteAudioRequest = internalMutation({
  args: {
    requestId: v.id("remoteAudioRequests"),
    expectedExpiresAt: v.number(),
  },
  handler: async (ctx, args) => {
    const request = await ctx.db.get("remoteAudioRequests", args.requestId);
    if (request === null || request.expiresAt !== args.expectedExpiresAt || request.expiresAt > Date.now()) return null;
    await terminateRequest(ctx, request, request.expiresAt);
    return null;
  },
});

export const clearCooldown = internalMutation({
  args: {
    childProfileId: v.id("childProfiles"),
    expectedCooldownUntil: v.number(),
  },
  handler: async (ctx, args) => {
    const cooldown = await cooldownForChild(ctx, args.childProfileId);
    if (
      cooldown !== null &&
      cooldown.cooldownUntil === args.expectedCooldownUntil &&
      cooldown.cooldownUntil <= Date.now()
    ) await ctx.db.delete("remoteAudioCooldowns", cooldown._id);
    return null;
  },
});
