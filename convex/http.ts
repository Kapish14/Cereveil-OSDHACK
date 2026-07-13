import { httpRouter } from "convex/server";
import { httpAction } from "./_generated/server";
import { env } from "./_generated/server";
import { internal } from "./_generated/api";
import { Id } from "./_generated/dataModel";
import { base64UrlDecode, randomBase64Url, sha256Base64Url } from "./lib/encoding";
import {
  CHILD_DEVICE_JWT_LIFETIME_MS,
  issueChildDeviceJwt,
} from "./modules/deviceIdentity/jwt";
import { encryptPushToken } from "./lib/sensitive";
import { childDeviceHttpAction } from "./lib/childDeviceHttpAction";
import { throwAppError } from "./lib/errors";

const http = httpRouter();

http.route({
  path: "/device-identity/enrollment/preview",
  method: "POST",
  handler: httpAction(async (ctx, request) => {
    const body = await parseJson(request);
    const code = typeof body?.code === "string" ? body.code : null;
    if (code === null || !/^[A-Za-z0-9_-]{22}$/.test(code)) return invalidCodeResponse();

    const serverNow = Date.now();
    const preview = await ctx.runQuery(internal.modules.deviceIdentity.internal.previewEnrollmentCode, {
      codeHash: await sha256Base64Url(code),
      serverNow,
    });
    if (preview === null) return invalidCodeResponse();
    return jsonResponse(preview);
  }),
});

const TOKEN_CHALLENGE_LIFETIME_MS = 2 * 60 * 1000;

http.route({
  path: "/device-identity/token/challenge",
  method: "POST",
  handler: httpAction(async (ctx, request) => {
    const body = await parseJson(request);
    const credentialId = stringField(body, "credentialId");
    if (credentialId === null) return unauthorizedResponse();
    const typedCredentialId = credentialId as Id<"childDeviceCredentials">;
    const challenge = randomBase64Url(32);
    const serverNow = Date.now();
    const expiresAt = serverNow + TOKEN_CHALLENGE_LIFETIME_MS;
    const created = await ctx.runMutation(
      internal.modules.deviceIdentity.internal.createTokenChallenge,
      {
        credentialId: typedCredentialId,
        challengeHash: await sha256Base64Url(challenge),
        expiresAt,
        serverNow,
      },
    );
    if (created === null) return unauthorizedResponse();
    return jsonResponse({ challenge, expiresAt, serverNow });
  }),
});

http.route({
  path: "/device-identity/token/exchange",
  method: "POST",
  handler: httpAction(async (ctx, request) => {
    const body = await parseJson(request);
    const credentialId = stringField(body, "credentialId");
    const challenge = stringField(body, "challenge");
    const proof = stringField(body, "proof");
    if (credentialId === null || challenge === null || proof === null) return unauthorizedResponse();
    const typedCredentialId = credentialId as Id<"childDeviceCredentials">;
    const serverNow = Date.now();
    const challengeHash = await sha256Base64Url(challenge);
    const current = await ctx.runQuery(internal.modules.deviceIdentity.internal.getTokenChallenge, {
      credentialId: typedCredentialId,
      challengeHash,
      serverNow,
    });
    if (
      current === null ||
      !(await verifyCredentialProof(
        current.publicKeySpki,
        `cereveil-child-token-refresh-v1\n${credentialId}\n${challenge}`,
        proof,
      ))
    ) {
      return unauthorizedResponse();
    }
    const actor = await ctx.runMutation(
      internal.modules.deviceIdentity.internal.consumeTokenChallenge,
      { credentialId: typedCredentialId, challengeHash, serverNow },
    );
    if (actor === null) return unauthorizedResponse();
    return jsonResponse({
      accessJwt: await issueChildDeviceJwt(actor, serverNow),
      accessJwtExpiresAt: serverNow + CHILD_DEVICE_JWT_LIFETIME_MS,
      serverNow,
    });
  }),
});

http.route({
  path: "/child/policy",
  method: "GET",
  handler: childDeviceHttpAction({
    operation: "child.policy.fetch",
    logSuccess: false,
    handler: async (ctx, actor) =>
      await ctx.runQuery(internal.modules.policies.internal.getCurrentPolicy, { actor, input: {} }),
  }),
});

http.route({
  path: "/child/safety-alerts",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.safetyAlerts.create",
    logSuccess: false,
    handler: async (ctx, actor, request) => {
      const body = await parseJson(request);
      const incidentId = stringField(body, "incidentId");
      const type = stringField(body, "type");
      const packageName = stringField(body, "packageName");
      const confidenceBand = stringField(body, "confidenceBand");
      const policyVersion = numberField(body, "policyVersion");
      const occurredAt = numberField(body, "occurredAt");
      if (
        incidentId === null || packageName === null || policyVersion === null || occurredAt === null ||
        (type !== "scam_text" && type !== "nsfw_screen") ||
        (confidenceBand !== "low" && confidenceBand !== "medium" && confidenceBand !== "high")
      ) throwAppError("VALIDATION_FAILED");
      return await ctx.runMutation(internal.modules.safetyAlerts.internal.createSafetyAlert, {
        actor,
        input: { incidentId, type, packageName, confidenceBand, policyVersion, occurredAt, serverNow: Date.now() },
      });
    },
  }),
});

http.route({
  path: "/child/app-catalog/generations/start",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.appCatalog.start",
    logSuccess: true,
    handler: async (ctx, actor, request) => {
      const expectedCount = numberField(await parseJson(request), "expectedCount");
      if (expectedCount === null) throwAppError("VALIDATION_FAILED");
      return await ctx.runMutation(internal.modules.appCatalog.internal.startGeneration, {
        actor,
        input: { expectedCount, serverNow: Date.now() },
      });
    },
  }),
});

http.route({
  path: "/child/app-catalog/generations/batch",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.appCatalog.batch",
    logSuccess: false,
    handler: async (ctx, actor, request) => {
      const body = await parseJson(request);
      const generationId = stringField(body, "generationId");
      const apps = appCatalogAppsField(body);
      if (generationId === null || apps === null) throwAppError("VALIDATION_FAILED");
      return await ctx.runMutation(internal.modules.appCatalog.internal.uploadBatch, {
        actor,
        input: {
          generationId: generationId as Id<"appCatalogGenerations">,
          apps,
          serverNow: Date.now(),
        },
      });
    },
  }),
});

http.route({
  path: "/child/app-catalog/generations/complete",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.appCatalog.complete",
    logSuccess: true,
    handler: async (ctx, actor, request) => {
      const generationId = stringField(await parseJson(request), "generationId");
      if (generationId === null) throwAppError("VALIDATION_FAILED");
      return await ctx.runMutation(internal.modules.appCatalog.internal.completeGeneration, {
        actor,
        input: {
          generationId: generationId as Id<"appCatalogGenerations">,
          serverNow: Date.now(),
        },
      });
    },
  }),
});

http.route({
  path: "/child/access-requests",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.access.createRequest",
    logSuccess: true,
    handler: async (ctx, actor, request) => {
      const body = await parseJson(request);
      const packageName = stringField(body, "packageName");
      const appliedPolicyVersion = numberField(body, "appliedPolicyVersion");
      const blockKind = stringField(body, "blockKind");
      const scheduledCoverageEnd = optionalNumberField(body, "scheduledCoverageEnd");
      if (
        packageName === null ||
        appliedPolicyVersion === null ||
        (blockKind !== "manual" && blockKind !== "scheduled") ||
        scheduledCoverageEnd === null
      ) throwAppError("VALIDATION_FAILED");
      return await ctx.runMutation(internal.modules.access.internal.createAccessRequest, {
        actor,
        input: {
          packageName,
          appliedPolicyVersion,
          blockKind,
          ...(scheduledCoverageEnd === undefined ? {} : { scheduledCoverageEnd }),
          serverNow: Date.now(),
        },
      });
    },
  }),
});

http.route({
  path: "/child/access-grants",
  method: "GET",
  handler: childDeviceHttpAction({
    operation: "child.access.reconcileGrants",
    logSuccess: false,
    handler: async (ctx, actor) => await ctx.runQuery(
      internal.modules.access.internal.reconcileAccessGrants,
      { actor, input: { serverNow: Date.now() } },
    ),
  }),
});

http.route({
  path: "/child/access-requests/outcome",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.access.requestOutcome",
    logSuccess: false,
    handler: async (ctx, actor, request) => {
      const requestId = stringField(await parseJson(request), "requestId");
      if (requestId === null) throwAppError("VALIDATION_FAILED");
      return await ctx.runQuery(internal.modules.access.internal.getAccessRequestOutcome, {
        actor,
        input: { requestId: requestId as Id<"accessRequests">, serverNow: Date.now() },
      });
    },
  }),
});

http.route({
  path: "/child/location",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.location.record",
    logSuccess: false,
    handler: async (ctx, actor, request) => {
      const body = await parseJson(request);
      const latitude = numberField(body, "latitude");
      const longitude = numberField(body, "longitude");
      const accuracyMeters = numberField(body, "accuracyMeters");
      const capturedAt = numberField(body, "capturedAt");
      const refreshRequestId = optionalStringField(body, "refreshRequestId");
      if (latitude === null || longitude === null || accuracyMeters === null || capturedAt === null || refreshRequestId === null) {
        throwAppError("VALIDATION_FAILED");
      }
      return await ctx.runMutation(internal.modules.location.internal.recordMeasurement, {
        actor,
        input: {
          latitude,
          longitude,
          accuracyMeters,
          capturedAt,
          ...(refreshRequestId === undefined ? {} : { refreshRequestId: refreshRequestId as Id<"locationRefreshRequests"> }),
          serverNow: Date.now(),
        },
      });
    },
  }),
});

http.route({
  path: "/child/location/refresh/fail",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.location.failRefresh",
    logSuccess: true,
    handler: async (ctx, actor, request) => {
      const body = await parseJson(request);
      const refreshRequestId = stringField(body, "refreshRequestId");
      const reason = stringField(body, "reason");
      if (refreshRequestId === null || (reason !== "measurement_failed" && reason !== "capability_unavailable")) {
        throwAppError("VALIDATION_FAILED");
      }
      return await ctx.runMutation(internal.modules.location.internal.failRefresh, {
        actor,
        input: {
          refreshRequestId: refreshRequestId as Id<"locationRefreshRequests">,
          reason,
          serverNow: Date.now(),
        },
      });
    },
  }),
});

http.route({
  path: "/child/screen-time/snapshots/start",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.screenTime.start",
    logSuccess: true,
    handler: async (ctx, actor, request) => {
      const body = await parseJson(request);
      const refreshRequestId = stringField(body, "refreshRequestId");
      const expectedCount = numberField(body, "expectedCount");
      const measuredAt = numberField(body, "measuredAt");
      const localDayStart = numberField(body, "localDayStart");
      const validUntil = numberField(body, "validUntil");
      if (refreshRequestId === null || expectedCount === null || measuredAt === null || localDayStart === null || validUntil === null) {
        throwAppError("VALIDATION_FAILED");
      }
      return await ctx.runMutation(internal.modules.screenTime.internal.startSnapshot, {
        actor,
        input: {
          refreshRequestId: refreshRequestId as Id<"screenTimeRefreshRequests">,
          expectedCount,
          measuredAt,
          localDayStart,
          validUntil,
          serverNow: Date.now(),
        },
      });
    },
  }),
});

http.route({
  path: "/child/screen-time/snapshots/batch",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.screenTime.batch",
    logSuccess: false,
    handler: async (ctx, actor, request) => {
      const body = await parseJson(request);
      const snapshotId = stringField(body, "snapshotId");
      const rows = screenTimeRowsField(body);
      if (snapshotId === null || rows === null) throwAppError("VALIDATION_FAILED");
      return await ctx.runMutation(internal.modules.screenTime.internal.uploadSnapshotBatch, {
        actor,
        input: { snapshotId: snapshotId as Id<"screenTimeSnapshots">, rows, serverNow: Date.now() },
      });
    },
  }),
});

http.route({
  path: "/child/screen-time/snapshots/complete",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.screenTime.complete",
    logSuccess: true,
    handler: async (ctx, actor, request) => {
      const snapshotId = stringField(await parseJson(request), "snapshotId");
      if (snapshotId === null) throwAppError("VALIDATION_FAILED");
      return await ctx.runMutation(internal.modules.screenTime.internal.completeSnapshot, {
        actor,
        input: { snapshotId: snapshotId as Id<"screenTimeSnapshots">, serverNow: Date.now() },
      });
    },
  }),
});

http.route({
  path: "/child/policy/acknowledge",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.policy.acknowledge",
    logSuccess: true,
    handler: async (ctx, actor, request) => {
      const appliedPolicyVersion = numberField(await parseJson(request), "appliedPolicyVersion");
      if (appliedPolicyVersion === null) throwAppError("VALIDATION_FAILED");
      await ctx.runMutation(internal.modules.policies.internal.acknowledgePolicy, {
        actor,
        input: { appliedPolicyVersion, serverNow: Date.now() },
      });
      return { ok: true };
    },
  }),
});

http.route({
  path: "/child/heartbeat",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.heartbeat",
    logSuccess: true,
    handler: async (ctx, actor, request) => {
      const body = await parseJson(request);
      const supportedPolicySchemaVersion = numberField(body, "supportedPolicySchemaVersion");
      const supportsNsfwValue = body?.supportsNsfwScreenDetection;
      const supportsNsfwScreenDetection = typeof supportsNsfwValue === "boolean"
        ? supportsNsfwValue
        : (supportedPolicySchemaVersion ?? 0) >= 3;
      const capabilities = supportedPolicySchemaVersion === null
        ? null
        : capabilitiesField(body, supportedPolicySchemaVersion);
      if (capabilities === null || supportedPolicySchemaVersion === null) throwAppError("VALIDATION_FAILED");
      return await ctx.runMutation(internal.modules.deviceIdentity.internal.recordHeartbeat, {
        actor,
        input: { capabilities, supportedPolicySchemaVersion, supportsNsfwScreenDetection, serverNow: Date.now() },
      });
    },
  }),
});

http.route({
  path: "/child/push-token",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.pushToken.register",
    logSuccess: true,
    handler: async (ctx, actor, request) => {
    const body = await parseJson(request);
    const token = stringField(body, "token");
    if (token === null || token.length > 4096) throwAppError("VALIDATION_FAILED");
    await ctx.runMutation(internal.modules.notifications.internal.registerPushToken, {
      actor,
      input: {
        tokenHash: await sha256Base64Url(token),
        encryptedToken: await encryptPushToken(token),
        serverNow: Date.now(),
      },
    });
    return { ok: true };
    },
  }),
});

http.route({
  path: "/child/commands",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.commands.reconcile",
    logSuccess: false,
    handler: async (ctx, actor, request) => {
      const body = await parseJson(request);
      const cursorValue = body?.cursor;
      if (cursorValue !== null && cursorValue !== undefined && typeof cursorValue !== "string") {
        throwAppError("VALIDATION_FAILED");
      }
      return await ctx.runQuery(internal.modules.commands.internal.reconcileCommands, {
        actor,
        input: { paginationOpts: { numItems: 50, cursor: typeof cursorValue === "string" ? cursorValue : null } },
      });
    },
  }),
});

http.route({
  path: "/child/commands/reject",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.commands.reject",
    logSuccess: true,
    handler: async (ctx, actor, request) => {
      const body = await parseJson(request);
      const commandId = stringField(body, "commandId");
      const reason = stringField(body, "reason");
      if (
        commandId === null ||
        !["unsupported_command", "invalid_command", "unable_to_apply", "unsupported_schema"].includes(reason ?? "")
      ) throwAppError("VALIDATION_FAILED");
      return await ctx.runMutation(internal.modules.commands.internal.rejectCommand, {
        actor,
        input: {
          commandId: commandId as Id<"childDeviceCommands">,
          reason: reason as "unsupported_command" | "invalid_command" | "unable_to_apply" | "unsupported_schema",
          serverNow: Date.now(),
        },
      });
    },
  }),
});

http.route({
  path: "/child/commands/acknowledge",
  method: "POST",
  handler: childDeviceHttpAction({
    operation: "child.commands.acknowledge",
    logSuccess: true,
    handler: async (ctx, actor, request) => {
      const commandId = stringField(await parseJson(request), "commandId");
      if (commandId === null) throwAppError("VALIDATION_FAILED");
      return await ctx.runMutation(internal.modules.commands.internal.acknowledgeFeatureCommand, {
        actor,
        input: {
          commandId: commandId as Id<"childDeviceCommands">,
          serverNow: Date.now(),
        },
      });
    },
  }),
});

http.route({
  path: "/device-identity/enrollment/complete",
  method: "POST",
  handler: httpAction(async (ctx, request) => {
    const body = await parseJson(request);
    const code = stringField(body, "code");
    const publicKeySpki = stringField(body, "publicKeySpki");
    const proof = stringField(body, "proof");
    const installationId = stringField(body, "installationId");
    const appBuild = stringField(body, "appBuild");
    const supportedPolicySchemaVersion = numberField(body, "supportedPolicySchemaVersion");
    const supportsNsfwScreenDetectionValue = body?.supportsNsfwScreenDetection;
    const supportsNsfwScreenDetection = typeof supportsNsfwScreenDetectionValue === "boolean"
      ? supportsNsfwScreenDetectionValue
      : (supportedPolicySchemaVersion ?? 0) >= 3;
    const deviceLabel = optionalStringField(body, "deviceLabel");
    if (
      code === null ||
      !/^[A-Za-z0-9_-]{22}$/.test(code) ||
      publicKeySpki === null ||
      proof === null ||
      installationId === null ||
      appBuild === null ||
      supportedPolicySchemaVersion === null ||
      installationId.length > 200 ||
      appBuild.length > 100 ||
      deviceLabel === null
    ) {
      return jsonResponse({ code: "ENROLLMENT_FAILED" }, 400);
    }

    // Validate signing configuration before consuming the one-use Enrollment Code.
    if (env.CHILD_DEVICE_JWT_SECRET.length < 32) {
      return jsonResponse({ code: "ENROLLMENT_FAILED" }, 500);
    }
    if (!(await verifyEnrollmentProof(code, publicKeySpki, proof))) {
      return jsonResponse({ code: "ENROLLMENT_FAILED" }, 400);
    }

    const serverNow = Date.now();
    const result = await ctx.runMutation(internal.modules.deviceIdentity.internal.completeEnrollment, {
      codeHash: await sha256Base64Url(code),
      publicKeySpki,
      installationId,
      ...(deviceLabel === undefined ? {} : { deviceLabel }),
      appBuild,
      supportedPolicySchemaVersion,
      supportsNsfwScreenDetection,
      serverNow,
    });
    if (result.kind === "invalid_code") return invalidCodeResponse();
    if (result.kind === "already_enrolled") {
      return jsonResponse({ code: "CHILD_ALREADY_ENROLLED" }, 409);
    }
    if (result.kind === "unsupported_policy") {
      return jsonResponse({ code: "POLICY_UNSUPPORTED" }, 409);
    }

    const accessJwt = await issueChildDeviceJwt(result, serverNow);
    return jsonResponse({
      childDeviceId: result.childDeviceId,
      activeEnrollmentId: result.activeEnrollmentId,
      credentialId: result.credentialId,
      childDisplayName: result.childDisplayName,
      desiredPolicyVersion: result.desiredPolicyVersion,
      accessJwt,
      accessJwtExpiresAt: serverNow + CHILD_DEVICE_JWT_LIFETIME_MS,
      enrolledAt: result.enrolledAt,
      serverNow,
      environment: result.environment,
    });
  }),
});

async function parseJson(request: Request): Promise<Record<string, unknown> | null> {
  try {
    const body: unknown = await request.json();
    return typeof body === "object" && body !== null ? (body as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

function invalidCodeResponse() {
  return jsonResponse({ code: "ENROLLMENT_CODE_INVALID" }, 400);
}

function stringField(body: Record<string, unknown> | null, key: string) {
  const value = body?.[key];
  return typeof value === "string" && value.length > 0 ? value : null;
}

function optionalStringField(body: Record<string, unknown> | null, key: string) {
  const value = body?.[key];
  if (value === undefined) return undefined;
  return typeof value === "string" && value.length <= 100 ? value : null;
}

async function verifyEnrollmentProof(code: string, publicKeySpki: string, proof: string) {
  return await verifyCredentialProof(
    publicKeySpki,
    `cereveil-child-enrollment-v1\n${code}\n${publicKeySpki}`,
    proof,
  );
}

async function verifyCredentialProof(publicKeySpki: string, message: string, proof: string) {
  try {
    const key = await crypto.subtle.importKey(
      "spki",
      arrayBuffer(base64UrlDecode(publicKeySpki)),
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
    return await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      key,
      arrayBuffer(base64UrlDecode(proof)),
      new TextEncoder().encode(message),
    );
  } catch {
    return false;
  }
}

function numberField(body: Record<string, unknown> | null, key: string) {
  const value = body?.[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function optionalNumberField(body: Record<string, unknown> | null, key: string) {
  const value = body?.[key];
  if (value === undefined) return undefined;
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function appCatalogAppsField(body: Record<string, unknown> | null) {
  const value = body?.apps;
  if (!Array.isArray(value)) return null;
  const apps: Array<{ packageName: string; label: string }> = [];
  for (const item of value) {
    if (typeof item !== "object" || item === null) return null;
    const packageName = (item as Record<string, unknown>).packageName;
    const label = (item as Record<string, unknown>).label;
    if (typeof packageName !== "string" || typeof label !== "string") return null;
    apps.push({ packageName, label });
  }
  return apps;
}

function screenTimeRowsField(body: Record<string, unknown> | null) {
  const value = body?.rows;
  if (!Array.isArray(value)) return null;
  const rows: Array<{ packageName: string; totalTimeInForegroundMs: number }> = [];
  for (const item of value) {
    if (typeof item !== "object" || item === null) return null;
    const packageName = (item as Record<string, unknown>).packageName;
    const totalTimeInForegroundMs = (item as Record<string, unknown>).totalTimeInForegroundMs;
    if (typeof packageName !== "string" || typeof totalTimeInForegroundMs !== "number") return null;
    rows.push({ packageName, totalTimeInForegroundMs });
  }
  return rows;
}

function capabilitiesField(body: Record<string, unknown> | null, supportedPolicySchemaVersion: number) {
  const value = body?.capabilities;
  if (typeof value !== "object" || value === null) return null;
  const capabilities = value as Record<string, unknown>;
  const keys = [
    "accessibilityService",
    "usageAccess",
    "location",
    "microphone",
    "notificationAccess",
    "batteryOptimizationExempt",
  ] as const;
  if (keys.some((key) => typeof capabilities[key] !== "boolean")) return null;
  const trustedDeviceTime = capabilities.trustedDeviceTime;
  if (supportedPolicySchemaVersion >= 2 && typeof trustedDeviceTime !== "boolean") return null;
  return {
    accessibilityService: capabilities.accessibilityService as boolean,
    usageAccess: capabilities.usageAccess as boolean,
    location: capabilities.location as boolean,
    microphone: capabilities.microphone as boolean,
    notificationAccess: capabilities.notificationAccess as boolean,
    batteryOptimizationExempt: capabilities.batteryOptimizationExempt as boolean,
    trustedDeviceTime: supportedPolicySchemaVersion >= 2
      ? trustedDeviceTime as boolean
      : true,
  };
}

function unauthorizedResponse() {
  return jsonResponse({ code: "CHILD_DEVICE_UNAUTHORIZED" }, 401);
}

function arrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.length);
  copy.set(bytes);
  return copy.buffer;
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

export default http;
