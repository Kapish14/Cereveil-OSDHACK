import { v } from "convex/values";
import { internalMutation } from "./_generated/server";
import { internal } from "./_generated/api";

const BATCH_SIZE = 50;
const RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

export const run = internalMutation({
  args: { serverNow: v.optional(v.number()) },
  handler: async (ctx, args) => {
    const serverNow = args.serverNow ?? Date.now();
    let processed = 0;
    const commands = await ctx.db
      .query("childDeviceCommands")
      .withIndex("by_expires_at", (q) => q.lte("expiresAt", serverNow))
      .take(BATCH_SIZE);
    for (const command of commands) {
      if (command.status === "pending") {
        await ctx.db.patch("childDeviceCommands", command._id, {
          status: "expired",
          updatedAt: serverNow,
          expiresAt: serverNow + RETENTION_MS,
        });
      } else {
        await ctx.db.delete("childDeviceCommands", command._id);
      }
      processed += 1;
    }
    const remaining = BATCH_SIZE - processed;
    if (remaining > 0) {
      const receipts = await ctx.db
        .query("guardianNoticeReceipts")
        .withIndex("by_expires_at", (q) => q.lte("expiresAt", serverNow))
        .take(remaining);
      for (const receipt of receipts) await ctx.db.delete("guardianNoticeReceipts", receipt._id);
      processed += receipts.length;
    }
    const noticeCapacity = BATCH_SIZE - processed;
    if (noticeCapacity > 0) {
      const notices = await ctx.db
        .query("guardianNotices")
        .withIndex("by_expires_at", (q) => q.lte("expiresAt", serverNow))
        .take(noticeCapacity);
      for (const notice of notices) await ctx.db.delete("guardianNotices", notice._id);
      processed += notices.length;
    }
    const deliveryCapacity = BATCH_SIZE - processed;
    if (deliveryCapacity > 0) {
      const attempts = await ctx.db
        .query("fcmDeliveryAttempts")
        .withIndex("by_expires_at", (q) => q.lte("expiresAt", serverNow))
        .take(deliveryCapacity);
      for (const attempt of attempts) await ctx.db.delete("fcmDeliveryAttempts", attempt._id);
      processed += attempts.length;
    }
    if (processed === BATCH_SIZE) {
      await ctx.scheduler.runAfter(0, internal.messagingCleanup.run, { serverNow });
    }
    return { processed };
  },
});
