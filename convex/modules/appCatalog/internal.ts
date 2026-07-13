import { v } from "convex/values";
import { internalMutation } from "../../_generated/server";
import { throwAppError } from "../../lib/errors";
import {
  childDeviceActorValidator,
  requireActiveChildDeviceActor,
} from "../deviceIdentity/internal";

const MAX_CATALOG_APPS = 500;
const MAX_BATCH_APPS = 50;
const STAGING_LIFETIME_MS = 15 * 60 * 1000;
const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$/;

const appValidator = v.object({
  packageName: v.string(),
  label: v.string(),
});

export const startGeneration = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({ expectedCount: v.number(), serverNow: v.number() }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    if (
      !Number.isInteger(args.input.expectedCount) ||
      args.input.expectedCount < 0 ||
      args.input.expectedCount > MAX_CATALOG_APPS
    ) throwAppError("VALIDATION_FAILED");

    const existing = await ctx.db
      .query("appCatalogGenerations")
      .withIndex("by_active_enrollment_id_and_status", (q) =>
        q.eq("activeEnrollmentId", actor.enrollment._id).eq("status", "staging"),
      )
      .take(10);
    for (const generation of existing) {
      await ctx.db.patch("appCatalogGenerations", generation._id, {
        status: "abandoned",
        updatedAt: args.input.serverNow,
        expiresAt: args.input.serverNow + STAGING_LIFETIME_MS,
      });
    }

    const generationId = await ctx.db.insert("appCatalogGenerations", {
      householdId: actor.household._id,
      childProfileId: actor.childProfile._id,
      activeEnrollmentId: actor.enrollment._id,
      childDeviceId: actor.device._id,
      status: "staging",
      expectedCount: args.input.expectedCount,
      uploadedCount: 0,
      createdAt: args.input.serverNow,
      updatedAt: args.input.serverNow,
      expiresAt: args.input.serverNow + STAGING_LIFETIME_MS,
    });
    return { generationId };
  },
});

export const uploadBatch = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({
      generationId: v.id("appCatalogGenerations"),
      apps: v.array(appValidator),
      serverNow: v.number(),
    }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const generation = await ctx.db.get("appCatalogGenerations", args.input.generationId);
    if (
      generation === null ||
      generation.activeEnrollmentId !== actor.enrollment._id ||
      generation.status !== "staging" ||
      generation.expiresAt <= args.input.serverNow ||
      args.input.apps.length < 1 ||
      args.input.apps.length > MAX_BATCH_APPS
    ) throwAppError("VALIDATION_FAILED");

    const packages = new Set<string>();
    for (const app of args.input.apps) {
      if (
        !PACKAGE_NAME.test(app.packageName) ||
        app.packageName.length > 255 ||
        app.label.trim().length < 1 ||
        app.label.length > 100 ||
        packages.has(app.packageName)
      ) throwAppError("VALIDATION_FAILED");
      packages.add(app.packageName);
    }

    let inserted = 0;
    for (const app of args.input.apps) {
      const existing = await ctx.db
        .query("appCatalogEntries")
        .withIndex("by_app_catalog_generation_id_and_package_name", (q) =>
          q
            .eq("appCatalogGenerationId", generation._id)
            .eq("packageName", app.packageName),
        )
        .unique();
      if (existing !== null) {
        if (existing.label !== app.label.trim()) throwAppError("VALIDATION_FAILED");
        continue;
      }
      await ctx.db.insert("appCatalogEntries", {
        appCatalogGenerationId: generation._id,
        householdId: actor.household._id,
        childProfileId: actor.childProfile._id,
        activeEnrollmentId: actor.enrollment._id,
        packageName: app.packageName,
        label: app.label.trim(),
        createdAt: args.input.serverNow,
      });
      inserted += 1;
    }
    const uploadedCount = generation.uploadedCount + inserted;
    if (uploadedCount > generation.expectedCount) throwAppError("VALIDATION_FAILED");
    await ctx.db.patch("appCatalogGenerations", generation._id, {
      uploadedCount,
      updatedAt: args.input.serverNow,
    });
    return { uploadedCount };
  },
});

export const completeGeneration = internalMutation({
  args: {
    actor: childDeviceActorValidator,
    input: v.object({
      generationId: v.id("appCatalogGenerations"),
      serverNow: v.number(),
    }),
  },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    const generation = await ctx.db.get("appCatalogGenerations", args.input.generationId);
    if (
      generation === null ||
      generation.activeEnrollmentId !== actor.enrollment._id ||
      generation.status !== "staging" ||
      generation.expiresAt <= args.input.serverNow ||
      generation.uploadedCount !== generation.expectedCount
    ) throwAppError("VALIDATION_FAILED");

    const entries = await ctx.db
      .query("appCatalogEntries")
      .withIndex("by_app_catalog_generation_id", (q) =>
        q.eq("appCatalogGenerationId", generation._id),
      )
      .take(MAX_CATALOG_APPS + 1);
    if (entries.length !== generation.expectedCount) throwAppError("VALIDATION_FAILED");

    const previous = await ctx.db
      .query("appCatalogGenerations")
      .withIndex("by_active_enrollment_id_and_status", (q) =>
        q.eq("activeEnrollmentId", actor.enrollment._id).eq("status", "current"),
      )
      .unique();
    if (previous !== null) {
      await ctx.db.patch("appCatalogGenerations", previous._id, {
        status: "superseded",
        updatedAt: args.input.serverNow,
        expiresAt: args.input.serverNow + STAGING_LIFETIME_MS,
      });
    }
    await ctx.db.patch("appCatalogGenerations", generation._id, {
      status: "current",
      syncedAt: args.input.serverNow,
      updatedAt: args.input.serverNow,
      expiresAt: Number.MAX_SAFE_INTEGER,
    });
    return { syncedAt: args.input.serverNow };
  },
});
