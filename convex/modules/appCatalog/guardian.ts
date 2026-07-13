import { v } from "convex/values";
import { guardianQuery } from "../../lib/functionWrappers";
import { requireGuardianForChildProfile } from "../../lib/authorize";

export const getLatestAppCatalog = guardianQuery({
  operation: "appCatalog.getLatest",
  args: { childProfileId: v.id("childProfiles") },
  handler: async (ctx, actor, args) => {
    await requireGuardianForChildProfile(ctx, actor, args.childProfileId);
    const enrollment = await ctx.db
      .query("activeEnrollments")
      .withIndex("by_child_profile_id_and_status", (q) =>
        q.eq("childProfileId", args.childProfileId).eq("status", "active"),
      )
      .unique();
    if (enrollment === null) return { syncedAt: null, apps: [] };
    const generation = await ctx.db
      .query("appCatalogGenerations")
      .withIndex("by_active_enrollment_id_and_status", (q) =>
        q.eq("activeEnrollmentId", enrollment._id).eq("status", "current"),
      )
      .unique();
    if (generation === null || generation.syncedAt === undefined) {
      return { syncedAt: null, apps: [] };
    }
    const entries = await ctx.db
      .query("appCatalogEntries")
      .withIndex("by_app_catalog_generation_id", (q) =>
        q.eq("appCatalogGenerationId", generation._id),
      )
      .take(501);
    return {
      syncedAt: generation.syncedAt,
      apps: entries
        .map(({ packageName, label }) => ({ packageName, label }))
        .sort((left, right) => left.packageName.localeCompare(right.packageName)),
    };
  },
});
