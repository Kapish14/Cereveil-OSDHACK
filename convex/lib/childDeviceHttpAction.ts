import { httpAction, ActionCtx } from "../_generated/server";
import { internal } from "../_generated/api";
import { appErrorData, AppErrorCode } from "./errors";
import { createRequestMetadata, logRequestOutcome } from "./requestLogging";
import { verifyChildDeviceJwt } from "../modules/deviceIdentity/jwt";
import { ChildDeviceActor } from "../modules/deviceIdentity/internal";
import { Value } from "convex/values";

type ChildHttpConfig<Result extends Value> = {
  operation: string;
  logSuccess: boolean;
  handler: (ctx: ActionCtx, actor: ChildDeviceActor, request: Request) => Promise<Result>;
};

export function childDeviceHttpAction<Result extends Value>(config: ChildHttpConfig<Result>) {
  return httpAction(async (ctx, request) => {
    const metadata = createRequestMetadata(config.operation);
    try {
      const actor = await authenticateChildDevice(ctx, request);
      if (actor === null) return failure("CHILD_DEVICE_UNAUTHORIZED", 401, metadata.requestId, metadata, config.operation);
      const result = await config.handler(ctx, actor, request);
      if (config.logSuccess) {
        logRequestOutcome({
          requestId: metadata.requestId,
          operation: config.operation,
          actorKind: "child_device",
          outcome: "success",
          durationMs: Date.now() - metadata.startedAt,
        });
      }
      return jsonResponse(result, 200, metadata.requestId);
    } catch (error) {
      const safe = appErrorData(error);
      const code = safe?.code ?? "INTERNAL_ERROR";
      return failure(code, statusFor(code), metadata.requestId, metadata, config.operation);
    }
  });
}

async function authenticateChildDevice(ctx: ActionCtx, request: Request): Promise<ChildDeviceActor | null> {
  const authorization = request.headers.get("authorization");
  if (authorization === null || !authorization.startsWith("Bearer ")) return null;
  try {
    const claims = await verifyChildDeviceJwt(authorization.slice(7), Date.now());
    if (claims === null) return null;
    return await ctx.runQuery(internal.modules.deviceIdentity.internal.resolveChildDeviceActor, {
      credentialId: claims.credentialId,
      activeEnrollmentId: claims.activeEnrollmentId,
      childDeviceId: claims.childDeviceId,
    });
  } catch {
    return null;
  }
}

function failure(
  code: AppErrorCode,
  status: number,
  requestId: string,
  metadata: { startedAt: number },
  operation: string,
) {
  logRequestOutcome({
    requestId,
    operation,
    actorKind: "child_device",
    outcome: "failure",
    durationMs: Date.now() - metadata.startedAt,
    errorCode: code,
  });
  return jsonResponse({ code }, status, requestId);
}

function statusFor(code: AppErrorCode) {
  if (code === "CHILD_DEVICE_UNAUTHORIZED") return 401;
  if (code === "POLICY_VERSION_MISMATCH") return 409;
  if (code === "VALIDATION_FAILED") return 400;
  return 500;
}

export function jsonResponse(body: unknown, status: number, requestId: string) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "x-request-id": requestId },
  });
}
