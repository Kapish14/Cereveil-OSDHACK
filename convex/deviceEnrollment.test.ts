import { FunctionReference, GenericMutationCtx, makeFunctionReference } from "convex/server";
import { convexTest } from "convex-test";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { api } from "./_generated/api";
import { DataModel } from "./_generated/dataModel";
import schema from "./schema";
import { base64UrlDecode, base64UrlEncode } from "./lib/encoding";

const modules = import.meta.glob("./**/*.ts");
const bootstrapGuardian = api.modules.guardianAuth.public.bootstrapGuardian;
const retireGuardianDevice = api.modules.guardianAuth.public.retireGuardianDevice;
const createChildProfile = api.modules.childProfiles.public.createChildProfile;
const createEnrollmentCode = api.modules.deviceIdentity.guardian.createEnrollmentCode;
const cancelEnrollmentCode = api.modules.deviceIdentity.guardian.cancelEnrollmentCode;
const getEnrollmentSummary = api.modules.deviceIdentity.guardian.getEnrollmentSummary;
const reconcileGuardianNotices = api.modules.notifications.public.reconcileGuardianNotices;
const acknowledgeGuardianNotice = api.modules.notifications.public.acknowledgeGuardianNotice;
const getPolicyState = api.modules.policies.guardian.getPolicyState;
const updateScreenTimeSummaries = api.modules.policies.guardian.updateScreenTimeSummaries;
const updateSafeBrowsing = api.modules.policies.guardian.updateSafeBrowsing;
const updateAppBlocking = api.modules.policies.guardian.updateAppBlocking;
const updateActiveScreenSafety = api.modules.policies.guardian.updateActiveScreenSafety;
const updateLocationSharing = api.modules.policies.guardian.updateLocationSharing;
const updateScreenTime = api.modules.policies.guardian.updateScreenTime;
const getLatestLocation = api.modules.location.guardian.getLatestLocation;
const getOrRequestScreenTime = api.modules.screenTime.guardian.getOrRequestScreenTime;
const listPendingAccessRequests = api.modules.access.guardian.listPendingAccessRequests;
const resolveAccessRequest = api.modules.access.guardian.resolveAccessRequest;
const replaceChildDevice = api.modules.deviceIdentity.guardian.replaceChildDevice;
const endSupervision = api.modules.deviceIdentity.guardian.endSupervision;

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

const TEST_CHILD_JWT_PRIVATE_JWK = JSON.stringify({
  kty: "EC",
  x: "g4ANOE94AnKUcHGY74r--Czs78KcE1nc5ddnCmHYrdY",
  y: "mBCgJJqq39IYtfSpnxUNfUT_yJU1tjPUOFvP5pRf1Eo",
  crv: "P-256",
  d: "xLctx56rutnUIBg7uCOmJKQCk9mL3OsYIiPv6yAEgo4",
});
const TEST_CHILD_JWT_PUBLIC_JWK = JSON.stringify({
  kty: "EC",
  x: "g4ANOE94AnKUcHGY74r--Czs78KcE1nc5ddnCmHYrdY",
  y: "mBCgJJqq39IYtfSpnxUNfUT_yJU1tjPUOFvP5pRf1Eo",
  crv: "P-256",
});

beforeEach(() => {
  process.env.CHILD_DEVICE_JWT_PRIVATE_JWK = TEST_CHILD_JWT_PRIVATE_JWK;
  process.env.CHILD_DEVICE_JWT_PUBLIC_JWK = TEST_CHILD_JWT_PUBLIC_JWK;
  process.env.CHILD_DEVICE_JWT_KEY_ID = "cereveil-child-test-1";
  process.env.CHILD_DEVICE_JWT_ISSUER = "https://cereveil.test";
});

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

describe("Guardian Device logout", () => {
  test("retires the authenticated installation and its active FCM token", async () => {
    const t = backend();
    const bootstrap = await t.withIdentity(identity).mutation(bootstrapGuardian, bootstrapArgs);
    await t.run(async (ctx: MutationCtx) => {
      for (let index = 0; index < 11; index += 1) {
        await ctx.db.insert("fcmTokens", {
          ownerKind: "guardianDevice",
          ownerId: bootstrap.guardianDeviceId,
          householdId: bootstrap.householdId,
          tokenHash: `old-guardian-token-hash-${index}`,
          encryptedToken: `old-guardian-token-ciphertext-${index}`,
          platform: "android",
          environment: "dev",
          status: "revoked",
          registeredAt: 1,
          lastSeenAt: 1,
          invalidatedAt: 2,
        });
      }
      await ctx.db.insert("fcmTokens", {
        ownerKind: "guardianDevice",
        ownerId: bootstrap.guardianDeviceId,
        householdId: bootstrap.householdId,
        tokenHash: "guardian-token-hash",
        encryptedToken: "guardian-token-ciphertext",
        platform: "android",
        environment: "dev",
        status: "active",
        registeredAt: 1,
        lastSeenAt: 1,
      });
    });

    expect(await t.withIdentity(identity).mutation(retireGuardianDevice, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
    })).toMatchObject({ retired: true });

    const state = await t.run(async (ctx: MutationCtx) => ({
      device: await ctx.db.get(bootstrap.guardianDeviceId),
      tokens: await ctx.db
        .query("fcmTokens")
        .withIndex("by_owner_kind_and_owner_id", (q) =>
          q.eq("ownerKind", "guardianDevice").eq("ownerId", bootstrap.guardianDeviceId),
        )
        .take(20),
    }));
    expect(state.device).toMatchObject({ status: "revoked", revokedAt: expect.any(Number) });
    expect(state.tokens).toHaveLength(12);
    expect(state.tokens.at(-1)).toMatchObject({
      tokenHash: "guardian-token-hash",
      status: "revoked",
      invalidatedAt: expect.any(Number),
    });
    expect(await t.withIdentity(identity).mutation(retireGuardianDevice, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
    })).toMatchObject({ retired: true });
  });

  test("cannot retire another Guardian Account's installation", async () => {
    const t = backend();
    const bootstrap = await t.withIdentity(identity).mutation(bootstrapGuardian, bootstrapArgs);

    expect(await t.withIdentity({ ...identity, tokenIdentifier: "other-account" }).mutation(
      retireGuardianDevice,
      { guardianInstallationId: bootstrapArgs.guardianInstallationId },
    )).toMatchObject({ retired: false });
    expect(await t.run(async (ctx: MutationCtx) => ctx.db.get(bootstrap.guardianDeviceId)))
      .toMatchObject({ status: "active" });
  });
});

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
    const [encodedHeader] = body.accessJwt.split(".");
    expect(JSON.parse(new TextDecoder().decode(base64UrlDecode(encodedHeader)))).toEqual({
      alg: "ES256",
      kid: "cereveil-child-test-1",
      typ: "JWT",
    });
    const jwks = await t.fetch("/.well-known/jwks.json");
    expect(jwks.status).toBe(200);
    expect(await jwks.json()).toEqual({
      keys: [expect.objectContaining({
        alg: "ES256",
        crv: "P-256",
        kid: "cereveil-child-test-1",
        kty: "EC",
        use: "sig",
      })],
    });
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
      schemaVersion: 2,
      appBlocking: { enabled: false, rules: [] },
      safeBrowsing: { enabled: false, safeSearchEnabled: false },
      activeScreenSafety: { enabled: false },
      locationSharing: { enabled: true },
      screenTime: { enabled: true },
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
        supportedPolicySchemaVersion: 2,
        capabilities: {
          accessibilityService: true,
          usageAccess: true,
          location: true,
          microphone: true,
          notificationAccess: true,
          batteryOptimizationExempt: true,
          trustedDeviceTime: true,
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

  test("creates, replays, and acknowledges an immutable Guardian policy change", async () => {
    const enrolled = await enrolledChild();
    await childRequest(enrolled.t, "/child/policy/acknowledge", enrolled.body.accessJwt, {
      appliedPolicyVersion: 1,
    });
    const args = {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
      expectedCurrentVersion: 1,
      operationId: "018f-policy-save-operation-0001",
      enabled: false,
    };
    const changed = await enrolled.t.withIdentity(identity).mutation(updateScreenTimeSummaries, args);
    expect(changed).toMatchObject({
      desiredPolicy: { version: 2, schemaVersion: 2, screenTime: { enabled: false } },
      appliedPolicy: { version: 1, screenTime: { enabled: true } },
      applicationStatus: "pending",
    });
    const replay = await enrolled.t.withIdentity(identity).mutation(updateScreenTimeSummaries, args);
    expect(replay.desiredPolicy.version).toBe(2);
    const rows = await enrolled.t.run(async (ctx: MutationCtx) => ({
      policies: await ctx.db.query("supervisionPolicies").collect(),
      commands: await ctx.db.query("childDeviceCommands").collect(),
    }));
    expect(rows.policies).toHaveLength(2);
    expect(rows.policies.map((policy) => policy.status).sort()).toEqual(["active", "superseded"]);
    expect(rows.commands.filter((command) => command.status === "pending")).toEqual([
      expect.objectContaining({ policyVersion: 2 }),
    ]);
    const fetched = await childRequest(enrolled.t, "/child/policy", enrolled.body.accessJwt);
    expect(await fetched.json()).toMatchObject({ version: 2, screenTime: { enabled: false } });
    await childRequest(enrolled.t, "/child/policy/acknowledge", enrolled.body.accessJwt, {
      appliedPolicyVersion: 2,
    });
    const applied = await enrolled.t.withIdentity(identity).query(getPolicyState, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
    });
    expect(applied).toMatchObject({
      applicationStatus: "applied",
      desiredPolicy: { version: 2 },
      appliedPolicy: { version: 2 },
    });
    const noOp = await enrolled.t.withIdentity(identity).mutation(updateScreenTimeSummaries, {
      ...args,
      expectedCurrentVersion: 2,
      operationId: "018f-policy-save-operation-noop",
    });
    expect(noOp.desiredPolicy.version).toBe(2);
    const afterNoOp = await enrolled.t.run(async (ctx: MutationCtx) => ({
      policies: await ctx.db.query("supervisionPolicies").collect(),
      commands: await ctx.db.query("childDeviceCommands").collect(),
      operations: await ctx.db.query("policySaveOperations").collect(),
    }));
    expect(afterNoOp.policies).toHaveLength(2);
    expect(afterNoOp.commands).toHaveLength(2);
    expect(afterNoOp.operations).toHaveLength(2);
  });

  test("rejects stale, contradictory, and reused Guardian policy saves", async () => {
    const enrolled = await enrolledChild();
    const common = {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
      expectedCurrentVersion: 1,
    };
    await expectAppError(enrolled.t.withIdentity(identity).mutation(updateSafeBrowsing, {
      ...common,
      operationId: "018f-policy-save-invalid-0001",
      enabled: false,
      safeSearchEnabled: true,
    }), "VALIDATION_FAILED");
    await enrolled.t.withIdentity(identity).mutation(updateScreenTimeSummaries, {
      ...common,
      operationId: "018f-policy-save-operation-0002",
      enabled: false,
    });
    await expectAppError(enrolled.t.withIdentity(identity).mutation(updateScreenTimeSummaries, {
      ...common,
      operationId: "018f-policy-save-operation-0003",
      enabled: true,
    }), "POLICY_CONFLICT");
    await expectAppError(enrolled.t.withIdentity(identity).mutation(updateScreenTimeSummaries, {
      ...common,
      operationId: "018f-policy-save-operation-0002",
      enabled: true,
    }), "POLICY_OPERATION_REUSED");
  });

  test("preserves unrelated sections across successive feature changes", async () => {
    const enrolled = await enrolledChild(3);
    const base = {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
    };
    const catalog = await childRequest(enrolled.t, "/child/app-catalog/generations/start", enrolled.body.accessJwt, {
      expectedCount: 1,
    });
    const generationId = (await catalog.json()).generationId;
    await childRequest(enrolled.t, "/child/app-catalog/generations/batch", enrolled.body.accessJwt, {
      generationId, apps: [{ packageName: "com.example.messages", label: "Messages" }],
    });
    await childRequest(enrolled.t, "/child/app-catalog/generations/complete", enrolled.body.accessJwt, { generationId });
    await enrolled.t.withIdentity(identity).mutation(updateSafeBrowsing, {
      ...base,
      expectedCurrentVersion: 1,
      operationId: "018f-policy-safe-browsing-0001",
      enabled: true,
      safeSearchEnabled: true,
    });
    await enrolled.t.withIdentity(identity).mutation(updateAppBlocking, {
      ...base,
      expectedCurrentVersion: 2,
      operationId: "018f-policy-app-blocking-0001",
      enabled: true,
    });
    const result = await enrolled.t.withIdentity(identity).mutation(updateActiveScreenSafety, {
      ...base,
      expectedCurrentVersion: 3,
      operationId: "018f-policy-screen-safety-0001",
      scamText: {
        enabled: true,
        monitoredPackageNames: ["com.example.messages"],
        sensitivity: "standard",
      },
      nsfwScreen: { enabled: false, monitoredPackageNames: [], sensitivity: "standard" },
    });
    expect(result.desiredPolicy).toMatchObject({
      version: 4,
      safeBrowsing: { enabled: true, safeSearchEnabled: true },
      appBlocking: { enabled: true },
      activeScreenSafety: {
        scamText: { enabled: true, monitoredPackageNames: ["com.example.messages"] },
        nsfwScreen: { enabled: false, monitoredPackageNames: [] },
      },
    });
    const commands = await enrolled.t.run(async (ctx: MutationCtx) =>
      await ctx.db.query("childDeviceCommands").collect(),
    );
    expect(commands.filter((command) => command.status === "pending")).toEqual([
      expect.objectContaining({ policyVersion: 4 }),
    ]);
  });

  test("maps permanent rejection only onto the current desired policy", async () => {
    const enrolled = await enrolledChild();
    await enrolled.t.withIdentity(identity).mutation(updateScreenTimeSummaries, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
      expectedCurrentVersion: 1,
      operationId: "018f-policy-permanent-failure-1",
      enabled: false,
    });
    const commandsResponse = await childRequest(enrolled.t, "/child/commands", enrolled.body.accessJwt, { cursor: null });
    const commands = (await commandsResponse.json()).commands as Array<{ commandId: string; policyVersion: number }>;
    const current = commands.find((command) => command.policyVersion === 2)!;
    const rejected = await childRequest(enrolled.t, "/child/commands/reject", enrolled.body.accessJwt, {
      commandId: current.commandId,
      reason: "unable_to_apply",
    });
    expect(rejected.status).toBe(200);
    const state = await enrolled.t.withIdentity(identity).query(getPolicyState, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
    });
    expect(state).toMatchObject({ applicationStatus: "failed", failureReason: "activation_failed" });
  });

  test("maps authenticated validation and policy conflicts without exposing authorization details", async () => {
    const enrolled = await enrolledChild();
    const invalid = await childRequest(
      enrolled.t,
      "/child/heartbeat",
      enrolled.body.accessJwt,
      { supportedPolicySchemaVersion: 2, capabilities: {} },
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
      trustedDeviceTime: true,
    };
    for (let index = 0; index < 2; index += 1) {
      const response = await childRequest(enrolled.t, "/child/heartbeat", enrolled.body.accessJwt, { supportedPolicySchemaVersion: 2, capabilities });
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
      supportedPolicySchemaVersion: 2,
      capabilities: { ...capabilities, accessibilityService: true },
    });
    expect(recovered.status).toBe(200);
    notices = await enrolled.t.run(async (ctx: MutationCtx) =>
      await ctx.db.query("guardianNotices").collect(),
    );
    expect(notices.filter((notice) => notice.type === "recovery")).toHaveLength(1);

    await childRequest(enrolled.t, "/child/heartbeat", enrolled.body.accessJwt, { supportedPolicySchemaVersion: 2, capabilities });
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
    const unsignedChallenge = await enrolled.t.fetch("/device-identity/token/challenge", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ credentialId: enrolled.body.credentialId }),
    });
    expect(unsignedChallenge.status).toBe(401);
    const replayNonce = crypto.randomUUID();
    const replayIssuedAt = Date.now();
    expect((await requestTokenChallenge(
      enrolled.t,
      enrolled.body.credentialId,
      enrolled.proof.keyPair,
      replayNonce,
      replayIssuedAt,
    )).status).toBe(200);
    expect((await requestTokenChallenge(
      enrolled.t,
      enrolled.body.credentialId,
      enrolled.proof.keyPair,
      replayNonce,
      replayIssuedAt,
    )).status).toBe(401);
    const challengeResponse = await requestTokenChallenge(
      enrolled.t,
      enrolled.body.credentialId,
      enrolled.proof.keyPair,
    );
    expect(challengeResponse.status).toBe(200);
    const challengeBody = await challengeResponse.json();
    // A second authenticated request must not invalidate an already-issued challenge.
    expect((await requestTokenChallenge(
      enrolled.t,
      enrolled.body.credentialId,
      enrolled.proof.keyPair,
    )).status).toBe(200);
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
      const challengeResponse = await requestTokenChallenge(
        enrolled.t,
        enrolled.body.credentialId,
        enrolled.proof.keyPair,
      );
      expect(challengeResponse.status).toBe(200);
      const challengeBody = await challengeResponse.json();
      const signature = await crypto.subtle.sign(
        { name: "ECDSA", hash: "SHA-256" },
        enrolled.proof.keyPair.privateKey,
        new TextEncoder().encode(
          `cereveil-child-token-refresh-v1\n${enrolled.body.credentialId}\n${challengeBody.challenge}`,
        ),
      );
      const exchange = await enrolled.t.fetch("/device-identity/token/exchange", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          credentialId: enrolled.body.credentialId,
          challenge: challengeBody.challenge,
          proof: base64UrlEncode(new Uint8Array(signature)),
        }),
      });
      expect(exchange.status).toBe(410);
      expect(await exchange.json()).toEqual({ code: "CHILD_DEVICE_REVOKED" });
    }
  });
});

describe("Latest App Catalog", () => {
  beforeEach(() => {
    process.env.CHILD_DEVICE_JWT_SECRET = "test-only-child-jwt-secret-with-at-least-32-bytes";
  });

  test("publishes one complete Child catalog for the Guardian", async () => {
    const enrolled = await enrolledChild();

    const started = await childRequest(
      enrolled.t,
      "/child/app-catalog/generations/start",
      enrolled.body.accessJwt,
      { expectedCount: 2 },
    );
    expect(started.status).toBe(200);
    const { generationId } = await started.json() as { generationId: string };

    expect((await childRequest(
      enrolled.t,
      "/child/app-catalog/generations/batch",
      enrolled.body.accessJwt,
      {
        generationId,
        apps: [
          { packageName: "com.example.reader", label: "Reader" },
          { packageName: "com.example.math", label: "Math Practice" },
        ],
      },
    )).status).toBe(200);
    expect((await childRequest(
      enrolled.t,
      "/child/app-catalog/generations/complete",
      enrolled.body.accessJwt,
      { generationId },
    )).status).toBe(200);

    const getLatestAppCatalog = makeFunctionReference<"query", {
      guardianInstallationId: string;
      childProfileId: string;
    }, {
      syncedAt: number;
      apps: Array<{ packageName: string; label: string }>;
    }>("modules/appCatalog/guardian:getLatestAppCatalog") as FunctionReference<
      "query",
      "public",
      { guardianInstallationId: string; childProfileId: string },
      { syncedAt: number; apps: Array<{ packageName: string; label: string }> }
    >;
    const catalog = await enrolled.t.withIdentity(identity).query(getLatestAppCatalog, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
    });

    expect(catalog.apps).toEqual([
      { packageName: "com.example.math", label: "Math Practice" },
      { packageName: "com.example.reader", label: "Reader" },
    ]);
    expect(catalog.syncedAt).toEqual(expect.any(Number));
  });
});

describe("Active Screen Safety policy schema v3", () => {
  beforeEach(() => {
    process.env.CHILD_DEVICE_JWT_SECRET = "test-only-child-jwt-secret-with-at-least-32-bytes";
  });

  test("applies independent monitored apps and sensitivities as one complete policy", async () => {
    const enrolled = await enrolledChild(3);
    await childRequest(enrolled.t, "/child/policy/acknowledge", enrolled.body.accessJwt, {
      appliedPolicyVersion: 1,
    });
    const started = await childRequest(
      enrolled.t,
      "/child/app-catalog/generations/start",
      enrolled.body.accessJwt,
      { expectedCount: 2 },
    );
    const { generationId } = await started.json() as { generationId: string };
    await childRequest(enrolled.t, "/child/app-catalog/generations/batch", enrolled.body.accessJwt, {
      generationId,
      apps: [
        { packageName: "com.example.messages", label: "Messages" },
        { packageName: "com.example.browser", label: "Browser" },
      ],
    });
    await childRequest(enrolled.t, "/child/app-catalog/generations/complete", enrolled.body.accessJwt, {
      generationId,
    });

    const changed = await enrolled.t.withIdentity(identity).mutation(updateActiveScreenSafety, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
      expectedCurrentVersion: 1,
      operationId: "active-screen-safety-v3-0001",
      scamText: {
        enabled: true,
        monitoredPackageNames: ["com.example.messages"],
        sensitivity: "standard",
      },
      nsfwScreen: {
        enabled: true,
        monitoredPackageNames: ["com.example.browser"],
        sensitivity: "higher",
      },
    });

    expect(changed).toMatchObject({
      applicationStatus: "pending",
      desiredPolicy: {
        version: 2,
        schemaVersion: 3,
        activeScreenSafety: {
          scamText: {
            enabled: true,
            monitoredPackageNames: ["com.example.messages"],
            sensitivity: "standard",
          },
          nsfwScreen: {
            enabled: true,
            monitoredPackageNames: ["com.example.browser"],
            sensitivity: "higher",
          },
        },
      },
      appliedPolicy: {
        version: 1,
        activeScreenSafety: {
          scamText: { enabled: false, monitoredPackageNames: [], sensitivity: "standard" },
          nsfwScreen: { enabled: false, monitoredPackageNames: [], sensitivity: "standard" },
        },
      },
    });
  });

  test("stores one metadata-only Child incident in the authenticated Guardian feed", async () => {
    const enrolled = await enrolledChild(3);
    await enrolled.t.withIdentity(identity).mutation(bootstrapGuardian, {
      ...bootstrapArgs,
      guardianInstallationId: "018f2e36-3d2c-78d8-b7bd-847f0f563333",
      deviceLabel: "Pixel Tablet",
    });
    const guardianArgs = {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
    };
    await childRequest(enrolled.t, "/child/policy/acknowledge", enrolled.body.accessJwt, {
      appliedPolicyVersion: 1,
    });
    const started = await childRequest(enrolled.t, "/child/app-catalog/generations/start", enrolled.body.accessJwt, {
      expectedCount: 1,
    });
    const generationId = (await started.json()).generationId;
    await childRequest(enrolled.t, "/child/app-catalog/generations/batch", enrolled.body.accessJwt, {
      generationId, apps: [{ packageName: "com.example.messages", label: "Messages" }],
    });
    await childRequest(enrolled.t, "/child/app-catalog/generations/complete", enrolled.body.accessJwt, { generationId });
    await enrolled.t.withIdentity(identity).mutation(updateActiveScreenSafety, {
      ...guardianArgs,
      expectedCurrentVersion: 1,
      operationId: "active-screen-safety-alert-0001",
      scamText: {
        enabled: true,
        monitoredPackageNames: ["com.example.messages"],
        sensitivity: "standard",
      },
      nsfwScreen: {
        enabled: true,
        monitoredPackageNames: ["com.example.messages"],
        sensitivity: "standard",
      },
    });
    await childRequest(enrolled.t, "/child/policy/acknowledge", enrolled.body.accessJwt, {
      appliedPolicyVersion: 2,
    });
    const occurredAt = Date.now();
    const alert = {
      incidentId: "018f-safety-incident-0001",
      type: "scam_text",
      packageName: "com.example.messages",
      confidenceBand: "high",
      policyVersion: 2,
      occurredAt,
    };

    const first = await childRequest(enrolled.t, "/child/safety-alerts", enrolled.body.accessJwt, alert);
    const replay = await childRequest(enrolled.t, "/child/safety-alerts", enrolled.body.accessJwt, alert);
    const firstCooldown = await enrolled.t.run(async (ctx: MutationCtx) =>
      (await ctx.db.query("safetyNotificationCooldowns").collect())[0].nextAllowedAt,
    );
    const suppressed = await childRequest(enrolled.t, "/child/safety-alerts", enrolled.body.accessJwt, {
      ...alert, incidentId: "018f-safety-incident-0002", occurredAt: occurredAt + 1,
    });
    expect(first.status).toBe(200);
    expect(replay.status).toBe(200);
    expect(suppressed.status).toBe(200);

    const listSafetyAlerts = makeFunctionReference<"query", any, any>(
      "modules/safetyAlerts/guardian:listSafetyAlerts",
    ) as FunctionReference<"query", "public", any, any>;
    const feed = await enrolled.t.withIdentity(identity).query(listSafetyAlerts, guardianArgs);
    expect(feed).toEqual([expect.objectContaining({
      incidentId: "018f-safety-incident-0002",
    }), {
      incidentId: alert.incidentId,
      type: "scam_text",
      packageName: "com.example.messages",
      appLabel: "Messages",
      confidenceBand: "high",
      policyVersion: 2,
      occurredAt,
      createdAt: expect.any(Number),
      expiresAt: expect.any(Number),
    }]);
    expect(JSON.stringify(feed)).not.toContain("rawText");
    expect(JSON.stringify(feed)).not.toContain("fingerprint");
    const deliveryState = await enrolled.t.run(async (ctx: MutationCtx) => ({
      alerts: await ctx.db.query("safetyAlerts").collect(),
      notices: (await ctx.db.query("guardianNotices").collect()).filter((notice) => notice.type === "safety"),
      cooldowns: await ctx.db.query("safetyNotificationCooldowns").collect(),
      receipts: await ctx.db.query("guardianNoticeReceipts").collect(),
    }));
    expect(deliveryState.alerts).toHaveLength(2);
    expect(deliveryState.notices).toHaveLength(1);
    expect(deliveryState.cooldowns).toHaveLength(1);
    expect(deliveryState.cooldowns[0].nextAllowedAt).toBe(firstCooldown);
    expect(deliveryState.receipts).toHaveLength(2);

    await childRequest(enrolled.t, "/child/safety-alerts", enrolled.body.accessJwt, {
      ...alert,
      incidentId: "018f-safety-incident-nsfw-0001",
      type: "nsfw_screen",
      occurredAt: occurredAt + 2,
    });
    const independentTypes = await enrolled.t.run(async (ctx: MutationCtx) => ({
      notices: (await ctx.db.query("guardianNotices").collect()).filter((notice) => notice.type === "safety"),
      cooldowns: await ctx.db.query("safetyNotificationCooldowns").collect(),
    }));
    expect(independentTypes.notices).toHaveLength(2);
    expect(independentTypes.cooldowns.map((row) => row.type).sort()).toEqual(["nsfw_screen", "scam_text"]);

    await enrolled.t.run(async (ctx: MutationCtx) => {
      const scamCooldown = independentTypes.cooldowns.find((row) => row.type === "scam_text")!;
      await ctx.db.patch("safetyNotificationCooldowns", scamCooldown._id, { nextAllowedAt: 0 });
    });
    await childRequest(enrolled.t, "/child/safety-alerts", enrolled.body.accessJwt, {
      ...alert, incidentId: "018f-safety-incident-0003", occurredAt: occurredAt + 2,
    });
    await childRequest(enrolled.t, "/child/safety-alerts", enrolled.body.accessJwt, {
      ...alert, incidentId: "018f-safety-incident-stale", occurredAt: occurredAt - 5 * 60 * 1000 - 1,
    });
    const after = await enrolled.t.run(async (ctx: MutationCtx) => ({
      alerts: await ctx.db.query("safetyAlerts").collect(),
      notices: (await ctx.db.query("guardianNotices").collect()).filter((notice) => notice.type === "safety"),
    }));
    expect(after.alerts).toHaveLength(5);
    expect(after.notices).toHaveLength(3);

    const clearSafetyAlerts = makeFunctionReference<"mutation", any, any>(
      "modules/safetyAlerts/guardian:clearSafetyAlerts",
    ) as FunctionReference<"mutation", "public", any, any>;
    expect(await enrolled.t.withIdentity(identity).mutation(clearSafetyAlerts, guardianArgs))
      .toEqual({ cleared: 5 });
    expect(await enrolled.t.withIdentity(identity).query(listSafetyAlerts, guardianArgs)).toEqual([]);
  });
});

describe("Supervision Policy schema v2", () => {
  beforeEach(() => {
    process.env.CHILD_DEVICE_JWT_SECRET = "test-only-child-jwt-secret-with-at-least-32-bytes";
  });

  test("enrolls with truthful App Blocking, Location Sharing, and Screen Time sections", async () => {
    const enrolled = await enrolledChild(2);
    const response = await childRequest(enrolled.t, "/child/policy", enrolled.body.accessJwt);

    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({
      version: 1,
      schemaVersion: 2,
      appBlocking: { enabled: false, rules: [] },
      locationSharing: { enabled: true },
      screenTime: { enabled: true },
    });
  });

  test("creates the first Manual Block as one pending complete policy change", async () => {
    const enrolled = await enrolledChild(2);
    const updateAppBlockingRules = makeFunctionReference<"mutation", {
      guardianInstallationId: string;
      childProfileId: string;
      expectedCurrentVersion: number;
      operationId: string;
      rules: Array<{
        packageName: string;
        manualBlocked: boolean;
        schedules: Array<{
          scheduleId: string;
          weekdays: number[];
          startMinute: number;
          endMinute: number;
        }>;
      }>;
    }, {
      desiredPolicy: { version: number; appBlocking: { enabled: boolean; rules: unknown[] } };
      applicationStatus: string;
    }>("modules/policies/guardian:updateAppBlockingRules") as FunctionReference<
      "mutation",
      "public",
      {
        guardianInstallationId: string;
        childProfileId: string;
        expectedCurrentVersion: number;
        operationId: string;
        rules: Array<{
          packageName: string;
          manualBlocked: boolean;
          schedules: Array<{
            scheduleId: string;
            weekdays: number[];
            startMinute: number;
            endMinute: number;
          }>;
        }>;
      },
      {
        desiredPolicy: { version: number; appBlocking: { enabled: boolean; rules: unknown[] } };
        applicationStatus: string;
      }
    >;

    const state = await enrolled.t.withIdentity(identity).mutation(updateAppBlockingRules, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
      expectedCurrentVersion: 1,
      operationId: "manual-block-reader-0001",
      rules: [{ packageName: "com.example.reader", manualBlocked: true, schedules: [] }],
    });

    expect(state).toMatchObject({
      desiredPolicy: {
        version: 2,
        appBlocking: {
          enabled: true,
          rules: [{ packageName: "com.example.reader", manualBlocked: true, schedules: [] }],
        },
      },
      applicationStatus: "pending",
    });
  });
});

describe("Latest-only feature convergence", () => {
  beforeEach(() => {
    process.env.CHILD_DEVICE_JWT_SECRET = "test-only-child-jwt-secret-with-at-least-32-bytes";
  });
  afterEach(() => vi.useRealTimers());

  test("publishes newer location, atomic screen time, and absolute access grants", async () => {
    const enrolled = await enrolledChild(2);
    const guardianArgs = {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
    };
    const updateRules = makeFunctionReference<"mutation">(
      "modules/policies/guardian:updateAppBlockingRules",
    ) as FunctionReference<"mutation", "public", any, any>;
    await enrolled.t.withIdentity(identity).mutation(updateRules, {
      ...guardianArgs,
      expectedCurrentVersion: 1,
      operationId: "enable-block-reader-0001",
      rules: [{ packageName: "com.example.reader", manualBlocked: true, schedules: [] }],
    });
    await childRequest(enrolled.t, "/child/policy/acknowledge", enrolled.body.accessJwt, {
      appliedPolicyVersion: 2,
    });
    await childRequest(enrolled.t, "/child/heartbeat", enrolled.body.accessJwt, {
      supportedPolicySchemaVersion: 2,
      capabilities: {
        accessibilityService: true, usageAccess: true, location: true, microphone: true,
        notificationAccess: true, batteryOptimizationExempt: true, trustedDeviceTime: true,
      },
    });

    const catalogStart = await childRequest(enrolled.t, "/child/app-catalog/generations/start", enrolled.body.accessJwt, {
      expectedCount: 1,
    });
    const generationId = (await catalogStart.json()).generationId;
    await childRequest(enrolled.t, "/child/app-catalog/generations/batch", enrolled.body.accessJwt, {
      generationId, apps: [{ packageName: "com.example.reader", label: "Reader" }],
    });
    await childRequest(enrolled.t, "/child/app-catalog/generations/complete", enrolled.body.accessJwt, { generationId });

    const capturedAt = Date.now();
    expect((await childRequest(enrolled.t, "/child/location", enrolled.body.accessJwt, {
      latitude: 12.9, longitude: 77.6, accuracyMeters: 18, capturedAt,
    })).status).toBe(200);
    await childRequest(enrolled.t, "/child/location", enrolled.body.accessJwt, {
      latitude: 1, longitude: 2, accuracyMeters: 100, capturedAt: capturedAt - 1,
    });
    const location = await enrolled.t.withIdentity(identity).query(getLatestLocation, guardianArgs);
    expect(location.location).toMatchObject({ latitude: 12.9, longitude: 77.6, capturedAt });
    await enrolled.t.withIdentity(identity).mutation(updateLocationSharing, {
      ...guardianArgs, expectedCurrentVersion: 2, operationId: "disable-location-0001", enabled: false,
    });
    expect((await childRequest(enrolled.t, "/child/location", enrolled.body.accessJwt, {
      latitude: 13, longitude: 78, accuracyMeters: 10, capturedAt: capturedAt + 1,
    })).status).toBe(400);
    expect((await enrolled.t.withIdentity(identity).query(getLatestLocation, guardianArgs)).location).toBeNull();

    const requested = await enrolled.t.withIdentity(identity).mutation(getOrRequestScreenTime, guardianArgs);
    expect(requested.snapshot).toBeNull();
    const staleRefreshRequestId = requested.refresh!.requestId;
    await enrolled.t.run(async (ctx: MutationCtx) => {
      await ctx.db.patch("screenTimeRefreshRequests", staleRefreshRequestId, { expiresAt: 0 });
      const command = (await ctx.db.query("childDeviceCommands")
        .withIndex("by_active_enrollment_id_and_intent_key", (q) =>
          q.eq("activeEnrollmentId", enrolled.body.activeEnrollmentId).eq("intentKey", "refresh_screen_time"),
        ).order("desc").take(1))[0];
      await ctx.db.patch("childDeviceCommands", command._id, { expiresAt: 0 });
    });
    const retried = await enrolled.t.withIdentity(identity).mutation(getOrRequestScreenTime, guardianArgs);
    const refreshRequestId = retried.refresh!.requestId;
    expect(refreshRequestId).not.toBe(staleRefreshRequestId);
    const pendingCommands = await enrolled.t.run(async (ctx: MutationCtx) =>
      await ctx.db.query("childDeviceCommands")
        .withIndex("by_active_enrollment_id_and_status", (q) =>
          q.eq("activeEnrollmentId", enrolled.body.activeEnrollmentId).eq("status", "pending"),
        ).collect(),
    );
    expect(pendingCommands.filter((command) => command.type === "refresh_screen_time")).toHaveLength(1);
    const measuredAt = Date.now();
    const started = await childRequest(enrolled.t, "/child/screen-time/snapshots/start", enrolled.body.accessJwt, {
      refreshRequestId, expectedCount: 1, measuredAt,
      localDayStart: measuredAt - 60 * 60 * 1000, validUntil: measuredAt + 60 * 60 * 1000,
    });
    const snapshotId = (await started.json()).snapshotId;
    expect((await enrolled.t.withIdentity(identity).mutation(getOrRequestScreenTime, guardianArgs)).snapshot).toBeNull();
    await childRequest(enrolled.t, "/child/screen-time/snapshots/batch", enrolled.body.accessJwt, {
      snapshotId, rows: [{ packageName: "com.example.reader", totalTimeInForegroundMs: 123_000 }],
    });
    await childRequest(enrolled.t, "/child/screen-time/snapshots/complete", enrolled.body.accessJwt, { snapshotId });
    const screen = await enrolled.t.withIdentity(identity).mutation(getOrRequestScreenTime, guardianArgs);
    expect(screen.snapshot?.apps).toEqual([
      { packageName: "com.example.reader", label: "Reader", totalTimeInForegroundMs: 123_000 },
    ]);

    expect((await childRequest(enrolled.t, "/child/access-requests", enrolled.body.accessJwt, {
      packageName: "com.example.reader", appliedPolicyVersion: 2, blockKind: "manual",
    })).status).toBe(200);
    const pending = await enrolled.t.withIdentity(identity).query(listPendingAccessRequests, guardianArgs);
    expect(pending).toHaveLength(1);
    const resolution = await enrolled.t.withIdentity(identity).mutation(resolveAccessRequest, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      requestId: pending[0].requestId,
      decision: "approve",
      durationMinutes: 15,
    });
    expect(resolution.status).toBe("approved");
    const grants = await childRequest(enrolled.t, "/child/access-grants", enrolled.body.accessJwt);
    expect((await grants.json()).grants[0]).toMatchObject({ packageName: "com.example.reader" });
  });

  test("expires a pending access request when its applicable block rule changes", async () => {
    const enrolled = await enrolledChild(2);
    const guardianArgs = {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
    };
    const updateRules = makeFunctionReference<"mutation">(
      "modules/policies/guardian:updateAppBlockingRules",
    ) as FunctionReference<"mutation", "public", any, any>;
    await enrolled.t.withIdentity(identity).mutation(updateRules, {
      ...guardianArgs,
      expectedCurrentVersion: 1,
      operationId: "enable-block-for-request-0001",
      rules: [{ packageName: "com.example.reader", manualBlocked: true, schedules: [] }],
    });
    await childRequest(enrolled.t, "/child/policy/acknowledge", enrolled.body.accessJwt, {
      appliedPolicyVersion: 2,
    });
    expect((await childRequest(enrolled.t, "/child/access-requests", enrolled.body.accessJwt, {
      packageName: "com.example.reader", appliedPolicyVersion: 2, blockKind: "manual",
    })).status).toBe(200);
    expect(await enrolled.t.withIdentity(identity).query(listPendingAccessRequests, guardianArgs)).toHaveLength(1);

    await enrolled.t.withIdentity(identity).mutation(updateRules, {
      ...guardianArgs,
      expectedCurrentVersion: 2,
      operationId: "remove-block-for-request-0001",
      rules: [],
    });

    expect(await enrolled.t.withIdentity(identity).query(listPendingAccessRequests, guardianArgs)).toHaveLength(0);
    const requests = await enrolled.t.run(async (ctx: MutationCtx) =>
      await ctx.db.query("accessRequests").collect(),
    );
    expect(requests).toHaveLength(1);
    expect(requests[0].status).toBe("expired");
  });

  test("replacement clears enrollment-owned feature state and end supervision deletes retained policy", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-13T10:00:00Z"));
    const enrolled = await enrolledChild(2);
    const guardianArgs = {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
    };
    const started = await childRequest(enrolled.t, "/child/app-catalog/generations/start", enrolled.body.accessJwt, {
      expectedCount: 1,
    });
    const generationId = (await started.json()).generationId;
    await childRequest(enrolled.t, "/child/app-catalog/generations/batch", enrolled.body.accessJwt, {
      generationId, apps: [{ packageName: "com.example.reader", label: "Reader" }],
    });
    await childRequest(enrolled.t, "/child/app-catalog/generations/complete", enrolled.body.accessJwt, { generationId });

    expect(await enrolled.t.withIdentity(identity).mutation(replaceChildDevice, guardianArgs)).toEqual({ replaced: true });

    const challengeResponse = await requestTokenChallenge(
      enrolled.t,
      enrolled.body.credentialId,
      enrolled.proof.keyPair,
    );
    expect(challengeResponse.status).toBe(200);
    const challengeBody = await challengeResponse.json();
    const signature = await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      enrolled.proof.keyPair.privateKey,
      new TextEncoder().encode(
        `cereveil-child-token-refresh-v1\n${enrolled.body.credentialId}\n${challengeBody.challenge}`,
      ),
    );
    const revokedExchange = await enrolled.t.fetch("/device-identity/token/exchange", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        credentialId: enrolled.body.credentialId,
        challenge: challengeBody.challenge,
        proof: base64UrlEncode(new Uint8Array(signature)),
      }),
    });
    expect(revokedExchange.status).toBe(410);
    expect(await revokedExchange.json()).toEqual({ code: "CHILD_DEVICE_REVOKED" });

    await enrolled.t.finishAllScheduledFunctions(() => vi.runAllTimers());
    const afterReplace = await enrolled.t.run(async (ctx: MutationCtx) => ({
      generations: await ctx.db.query("appCatalogGenerations").collect(),
      entries: await ctx.db.query("appCatalogEntries").collect(),
      enrollment: await ctx.db.get("activeEnrollments", enrolled.body.activeEnrollmentId),
      policies: await ctx.db.query("supervisionPolicies").collect(),
    }));
    expect(afterReplace.generations).toHaveLength(0);
    expect(afterReplace.entries).toHaveLength(0);
    expect(afterReplace.enrollment?.status).toBe("revoked");
    expect(afterReplace.policies).toHaveLength(1);

    await enrolled.t.run(async (ctx: MutationCtx) => {
      const enrollment = await ctx.db.get("activeEnrollments", enrolled.body.activeEnrollmentId);
      if (enrollment === null) throw new Error("Expected retained revoked enrollment");
      const guardianDevice = (await ctx.db.query("guardianDevices")
        .withIndex("by_status", (q) => q.eq("status", "active")).take(1)).at(0);
      if (guardianDevice === undefined) throw new Error("Expected active Guardian Device");
      const credential = (await ctx.db.query("childDeviceCredentials")
        .withIndex("by_active_enrollment_id_and_status", (q) => q.eq("activeEnrollmentId", enrollment._id)).take(1)).at(0);
      if (credential === undefined) throw new Error("Expected Child Device credential");
      for (let index = 0; index < 51; index += 1) {
        await ctx.db.insert("childDeviceTokenChallenges", {
          credentialId: credential._id,
          challengeHash: `end-supervision-challenge-${index}`,
          status: "used",
          expiresAt: 10_000,
          createdAt: 1,
          usedAt: 2,
        });
      }
      const fcmTokenId = await ctx.db.insert("fcmTokens", {
        ownerKind: "childDevice",
        ownerId: enrollment.childDeviceId,
        householdId: enrollment.householdId,
        childProfileId: enrollment.childProfileId,
        childDeviceId: enrollment.childDeviceId,
        activeEnrollmentId: enrollment._id,
        tokenHash: "end-supervision-delivery-token",
        encryptedToken: "end-supervision-delivery-ciphertext",
        platform: "android",
        environment: "dev",
        status: "revoked",
        registeredAt: 1,
        lastSeenAt: 1,
        invalidatedAt: 2,
      });
      const commandId = await ctx.db.insert("childDeviceCommands", {
        householdId: enrollment.householdId,
        childProfileId: enrollment.childProfileId,
        activeEnrollmentId: enrollment._id,
        childDeviceId: enrollment.childDeviceId,
        type: "refresh_location",
        intentKey: "end-supervision-cleanup",
        status: "pending",
        createdAt: 1,
        updatedAt: 1,
        expiresAt: 10_000,
      });
      const noticeId = await ctx.db.insert("guardianNotices", {
        householdId: enrollment.householdId,
        childProfileId: enrollment.childProfileId,
        activeEnrollmentId: enrollment._id,
        type: "recovery",
        episodeKey: "end-supervision-cleanup",
        status: "active",
        occurredAt: 1,
        expiresAt: 10_000,
      });
      await ctx.db.insert("guardianNoticeReceipts", {
        guardianNoticeId: noticeId,
        guardianDeviceId: guardianDevice._id,
        householdId: enrollment.householdId,
        status: "pending",
        createdAt: 1,
        expiresAt: 10_000,
      });
      await ctx.db.insert("fcmDeliveryAttempts", {
        recordKind: "childDeviceCommand",
        recordId: commandId,
        fcmTokenId,
        attempt: 1,
        outcome: "accepted",
        attemptedAt: 1,
        expiresAt: 10_000,
      });
      await ctx.db.insert("fcmDeliveryAttempts", {
        recordKind: "guardianNotice",
        recordId: noticeId,
        fcmTokenId,
        attempt: 1,
        outcome: "accepted",
        attemptedAt: 1,
        expiresAt: 10_000,
      });
    });

    expect(await enrolled.t.withIdentity(identity).mutation(endSupervision, guardianArgs)).toEqual({ ended: true });
    expect(await enrolled.t.withIdentity(identity).mutation(endSupervision, guardianArgs)).toEqual({ ended: true });
    await enrolled.t.finishAllScheduledFunctions(() => vi.runAllTimers());
    expect(await enrolled.t.withIdentity(identity).mutation(endSupervision, guardianArgs)).toEqual({ ended: true });
    const afterEnd = await enrolled.t.run(async (ctx: MutationCtx) => ({
      profiles: await ctx.db.query("childProfiles").collect(),
      policies: await ctx.db.query("supervisionPolicies").collect(),
      devices: await ctx.db.query("childDevices").collect(),
      enrollments: await ctx.db.query("activeEnrollments").collect(),
      challenges: await ctx.db.query("childDeviceTokenChallenges").collect(),
      deliveryAttempts: await ctx.db.query("fcmDeliveryAttempts").collect(),
    }));
    expect(afterEnd).toEqual({
      profiles: [],
      policies: [],
      devices: [],
      enrollments: [],
      challenges: [],
      deliveryAttempts: [],
    });

    const endedChallengeResponse = await requestTokenChallenge(
      enrolled.t,
      enrolled.body.credentialId,
      enrolled.proof.keyPair,
    );
    expect(endedChallengeResponse.status).toBe(200);
    const endedChallenge = await endedChallengeResponse.json();
    const unrelatedKey = await enrollmentProof("unrelated-revocation-proof-key");
    const unrelatedSignature = await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      unrelatedKey.keyPair.privateKey,
      new TextEncoder().encode(
        `cereveil-child-token-refresh-v1\n${enrolled.body.credentialId}\n${endedChallenge.challenge}`,
      ),
    );
    const unauthenticatedExchange = await enrolled.t.fetch("/device-identity/token/exchange", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        credentialId: enrolled.body.credentialId,
        challenge: endedChallenge.challenge,
        proof: base64UrlEncode(new Uint8Array(unrelatedSignature)),
      }),
    });
    expect(unauthenticatedExchange.status).toBe(401);

    const endedSignature = await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      enrolled.proof.keyPair.privateKey,
      new TextEncoder().encode(
        `cereveil-child-token-refresh-v1\n${enrolled.body.credentialId}\n${endedChallenge.challenge}`,
      ),
    );
    const endedExchange = await enrolled.t.fetch("/device-identity/token/exchange", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        credentialId: enrolled.body.credentialId,
        challenge: endedChallenge.challenge,
        proof: base64UrlEncode(new Uint8Array(endedSignature)),
      }),
    });
    expect(endedExchange.status).toBe(410);
    expect(await endedExchange.json()).toEqual({ code: "CHILD_DEVICE_REVOKED" });
  });
});

async function enrolledChild(supportedPolicySchemaVersion = 2) {
  const t = backend();
  const child = await preparedGuardian(t);
  const code = await t.withIdentity(identity).mutation(createEnrollmentCode, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
    childProfileId: child.childProfileId,
  });
  const proof = await enrollmentProof(code.code);
  const response = await completeEnrollment(t, code.code, proof, supportedPolicySchemaVersion);
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

async function requestTokenChallenge(
  t: ReturnType<typeof backend>,
  credentialId: string,
  keyPair: CryptoKeyPair,
  suppliedNonce?: string,
  suppliedIssuedAt?: number,
) {
  let requestNonce = suppliedNonce;
  let requestIssuedAt = suppliedIssuedAt;
  if (requestNonce === undefined || requestIssuedAt === undefined) {
    const issued = await t.fetch("/device-identity/token/challenge/request", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "{}",
    });
    if (issued.status !== 200) return issued;
    const body = await issued.json();
    requestNonce = body.requestNonce;
    requestIssuedAt = body.requestIssuedAt;
  }
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    keyPair.privateKey,
    new TextEncoder().encode(
      `cereveil-child-token-challenge-v1\n${credentialId}\n${requestNonce}\n${requestIssuedAt}`,
    ),
  );
  return await t.fetch("/device-identity/token/challenge", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      credentialId,
      requestNonce,
      requestIssuedAt,
      proof: base64UrlEncode(new Uint8Array(signature)),
    }),
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
  supportedPolicySchemaVersion = 2,
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
      supportedPolicySchemaVersion,
    }),
  });
}
