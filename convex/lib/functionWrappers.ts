import { PropertyValidators, ObjectType, v } from "convex/values";
import { mutation, MutationCtx, query, QueryCtx } from "../_generated/server";
import { requireClerkIdentity } from "./auth";
import { GuardianActor, resolveGuardianActor } from "./actors";
import { appError, appErrorData } from "./errors";
import { createRequestMetadata, logRequestOutcome } from "./requestLogging";
import { loadActiveChildActor } from "../modules/deviceIdentity/internal";
import { throwAppError } from "./errors";

type GuardianFunctionConfig<Ctx, ArgsValidator extends PropertyValidators, Result> = {
  operation: string;
  privacySensitive?: boolean;
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
        if (!config.privacySensitive) logRequestOutcome({
          requestId: request.requestId,
          operation: request.operation,
          actorKind: "guardian",
          outcome: "success",
          durationMs: Date.now() - request.startedAt,
        });
        return result;
      } catch (error) {
        const safe = appErrorData(error);
        if (!config.privacySensitive) logRequestOutcome({
          requestId: request.requestId,
          operation: request.operation,
          actorKind: "guardian",
          outcome: "failure",
          durationMs: Date.now() - request.startedAt,
          errorCode: safe?.code ?? "INTERNAL_ERROR",
        });
        if (safe !== null) throw error;
        throw appError("INTERNAL_ERROR", config.privacySensitive
          ? "The request could not be completed."
          : `The request could not be completed. Reference: ${request.requestId}`);
      }
    },
  });
}

type ChildActor = NonNullable<Awaited<ReturnType<typeof loadActiveChildActor>>>;
type ChildFunctionConfig<Ctx, ArgsValidator extends PropertyValidators, Result> = {
  operation: string;
  args: ArgsValidator;
  handler: (ctx: Ctx, actor: ChildActor, args: ObjectType<ArgsValidator>) => Promise<Result>;
};

export function childDeviceQuery<ArgsValidator extends PropertyValidators, Result>(
  config: ChildFunctionConfig<QueryCtx, ArgsValidator, Result>,
) {
  return query({
    args: { childInstallationId: v.string(), ...config.args },
    handler: async (ctx, args) => {
      createRequestMetadata(config.operation);
      try {
        const actor = await authenticateChildDevice(ctx);
        if (actor.device.installationId !== args.childInstallationId) throwAppError("CHILD_DEVICE_UNAUTHORIZED");
        const { childInstallationId: _installationId, ...applicationArgs } = args;
        return await config.handler(ctx, actor, applicationArgs as ObjectType<ArgsValidator>);
      } catch (error) {
        if (appErrorData(error) !== null) throw error;
        throw appError("INTERNAL_ERROR", "The request could not be completed.");
      }
    },
  });
}

export function childDeviceMutation<ArgsValidator extends PropertyValidators, Result>(
  config: ChildFunctionConfig<MutationCtx, ArgsValidator, Result>,
) {
  return mutation({
    args: { childInstallationId: v.string(), ...config.args },
    handler: async (ctx, args) => {
      createRequestMetadata(config.operation);
      try {
        const actor = await authenticateChildDevice(ctx);
        if (actor.device.installationId !== args.childInstallationId) throwAppError("CHILD_DEVICE_UNAUTHORIZED");
        const { childInstallationId: _installationId, ...applicationArgs } = args;
        return await config.handler(ctx, actor, applicationArgs as ObjectType<ArgsValidator>);
      } catch (error) {
        if (appErrorData(error) !== null) throw error;
        throw appError("INTERNAL_ERROR", "The request could not be completed.");
      }
    },
  });
}

async function authenticateChildDevice(ctx: QueryCtx | MutationCtx) {
  const identity = await ctx.auth.getUserIdentity();
  if (identity === null) throwAppError("CHILD_DEVICE_UNAUTHORIZED");
  const credentialId = childStringClaim(identity, "credentialId");
  const activeEnrollmentId = childStringClaim(identity, "activeEnrollmentId");
  const childDeviceId = childStringClaim(identity, "childDeviceId");
  const ids = {
    credentialId: ctx.db.normalizeId("childDeviceCredentials", credentialId),
    activeEnrollmentId: ctx.db.normalizeId("activeEnrollments", activeEnrollmentId),
    childDeviceId: ctx.db.normalizeId("childDevices", childDeviceId),
  };
  if (ids.credentialId === null || ids.activeEnrollmentId === null || ids.childDeviceId === null) {
    throwAppError("CHILD_DEVICE_UNAUTHORIZED");
  }
  const actor = await loadActiveChildActor(ctx, ids as {
    credentialId: NonNullable<typeof ids.credentialId>;
    activeEnrollmentId: NonNullable<typeof ids.activeEnrollmentId>;
    childDeviceId: NonNullable<typeof ids.childDeviceId>;
  });
  if (actor === null) throwAppError("CHILD_DEVICE_UNAUTHORIZED");
  return actor;
}

function childStringClaim(identity: Record<string, unknown>, key: string) {
  const value = identity[key];
  if (typeof value !== "string") throwAppError("CHILD_DEVICE_UNAUTHORIZED");
  return value;
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
        if (!config.privacySensitive) logRequestOutcome({
          requestId: request.requestId,
          operation: request.operation,
          actorKind: "guardian",
          outcome: "failure",
          durationMs: Date.now() - request.startedAt,
          errorCode: safe?.code ?? "INTERNAL_ERROR",
        });
        if (safe !== null) throw error;
        throw appError("INTERNAL_ERROR", config.privacySensitive
          ? "The request could not be completed."
          : `The request could not be completed. Reference: ${request.requestId}`);
      }
    },
  });
}
