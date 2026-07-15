import { UserIdentity } from "convex/server";
import { MutationCtx } from "../../_generated/server";
import { Id } from "../../_generated/dataModel";
import { throwAppError } from "../../lib/errors";
import {
  hasActiveChildProfiles,
  hasTwoActiveGuardianDevices,
  loadActiveHouseholdForGuardianAccount,
  loadDeletingHouseholdForGuardianAccount,
  loadGuardianAccountByTokenIdentifier,
  loadGuardianDeviceByInstallation,
} from "./data";
import {
  normalizeTimezone,
  validateBootstrapGuardianArgs,
  validateRetireGuardianDeviceArgs,
} from "./validators";

type BootstrapGuardianArgs = {
  guardianInstallationId: string;
  deviceLabel?: string;
  appBuild: string;
  timezone?: string;
};

export type GuardianBootstrapState = {
  guardianAccountId: Id<"guardianAccounts">;
  householdId: Id<"households">;
  guardianDeviceId: Id<"guardianDevices">;
  guardianDeviceStatus: "active";
  hasChildProfiles: boolean;
  serverNow: number;
};

export async function bootstrapGuardian(
  ctx: MutationCtx,
  identity: UserIdentity,
  args: BootstrapGuardianArgs,
  serverNow: number,
): Promise<GuardianBootstrapState> {
  validateBootstrapGuardianArgs(args);

  const guardianAccount = await findOrCreateGuardianAccount(ctx, identity, serverNow);
  const householdId = await findOrCreateHousehold(ctx, guardianAccount, args, serverNow);
  const guardianDeviceId = await findOrCreateGuardianDevice(
    ctx,
    guardianAccount._id,
    householdId,
    args,
    serverNow,
  );

  return {
    guardianAccountId: guardianAccount._id,
    householdId,
    guardianDeviceId,
    guardianDeviceStatus: "active",
    hasChildProfiles: await hasActiveChildProfiles(ctx, householdId),
    serverNow,
  };
}

export async function retireGuardianDevice(
  ctx: MutationCtx,
  identity: UserIdentity,
  args: { guardianInstallationId: string },
  serverNow: number,
) {
  validateRetireGuardianDeviceArgs(args);
  const guardianAccount = await loadGuardianAccountByTokenIdentifier(ctx, identity.tokenIdentifier);
  if (guardianAccount === null) return { retired: false, serverNow };

  const device = await loadGuardianDeviceByInstallation(
    ctx,
    guardianAccount._id,
    args.guardianInstallationId,
  );
  // Retirement is idempotent. If this account no longer has the installation,
  // the desired postcondition is already true and the client may finish logout.
  if (device === null) return { retired: true, serverNow };
  if (device.status === "revoked") return { retired: true, serverNow };

  await ctx.db.patch("guardianDevices", device._id, {
    status: "revoked",
    revokedAt: serverNow,
    updatedAt: serverNow,
  });
  const tokens = ctx.db
    .query("fcmTokens")
    .withIndex("by_owner_kind_and_owner_id", (q) =>
      q.eq("ownerKind", "guardianDevice").eq("ownerId", device._id),
    );
  for await (const token of tokens) {
    if (token.status !== "active") continue;
    await ctx.db.patch("fcmTokens", token._id, {
      status: "revoked",
      invalidatedAt: serverNow,
    });
  }
  return { retired: true, serverNow };
}

async function findOrCreateGuardianAccount(
  ctx: MutationCtx,
  identity: UserIdentity,
  serverNow: number,
) {
  const existing = await loadGuardianAccountByTokenIdentifier(
    ctx,
    identity.tokenIdentifier,
  );
  const primaryEmail = identity.email;
  const clerkUserId = identity.subject;

  if (existing === null) {
    const guardianAccountId = await ctx.db.insert("guardianAccounts", {
      clerkTokenIdentifier: identity.tokenIdentifier,
      clerkUserId,
      ...(primaryEmail !== undefined ? { primaryEmail } : {}),
      status: "active",
      createdAt: serverNow,
      updatedAt: serverNow,
    });
    const created = await ctx.db.get(guardianAccountId);
    if (created === null) {
      throw new Error("Inserted Guardian Account could not be loaded.");
    }
    return created;
  }

  if (existing.status === "disabled") {
    throwAppError("ACCOUNT_DISABLED");
  }
  if (existing.status === "deleting") {
    throwAppError("ACCOUNT_DELETING");
  }

  const accountPatch: {
    clerkUserId?: string;
    primaryEmail?: string;
    updatedAt: number;
  } = { clerkUserId, updatedAt: serverNow };
  if (primaryEmail !== undefined) {
    accountPatch.primaryEmail = primaryEmail;
  }

  await ctx.db.patch("guardianAccounts", existing._id, accountPatch);
  const updated = await ctx.db.get(existing._id);
  if (updated === null) {
    throw new Error("Updated Guardian Account could not be loaded.");
  }
  return updated;
}

async function findOrCreateHousehold(
  ctx: MutationCtx,
  guardianAccount: { _id: Id<"guardianAccounts"> },
  args: BootstrapGuardianArgs,
  serverNow: number,
) {
  const activeHousehold = await loadActiveHouseholdForGuardianAccount(
    ctx,
    guardianAccount._id,
  );
  if (activeHousehold !== null) {
    return activeHousehold._id;
  }

  const deletingHousehold = await loadDeletingHouseholdForGuardianAccount(
    ctx,
    guardianAccount._id,
  );
  if (deletingHousehold !== null) {
    throwAppError("HOUSEHOLD_DELETING");
  }

  return await ctx.db.insert("households", {
    guardianAccountId: guardianAccount._id,
    status: "active",
    timezone: normalizeTimezone(args.timezone),
    country: "IN",
    createdAt: serverNow,
    updatedAt: serverNow,
  });
}

async function findOrCreateGuardianDevice(
  ctx: MutationCtx,
  guardianAccountId: Id<"guardianAccounts">,
  householdId: Id<"households">,
  args: BootstrapGuardianArgs,
  serverNow: number,
) {
  const existing = await loadGuardianDeviceByInstallation(
    ctx,
    guardianAccountId,
    args.guardianInstallationId,
  );

  if (existing !== null) {
    if (existing.status === "revoked") {
      throwAppError("DEVICE_REVOKED");
    }

    const devicePatch: {
      householdId: Id<"households">;
      deviceLabel?: string;
      appBuild: string;
      lastSeenAt: number;
      updatedAt: number;
    } = {
      householdId,
      appBuild: args.appBuild,
      lastSeenAt: serverNow,
      updatedAt: serverNow,
    };
    if (args.deviceLabel !== undefined) {
      devicePatch.deviceLabel = args.deviceLabel;
    }

    await ctx.db.patch("guardianDevices", existing._id, devicePatch);
    return existing._id;
  }

  if (await hasTwoActiveGuardianDevices(ctx, guardianAccountId)) {
    throwAppError("DEVICE_LIMIT_REACHED");
  }

  return await ctx.db.insert("guardianDevices", {
    guardianAccountId,
    householdId,
    guardianInstallationId: args.guardianInstallationId,
    ...(args.deviceLabel !== undefined ? { deviceLabel: args.deviceLabel } : {}),
    platform: "android",
    appBuild: args.appBuild,
    environment: "dev",
    status: "active",
    lastSeenAt: serverNow,
    createdAt: serverNow,
    updatedAt: serverNow,
  });
}
