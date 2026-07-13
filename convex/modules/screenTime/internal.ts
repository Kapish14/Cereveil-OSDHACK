import { v } from "convex/values";
import { internalMutation } from "../../_generated/server";
import { throwAppError } from "../../lib/errors";
import {
  childDeviceActorValidator,
  requireActiveChildDeviceActor,
} from "../deviceIdentity/internal";

const MAX_ROWS = 500;
const MAX_BATCH_ROWS = 50;
const STAGING_RETENTION_MS = 15 * 60 * 1000;

export const startSnapshot = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({
      refreshRequestId: v.id("screenTimeRefreshRequests"),
      expectedCount: v.number(),
      measuredAt: v.number(),
      localDayStart: v.number(),
      validUntil: v.number(),
      serverNow: v.number(),
    }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const request = await ctx.db.get("screenTimeRefreshRequests", args.input.refreshRequestId);
    if (
      request === null || request.activeEnrollmentId !== actor.enrollment._id ||
      request.status !== "pending" || request.expiresAt <= args.input.serverNow ||
      !Number.isInteger(args.input.expectedCount) || args.input.expectedCount < 0 || args.input.expectedCount > MAX_ROWS ||
      args.input.measuredAt < request.requestedAt || args.input.measuredAt > args.input.serverNow + 5 * 60 * 1000 ||
      args.input.localDayStart > args.input.measuredAt ||
      args.input.validUntil <= args.input.measuredAt ||
      args.input.validUntil - args.input.localDayStart > 26 * 60 * 60 * 1000
    ) throwAppError("VALIDATION_FAILED");
    const existing = await ctx.db
      .query("screenTimeSnapshots")
      .withIndex("by_screen_time_refresh_request_id", (q) => q.eq("screenTimeRefreshRequestId", request._id))
      .unique();
    if (existing !== null) return { snapshotId: existing._id };
    const snapshotId = await ctx.db.insert("screenTimeSnapshots", {
      householdId: actor.household._id,
      childProfileId: actor.childProfile._id,
      activeEnrollmentId: actor.enrollment._id,
      childDeviceId: actor.device._id,
      screenTimeRefreshRequestId: request._id,
      status: "staging",
      expectedCount: args.input.expectedCount,
      uploadedCount: 0,
      measuredAt: args.input.measuredAt,
      localDayStart: args.input.localDayStart,
      validUntil: args.input.validUntil,
      createdAt: args.input.serverNow,
      updatedAt: args.input.serverNow,
      purgeAt: args.input.serverNow + STAGING_RETENTION_MS,
    });
    return { snapshotId };
  },
});

export const uploadSnapshotBatch = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({
      snapshotId: v.id("screenTimeSnapshots"),
      rows: v.array(v.object({ packageName: v.string(), totalTimeInForegroundMs: v.number() })),
      serverNow: v.number(),
    }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const snapshot = await ctx.db.get("screenTimeSnapshots", args.input.snapshotId);
    if (
      snapshot === null || snapshot.activeEnrollmentId !== actor.enrollment._id ||
      snapshot.status !== "staging" || snapshot.purgeAt <= args.input.serverNow ||
      args.input.rows.length < 1 || args.input.rows.length > MAX_BATCH_ROWS
    ) throwAppError("VALIDATION_FAILED");
    const generation = await ctx.db.query("appCatalogGenerations")
      .withIndex("by_active_enrollment_id_and_status", (q) =>
        q.eq("activeEnrollmentId", actor.enrollment._id).eq("status", "current"),
      ).unique();
    if (generation === null) throwAppError("VALIDATION_FAILED");
    const packages = new Set<string>();
    let inserted = 0;
    for (const row of args.input.rows) {
      if (
        packages.has(row.packageName) ||
        !Number.isFinite(row.totalTimeInForegroundMs) ||
        row.totalTimeInForegroundMs <= 0 ||
        row.totalTimeInForegroundMs > 26 * 60 * 60 * 1000
      ) throwAppError("VALIDATION_FAILED");
      packages.add(row.packageName);
      const catalogEntry = await ctx.db.query("appCatalogEntries")
        .withIndex("by_app_catalog_generation_id_and_package_name", (q) =>
          q.eq("appCatalogGenerationId", generation._id).eq("packageName", row.packageName),
        ).unique();
      if (catalogEntry === null) throwAppError("VALIDATION_FAILED");
      const existing = await ctx.db.query("screenTimeSnapshotRows")
        .withIndex("by_screen_time_snapshot_id_and_package_name", (q) =>
          q.eq("screenTimeSnapshotId", snapshot._id).eq("packageName", row.packageName),
        ).unique();
      if (existing !== null) {
        if (existing.totalTimeInForegroundMs !== row.totalTimeInForegroundMs) throwAppError("VALIDATION_FAILED");
        continue;
      }
      await ctx.db.insert("screenTimeSnapshotRows", {
        screenTimeSnapshotId: snapshot._id,
        householdId: actor.household._id,
        childProfileId: actor.childProfile._id,
        activeEnrollmentId: actor.enrollment._id,
        packageName: row.packageName,
        totalTimeInForegroundMs: row.totalTimeInForegroundMs,
        createdAt: args.input.serverNow,
      });
      inserted += 1;
    }
    const uploadedCount = snapshot.uploadedCount + inserted;
    if (uploadedCount > snapshot.expectedCount) throwAppError("VALIDATION_FAILED");
    await ctx.db.patch("screenTimeSnapshots", snapshot._id, { uploadedCount, updatedAt: args.input.serverNow });
    return { uploadedCount };
  },
});

export const completeSnapshot = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({ snapshotId: v.id("screenTimeSnapshots"), serverNow: v.number() }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const snapshot = await ctx.db.get("screenTimeSnapshots", args.input.snapshotId);
    if (
      snapshot === null || snapshot.activeEnrollmentId !== actor.enrollment._id ||
      snapshot.status !== "staging" || snapshot.uploadedCount !== snapshot.expectedCount ||
      snapshot.validUntil <= args.input.serverNow
    ) throwAppError("VALIDATION_FAILED");
    const rows = await ctx.db.query("screenTimeSnapshotRows")
      .withIndex("by_screen_time_snapshot_id", (q) => q.eq("screenTimeSnapshotId", snapshot._id))
      .take(MAX_ROWS + 1);
    if (rows.length !== snapshot.expectedCount) throwAppError("VALIDATION_FAILED");
    const previous = await ctx.db.query("screenTimeSnapshots")
      .withIndex("by_active_enrollment_id_and_status", (q) =>
        q.eq("activeEnrollmentId", actor.enrollment._id).eq("status", "current"),
      ).unique();
    if (previous !== null) {
      await ctx.db.patch("screenTimeSnapshots", previous._id, {
        status: "superseded",
        updatedAt: args.input.serverNow,
        purgeAt: args.input.serverNow + STAGING_RETENTION_MS,
      });
    }
    await ctx.db.patch("screenTimeSnapshots", snapshot._id, {
      status: "current",
      updatedAt: args.input.serverNow,
      purgeAt: snapshot.validUntil + STAGING_RETENTION_MS,
    });
    await ctx.db.patch("screenTimeRefreshRequests", snapshot.screenTimeRefreshRequestId, {
      status: "completed",
      completedAt: args.input.serverNow,
      purgeAt: args.input.serverNow + STAGING_RETENTION_MS,
    });
    return { measuredAt: snapshot.measuredAt, validUntil: snapshot.validUntil };
  },
});
