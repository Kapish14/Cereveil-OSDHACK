import { v } from "convex/values";
import { guardianQuery } from "../../lib/functionWrappers";
import { requireGuardianForChildProfile } from "../../lib/authorize";

const RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

export const listSafetyAlerts = guardianQuery({
  operation: "safetyAlerts.list",
  args: { childProfileId: v.id("childProfiles") },
  handler: async (ctx, actor, args) => {
    await requireGuardianForChildProfile(ctx, actor, args.childProfileId);
    const rows = await ctx.db.query("safetyAlerts")
      .withIndex("by_child_profile_id_and_occurred_at", (q) =>
        q.eq("childProfileId", args.childProfileId).gte("occurredAt", Date.now() - RETENTION_MS),
      )
      .order("desc")
      .take(200);
    return rows.map((row) => ({
      incidentId: row.incidentId,
      type: row.type,
      packageName: row.packageName,
      appLabel: row.appLabel,
      confidenceBand: row.confidenceBand,
      policyVersion: row.policyVersion,
      occurredAt: row.occurredAt,
      createdAt: row.createdAt,
      expiresAt: row.expiresAt,
    }));
  },
});
