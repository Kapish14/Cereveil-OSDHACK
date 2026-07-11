import { UserIdentity } from "convex/server";
import { Id } from "../_generated/dataModel";
import { MutationCtx, QueryCtx } from "../_generated/server";
import { throwAppError } from "./errors";

export type GuardianActor = {
  guardianAccountId: Id<"guardianAccounts">;
  householdId: Id<"households">;
  guardianDeviceId: Id<"guardianDevices">;
};

export async function resolveGuardianActor(
  ctx: MutationCtx | QueryCtx,
  identity: UserIdentity,
  guardianInstallationId: string,
): Promise<GuardianActor> {
  const account = await ctx.db
    .query("guardianAccounts")
    .withIndex("by_clerk_token_identifier", (q) =>
      q.eq("clerkTokenIdentifier", identity.tokenIdentifier),
    )
    .unique();
  if (account === null) throwAppError("UNAUTHENTICATED");
  if (account.status === "disabled") throwAppError("ACCOUNT_DISABLED");
  if (account.status === "deleting") throwAppError("ACCOUNT_DELETING");

  const household = await ctx.db
    .query("households")
    .withIndex("by_guardian_account_id_and_status", (q) =>
      q.eq("guardianAccountId", account._id).eq("status", "active"),
    )
    .unique();
  if (household === null) {
    const deleting = await ctx.db
      .query("households")
      .withIndex("by_guardian_account_id_and_status", (q) =>
        q.eq("guardianAccountId", account._id).eq("status", "deleting"),
      )
      .unique();
    if (deleting !== null) throwAppError("HOUSEHOLD_DELETING");
    throwAppError("UNAUTHENTICATED");
  }

  const device = await ctx.db
    .query("guardianDevices")
    .withIndex("by_guardian_account_id_and_guardian_installation_id", (q) =>
      q.eq("guardianAccountId", account._id).eq("guardianInstallationId", guardianInstallationId),
    )
    .unique();
  if (device === null) throwAppError("UNAUTHENTICATED");
  if (device.householdId !== household._id) throwAppError("UNAUTHENTICATED");
  if (device.status === "revoked") throwAppError("DEVICE_REVOKED");
  return {
    guardianAccountId: account._id,
    householdId: household._id,
    guardianDeviceId: device._id,
  };
}
