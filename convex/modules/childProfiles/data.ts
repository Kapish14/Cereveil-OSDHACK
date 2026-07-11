import { MutationCtx, QueryCtx } from "../../_generated/server";
import { Id } from "../../_generated/dataModel";

export async function loadGuardianAccountByTokenIdentifier(
  ctx: MutationCtx | QueryCtx,
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
  ctx: MutationCtx | QueryCtx,
  guardianAccountId: Id<"guardianAccounts">,
) {
  return await ctx.db
    .query("households")
    .withIndex("by_guardian_account_id_and_status", (q) =>
      q.eq("guardianAccountId", guardianAccountId).eq("status", "active"),
    )
    .unique();
}

export async function loadDeletingHouseholdForGuardianAccount(
  ctx: MutationCtx | QueryCtx,
  guardianAccountId: Id<"guardianAccounts">,
) {
  return await ctx.db
    .query("households")
    .withIndex("by_guardian_account_id_and_status", (q) =>
      q.eq("guardianAccountId", guardianAccountId).eq("status", "deleting"),
    )
    .unique();
}

export async function loadActiveChildProfilesForHousehold(
  ctx: MutationCtx | QueryCtx,
  householdId: Id<"households">,
) {
  return await ctx.db
    .query("childProfiles")
    .withIndex("by_household_id_and_status", (q) =>
      q.eq("householdId", householdId).eq("status", "active"),
    )
    .take(50);
}

export async function loadCurrentPolicyForChildProfile(
  ctx: MutationCtx | QueryCtx,
  childProfileId: Id<"childProfiles">,
) {
  const policies = await ctx.db
    .query("supervisionPolicies")
    .withIndex("by_child_profile_id_and_version", (q) =>
      q.eq("childProfileId", childProfileId),
    )
    .order("desc")
    .take(1);

  return policies.at(0) ?? null;
}
