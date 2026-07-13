import { v } from "convex/values";
import { guardianMutation, guardianQuery } from "../../lib/functionWrappers";
import { requireGuardianForChildProfile } from "../../lib/authorize";
import { throwAppError } from "../../lib/errors";
import { internal } from "../../_generated/api";
import { cooldownForChild, incrementRemoteAudioAggregate, liveRequestForChild, publishSignal, REQUEST_LIFETIME_MS, signalView, stunUrls, terminateRequest } from "./model";

export const getRemoteAudioState = guardianQuery({
  operation: "remoteAudio.getState",
  privacySensitive: true,
  args: { childProfileId: v.id("childProfiles") },
  handler: async (ctx, actor, args) => {
    await requireGuardianForChildProfile(ctx, actor, args.childProfileId);
    const serverNow = Date.now();
    const request = await liveRequestForChild(ctx, args.childProfileId);
    if (request !== null && request.expiresAt > serverNow) {
      if (request.requestedByGuardianDeviceId !== actor.guardianDeviceId) {
        return { availability: "busy" as const, request: null, serverNow };
      }
      return {
        availability: "live" as const,
        request: requestView(request),
        signals: (await ctx.db
          .query("remoteAudioSignals")
          .withIndex("by_remote_audio_request_id", (q) => q.eq("remoteAudioRequestId", request._id))
          .take(66))
          .filter((signal) => signal.sender === "child")
          .map(signalView),
        stunUrls: stunUrls((await ctx.db.get("childDevices", request.childDeviceId))?.environment ?? "prod"),
        serverNow,
      };
    }
    const cooldown = await cooldownForChild(ctx, args.childProfileId);
    const cooldownUntil = request !== null ? request.expiresAt + 3 * 60 * 1000 : cooldown?.cooldownUntil;
    if (cooldownUntil !== undefined && cooldownUntil > serverNow) {
      return { availability: "cooldown" as const, request: null, cooldownUntil, serverNow };
    }
    const unavailableReason = await availabilityReason(ctx, args.childProfileId);
    return unavailableReason === null
      ? { availability: "ready" as const, request: null, serverNow }
      : { availability: "unavailable" as const, reason: unavailableReason, request: null, serverNow };
  },
});

export const createRemoteAudioRequest = guardianMutation({
  operation: "remoteAudio.create",
  privacySensitive: true,
  args: { childProfileId: v.id("childProfiles"), operationId: v.string() },
  handler: async (ctx, actor, args) => {
    await requireGuardianForChildProfile(ctx, actor, args.childProfileId);
    if (args.operationId.length < 8 || args.operationId.length > 200) throwAppError("VALIDATION_FAILED");
    const serverNow = Date.now();
    const current = await liveRequestForChild(ctx, args.childProfileId);
    if (current !== null) {
      if (current.expiresAt <= serverNow) await terminateRequest(ctx, current, current.expiresAt);
      else if (current.requestedByGuardianDeviceId !== actor.guardianDeviceId) throwAppError("REMOTE_AUDIO_BUSY");
      else return { requestId: current._id, status: current.status, expiresAt: current.expiresAt, serverNow, reused: true };
    }
    const cooldown = await cooldownForChild(ctx, args.childProfileId);
    if (cooldown !== null && cooldown.cooldownUntil > serverNow) throwAppError("REMOTE_AUDIO_COOLDOWN");
    if (cooldown !== null) await ctx.db.delete("remoteAudioCooldowns", cooldown._id);
    const reason = await availabilityReason(ctx, args.childProfileId);
    if (reason !== null) throwAppError("REMOTE_AUDIO_UNAVAILABLE", reason);
    const enrollment = await ctx.db
      .query("activeEnrollments")
      .withIndex("by_child_profile_id_and_status", (q) => q.eq("childProfileId", args.childProfileId).eq("status", "active"))
      .unique();
    if (enrollment === null) throwAppError("REMOTE_AUDIO_UNAVAILABLE");
    const expiresAt = serverNow + REQUEST_LIFETIME_MS;
    const requestId = await ctx.db.insert("remoteAudioRequests", {
      householdId: actor.householdId,
      childProfileId: args.childProfileId,
      childDeviceId: enrollment.childDeviceId,
      activeEnrollmentId: enrollment._id,
      requestedByGuardianAccountId: actor.guardianAccountId,
      requestedByGuardianDeviceId: actor.guardianDeviceId,
      operationId: args.operationId,
      status: "awaiting_child",
      requestedAt: serverNow,
      expiresAt,
    });
    const commandId = await ctx.db.insert("childDeviceCommands", {
      householdId: actor.householdId,
      childProfileId: args.childProfileId,
      activeEnrollmentId: enrollment._id,
      childDeviceId: enrollment.childDeviceId,
      type: "request_remote_audio",
      referenceId: requestId,
      intentKey: `remote_audio:${requestId}`,
      status: "pending",
      createdAt: serverNow,
      updatedAt: serverNow,
      expiresAt,
    });
    await ctx.scheduler.runAfter(0, internal.fcmDelivery.deliver, {
      recordKind: "childDeviceCommand",
      recordId: commandId,
      attempt: 1,
    });
    await ctx.scheduler.runAt(expiresAt, internal.modules.remoteAudio.internal.expireRemoteAudioRequest, {
      requestId,
      expectedExpiresAt: expiresAt,
    });
    await incrementRemoteAudioAggregate(ctx, "requestsCreated");
    return { requestId, status: "awaiting_child" as const, expiresAt, serverNow, reused: false };
  },
});

export const terminateRemoteAudioRequest = guardianMutation({
  operation: "remoteAudio.terminate",
  privacySensitive: true,
  args: { requestId: v.id("remoteAudioRequests") },
  handler: async (ctx, actor, args) => {
    const request = await ctx.db.get("remoteAudioRequests", args.requestId);
    if (request === null) return { ended: true };
    if (request.householdId !== actor.householdId || request.requestedByGuardianAccountId !== actor.guardianAccountId) {
      throwAppError("UNAUTHENTICATED");
    }
    const cooldownUntil = await terminateRequest(ctx, request, Date.now());
    return { ended: true, cooldownUntil };
  },
});

export const terminateRemoteAudioForChild = guardianMutation({
  operation: "remoteAudio.terminateForChild",
  privacySensitive: true,
  args: { childProfileId: v.id("childProfiles") },
  handler: async (ctx, actor, args) => {
    await requireGuardianForChildProfile(ctx, actor, args.childProfileId);
    const request = await liveRequestForChild(ctx, args.childProfileId);
    if (request === null) return { ended: true };
    const cooldownUntil = await terminateRequest(ctx, request, Date.now());
    return { ended: true, cooldownUntil };
  },
});

export const publishRemoteAudioSignal = guardianMutation({
  operation: "remoteAudio.signal",
  privacySensitive: true,
  args: {
    requestId: v.id("remoteAudioRequests"),
    type: v.union(v.literal("answer"), v.literal("ice_candidate")),
    idempotencyKey: v.string(),
    payload: v.string(),
  },
  handler: async (ctx, actor, args) => {
    const request = await ctx.db.get("remoteAudioRequests", args.requestId);
    if (request === null || request.requestedByGuardianDeviceId !== actor.guardianDeviceId) throwAppError("UNAUTHENTICATED");
    const signalId = await publishSignal(ctx, request, { ...args, sender: "guardian" });
    return { signalId };
  },
});

async function availabilityReason(
  ctx: Parameters<typeof requireGuardianForChildProfile>[0],
  childProfileId: Parameters<typeof requireGuardianForChildProfile>[2],
) {
  const enrollment = await ctx.db
    .query("activeEnrollments")
    .withIndex("by_child_profile_id_and_status", (q) => q.eq("childProfileId", childProfileId).eq("status", "active"))
    .unique();
  if (enrollment === null) return "not_enrolled";
  const device = await ctx.db.get("childDevices", enrollment.childDeviceId);
  if (device === null || device.status !== "active") return "device_unavailable";
  const credential = await ctx.db
    .query("childDeviceCredentials")
    .withIndex("by_active_enrollment_id_and_status", (q) => q.eq("activeEnrollmentId", enrollment._id).eq("status", "active"))
    .unique();
  if (credential === null || credential.childDeviceId !== device._id) return "device_unavailable";
  if (stunUrls(device.environment).length === 0) return "network_configuration_unavailable";
  const health = await ctx.db
    .query("supervisionHealth")
    .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", enrollment._id))
    .unique();
  if (health?.connectivityStatus !== "online") return "offline";
  if (health.capabilities?.microphone !== true) return "microphone_unavailable";
  if (health.capabilities.notificationAccess !== true) return "notifications_unavailable";
  const tokens = await ctx.db
    .query("fcmTokens")
    .withIndex("by_child_device_id", (q) => q.eq("childDeviceId", enrollment.childDeviceId))
    .take(10);
  if (!tokens.some((token) => token.status === "active" && token.ownerKind === "childDevice")) return "notifications_unavailable";
  return null;
}

function requestView(request: {
  _id: string;
  status: "awaiting_child" | "connecting" | "active";
  requestedAt: number;
  startedAt?: number;
  expiresAt: number;
}) {
  return {
    requestId: request._id,
    status: request.status,
    requestedAt: request.requestedAt,
    ...(request.startedAt === undefined ? {} : { startedAt: request.startedAt }),
    expiresAt: request.expiresAt,
  };
}
