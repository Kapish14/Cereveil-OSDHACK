import { env, MutationCtx, QueryCtx } from "../../_generated/server";
import { Doc, Id } from "../../_generated/dataModel";
import { internal } from "../../_generated/api";
import { throwAppError } from "../../lib/errors";

export const REQUEST_LIFETIME_MS = 2 * 60 * 1000;
export const COOLDOWN_MS = 3 * 60 * 1000;
export const MAX_SDP_BYTES = 64 * 1024;
export const MAX_ICE_BYTES = 4 * 1024;
export const MAX_ICE_PER_SENDER = 32;

export async function liveRequestForChild(
  ctx: QueryCtx | MutationCtx,
  childProfileId: Id<"childProfiles">,
) {
  return await ctx.db
    .query("remoteAudioRequests")
    .withIndex("by_child_profile_id", (q) => q.eq("childProfileId", childProfileId))
    .unique();
}

export async function cooldownForChild(
  ctx: QueryCtx | MutationCtx,
  childProfileId: Id<"childProfiles">,
) {
  return await ctx.db
    .query("remoteAudioCooldowns")
    .withIndex("by_child_profile_id", (q) => q.eq("childProfileId", childProfileId))
    .unique();
}

export async function terminateRequest(
  ctx: MutationCtx,
  request: Doc<"remoteAudioRequests">,
  serverNow: number,
) {
  const signals = await ctx.db
    .query("remoteAudioSignals")
    .withIndex("by_remote_audio_request_id", (q) => q.eq("remoteAudioRequestId", request._id))
    .take(70);
  for (const signal of signals) await ctx.db.delete("remoteAudioSignals", signal._id);

  const commands = await ctx.db
    .query("childDeviceCommands")
    .withIndex("by_active_enrollment_id_and_intent_key", (q) =>
      q.eq("activeEnrollmentId", request.activeEnrollmentId).eq("intentKey", `remote_audio:${request._id}`),
    )
    .take(2);
  for (const command of commands) {
    const attempts = await ctx.db
      .query("fcmDeliveryAttempts")
      .withIndex("by_record_kind_and_record_id", (q) =>
        q.eq("recordKind", "childDeviceCommand").eq("recordId", command._id),
      )
      .take(60);
    for (const attempt of attempts) await ctx.db.delete("fcmDeliveryAttempts", attempt._id);
    await ctx.db.delete("childDeviceCommands", command._id);
  }
  await ctx.db.delete("remoteAudioRequests", request._id);

  const cooldownUntil = serverNow + COOLDOWN_MS;
  const current = await cooldownForChild(ctx, request.childProfileId);
  if (current === null) {
    await ctx.db.insert("remoteAudioCooldowns", { childProfileId: request.childProfileId, cooldownUntil });
  } else {
    await ctx.db.patch("remoteAudioCooldowns", current._id, { cooldownUntil: Math.max(current.cooldownUntil, cooldownUntil) });
  }
  await ctx.scheduler.runAt(cooldownUntil, internal.modules.remoteAudio.internal.clearCooldown, {
    childProfileId: request.childProfileId,
    expectedCooldownUntil: cooldownUntil,
  });
  return cooldownUntil;
}

export async function publishSignal(
  ctx: MutationCtx,
  request: Doc<"remoteAudioRequests">,
  args: {
    sender: "guardian" | "child";
    type: "offer" | "answer" | "ice_candidate";
    idempotencyKey: string;
    payload: string;
  },
) {
  if (request.expiresAt <= Date.now() || request.status === "awaiting_child") throwAppError("REMOTE_AUDIO_UNAVAILABLE");
  if (args.idempotencyKey.length < 8 || args.idempotencyKey.length > 200) throwAppError("VALIDATION_FAILED");
  const allowed = args.sender === "child"
    ? args.type === "offer" || args.type === "ice_candidate"
    : args.type === "answer" || args.type === "ice_candidate";
  if (!allowed) throwAppError("VALIDATION_FAILED");
  const size = new TextEncoder().encode(args.payload).byteLength;
  const limit = args.type === "ice_candidate" ? MAX_ICE_BYTES : MAX_SDP_BYTES;
  if (size === 0 || size > limit) throwAppError("VALIDATION_FAILED");
  const existing = await ctx.db
    .query("remoteAudioSignals")
    .withIndex("by_remote_audio_request_id_and_sender_and_idempotency_key", (q) =>
      q.eq("remoteAudioRequestId", request._id).eq("sender", args.sender).eq("idempotencyKey", args.idempotencyKey),
    )
    .unique();
  if (existing !== null) {
    if (existing.type !== args.type || existing.payload !== args.payload) throwAppError("VALIDATION_FAILED");
    return existing._id;
  }
  const signals = await ctx.db
    .query("remoteAudioSignals")
    .withIndex("by_remote_audio_request_id", (q) => q.eq("remoteAudioRequestId", request._id))
    .take(70);
  if (args.type === "ice_candidate") {
    if (signals.filter((signal) => signal.sender === args.sender && signal.type === "ice_candidate").length >= MAX_ICE_PER_SENDER) {
      throwAppError("VALIDATION_FAILED");
    }
  } else if (signals.some((signal) => signal.sender === args.sender && signal.type === args.type)) {
    throwAppError("VALIDATION_FAILED");
  }
  return await ctx.db.insert("remoteAudioSignals", {
    remoteAudioRequestId: request._id,
    sender: args.sender,
    type: args.type,
    idempotencyKey: args.idempotencyKey,
    payload: args.payload,
    createdAt: Date.now(),
    expiresAt: request.expiresAt,
  });
}

export function stunUrls(environment: "dev" | "prod") {
  const configured = env.REMOTE_AUDIO_STUN_URLS
    ?.split(",")
    .map((url) => url.trim())
    .filter((url) => url.startsWith("stun:") && url.length <= 500)
    .slice(0, 4);
  if (configured?.length) return configured;
  if (environment === "dev") return ["stun:stun.l.google.com:19302"];
  return [];
}

export function signalView(signal: Doc<"remoteAudioSignals">) {
  return { signalId: signal._id, sender: signal.sender, type: signal.type, payload: signal.payload, createdAt: signal.createdAt };
}

export async function incrementRemoteAudioAggregate(
  ctx: MutationCtx,
  field: "requestsCreated" | "sessionsConnected",
) {
  const counter = await ctx.db.query("remoteAudioAggregateCounters")
    .withIndex("by_key", (q) => q.eq("key", "global")).unique();
  if (counter === null) {
    await ctx.db.insert("remoteAudioAggregateCounters", {
      key: "global",
      requestsCreated: field === "requestsCreated" ? 1 : 0,
      sessionsConnected: field === "sessionsConnected" ? 1 : 0,
    });
  } else {
    await ctx.db.patch("remoteAudioAggregateCounters", counter._id, { [field]: counter[field] + 1 });
  }
}
