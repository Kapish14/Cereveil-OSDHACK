import { paginationOptsValidator } from "convex/server";
import { v } from "convex/values";
import { action } from "../../_generated/server";
import { internal } from "../../_generated/api";
import { guardianMutation, guardianQuery } from "../../lib/functionWrappers";
import { sha256Base64Url } from "../../lib/encoding";
import { encryptPushToken } from "../../lib/sensitive";
import { throwAppError } from "../../lib/errors";

export const registerGuardianPushToken = action({
  args: { guardianInstallationId: v.string(), token: v.string() },
  handler: async (ctx, args) => {
    if (args.token.length === 0 || args.token.length > 4096) throwAppError("VALIDATION_FAILED");
    const identity = await ctx.auth.getUserIdentity();
    if (identity === null) throwAppError("UNAUTHENTICATED");
    const owner = await ctx.runQuery(internal.modules.notifications.internal.resolveGuardianDeliveryOwner, {
      tokenIdentifier: identity.tokenIdentifier,
      guardianInstallationId: args.guardianInstallationId,
    });
    if (owner === null) throwAppError("UNAUTHENTICATED");
    await ctx.runMutation(internal.modules.notifications.internal.registerGuardianPushToken, {
      ...owner,
      input: {
        tokenHash: await sha256Base64Url(args.token),
        encryptedToken: await encryptPushToken(args.token),
        serverNow: Date.now(),
      },
    });
    return { ok: true };
  },
});

export const reconcileGuardianNotices = guardianQuery({
  operation: "guardianNotices.reconcile",
  args: { paginationOpts: paginationOptsValidator },
  handler: async (ctx, actor, args) => {
    if (args.paginationOpts.numItems > 50) throwAppError("VALIDATION_FAILED");
    const result = await ctx.db
      .query("guardianNoticeReceipts")
      .withIndex("by_guardian_device_id_and_status", (q) =>
        q.eq("guardianDeviceId", actor.guardianDeviceId).eq("status", "pending"),
      )
      .order("asc")
      .paginate(args.paginationOpts);
    return {
      ...result,
      page: await Promise.all(result.page.map(async (receipt) => {
        const notice = await ctx.db.get("guardianNotices", receipt.guardianNoticeId);
        if (notice === null || notice.householdId !== actor.householdId) throwAppError("INTERNAL_ERROR");
        return { receiptId: receipt._id, notice };
      })),
    };
  },
});

export const acknowledgeGuardianNotice = guardianMutation({
  operation: "guardianNotices.acknowledge",
  args: {
    receiptId: v.id("guardianNoticeReceipts"),
    presentation: v.union(v.literal("shown"), v.literal("suppressed")),
  },
  handler: async (ctx, actor, args) => {
    const receipt = await ctx.db.get("guardianNoticeReceipts", args.receiptId);
    if (receipt === null || receipt.guardianDeviceId !== actor.guardianDeviceId || receipt.householdId !== actor.householdId) {
      throwAppError("UNAUTHENTICATED");
    }
    if (receipt.status === "processed") return { ok: true };
    await ctx.db.patch("guardianNoticeReceipts", receipt._id, {
      status: "processed",
      presentation: args.presentation,
      processedAt: Date.now(),
    });
    return { ok: true };
  },
});
