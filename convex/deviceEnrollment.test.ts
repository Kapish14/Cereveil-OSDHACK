import { GenericMutationCtx } from "convex/server";
import { convexTest } from "convex-test";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { api } from "./_generated/api";
import { DataModel } from "./_generated/dataModel";
import schema from "./schema";
import { base64UrlEncode } from "./lib/encoding";

const modules = import.meta.glob("./**/*.ts");
const bootstrapGuardian = api.modules.guardianAuth.public.bootstrapGuardian;
const createChildProfile = api.modules.childProfiles.public.createChildProfile;
const createEnrollmentCode = api.modules.deviceIdentity.guardian.createEnrollmentCode;
const cancelEnrollmentCode = api.modules.deviceIdentity.guardian.cancelEnrollmentCode;
const getEnrollmentSummary = api.modules.deviceIdentity.guardian.getEnrollmentSummary;
const reconcileGuardianNotices = api.modules.notifications.public.reconcileGuardianNotices;
const acknowledgeGuardianNotice = api.modules.notifications.public.acknowledgeGuardianNotice;

const identity = {
  tokenIdentifier: "https://clerk.example|guardian_enrollment",
  subject: "guardian_enrollment",
  email: "guardian@example.com",
};

const bootstrapArgs = {
  guardianInstallationId: "018f2e36-3d2c-78d8-b7bd-847f0f562222",
  deviceLabel: "Pixel 8",
  appBuild: "guardian-debug-1",
  timezone: "Asia/Kolkata",
};

type MutationCtx = GenericMutationCtx<DataModel>;

function backend() {
  return convexTest({ schema, modules });
}

async function preparedGuardian(t: ReturnType<typeof backend>) {
  await t.withIdentity(identity).mutation(bootstrapGuardian, bootstrapArgs);
  return await t.withIdentity(identity).mutation(createChildProfile, {
    guardianInstallationId: bootstrapArgs.guardianInstallationId,
    displayName: "Aarav",
    birthMonth: 7,
    birthYear: 2015,
  });
}

async function expectAppError(promise: Promise<unknown>, code: string) {
  await expect(promise).rejects.toMatchObject({
    data: expect.objectContaining({ code }),
  });
}

describe("Guardian Enrollment Code lifecycle", () => {
  test("creates a five-minute QR code while storing only its hash", async () => {
    const t = backend();
    const child = await preparedGuardian(t);

    const result = await t.withIdentity(identity).mutation(createEnrollmentCode, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: child.childProfileId,
    });

    expect(result.code).toMatch(/^[A-Za-z0-9_-]{22}$/);
    expect(result.qrPayload).toBe(
      JSON.stringify({ type: "cereveil.child-enrollment", version: 1, code: result.code }),
    );
    expect(result.expiresAt - result.serverNow).toBe(5 * 60 * 1000);

    const rows = await t.run(async (ctx: MutationCtx) =>
      await ctx.db.query("enrollmentCodes").collect(),
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({
      childProfileId: child.childProfileId,
      status: "active",
      codeHash: expect.stringMatching(/^[A-Za-z0-9_-]{43}$/),
    });
    expect(JSON.stringify(rows[0])).not.toContain(result.code);
  });

  test("regeneration revokes the prior code and cancellation invalidates the current code", async () => {
    const t = backend();
    const child = await preparedGuardian(t);
    const first = await t.withIdentity(identity).mutation(createEnrollmentCode, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: child.childProfileId,
    });
    const second = await t.withIdentity(identity).mutation(createEnrollmentCode, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: child.childProfileId,
    });

    await t.withIdentity(identity).mutation(cancelEnrollmentCode, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      enrollmentCodeId: second.enrollmentCodeId,
    });

    const rows = await t.run(async (ctx: MutationCtx) =>
      await ctx.db.query("enrollmentCodes").collect(),
    );
    expect(rows.find((row) => row._id === first.enrollmentCodeId)?.status).toBe("revoked");
    expect(rows.find((row) => row._id === second.enrollmentCodeId)?.status).toBe("revoked");
    expect(rows.find((row) => row._id === second.enrollmentCodeId)?.revokedAt).toEqual(
      expect.any(Number),
    );
  });

  test("derives ownership from Guardian identity", async () => {
    const ownerBackend = backend();
    const child = await preparedGuardian(ownerBackend);

    await expectAppError(
      ownerBackend.withIdentity({ ...identity, tokenIdentifier: "other" }).mutation(
        createEnrollmentCode,
        { guardianInstallationId: bootstrapArgs.guardianInstallationId, childProfileId: child.childProfileId },
      ),
      "UNAUTHENTICATED",
    );
  });

  test("reports an unenrolled summary without unrelated dashboard data", async () => {
    const t = backend();
    const child = await preparedGuardian(t);

    const summary = await t.withIdentity(identity).query(getEnrollmentSummary, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: child.childProfileId,
    });

    expect(summary).toEqual({
      enrollmentStatus: "unenrolled",
      policyStatus: "not_applicable",
      connectivityStatus: "not_applicable",
      protectionHealthStatus: "not_applicable",
      serverNow: expect.any(Number),
    });
    expect(Object.keys(summary).sort()).toEqual([
      "connectivityStatus",
      "enrollmentStatus",
      "policyStatus",
      "protectionHealthStatus",
      "serverNow",
    ]);
  });
});

describe("Child Enrollment Preview", () => {
  test("returns sanitized details without consuming the code or creating device identity", async () => {
    const t = backend();
    const child = await preparedGuardian(t);
    const code = await t.withIdentity(identity).mutation(createEnrollmentCode, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: child.childProfileId,
    });

    const response = await t.fetch("/device-identity/enrollment/preview", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code: code.code }),
    });

    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toEqual({
      childDisplayName: "Aarav",
      codeExpiresAt: code.expiresAt,
      serverNow: expect.any(Number),
    });
    expect(Object.keys(body).sort()).toEqual([
      "childDisplayName",
      "codeExpiresAt",
      "serverNow",
    ]);

    const rows = await t.run(async (ctx: MutationCtx) => ({
      codes: await ctx.db.query("enrollmentCodes").collect(),
      devices: await ctx.db.query("childDevices").collect(),
      enrollments: await ctx.db.query("activeEnrollments").collect(),
      credentials: await ctx.db.query("childDeviceCredentials").collect(),
    }));
    expect(rows.codes[0].status).toBe("active");
    expect(rows.devices).toHaveLength(0);
    expect(rows.enrollments).toHaveLength(0);
    expect(rows.credentials).toHaveLength(0);
  });

  test("uses one generic response for unknown and revoked codes", async () => {
    const t = backend();
    const child = await preparedGuardian(t);
    const code = await t.withIdentity(identity).mutation(createEnrollmentCode, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: child.childProfileId,
    });
    await t.withIdentity(identity).mutation(cancelEnrollmentCode, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      enrollmentCodeId: code.enrollmentCodeId,
    });

    for (const candidate of [code.code, "AAAAAAAAAAAAAAAAAAAAAA"]) {
      const response = await t.fetch("/device-identity/enrollment/preview", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ code: candidate }),
      });
      expect(response.status).toBe(400);
      expect(await response.json()).toEqual({ code: "ENROLLMENT_CODE_INVALID" });
    }
  });

  test("uses the same generic response for expired codes", async () => {
    const t = backend();
    const child = await preparedGuardian(t);
    const code = await t.withIdentity(identity).mutation(createEnrollmentCode, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: child.childProfileId,
    });
    await t.run(async (ctx: MutationCtx) => {
      await ctx.db.patch("enrollmentCodes", code.enrollmentCodeId, { expiresAt: 0 });
    });

    const response = await t.fetch("/device-identity/enrollment/preview", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code: code.code }),
    });
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ code: "ENROLLMENT_CODE_INVALID" });
  });
});

describe("Child Enrollment Completion", () => {
  beforeEach(() => {
    process.env.CHILD_DEVICE_JWT_SECRET = "test-only-child-jwt-secret-with-at-least-32-bytes";
    process.env.CHILD_PUSH_TOKEN_ENCRYPTION_SECRET = "test-only-push-token-secret-with-at-least-32-bytes";
  });

  test("verifies key possession and atomically creates active enrollment state", async () => {
    const t = backend();
    const child = await preparedGuardian(t);
    const code = await t.withIdentity(identity).mutation(createEnrollmentCode, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: child.childProfileId,
    });
    const proof = await enrollmentProof(code.code);

    const response = await completeEnrollment(t, code.code, proof);

    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toMatchObject({
      childDeviceId: expect.any(String),
      activeEnrollmentId: expect.any(String),
      credentialId: expect.any(String),
      childDisplayName: "Aarav",
      desiredPolicyVersion: 1,
      accessJwt: expect.stringMatching(/^[^.]+\.[^.]+\.[^.]+$/),
      accessJwtExpiresAt: expect.any(Number),
      enrolledAt: expect.any(Number),
      serverNow: expect.any(Number),
      environment: "dev",
    });
    expect(body.accessJwtExpiresAt - body.serverNow).toBe(15 * 60 * 1000);
    expect(Object.keys(body).sort()).toEqual([
      "accessJwt",
      "accessJwtExpiresAt",
      "activeEnrollmentId",
      "childDeviceId",
      "childDisplayName",
      "credentialId",
      "desiredPolicyVersion",
      "enrolledAt",
      "environment",
      "serverNow",
    ]);

    const rows = await t.run(async (ctx: MutationCtx) => ({
      code: (await ctx.db.query("enrollmentCodes").collect())[0],
      devices: await ctx.db.query("childDevices").collect(),
      enrollments: await ctx.db.query("activeEnrollments").collect(),
      credentials: await ctx.db.query("childDeviceCredentials").collect(),
      policyStates: await ctx.db.query("policyApplicationStates").collect(),
      health: await ctx.db.query("supervisionHealth").collect(),
      challenges: await ctx.db.query("childDeviceTokenChallenges").collect(),
      pushTokens: await ctx.db.query("fcmTokens").collect(),
      commands: await ctx.db.query("childDeviceCommands").collect(),
    }));
    expect(rows.code).toMatchObject({
      status: "consumed",
      consumedByActiveEnrollmentId: body.activeEnrollmentId,
    });
    expect(rows.devices).toHaveLength(1);
    expect(rows.devices[0].status).toBe("active");
    expect(rows.enrollments[0]).toMatchObject({ status: "active", roleLockActive: true });
    expect(rows.credentials[0]).toMatchObject({ status: "active", algorithm: "ES256" });
    expect(rows.policyStates[0]).toMatchObject({
      status: "pending",
      desiredPolicyVersion: 1,
    });
    expect(rows.policyStates[0].appliedPolicyVersion).toBeUndefined();
    expect(rows.health[0]).toMatchObject({
      connectivityStatus: "pending",
      protectionStatus: "pending",
    });
    expect(rows.health[0].capabilities).toBeUndefined();
    expect(rows.challenges).toHaveLength(0);
    expect(rows.pushTokens).toHaveLength(0);
    expect(rows.commands).toEqual([
      expect.objectContaining({
        type: "apply_policy_version",
        policyVersion: 1,
        status: "pending",
      }),
    ]);
  });

  test("invalid proof does not consume the code and a consumed code cannot be resumed", async () => {
    const t = backend();
    const child = await preparedGuardian(t);
    const code = await t.withIdentity(identity).mutation(createEnrollmentCode, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: child.childProfileId,
    });
    const validProof = await enrollmentProof(code.code);
    const invalidResponse = await completeEnrollment(t, code.code, {
      ...validProof,
      proof: base64UrlEncode(new Uint8Array(64)),
    });
    expect(invalidResponse.status).toBe(400);

    const codeAfterFailure = await t.run(async (ctx: MutationCtx) =>
      ctx.db.get("enrollmentCodes", code.enrollmentCodeId),
    );
    expect(codeAfterFailure?.status).toBe("active");

    expect((await completeEnrollment(t, code.code, validProof)).status).toBe(200);
    const retry = await completeEnrollment(t, code.code, validProof);
    expect(retry.status).toBe(400);
    expect(await retry.json()).toEqual({ code: "ENROLLMENT_CODE_INVALID" });
  });
});

describe("Enrolled Child Device APIs", () => {
  beforeEach(() => {
    process.env.CHILD_DEVICE_JWT_SECRET = "test-only-child-jwt-secret-with-at-least-32-bytes";
    process.env.CHILD_PUSH_TOKEN_ENCRYPTION_SECRET = "test-only-push-token-secret-with-at-least-32-bytes";
  });

  afterEach(() => vi.useRealTimers());

  test("marks an enrollment offline when its first heartbeat never arrives", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-11T10:00:00Z"));
    const enrolled = await enrolledChild();

    await enrolled.t.finishAllScheduledFunctions(() => vi.advanceTimersByTime(45 * 60 * 1000));

    const summary = await enrolled.t.withIdentity(identity).query(getEnrollmentSummary, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
    });
    expect(summary).toMatchObject({
      connectivityStatus: "offline",
      protectionHealthStatus: "pending",
    });
    const notices = await enrolled.t.run(async (ctx: MutationCtx) =>
      await ctx.db.query("guardianNotices").collect(),
    );
    expect(notices).toEqual([
      expect.objectContaining({ type: "offline", status: "active" }),
    ]);
    const reconciliation = await enrolled.t.withIdentity(identity).query(reconcileGuardianNotices, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      paginationOpts: { numItems: 50, cursor: null },
    });
    expect(reconciliation.page).toHaveLength(1);
    expect(reconciliation.page[0].notice.type).toBe("offline");
    await enrolled.t.withIdentity(identity).mutation(acknowledgeGuardianNotice, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      receiptId: reconciliation.page[0].receiptId,
      presentation: "suppressed",
    });
    const after = await enrolled.t.withIdentity(identity).query(reconcileGuardianNotices, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      paginationOpts: { numItems: 50, cursor: null },
    });
    expect(after.page).toHaveLength(0);
  });

  test("fetches then acknowledges policy and reports first protection heartbeat", async () => {
    const enrolled = await enrolledChild();
    const commandsResponse = await childRequest(enrolled.t, "/child/commands", enrolled.body.accessJwt, { cursor: null });
    expect(commandsResponse.status).toBe(200);
    expect(await commandsResponse.json()).toEqual({
      commands: [expect.objectContaining({ type: "apply_policy_version", policyVersion: 1 })],
      continueCursor: expect.any(String),
      isDone: true,
    });
    const policyResponse = await childRequest(enrolled.t, "/child/policy", enrolled.body.accessJwt);
    expect(policyResponse.status).toBe(200);
    expect(policyResponse.headers.get("x-request-id")).toMatch(/^[0-9a-f-]{36}$/);
    expect(await policyResponse.json()).toMatchObject({
      version: 1,
      appBlocking: { enabled: false },
      safeBrowsing: { enabled: false, safeSearchEnabled: false },
      activeScreenSafety: { enabled: false },
      screenTimeSummariesEnabled: false,
    });

    const ackResponse = await childRequest(
      enrolled.t,
      "/child/policy/acknowledge",
      enrolled.body.accessJwt,
      { appliedPolicyVersion: 1 },
    );
    expect(ackResponse.status).toBe(200);

    const heartbeatResponse = await childRequest(
      enrolled.t,
      "/child/heartbeat",
      enrolled.body.accessJwt,
      {
        capabilities: {
          accessibilityService: true,
          usageAccess: true,
          location: true,
          microphone: true,
          notificationAccess: true,
          batteryOptimizationExempt: true,
        },
      },
    );
    expect(heartbeatResponse.status).toBe(200);
    expect(await heartbeatResponse.json()).toMatchObject({ status: "fully_protected" });

    const rows = await enrolled.t.run(async (ctx: MutationCtx) => ({
      policy: (await ctx.db.query("policyApplicationStates").collect())[0],
      health: (await ctx.db.query("supervisionHealth").collect())[0],
    }));
    expect(rows.policy).toMatchObject({ status: "applied", appliedPolicyVersion: 1 });
    const commands = await enrolled.t.run(async (ctx: MutationCtx) =>
      await ctx.db.query("childDeviceCommands").collect(),
    );
    expect(commands[0]).toMatchObject({ status: "acknowledged", policyVersion: 1 });
    expect(rows.health).toMatchObject({
      connectivityStatus: "online",
      protectionStatus: "fully_protected",
      lastHeartbeatAt: expect.any(Number),
    });

    const summary = await enrolled.t.withIdentity(identity).query(getEnrollmentSummary, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
    });
    expect(summary).toMatchObject({
      enrollmentStatus: "active",
      policyStatus: "applied",
      connectivityStatus: "online",
      protectionHealthStatus: "fully_protected",
    });
  });

  test("maps authenticated validation and policy conflicts without exposing authorization details", async () => {
    const enrolled = await enrolledChild();
    const invalid = await childRequest(
      enrolled.t,
      "/child/heartbeat",
      enrolled.body.accessJwt,
      { capabilities: {} },
    );
    expect(invalid.status).toBe(400);
    expect(await invalid.json()).toEqual({ code: "VALIDATION_FAILED" });

    const mismatch = await childRequest(
      enrolled.t,
      "/child/policy/acknowledge",
      enrolled.body.accessJwt,
      { appliedPolicyVersion: 2 },
    );
    expect(mismatch.status).toBe(409);
    expect(await mismatch.json()).toEqual({ code: "POLICY_VERSION_MISMATCH" });

    await enrolled.t.run(async (ctx: MutationCtx) => {
      await ctx.db.patch("childProfiles", enrolled.child.childProfileId, { status: "deleting" });
    });
    const unauthorized = await childRequest(enrolled.t, "/child/policy", enrolled.body.accessJwt);
    expect(unauthorized.status).toBe(401);
    expect(await unauthorized.json()).toEqual({ code: "CHILD_DEVICE_UNAUTHORIZED" });
  });

  test("creates deduplicated Tamper Alerts and a correlated Recovery Notice", async () => {
    const enrolled = await enrolledChild();
    const capabilities = {
      accessibilityService: false,
      usageAccess: true,
      location: true,
      microphone: true,
      notificationAccess: true,
      batteryOptimizationExempt: true,
    };
    for (let index = 0; index < 2; index += 1) {
      const response = await childRequest(enrolled.t, "/child/heartbeat", enrolled.body.accessJwt, { capabilities });
      expect(response.status).toBe(200);
    }
    let notices = await enrolled.t.run(async (ctx: MutationCtx) =>
      await ctx.db.query("guardianNotices").collect(),
    );
    expect(notices.filter((notice) => notice.type === "tamper")).toEqual([
      expect.objectContaining({ unavailableCapabilities: ["accessibilityService"] }),
    ]);

    await enrolled.t.run(async (ctx: MutationCtx) => {
      const health = (await ctx.db.query("supervisionHealth").collect())[0];
      await ctx.db.patch("supervisionHealth", health._id, {
        connectivityStatus: "offline",
        activeOfflineEpisodeKey: "offline:test-episode",
      });
    });
    const recovered = await childRequest(enrolled.t, "/child/heartbeat", enrolled.body.accessJwt, {
      capabilities: { ...capabilities, accessibilityService: true },
    });
    expect(recovered.status).toBe(200);
    notices = await enrolled.t.run(async (ctx: MutationCtx) =>
      await ctx.db.query("guardianNotices").collect(),
    );
    expect(notices.filter((notice) => notice.type === "recovery")).toHaveLength(1);

    await childRequest(enrolled.t, "/child/heartbeat", enrolled.body.accessJwt, { capabilities });
    notices = await enrolled.t.run(async (ctx: MutationCtx) =>
      await ctx.db.query("guardianNotices").collect(),
    );
    expect(notices.filter((notice) => notice.type === "tamper")).toHaveLength(2);
  });

  test("registers and rotates FCM delivery state without changing identity", async () => {
    const enrolled = await enrolledChild();
    for (const token of ["fcm-token-one", "fcm-token-two"]) {
      const response = await childRequest(
        enrolled.t,
        "/child/push-token",
        enrolled.body.accessJwt,
        { token },
      );
      expect(response.status).toBe(200);
    }

    const rows = await enrolled.t.run(async (ctx: MutationCtx) => ({
      pushTokens: await ctx.db.query("fcmTokens").collect(),
      devices: await ctx.db.query("childDevices").collect(),
      enrollments: await ctx.db.query("activeEnrollments").collect(),
    }));
    expect(rows.pushTokens).toHaveLength(1);
    expect(rows.pushTokens[0].tokenHash).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(rows.pushTokens[0].encryptedToken).not.toContain("fcm-token-two");
    expect(rows.devices).toHaveLength(1);
    expect(rows.enrollments).toHaveLength(1);
  });

  test("refreshes through a one-use credential-bound challenge", async () => {
    const enrolled = await enrolledChild();
    const challengeResponse = await enrolled.t.fetch("/device-identity/token/challenge", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ credentialId: enrolled.body.credentialId }),
    });
    expect(challengeResponse.status).toBe(200);
    const challengeBody = await challengeResponse.json();
    const signature = await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      enrolled.proof.keyPair.privateKey,
      new TextEncoder().encode(
        `cereveil-child-token-refresh-v1\n${enrolled.body.credentialId}\n${challengeBody.challenge}`,
      ),
    );

    const exchange = () =>
      enrolled.t.fetch("/device-identity/token/exchange", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          credentialId: enrolled.body.credentialId,
          challenge: challengeBody.challenge,
          proof: base64UrlEncode(new Uint8Array(signature)),
        }),
      });
    const refreshed = await exchange();
    expect(refreshed.status).toBe(200);
    const refreshedBody = await refreshed.json();
    expect(refreshedBody.accessJwt).toMatch(/^[^.]+\.[^.]+\.[^.]+$/);
    const claims = JSON.parse(
      new TextDecoder().decode(
        Uint8Array.from(
          atob(refreshedBody.accessJwt.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")),
          (character) => character.charCodeAt(0),
        ),
      ),
    );
    expect(claims).toMatchObject({
      credentialId: enrolled.body.credentialId,
      activeEnrollmentId: enrolled.body.activeEnrollmentId,
      childDeviceId: enrolled.body.childDeviceId,
    });
    expect((await exchange()).status).toBe(401);
  });

  test("current credential and enrollment state revoke access before JWT expiry", async () => {
    for (const revoke of ["credential", "enrollment"] as const) {
      const enrolled = await enrolledChild();
      await enrolled.t.run(async (ctx: MutationCtx) => {
        if (revoke === "credential") {
          await ctx.db.patch("childDeviceCredentials", enrolled.body.credentialId, {
            status: "revoked",
            revokedAt: Date.now(),
          });
        } else {
          await ctx.db.patch("activeEnrollments", enrolled.body.activeEnrollmentId, {
            status: "ended",
            endedAt: Date.now(),
          });
        }
      });

      expect(
        (await childRequest(enrolled.t, "/child/policy", enrolled.body.accessJwt)).status,
      ).toBe(401);
      expect(
        (
          await enrolled.t.fetch("/device-identity/token/challenge", {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({ credentialId: enrolled.body.credentialId }),
          })
        ).status,
      ).toBe(401);
    }
  });
});

async function enrolledChild() {
  const t = backend();
  const child = await preparedGuardian(t);
  const code = await t.withIdentity(identity).mutation(createEnrollmentCode, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
    childProfileId: child.childProfileId,
  });
  const proof = await enrollmentProof(code.code);
  const response = await completeEnrollment(t, code.code, proof);
  expect(response.status).toBe(200);
  return { t, child, proof, body: await response.json() };
}

async function childRequest(
  t: ReturnType<typeof backend>,
  path: string,
  accessJwt: string,
  body?: unknown,
) {
  return await t.fetch(path, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      authorization: `Bearer ${accessJwt}`,
      ...(body === undefined ? {} : { "content-type": "application/json" }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

async function enrollmentProof(code: string) {
  const keyPair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const publicKeySpki = base64UrlEncode(
    new Uint8Array(await crypto.subtle.exportKey("spki", keyPair.publicKey)),
  );
  const message = new TextEncoder().encode(
    `cereveil-child-enrollment-v1\n${code}\n${publicKeySpki}`,
  );
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    keyPair.privateKey,
    message,
  );
  return { publicKeySpki, proof: base64UrlEncode(new Uint8Array(signature)), keyPair };
}

async function completeEnrollment(
  t: ReturnType<typeof backend>,
  code: string,
  proof: { publicKeySpki: string; proof: string },
) {
  return await t.fetch("/device-identity/enrollment/complete", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      code,
      publicKeySpki: proof.publicKeySpki,
      proof: proof.proof,
      installationId: "child-installation-1",
      deviceLabel: "Pixel 7a",
      appBuild: "child-debug-1",
    }),
  });
}
