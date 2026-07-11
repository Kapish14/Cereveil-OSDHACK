import { guardianMutation, guardianQuery } from "../../lib/functionWrappers";
import { now } from "../../lib/time";
import {
  createChildProfile as createChildProfileUseCase,
  listChildProfiles as listChildProfilesUseCase,
} from "./useCases";
import { createChildProfileArgs } from "./validators";

export const createChildProfile = guardianMutation({
  operation: "childProfiles.create",
  args: createChildProfileArgs,
  handler: async (ctx, actor, args) => {
    return await createChildProfileUseCase(ctx, actor, args, now());
  },
});

export const listChildProfiles = guardianQuery({
  operation: "childProfiles.list",
  args: {},
  handler: async (ctx, actor) => {
    return await listChildProfilesUseCase(ctx, actor);
  },
});
