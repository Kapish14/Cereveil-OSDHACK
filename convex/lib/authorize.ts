import { Id } from "../_generated/dataModel";
import { MutationCtx, QueryCtx } from "../_generated/server";
import { GuardianActor } from "./actors";
import { throwAppError } from "./errors";

export async function requireGuardianForChildProfile(
  ctx: MutationCtx | QueryCtx,
  actor: GuardianActor,
  childProfileId: Id<"childProfiles">,
) {
  const childProfile = await ctx.db.get("childProfiles", childProfileId);
  if (childProfile === null || childProfile.householdId !== actor.householdId) {
    throwAppError("UNAUTHENTICATED");
  }
  if (childProfile.status !== "active") throwAppError("VALIDATION_FAILED");
  return childProfile;
}

export async function requireUnenrolledChildProfile(
  ctx: MutationCtx | QueryCtx,
  childProfileId: Id<"childProfiles">,
) {
  const enrollment = await ctx.db
    .query("activeEnrollments")
    .withIndex("by_child_profile_id_and_status", (q) =>
      q.eq("childProfileId", childProfileId).eq("status", "active"),
    )
    .unique();
  if (enrollment !== null) throwAppError("CHILD_ALREADY_ENROLLED");
}
