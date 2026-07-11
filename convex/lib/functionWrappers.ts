import { PropertyValidators, ObjectType, v } from "convex/values";
import { mutation, MutationCtx, query, QueryCtx } from "../_generated/server";
import { requireClerkIdentity } from "./auth";
import { GuardianActor, resolveGuardianActor } from "./actors";
import { appError, appErrorData } from "./errors";
import { createRequestMetadata, logRequestOutcome } from "./requestLogging";

type GuardianFunctionConfig<Ctx, ArgsValidator extends PropertyValidators, Result> = {
  operation: string;
  args: ArgsValidator;
  handler: (ctx: Ctx, actor: GuardianActor, args: ObjectType<ArgsValidator>) => Promise<Result>;
};

export function guardianMutation<ArgsValidator extends PropertyValidators, Result>(
  config: GuardianFunctionConfig<MutationCtx, ArgsValidator, Result>,
) {
  return mutation({
    args: { guardianInstallationId: v.string(), ...config.args },
    handler: async (ctx, args) => {
      const request = createRequestMetadata(config.operation);
      try {
        const identity = await requireClerkIdentity(ctx);
        const actor = await resolveGuardianActor(ctx, identity, args.guardianInstallationId);
        const { guardianInstallationId: _installationId, ...applicationArgs } = args;
        const result = await config.handler(ctx, actor, applicationArgs as ObjectType<ArgsValidator>);
        logRequestOutcome({
          requestId: request.requestId,
          operation: request.operation,
          actorKind: "guardian",
          outcome: "success",
          durationMs: Date.now() - request.startedAt,
        });
        return result;
      } catch (error) {
        const safe = appErrorData(error);
        logRequestOutcome({
          requestId: request.requestId,
          operation: request.operation,
          actorKind: "guardian",
          outcome: "failure",
          durationMs: Date.now() - request.startedAt,
          errorCode: safe?.code ?? "INTERNAL_ERROR",
        });
        if (safe !== null) throw error;
        throw appError("INTERNAL_ERROR", `The request could not be completed. Reference: ${request.requestId}`);
      }
    },
  });
}

export function guardianQuery<ArgsValidator extends PropertyValidators, Result>(
  config: GuardianFunctionConfig<QueryCtx, ArgsValidator, Result>,
) {
  return query({
    args: { guardianInstallationId: v.string(), ...config.args },
    handler: async (ctx, args) => {
      const request = createRequestMetadata(config.operation);
      try {
        const identity = await requireClerkIdentity(ctx);
        const actor = await resolveGuardianActor(ctx, identity, args.guardianInstallationId);
        const { guardianInstallationId: _installationId, ...applicationArgs } = args;
        return await config.handler(ctx, actor, applicationArgs as ObjectType<ArgsValidator>);
      } catch (error) {
        const safe = appErrorData(error);
        logRequestOutcome({
          requestId: request.requestId,
          operation: request.operation,
          actorKind: "guardian",
          outcome: "failure",
          durationMs: Date.now() - request.startedAt,
          errorCode: safe?.code ?? "INTERNAL_ERROR",
        });
        if (safe !== null) throw error;
        throw appError("INTERNAL_ERROR", `The request could not be completed. Reference: ${request.requestId}`);
      }
    },
  });
}
