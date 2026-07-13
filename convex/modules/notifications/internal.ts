import { v } from "convex/values";
import { internalMutation, internalQuery, MutationCtx } from "../../_generated/server";
import { Id } from "../../_generated/dataModel";
import { internal } from "../../_generated/api";
import { throwAppError } from "../../lib/errors";
import {
  childDeviceActorValidator,
  requireActiveChildDeviceActor,
} from "../deviceIdentity/internal";

const tokenInput = v.object({ tokenHash: v.string(), encryptedToken: v.string(), serverNow: v.number() });

export const registerPushToken = internalMutation({
  args: { actor: childDeviceActorValidator, input: tokenInput },
  handler: async (ctx, args) => {
    const actor = await requireActiveChildDeviceActor(ctx, args.actor);
    return await bindToken(ctx, {
      ownerKind: "childDevice",
      ownerId: actor.device._id,
      householdId: actor.enrollment.householdId,
      childProfileId: actor.enrollment.childProfileId,
      activeEnrollmentId: actor.enrollment._id,
      childDeviceId: actor.device._id,
      environment: actor.device.environment,
      ...args.input,
    });
  },
});

export const resolveGuardianDeliveryOwner = internalQuery({
  args: { tokenIdentifier: v.string(), guardianInstallationId: v.string() },
  handler: async (ctx, args) => {
    const account = await ctx.db
      .query("guardianAccounts")
      .withIndex("by_clerk_token_identifier", (q) => q.eq("clerkTokenIdentifier", args.tokenIdentifier))
      .unique();
    if (account === null || account.status !== "active") return null;
    const device = await ctx.db
      .query("guardianDevices")
      .withIndex("by_guardian_account_id_and_guardian_installation_id", (q) =>
        q.eq("guardianAccountId", account._id).eq("guardianInstallationId", args.guardianInstallationId),
      )
      .unique();
    if (device === null || device.status !== "active") return null;
    return {
      guardianDeviceId: device._id,
      householdId: device.householdId,
      environment: device.environment,
    };
  },
});

export const registerGuardianPushToken = internalMutation({
  args: {
    guardianDeviceId: v.id("guardianDevices"),
    householdId: v.id("households"),
    environment: v.union(v.literal("dev"), v.literal("prod")),
    input: tokenInput,
  },
  handler: async (ctx, args) => {
    const device = await ctx.db.get("guardianDevices", args.guardianDeviceId);
    if (device === null || device.status !== "active" || device.householdId !== args.householdId) {
      throwAppError("UNAUTHENTICATED");
    }
    return await bindToken(ctx, {
      ownerKind: "guardianDevice",
      ownerId: device._id,
      householdId: device.householdId,
      environment: args.environment,
      ...args.input,
    });
  },
});

export const getDeliveryTargets = internalQuery({
  args: {
    recordKind: v.union(v.literal("guardianNotice"), v.literal("childDeviceCommand")),
    recordId: v.string(),
  },
  handler: async (ctx, args) => {
    if (args.recordKind === "guardianNotice") {
      const noticeId = ctx.db.normalizeId("guardianNotices", args.recordId);
      if (noticeId === null) return null;
      const notice = await ctx.db.get("guardianNotices", noticeId);
      if (notice === null || notice.status !== "active") return null;
      const receipts = await ctx.db
        .query("guardianNoticeReceipts")
        .withIndex("by_guardian_notice_id_and_guardian_device_id", (q) => q.eq("guardianNoticeId", notice._id))
        .take(3);
      const targets = [];
      for (const receipt of receipts) {
        if (receipt.status !== "pending") continue;
        const tokens = await ctx.db
          .query("fcmTokens")
          .withIndex("by_owner_kind_and_owner_id", (q) =>
            q.eq("ownerKind", "guardianDevice").eq("ownerId", receipt.guardianDeviceId),
          )
          .take(10);
        targets.push(...tokens.filter((token) => token.status === "active"));
      }
      return {
        category: "guardian_notice" as const,
        priority: notice.type === "tamper" || notice.type === "access_request" || notice.type === "safety"
          ? "high" as const
          : "normal" as const,
        targets,
      };
    }
    const commandId = ctx.db.normalizeId("childDeviceCommands", args.recordId);
    if (commandId === null) return null;
    const command = await ctx.db.get("childDeviceCommands", commandId);
    if (command === null || command.status !== "pending") return null;
    const tokens = await ctx.db
      .query("fcmTokens")
      .withIndex("by_owner_kind_and_owner_id", (q) =>
        q.eq("ownerKind", "childDevice").eq("ownerId", command.childDeviceId),
      )
      .take(10);
    return {
      category: "child_command" as const,
      priority: command.type === "refresh_location" || command.type === "request_remote_audio" ||
        (command.type === "reconcile_access_grants" && !command.intentKey.startsWith("access_outcome:"))
        ? "high" as const
        : "normal" as const,
      targets: tokens.filter((token) => token.status === "active"),
    };
  },
});

export const recordDeliveryOutcome = internalMutation({
  args: {
    recordKind: v.union(v.literal("guardianNotice"), v.literal("childDeviceCommand")),
    recordId: v.string(),
    fcmTokenId: v.id("fcmTokens"),
    attempt: v.number(),
    outcome: v.union(v.literal("accepted"), v.literal("transient"), v.literal("invalid"), v.literal("exhausted")),
    serverNow: v.number(),
  },
  handler: async (ctx, args) => {
    if (args.outcome === "invalid") {
      const token = await ctx.db.get("fcmTokens", args.fcmTokenId);
      if (token?.status === "active") {
        await ctx.db.patch("fcmTokens", token._id, { status: "invalid", invalidatedAt: args.serverNow });
      }
    }
    if (args.recordKind === "childDeviceCommand") {
      const commandId = ctx.db.normalizeId("childDeviceCommands", args.recordId);
      const command = commandId === null ? null : await ctx.db.get("childDeviceCommands", commandId);
      if (command === null || (command.type === "request_remote_audio" && command.status !== "pending")) return null;
    } else {
      const noticeId = ctx.db.normalizeId("guardianNotices", args.recordId);
      const notice = noticeId === null ? null : await ctx.db.get("guardianNotices", noticeId);
      if (notice === null) return null;
    }
    await ctx.db.insert("fcmDeliveryAttempts", {
      ...args,
      attemptedAt: args.serverNow,
      expiresAt: args.serverNow + 7 * 24 * 60 * 60 * 1000,
    });
    return null;
  },
});

type DeliveryOwner =
  | {
      ownerKind: "guardianDevice";
      ownerId: Id<"guardianDevices">;
      householdId: Id<"households">;
      environment: "dev" | "prod";
    }
  | {
      ownerKind: "childDevice";
      ownerId: Id<"childDevices">;
      householdId: Id<"households">;
      childProfileId: Id<"childProfiles">;
      activeEnrollmentId: Id<"activeEnrollments">;
      childDeviceId: Id<"childDevices">;
      environment: "dev" | "prod";
    };

async function bindToken(ctx: MutationCtx, args: DeliveryOwner & {
  tokenHash: string;
  encryptedToken: string;
  serverNow: number;
}) {
  const sameTokens = await ctx.db
    .query("fcmTokens")
    .withIndex("by_environment_and_token_hash", (q) =>
      q.eq("environment", args.environment).eq("tokenHash", args.tokenHash),
    )
    .take(10);
  for (const token of sameTokens) {
    if (token.status === "active" && (token.ownerKind !== args.ownerKind || token.ownerId !== args.ownerId)) {
      await ctx.db.patch("fcmTokens", token._id, {
        status: "revoked",
        invalidatedAt: args.serverNow,
      });
    }
  }
  const ownerTokens = await ctx.db
    .query("fcmTokens")
    .withIndex("by_owner_kind_and_owner_id", (q) =>
      q.eq("ownerKind", args.ownerKind).eq("ownerId", args.ownerId),
    )
    .take(10);
  const current = ownerTokens.find((token) => token.status === "active");
  for (const token of ownerTokens) {
    if (token.status === "active" && token._id !== current?._id) {
      await ctx.db.patch("fcmTokens", token._id, { status: "revoked", invalidatedAt: args.serverNow });
    }
  }
  const value = {
    tokenHash: args.tokenHash,
    encryptedToken: args.encryptedToken,
    platform: "android" as const,
    environment: args.environment,
    status: "active" as const,
    lastSeenAt: args.serverNow,
  };
  if (current !== undefined) {
    await ctx.db.patch("fcmTokens", current._id, value);
    await schedulePendingOwnerWork(ctx, args);
    return current._id;
  }
  const tokenId = await ctx.db.insert("fcmTokens", {
    ownerKind: args.ownerKind,
    ownerId: args.ownerId,
    householdId: args.householdId,
    ...(args.ownerKind === "childDevice"
      ? {
          childProfileId: args.childProfileId,
          activeEnrollmentId: args.activeEnrollmentId,
          childDeviceId: args.childDeviceId,
        }
      : {}),
    ...value,
    registeredAt: args.serverNow,
  });
  await schedulePendingOwnerWork(ctx, args);
  return tokenId;
}

async function schedulePendingOwnerWork(
  ctx: MutationCtx,
  owner: DeliveryOwner,
) {
  if (owner.ownerKind === "guardianDevice") {
    const receipts = await ctx.db
      .query("guardianNoticeReceipts")
      .withIndex("by_guardian_device_id_and_status", (q) =>
        q.eq("guardianDeviceId", owner.ownerId).eq("status", "pending"),
      )
      .take(50);
    for (const receipt of receipts) {
      await ctx.scheduler.runAfter(0, internal.fcmDelivery.deliver, {
        recordKind: "guardianNotice",
        recordId: receipt.guardianNoticeId,
        attempt: 1,
      });
    }
    return;
  }
  const device = await ctx.db.get("childDevices", owner.ownerId);
  if (device === null) return;
  const enrollment = await ctx.db
    .query("activeEnrollments")
    .withIndex("by_child_device_id", (q) => q.eq("childDeviceId", device._id))
    .unique();
  if (enrollment === null || enrollment.status !== "active") return;
  const commands = await ctx.db
    .query("childDeviceCommands")
    .withIndex("by_active_enrollment_id_and_status", (q) =>
      q.eq("activeEnrollmentId", enrollment._id).eq("status", "pending"),
    )
    .take(50);
  for (const command of commands) {
    await ctx.scheduler.runAfter(0, internal.fcmDelivery.deliver, {
      recordKind: "childDeviceCommand",
      recordId: command._id,
      attempt: 1,
    });
  }
}
