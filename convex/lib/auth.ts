import { UserIdentity } from "convex/server";
import { MutationCtx, QueryCtx } from "../_generated/server";
import { throwAppError } from "./errors";

export async function requireClerkIdentity(
  ctx: MutationCtx | QueryCtx,
): Promise<UserIdentity> {
  const identity = await ctx.auth.getUserIdentity();
  if (identity === null) {
    throwAppError("UNAUTHENTICATED");
  }
  return identity;
}
