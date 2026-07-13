import { v } from "convex/values";
import { internal } from "../../_generated/api";
import { Id } from "../../_generated/dataModel";
import { internalMutation, MutationCtx } from "../../_generated/server";

const BATCH = 50;

/** Deletes at most one bounded batch and returns whether the enrollment is drained. */
export async function deleteEnrollmentFeatureBatch(
  ctx: MutationCtx,
  activeEnrollmentId: Id<"activeEnrollments">,
): Promise<boolean> {
  const snapshot = (await ctx.db.query("screenTimeSnapshots")
    .withIndex("by_active_enrollment_id_and_status", (q) => q.eq("activeEnrollmentId", activeEnrollmentId)).take(1)).at(0);
  if (snapshot !== undefined) {
    const rows = await ctx.db.query("screenTimeSnapshotRows")
      .withIndex("by_screen_time_snapshot_id", (q) => q.eq("screenTimeSnapshotId", snapshot._id)).take(BATCH);
    for (const row of rows) await ctx.db.delete("screenTimeSnapshotRows", row._id);
    if (rows.length === 0) await ctx.db.delete("screenTimeSnapshots", snapshot._id);
    return false;
  }
  const screenRequests = await ctx.db.query("screenTimeRefreshRequests")
    .withIndex("by_active_enrollment_id_and_status", (q) => q.eq("activeEnrollmentId", activeEnrollmentId)).take(BATCH);
  if (screenRequests.length > 0) {
    for (const row of screenRequests) await ctx.db.delete("screenTimeRefreshRequests", row._id);
    return false;
  }
  const generation = (await ctx.db.query("appCatalogGenerations")
    .withIndex("by_active_enrollment_id_and_status", (q) => q.eq("activeEnrollmentId", activeEnrollmentId)).take(1)).at(0);
  if (generation !== undefined) {
    const entries = await ctx.db.query("appCatalogEntries")
      .withIndex("by_app_catalog_generation_id", (q) => q.eq("appCatalogGenerationId", generation._id)).take(BATCH);
    for (const entry of entries) await ctx.db.delete("appCatalogEntries", entry._id);
    if (entries.length === 0) await ctx.db.delete("appCatalogGenerations", generation._id);
    return false;
  }
  const grants = await ctx.db.query("accessGrants")
    .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", activeEnrollmentId)).take(BATCH);
  if (grants.length > 0) {
    for (const row of grants) await ctx.db.delete("accessGrants", row._id);
    return false;
  }
  const requests = await ctx.db.query("accessRequests")
    .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", activeEnrollmentId)).take(BATCH);
  if (requests.length > 0) {
    for (const row of requests) await ctx.db.delete("accessRequests", row._id);
    return false;
  }
  const latest = await ctx.db.query("latestLocationStates")
    .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", activeEnrollmentId)).unique();
  if (latest !== null) { await ctx.db.delete("latestLocationStates", latest._id); return false; }
  const locationRequests = await ctx.db.query("locationRefreshRequests")
    .withIndex("by_active_enrollment_id_and_status", (q) => q.eq("activeEnrollmentId", activeEnrollmentId)).take(BATCH);
  if (locationRequests.length > 0) {
    for (const row of locationRequests) await ctx.db.delete("locationRefreshRequests", row._id);
    return false;
  }
  return true;
}

export const purgeEnrollmentFeatureData = internalMutation({
  args: { activeEnrollmentId: v.id("activeEnrollments") },
  handler: async (ctx, args) => {
    const done = await deleteEnrollmentFeatureBatch(ctx, args.activeEnrollmentId);
    if (!done) await ctx.scheduler.runAfter(0, internal.modules.featureLifecycle.internal.purgeEnrollmentFeatureData, args);
    return done;
  },
});
