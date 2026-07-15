import { mutation } from "../../_generated/server";
import { requireClerkIdentity } from "../../lib/auth";
import { now } from "../../lib/time";
import { bootstrapGuardianArgs, retireGuardianDeviceArgs } from "./validators";
import {
  bootstrapGuardian as bootstrapGuardianUseCase,
  retireGuardianDevice as retireGuardianDeviceUseCase,
} from "./useCases";

export const bootstrapGuardian = mutation({
  args: bootstrapGuardianArgs,
  handler: async (ctx, args) => {
    const identity = await requireClerkIdentity(ctx);
    return await bootstrapGuardianUseCase(ctx, identity, args, now());
  },
});

export const retireGuardianDevice = mutation({
  args: retireGuardianDeviceArgs,
  handler: async (ctx, args) => {
    const identity = await requireClerkIdentity(ctx);
    return await retireGuardianDeviceUseCase(ctx, identity, args, now());
  },
});
