import { v } from "convex/values";
import { childDeviceMutation, childDeviceQuery } from "../../lib/functionWrappers";
import { throwAppError } from "../../lib/errors";
import { Id } from "../../_generated/dataModel";
import { incrementRemoteAudioAggregate, publishSignal, signalView, stunUrls, terminateRequest } from "./model";

export const getRemoteAudioState = childDeviceQuery({
  operation: "remoteAudio.child.getState",
  args: {},
  handler: async (ctx, actor) => {
    const request = await ctx.db
      .query("remoteAudioRequests")
      .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", actor.enrollment._id))
      .unique();
    const serverNow = Date.now();
    if (request === null || request.expiresAt <= serverNow) return { request: null, signals: [], serverNow };
    const signals = request.status === "awaiting_child" ? [] : await ctx.db
      .query("remoteAudioSignals")
      .withIndex("by_remote_audio_request_id", (q) => q.eq("remoteAudioRequestId", request._id))
      .take(66);
    return {
      request: {
        requestId: request._id,
        status: request.status,
        requestedAt: request.requestedAt,
        ...(request.startedAt === undefined ? {} : { startedAt: request.startedAt }),
        expiresAt: request.expiresAt,
      },
      signals: signals.filter((signal) => signal.sender === "guardian").map(signalView),
      stunUrls: stunUrls(actor.device.environment),
      serverNow,
    };
  },
});

export const startRemoteAudioRequest = childDeviceMutation({
  operation: "remoteAudio.child.start",
  args: { requestId: v.id("remoteAudioRequests") },
  handler: async (ctx, actor, args) => {
    const request = await requireOwnedRequest(ctx, actor.enrollment._id, args.requestId);
    if (request.expiresAt <= Date.now()) {
      await terminateRequest(ctx, request, request.expiresAt);
      throwAppError("REMOTE_AUDIO_UNAVAILABLE");
    }
    if (request.status === "awaiting_child") {
      await ctx.db.patch("remoteAudioRequests", request._id, { status: "connecting", startedAt: Date.now() });
    }
    return { status: request.status === "active" ? "active" as const : "connecting" as const };
  },
});

export const markRemoteAudioActive = childDeviceMutation({
  operation: "remoteAudio.child.active",
  args: { requestId: v.id("remoteAudioRequests") },
  handler: async (ctx, actor, args) => {
    const request = await requireOwnedRequest(ctx, actor.enrollment._id, args.requestId);
    if (request.status !== "connecting" || request.expiresAt <= Date.now()) throwAppError("VALIDATION_FAILED");
    await ctx.db.patch("remoteAudioRequests", request._id, { status: "active" });
    await incrementRemoteAudioAggregate(ctx, "sessionsConnected");
    return { status: "active" as const };
  },
});

export const terminateRemoteAudioRequest = childDeviceMutation({
  operation: "remoteAudio.child.terminate",
  args: {
    requestId: v.id("remoteAudioRequests"),
    reason: v.union(
      v.literal("declined"),
      v.literal("stopped"),
      v.literal("notification_unavailable"),
      v.literal("microphone_unavailable"),
      v.literal("webrtc_failed"),
      v.literal("interrupted"),
    ),
  },
  handler: async (ctx, actor, args) => {
    const request = await ctx.db.get("remoteAudioRequests", args.requestId);
    if (request === null) return { ended: true };
    if (request.activeEnrollmentId !== actor.enrollment._id) throwAppError("CHILD_DEVICE_UNAUTHORIZED");
    const cooldownUntil = await terminateRequest(ctx, request, Date.now());
    return { ended: true, cooldownUntil };
  },
});

export const publishRemoteAudioSignal = childDeviceMutation({
  operation: "remoteAudio.child.signal",
  args: {
    requestId: v.id("remoteAudioRequests"),
    type: v.union(v.literal("offer"), v.literal("ice_candidate")),
    idempotencyKey: v.string(),
    payload: v.string(),
  },
  handler: async (ctx, actor, args) => {
    const request = await requireOwnedRequest(ctx, actor.enrollment._id, args.requestId);
    const signalId = await publishSignal(ctx, request, { ...args, sender: "child" });
    return { signalId };
  },
});

async function requireOwnedRequest(
  ctx: Parameters<typeof terminateRequest>[0],
  activeEnrollmentId: Id<"activeEnrollments">,
  requestId: Id<"remoteAudioRequests">,
) {
  const request = await ctx.db.get("remoteAudioRequests", requestId);
  if (request === null || request.activeEnrollmentId !== activeEnrollmentId) throwAppError("CHILD_DEVICE_UNAUTHORIZED");
  return request;
}
