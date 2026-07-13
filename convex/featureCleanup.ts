import { v } from "convex/values";
import { internal } from "./_generated/api";
import { internalMutation } from "./_generated/server";

const BATCH_SIZE = 25;

/** Bounded, cascading cleanup for feature records with explicit retention deadlines. */
export const run = internalMutation({
  args: { serverNow: v.optional(v.number()) },
  handler: async (ctx, args) => {
    const serverNow = args.serverNow ?? Date.now();
    let processed = 0;

    const safetyAlerts = await ctx.db.query("safetyAlerts")
      .withIndex("by_expires_at", (q) => q.lte("expiresAt", serverNow)).take(BATCH_SIZE);
    for (const row of safetyAlerts) { await ctx.db.delete("safetyAlerts", row._id); processed += 1; }

    const grants = await ctx.db.query("accessGrants")
      .withIndex("by_purge_at", (q) => q.lte("purgeAt", serverNow)).take(BATCH_SIZE - processed);
    for (const row of grants) { await ctx.db.delete("accessGrants", row._id); processed += 1; }

    const accessCapacity = BATCH_SIZE - processed;
    if (accessCapacity > 0) {
      const requests = await ctx.db.query("accessRequests")
        .withIndex("by_purge_at", (q) => q.lte("purgeAt", serverNow)).take(accessCapacity);
      for (const row of requests) {
        const linked = await ctx.db.query("accessGrants")
          .withIndex("by_access_request_id", (q) => q.eq("accessRequestId", row._id)).first();
        if (linked === null) { await ctx.db.delete("accessRequests", row._id); processed += 1; }
      }
    }

    const locationCapacity = BATCH_SIZE - processed;
    if (locationCapacity > 0) {
      const rows = await ctx.db.query("locationRefreshRequests")
        .withIndex("by_purge_at", (q) => q.lte("purgeAt", serverNow)).take(locationCapacity);
      for (const row of rows) { await ctx.db.delete("locationRefreshRequests", row._id); processed += 1; }
    }

    const refreshCapacity = BATCH_SIZE - processed;
    if (refreshCapacity > 0) {
      const rows = await ctx.db.query("screenTimeRefreshRequests")
        .withIndex("by_purge_at", (q) => q.lte("purgeAt", serverNow)).take(refreshCapacity);
      for (const row of rows) {
        const snapshot = await ctx.db.query("screenTimeSnapshots")
          .withIndex("by_screen_time_refresh_request_id", (q) => q.eq("screenTimeRefreshRequestId", row._id)).first();
        if (snapshot === null) { await ctx.db.delete("screenTimeRefreshRequests", row._id); processed += 1; }
      }
    }

    const snapshotCapacity = BATCH_SIZE - processed;
    if (snapshotCapacity > 0) {
      const snapshots = await ctx.db.query("screenTimeSnapshots")
        .withIndex("by_purge_at", (q) => q.lte("purgeAt", serverNow)).take(snapshotCapacity);
      for (const snapshot of snapshots) {
        const rows = await ctx.db.query("screenTimeSnapshotRows")
          .withIndex("by_screen_time_snapshot_id", (q) => q.eq("screenTimeSnapshotId", snapshot._id)).take(501);
        for (const row of rows) await ctx.db.delete("screenTimeSnapshotRows", row._id);
        await ctx.db.delete("screenTimeSnapshots", snapshot._id);
        processed += 1;
      }
    }

    const catalogCapacity = BATCH_SIZE - processed;
    if (catalogCapacity > 0) {
      const generations = await ctx.db.query("appCatalogGenerations")
        .withIndex("by_expires_at", (q) => q.lte("expiresAt", serverNow)).take(catalogCapacity);
      for (const generation of generations) {
        if (generation.status === "current") continue;
        const entries = await ctx.db.query("appCatalogEntries")
          .withIndex("by_app_catalog_generation_id", (q) => q.eq("appCatalogGenerationId", generation._id)).take(501);
        for (const entry of entries) await ctx.db.delete("appCatalogEntries", entry._id);
        await ctx.db.delete("appCatalogGenerations", generation._id);
        processed += 1;
      }
    }

    if (processed >= BATCH_SIZE) await ctx.scheduler.runAfter(0, internal.featureCleanup.run, { serverNow });
    return { processed };
  },
});
