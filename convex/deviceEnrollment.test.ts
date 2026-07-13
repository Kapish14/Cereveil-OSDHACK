import { FunctionReference, GenericMutationCtx, makeFunctionReference } from "convex/server";
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
      schemaVersion: 2,
      appBlocking: { enabled: false, rules: [] },
      safeBrowsing: { enabled: false, safeSearchEnabled: false },
      activeScreenSafety: { enabled: false },
      locationSharing: { enabled: false },
      screenTime: { enabled: false },
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
      enabled: true,
    };
    const changed = await enrolled.t.withIdentity(identity).mutation(updateScreenTimeSummaries, args);
    expect(changed).toMatchObject({
      desiredPolicy: { version: 2, schemaVersion: 2, screenTime: { enabled: true } },
      appliedPolicy: { version: 1, screenTime: { enabled: false } },
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
    expect(await fetched.json()).toMatchObject({ version: 2, screenTime: { enabled: true } });
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
      enabled: true,
    });
    await expectAppError(enrolled.t.withIdentity(identity).mutation(updateScreenTimeSummaries, {
      ...common,
      operationId: "018f-policy-save-operation-0003",
      enabled: false,
    }), "POLICY_CONFLICT");
    await expectAppError(enrolled.t.withIdentity(identity).mutation(updateScreenTimeSummaries, {
      ...common,
      operationId: "018f-policy-save-operation-0002",
      enabled: false,
    }), "POLICY_OPERATION_REUSED");
  });

  test("preserves unrelated sections across successive feature changes", async () => {
    const enrolled = await enrolledChild();
    const base = {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
    };
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
      enabled: true,
    });
    expect(result.desiredPolicy).toMatchObject({
      version: 4,
      safeBrowsing: { enabled: true, safeSearchEnabled: true },
      appBlocking: { enabled: true },
      activeScreenSafety: { enabled: true },
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
      enabled: true,
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
      locationSharing: { enabled: false },
      screenTime: { enabled: false },
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

  test("publishes newer location, atomic screen time, and absolute access grants", async () => {
    const enrolled = await enrolledChild(2);
    const guardianArgs = {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      childProfileId: enrolled.child.childProfileId,
    };
    await enrolled.t.withIdentity(identity).mutation(updateLocationSharing, {
      ...guardianArgs, expectedCurrentVersion: 1, operationId: "enable-location-0001", enabled: true,
    });
    await enrolled.t.withIdentity(identity).mutation(updateScreenTime, {
      ...guardianArgs, expectedCurrentVersion: 2, operationId: "enable-screen-time-0001", enabled: true,
    });
    const updateRules = makeFunctionReference<"mutation">(
      "modules/policies/guardian:updateAppBlockingRules",
    ) as FunctionReference<"mutation", "public", any, any>;
    await enrolled.t.withIdentity(identity).mutation(updateRules, {
      ...guardianArgs,
      expectedCurrentVersion: 3,
      operationId: "enable-block-reader-0001",
      rules: [{ packageName: "com.example.reader", manualBlocked: true, schedules: [] }],
    });
    await childRequest(enrolled.t, "/child/policy/acknowledge", enrolled.body.accessJwt, {
      appliedPolicyVersion: 4,
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

    const requested = await enrolled.t.withIdentity(identity).mutation(getOrRequestScreenTime, guardianArgs);
    expect(requested.snapshot).toBeNull();
    const refreshRequestId = requested.refresh!.requestId;
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
      packageName: "com.example.reader", appliedPolicyVersion: 4, blockKind: "manual",
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
