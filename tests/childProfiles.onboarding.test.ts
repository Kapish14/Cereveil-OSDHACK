import { describe, expect, test } from "vitest";
import { convexTest } from "convex-test";
import { GenericMutationCtx } from "convex/server";
import schema from "../convex/schema";
import { api } from "../convex/_generated/api";
import { DataModel } from "../convex/_generated/dataModel";

const modules = import.meta.glob("../convex/**/*.ts");
const bootstrap = api.modules.guardianAuth.public.bootstrapGuardian;
const createChildProfile = api.modules.childProfiles.public.createChildProfile;
const listChildProfiles = api.modules.childProfiles.public.listChildProfiles;

const identity = {
  tokenIdentifier: "https://clerk.example|user_guardian_1",
  subject: "user_guardian_1",
  email: "guardian@example.com",
};

const bootstrapArgs = {
  guardianInstallationId: "018f2e36-3d2c-78d8-b7bd-847f0f561111",
  deviceLabel: "Pixel 8",
  appBuild: "guardian-debug-1",
  timezone: "Asia/Kolkata",
};

type MutationCtx = GenericMutationCtx<DataModel>;

function testBackend() {
  return convexTest({ schema, modules });
}

async function expectAppError(promise: Promise<unknown>, code: string) {
  await expect(promise).rejects.toMatchObject({
    data: expect.objectContaining({ code }),
  });
}

async function listRows(ctx: MutationCtx) {
  return {
    guardianAccounts: await ctx.db.query("guardianAccounts").collect(),
    households: await ctx.db.query("households").collect(),
    childProfiles: await ctx.db.query("childProfiles").collect(),
    supervisionPolicies: await ctx.db.query("supervisionPolicies").collect(),
  };
}

describe("createChildProfile", () => {
  test("requires the authenticated Guardian's active installation", async () => {
    const t = testBackend();
    await t.withIdentity(identity).mutation(bootstrap, bootstrapArgs);

    await expectAppError(
      t.withIdentity(identity).mutation(createChildProfile, {
        guardianInstallationId: "unknown-installation-id",
        displayName: "Aarav",
        birthMonth: 7,
        birthYear: 2015,
      }),
      "UNAUTHENTICATED",
    );

    await t.run(async (ctx: MutationCtx) => {
      const device = (await ctx.db.query("guardianDevices").collect())[0];
      await ctx.db.patch("guardianDevices", device._id, { status: "revoked", revokedAt: Date.now() });
    });
    await expectAppError(
      t.withIdentity(identity).mutation(createChildProfile, {
        guardianInstallationId: bootstrapArgs.guardianInstallationId,
        displayName: "Aarav",
        birthMonth: 7,
        birthYear: 2015,
      }),
      "DEVICE_REVOKED",
    );
  });

  test("creates an Unenrolled Child Profile with an Initial Supervision Policy", async () => {
    const t = testBackend();
    await t.withIdentity(identity).mutation(bootstrap, bootstrapArgs);

    const result = await t.withIdentity(identity).mutation(createChildProfile, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      displayName: "Aarav",
      birthMonth: 7,
      birthYear: 2015,
    });

    const rows = await t.run(listRows);
    expect(rows.childProfiles).toHaveLength(1);
    expect(rows.supervisionPolicies).toHaveLength(1);

    expect(rows.childProfiles[0]).toMatchObject({
      householdId: rows.households[0]._id,
      displayName: "Aarav",
      birthMonth: 7,
      birthYear: 2015,
      status: "active",
    });
    expect(rows.supervisionPolicies[0]).toMatchObject({
      householdId: rows.households[0]._id,
      childProfileId: rows.childProfiles[0]._id,
      version: 1,
      status: "active",
      schemaVersion: 2,
      appBlocking: { enabled: false, rules: [] },
      activeScreenSafety: { enabled: false },
      locationSharing: { enabled: false },
      screenTime: { enabled: false },
      createdByGuardianAccountId: rows.guardianAccounts[0]._id,
    });
    expect(rows.supervisionPolicies[0].safeBrowsing).toEqual({
      enabled: false,
      safeSearchEnabled: false,
    });
    expect(result).toEqual({
      childProfileId: rows.childProfiles[0]._id,
      displayName: "Aarav",
      birthMonth: 7,
      birthYear: 2015,
      status: "active",
      enrollmentStatus: "unenrolled",
      connectivityStatus: "not_applicable",
      protectionHealthStatus: "not_applicable",
      currentPolicyVersionId: rows.supervisionPolicies[0]._id,
      currentPolicyVersion: 1,
      serverNow: expect.any(Number),
    });
  });

  test("rejects unauthenticated creation without creating child data", async () => {
    const t = testBackend();

    await expectAppError(
      t.mutation(createChildProfile, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
        displayName: "Aarav",
        birthMonth: 7,
        birthYear: 2015,
      }),
      "UNAUTHENTICATED",
    );

    const rows = await t.run(listRows);
    expect(rows.childProfiles).toHaveLength(0);
    expect(rows.supervisionPolicies).toHaveLength(0);
  });

  test("rejects invalid child facts without creating child data", async () => {
    const cases = [
      { args: { displayName: "", birthMonth: 7, birthYear: 2015 }, code: "VALIDATION_FAILED" },
      {
        args: { displayName: "x".repeat(81), birthMonth: 7, birthYear: 2015 },
        code: "VALIDATION_FAILED",
      },
      { args: { displayName: "Aarav", birthMonth: 13, birthYear: 2015 }, code: "VALIDATION_FAILED" },
      { args: { displayName: "Aarav", birthMonth: 1, birthYear: 1800 }, code: "VALIDATION_FAILED" },
      { args: { displayName: "Too young", birthMonth: 1, birthYear: 2021 }, code: "CHILD_AGE_OUT_OF_RANGE" },
      { args: { displayName: "Too old", birthMonth: 1, birthYear: 2005 }, code: "CHILD_AGE_OUT_OF_RANGE" },
    ];

    for (const { args, code } of cases) {
      const t = testBackend();
      await t.withIdentity(identity).mutation(bootstrap, bootstrapArgs);

      await expectAppError(
        t.withIdentity(identity).mutation(createChildProfile, {
          guardianInstallationId: bootstrapArgs.guardianInstallationId,
          ...args,
        }),
        code,
      );

      const rows = await t.run(listRows);
      expect(rows.childProfiles).toHaveLength(0);
      expect(rows.supervisionPolicies).toHaveLength(0);
    }
  });

  test("blocks disabled and deleting Guardian Account or deleting Household states", async () => {
    const cases = [
      { accountStatus: "disabled" as const, householdStatus: "active" as const, code: "ACCOUNT_DISABLED" },
      { accountStatus: "deleting" as const, householdStatus: "active" as const, code: "ACCOUNT_DELETING" },
      { accountStatus: "active" as const, householdStatus: "deleting" as const, code: "HOUSEHOLD_DELETING" },
    ];

    for (const { accountStatus, householdStatus, code } of cases) {
      const t = testBackend();
      await t.run(async (ctx) => {
        const guardianAccountId = await ctx.db.insert("guardianAccounts", {
          clerkTokenIdentifier: identity.tokenIdentifier,
          clerkUserId: identity.subject,
          status: accountStatus,
          createdAt: 1,
          updatedAt: 1,
        });
        await ctx.db.insert("households", {
          guardianAccountId,
          status: householdStatus,
          timezone: "Asia/Kolkata",
          country: "IN",
          createdAt: 1,
          updatedAt: 1,
        });
      });

      await expectAppError(
        t.withIdentity(identity).mutation(createChildProfile, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
          displayName: "Aarav",
          birthMonth: 7,
          birthYear: 2015,
        }),
        code,
      );

      const rows = await t.run(listRows);
      expect(rows.childProfiles).toHaveLength(0);
      expect(rows.supervisionPolicies).toHaveLength(0);
    }
  });
});

describe("listChildProfiles", () => {
  test("returns active Child Profile summaries for the authenticated Guardian Household", async () => {
    const t = testBackend();
    await t.withIdentity(identity).mutation(bootstrap, bootstrapArgs);
    const first = await t.withIdentity(identity).mutation(createChildProfile, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      displayName: "Aarav",
      birthMonth: 7,
      birthYear: 2015,
    });
    const second = await t.withIdentity(identity).mutation(createChildProfile, {
      guardianInstallationId: bootstrapArgs.guardianInstallationId,
      displayName: "Isha",
      birthMonth: 3,
      birthYear: 2013,
    });
    await t.run(async (ctx) => {
      const rows = await listRows(ctx);
      await ctx.db.insert("childProfiles", {
        householdId: rows.households[0]._id,
        displayName: "Deleting",
        birthMonth: 1,
        birthYear: 2014,
        status: "deleting",
        createdAt: 1,
        updatedAt: 1,
      });
    });

    const result = await t.withIdentity(identity).query(listChildProfiles, { guardianInstallationId: bootstrapArgs.guardianInstallationId });

    expect(result).toEqual([
      {
        childProfileId: first.childProfileId,
        displayName: "Aarav",
        birthMonth: 7,
        birthYear: 2015,
        status: "active",
        enrollmentStatus: "unenrolled",
        connectivityStatus: "not_applicable",
        protectionHealthStatus: "not_applicable",
        serverNow: expect.any(Number),
        currentPolicyVersionId: first.currentPolicyVersionId,
        currentPolicyVersion: 1,
      },
      {
        childProfileId: second.childProfileId,
        displayName: "Isha",
        birthMonth: 3,
        birthYear: 2013,
        status: "active",
        enrollmentStatus: "unenrolled",
        connectivityStatus: "not_applicable",
        protectionHealthStatus: "not_applicable",
        serverNow: expect.any(Number),
        currentPolicyVersionId: second.currentPolicyVersionId,
        currentPolicyVersion: 1,
      },
    ]);
    expect(JSON.stringify(result)).not.toContain("appBlocks");
    expect(JSON.stringify(result)).not.toContain("Safety");
  });

  test("returns an empty list when the Household has no active Child Profiles", async () => {
    const t = testBackend();
    await t.withIdentity(identity).mutation(bootstrap, bootstrapArgs);

    expect(await t.withIdentity(identity).query(listChildProfiles, { guardianInstallationId: bootstrapArgs.guardianInstallationId })).toEqual([]);
  });

  test("rejects unauthenticated list requests", async () => {
    const t = testBackend();

    await expectAppError(t.query(listChildProfiles, { guardianInstallationId: bootstrapArgs.guardianInstallationId }), "UNAUTHENTICATED");
  });
});
