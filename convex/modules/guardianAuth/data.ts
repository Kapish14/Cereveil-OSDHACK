import { MutationCtx } from "../../_generated/server";
import { Id } from "../../_generated/dataModel";

export async function loadGuardianAccountByTokenIdentifier(
  ctx: MutationCtx,
  clerkTokenIdentifier: string,
) {
  return await ctx.db
    .query("guardianAccounts")
    .withIndex("by_clerk_token_identifier", (q) =>
      q.eq("clerkTokenIdentifier", clerkTokenIdentifier),
    )
    .unique();
}

export async function loadActiveHouseholdForGuardianAccount(
  ctx: MutationCtx,
  guardianAccountId: Id<"guardianAccounts">,
) {
  const households = await ctx.db
    .query("households")
    .withIndex("by_guardian_account_id_and_status", (q) =>
      q.eq("guardianAccountId", guardianAccountId).eq("status", "active"),
    )
    .take(1);

  return households.at(0) ?? null;
}

export async function loadDeletingHouseholdForGuardianAccount(
  ctx: MutationCtx,
  guardianAccountId: Id<"guardianAccounts">,
) {
  const households = await ctx.db
    .query("households")
    .withIndex("by_guardian_account_id_and_status", (q) =>
      q.eq("guardianAccountId", guardianAccountId).eq("status", "deleting"),
    )
    .take(1);

  return households.at(0) ?? null;
}

export async function loadGuardianDeviceByInstallation(
  ctx: MutationCtx,
  guardianAccountId: Id<"guardianAccounts">,
  guardianInstallationId: string,
) {
  return await ctx.db
    .query("guardianDevices")
    .withIndex("by_guardian_account_id_and_guardian_installation_id", (q) =>
      q
        .eq("guardianAccountId", guardianAccountId)
        .eq("guardianInstallationId", guardianInstallationId),
    )
    .unique();
}

export async function hasTwoActiveGuardianDevices(
  ctx: MutationCtx,
  guardianAccountId: Id<"guardianAccounts">,
) {
  const devices = await ctx.db
    .query("guardianDevices")
    .withIndex("by_guardian_account_id_and_status", (q) =>
      q.eq("guardianAccountId", guardianAccountId).eq("status", "active"),
    )
    .take(2);

  return devices.length >= 2;
}

export async function hasActiveChildProfiles(
  ctx: MutationCtx,
  householdId: Id<"households">,
) {
  const childProfiles = await ctx.db
    .query("childProfiles")
    .withIndex("by_household_id_and_status", (q) =>
      q.eq("householdId", householdId).eq("status", "active"),
    )
    .take(1);

  return childProfiles.length > 0;
}
