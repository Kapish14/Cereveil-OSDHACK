import { GenericMutationCtx, makeFunctionReference } from "convex/server";
import { convexTest } from "convex-test";
import { expect, test, vi } from "vitest";
import { DataModel } from "./_generated/dataModel";
import schema from "./schema";

const modules = import.meta.glob("./**/*.ts");
const createRequest = makeFunctionReference<"mutation">("modules/remoteAudio/guardian:createRemoteAudioRequest");
const guardianState = makeFunctionReference<"query">("modules/remoteAudio/guardian:getRemoteAudioState");
const childState = makeFunctionReference<"query">("modules/remoteAudio/child:getRemoteAudioState");
const terminateAsChild = makeFunctionReference<"mutation">("modules/remoteAudio/child:terminateRemoteAudioRequest");
const startAsChild = makeFunctionReference<"mutation">("modules/remoteAudio/child:startRemoteAudioRequest");
const childPublishSignal = makeFunctionReference<"mutation">("modules/remoteAudio/child:publishRemoteAudioSignal");
const guardianPublishSignal = makeFunctionReference<"mutation">("modules/remoteAudio/guardian:publishRemoteAudioSignal");
const markActive = makeFunctionReference<"mutation">("modules/remoteAudio/child:markRemoteAudioActive");
const recordDelivery = makeFunctionReference<"mutation">("modules/notifications/internal:recordDeliveryOutcome");
const terminateForChild = makeFunctionReference<"mutation">("modules/remoteAudio/guardian:terminateRemoteAudioForChild");

type MutationCtx = GenericMutationCtx<DataModel>;

const guardianIdentity = {
  tokenIdentifier: "https://clerk.example|guardian_remote_audio",
  subject: "guardian_remote_audio",
};
const guardianInstallationId = "018f-remote-audio-guardian-device";

test("Guardian request can be declined by the authorized Child and leaves only cooldown", async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-07-14T10:00:00Z"));
  const t = convexTest({ schema, modules });
  const seeded = await t.run(async (ctx: MutationCtx) => {
    const now = Date.now();
    const guardianAccountId = await ctx.db.insert("guardianAccounts", {
      clerkTokenIdentifier: guardianIdentity.tokenIdentifier,
      status: "active",
      createdAt: now,
      updatedAt: now,
    });
    const householdId = await ctx.db.insert("households", {
      guardianAccountId,
      status: "active",
      timezone: "Asia/Kolkata",
      country: "IN",
      createdAt: now,
      updatedAt: now,
    });
    const guardianDeviceId = await ctx.db.insert("guardianDevices", {
      guardianAccountId,
      householdId,
      guardianInstallationId,
      platform: "android",
      appBuild: "guardian-debug",
      environment: "dev",
      status: "active",
      lastSeenAt: now,
      createdAt: now,
      updatedAt: now,
    });
    await ctx.db.insert("guardianDevices", {
      guardianAccountId,
      householdId,
      guardianInstallationId: "018f-remote-audio-second-guardian",
      platform: "android",
      appBuild: "guardian-debug",
      environment: "dev",
      status: "active",
      lastSeenAt: now,
      createdAt: now,
      updatedAt: now,
    });
    const childProfileId = await ctx.db.insert("childProfiles", {
      householdId,
      displayName: "Aarav",
      birthMonth: 7,
      birthYear: 2015,
      status: "active",
      createdAt: now,
      updatedAt: now,
    });
    const codeId = await ctx.db.insert("enrollmentCodes", {
      householdId,
      childProfileId,
      codeHash: "remote-audio-test-code-hash",
      status: "consumed",
      expiresAt: now,
      createdByGuardianAccountId: guardianAccountId,
      createdByGuardianDeviceId: guardianDeviceId,
      createdAt: now,
    });
    const childDeviceId = await ctx.db.insert("childDevices", {
      householdId,
      childProfileId,
      installationId: "remote-audio-child",
      platform: "android",
      appBuild: "child-debug",
      environment: "dev",
      status: "active",
      createdAt: now,
      updatedAt: now,
    });
    const activeEnrollmentId = await ctx.db.insert("activeEnrollments", {
      householdId,
      childProfileId,
      childDeviceId,
      enrollmentCodeId: codeId,
      status: "active",
      roleLockActive: true,
      supportedPolicySchemaVersion: 3,
      supportsNsfwScreenDetection: true,
      enrolledAt: now,
      createdAt: now,
      updatedAt: now,
    });
    const credentialId = await ctx.db.insert("childDeviceCredentials", {
      activeEnrollmentId,
      childDeviceId,
      publicKeySpki: "test-key",
      algorithm: "ES256",
      status: "active",
      createdAt: now,
      updatedAt: now,
    });
    await ctx.db.insert("supervisionHealth", {
      householdId,
      childProfileId,
      activeEnrollmentId,
      childDeviceId,
      connectivityStatus: "online",
      protectionStatus: "fully_protected",
      capabilities: {
        accessibilityService: true,
        usageAccess: true,
        location: true,
        microphone: true,
        notificationAccess: true,
        batteryOptimizationExempt: true,
        trustedDeviceTime: true,
      },
      lastHeartbeatAt: now,
      createdAt: now,
      updatedAt: now,
    });
    const fcmTokenId = await ctx.db.insert("fcmTokens", {
      ownerKind: "childDevice",
      ownerId: childDeviceId,
      householdId,
      childProfileId,
      childDeviceId,
      activeEnrollmentId,
      tokenHash: "remote-audio-token-hash",
      encryptedToken: "1.test.test",
      platform: "android",
      environment: "dev",
      status: "active",
      registeredAt: now,
      lastSeenAt: now,
    });
    return { childProfileId, childDeviceId, activeEnrollmentId, credentialId, householdId, fcmTokenId };
  });

  const created = await t.withIdentity(guardianIdentity).mutation(createRequest, {
    guardianInstallationId,
    childProfileId: seeded.childProfileId,
    operationId: "018f-remote-audio-operation-1",
  });
  expect(created).toMatchObject({ status: "awaiting_child", reused: false });
  expect(created.expiresAt - created.serverNow).toBe(120_000);
  const retried = await t.withIdentity(guardianIdentity).mutation(createRequest, {
    guardianInstallationId,
    childProfileId: seeded.childProfileId,
    operationId: "018f-remote-audio-operation-1",
  });
  expect(retried).toMatchObject({ requestId: created.requestId, reused: true, expiresAt: created.expiresAt });

  const visible = await t.withIdentity(guardianIdentity).query(guardianState, {
    guardianInstallationId,
    childProfileId: seeded.childProfileId,
  });
  expect(visible).toMatchObject({ availability: "live", request: { status: "awaiting_child" } });

  const childIdentity = {
    tokenIdentifier: `https://cereveil.test|child-device:${seeded.childDeviceId}`,
    subject: `child-device:${seeded.childDeviceId}`,
    credentialId: seeded.credentialId,
    activeEnrollmentId: seeded.activeEnrollmentId,
    childDeviceId: seeded.childDeviceId,
  };
  const childVisible = await t.withIdentity(childIdentity).query(childState, {
    childInstallationId: "remote-audio-child",
  });
  expect(childVisible).toMatchObject({ request: { requestId: created.requestId, status: "awaiting_child" } });
  await expect(t.withIdentity(childIdentity).query(childState, {
    childInstallationId: "wrong-installation",
  })).rejects.toThrow();

  await t.withIdentity(childIdentity).mutation(startAsChild, {
    childInstallationId: "remote-audio-child",
    requestId: created.requestId,
  });
  const offered = await t.withIdentity(childIdentity).mutation(childPublishSignal, {
    childInstallationId: "remote-audio-child",
    requestId: created.requestId,
    type: "offer",
    idempotencyKey: "child-offer-1",
    payload: "v=0\r\na=sendonly",
  });
  const duplicateOffer = await t.withIdentity(childIdentity).mutation(childPublishSignal, {
    childInstallationId: "remote-audio-child",
    requestId: created.requestId,
    type: "offer",
    idempotencyKey: "child-offer-1",
    payload: "v=0\r\na=sendonly",
  });
  expect(duplicateOffer.signalId).toBe(offered.signalId);

  const connecting = await t.withIdentity(guardianIdentity).query(guardianState, {
    guardianInstallationId,
    childProfileId: seeded.childProfileId,
  });
  expect(connecting).toMatchObject({
    availability: "live",
    request: { status: "connecting" },
    signals: [expect.objectContaining({ type: "offer", payload: "v=0\r\na=sendonly" })],
  });
  await t.withIdentity(guardianIdentity).mutation(guardianPublishSignal, {
    guardianInstallationId,
    requestId: created.requestId,
    type: "answer",
    idempotencyKey: "guardian-answer-1",
    payload: "v=0\r\na=recvonly",
  });
  const answered = await t.withIdentity(childIdentity).query(childState, {
    childInstallationId: "remote-audio-child",
  });
  expect(answered).toMatchObject({
    stunUrls: ["stun:stun.l.google.com:19302"],
    signals: [expect.objectContaining({ type: "answer", payload: "v=0\r\na=recvonly" })],
  });
  await t.withIdentity(childIdentity).mutation(markActive, {
    childInstallationId: "remote-audio-child",
    requestId: created.requestId,
  });
  await expect(t.withIdentity(childIdentity).mutation(childPublishSignal, {
    childInstallationId: "remote-audio-child",
    requestId: created.requestId,
    type: "ice_candidate",
    idempotencyKey: "oversized-candidate",
    payload: "x".repeat(4097),
  })).rejects.toThrow();

  const remoteCommandId = await t.run(async (ctx: MutationCtx) =>
    (await ctx.db.query("childDeviceCommands").collect()).find((row) => row.type === "request_remote_audio")!._id);

  await t.withIdentity(childIdentity).mutation(terminateAsChild, {
    childInstallationId: "remote-audio-child",
    requestId: created.requestId,
    reason: "declined",
  });
  await t.mutation(recordDelivery, {
    recordKind: "childDeviceCommand",
    recordId: remoteCommandId,
    fcmTokenId: seeded.fcmTokenId,
    attempt: 1,
    outcome: "accepted",
    serverNow: Date.now(),
  });

  const cooled = await t.withIdentity(guardianIdentity).query(guardianState, {
    guardianInstallationId,
    childProfileId: seeded.childProfileId,
  });
  expect(cooled).toMatchObject({ availability: "cooldown", cooldownUntil: expect.any(Number), request: null });
  const retained = await t.run(async (ctx: MutationCtx) => ({
    requests: await ctx.db.query("remoteAudioRequests").collect(),
    commands: (await ctx.db.query("childDeviceCommands").collect()).filter((row) => row.type === "request_remote_audio"),
    cooldowns: await ctx.db.query("remoteAudioCooldowns").collect(),
    signals: await ctx.db.query("remoteAudioSignals").collect(),
    attempts: await ctx.db.query("fcmDeliveryAttempts").collect(),
  }));
  expect(retained.requests).toEqual([]);
  expect(retained.commands).toEqual([]);
  expect(retained.cooldowns).toEqual([expect.objectContaining({ childProfileId: seeded.childProfileId })]);
  expect(retained.signals).toEqual([]);
  expect(retained.attempts).toEqual([]);
  await t.finishAllScheduledFunctions(() => vi.advanceTimersByTime(3 * 60 * 1000));
  expect(await t.run(async (ctx: MutationCtx) => await ctx.db.query("remoteAudioCooldowns").collect())).toEqual([]);
  const secondRequest = await t.withIdentity(guardianIdentity).mutation(createRequest, {
    guardianInstallationId,
    childProfileId: seeded.childProfileId,
    operationId: "018f-remote-audio-operation-2",
  });
  await t.withIdentity(guardianIdentity).mutation(terminateForChild, {
    guardianInstallationId: "018f-remote-audio-second-guardian",
    childProfileId: seeded.childProfileId,
  });
  expect(await t.run(async (ctx: MutationCtx) => await ctx.db.get("remoteAudioRequests", secondRequest.requestId))).toBeNull();
  vi.useRealTimers();
});
