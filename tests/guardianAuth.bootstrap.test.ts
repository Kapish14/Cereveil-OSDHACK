import { describe, expect, test } from "vitest";
import { convexTest } from "convex-test";
import { GenericMutationCtx } from "convex/server";
import schema from "../convex/schema";
import { api } from "../convex/_generated/api";
import { DataModel, Id } from "../convex/_generated/dataModel";

const modules = import.meta.glob("../convex/**/*.ts");
const bootstrap = api.modules.guardianAuth.public.bootstrapGuardian;

const identity = {
  tokenIdentifier: "https://clerk.example|user_guardian_1",
  subject: "user_guardian_1",
  email: "guardian@example.com",
};

const validArgs = {
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
    guardianDevices: await ctx.db.query("guardianDevices").collect(),
    childProfiles: await ctx.db.query("childProfiles").collect(),
  };
}

describe("bootstrapGuardian", () => {
  test("rejects unauthenticated calls without creating records", async () => {
    const t = testBackend();

    await expectAppError(t.mutation(bootstrap, validArgs), "UNAUTHENTICATED");

    const rows = await t.run(listRows);
    expect(rows.guardianAccounts).toHaveLength(0);
    expect(rows.households).toHaveLength(0);
    expect(rows.guardianDevices).toHaveLength(0);
  });

  test("creates the Guardian Account, Household, and Guardian Device for first bootstrap", async () => {
    const t = testBackend();
    const before = Date.now();

    const result = await t.withIdentity(identity).mutation(bootstrap, validArgs);

    const rows = await t.run(listRows);
    expect(rows.guardianAccounts).toHaveLength(1);
    expect(rows.households).toHaveLength(1);
    expect(rows.guardianDevices).toHaveLength(1);
    expect(rows.childProfiles).toHaveLength(0);

    expect(rows.guardianAccounts[0]).toMatchObject({
      clerkTokenIdentifier: identity.tokenIdentifier,
      clerkUserId: identity.subject,
      primaryEmail: identity.email,
      status: "active",
    });
    expect(rows.households[0]).toMatchObject({
      guardianAccountId: rows.guardianAccounts[0]._id,
      status: "active",
      timezone: "Asia/Kolkata",
      country: "IN",
    });
    expect(rows.guardianDevices[0]).toMatchObject({
      guardianAccountId: rows.guardianAccounts[0]._id,
      householdId: rows.households[0]._id,
      guardianInstallationId: validArgs.guardianInstallationId,
      deviceLabel: "Pixel 8",
      platform: "android",
      appBuild: "guardian-debug-1",
      environment: "dev",
      status: "active",
    });
    expect(result).toEqual({
      guardianAccountId: rows.guardianAccounts[0]._id,
      householdId: rows.households[0]._id,
      guardianDeviceId: rows.guardianDevices[0]._id,
      guardianDeviceStatus: "active",
      hasChildProfiles: false,
      serverNow: expect.any(Number),
    });
    expect(result.serverNow).toBeGreaterThanOrEqual(before);
  });

  test("defaults missing or invalid timezone to Asia/Kolkata", async () => {
    const t = testBackend();
    const { timezone: _timezone, ...argsWithoutTimezone } = validArgs;
    await t.withIdentity(identity).mutation(bootstrap, argsWithoutTimezone);

    const rows = await t.run(listRows);
    expect(rows.households[0].timezone).toBe("Asia/Kolkata");

    const invalidTimezoneBackend = testBackend();
    await invalidTimezoneBackend.withIdentity(identity).mutation(bootstrap, {
      ...validArgs,
      timezone: "Not/A_Timezone",
    });
    const invalidTimezoneRows = await invalidTimezoneBackend.run(listRows);
    expect(invalidTimezoneRows.households[0].timezone).toBe("Asia/Kolkata");
  });

  test("reuses existing rows and refreshes mutable metadata on repeat bootstrap", async () => {
    const t = testBackend();
    const first = await t.withIdentity(identity).mutation(bootstrap, validArgs);

    await new Promise((resolve) => setTimeout(resolve, 1));
    const second = await t.withIdentity({
      ...identity,
      email: "updated@example.com",
    }).mutation(bootstrap, {
      ...validArgs,
      deviceLabel: "Updated Pixel",
      appBuild: "guardian-debug-2",
    });

    const rows = await t.run(listRows);
    expect(rows.guardianAccounts).toHaveLength(1);
    expect(rows.households).toHaveLength(1);
    expect(rows.guardianDevices).toHaveLength(1);
    expect(second.guardianAccountId).toBe(first.guardianAccountId);
    expect(second.householdId).toBe(first.householdId);
    expect(second.guardianDeviceId).toBe(first.guardianDeviceId);
    expect(rows.guardianAccounts[0].primaryEmail).toBe("updated@example.com");
    expect(rows.guardianDevices[0].deviceLabel).toBe("Updated Pixel");
    expect(rows.guardianDevices[0].appBuild).toBe("guardian-debug-2");
    expect(rows.guardianDevices[0].lastSeenAt).toBe(second.serverNow);
    expect(second.serverNow).toBeGreaterThanOrEqual(first.serverNow);
  });

  test("preserves existing email metadata when Clerk provides no email", async () => {
    const t = testBackend();
    await t.withIdentity(identity).mutation(bootstrap, validArgs);

    await t.withIdentity({
      tokenIdentifier: identity.tokenIdentifier,
      subject: identity.subject,
    }).mutation(bootstrap, validArgs);

    const rows = await t.run(listRows);
    expect(rows.guardianAccounts[0].primaryEmail).toBe(identity.email);
  });

  test("rejects invalid installation IDs without creating records", async () => {
    const t = testBackend();

    await expectAppError(
      t.withIdentity(identity).mutation(bootstrap, {
        ...validArgs,
        guardianInstallationId: "too-short",
      }),
      "VALIDATION_FAILED",
    );

    const rows = await t.run(listRows);
    expect(rows.guardianAccounts).toHaveLength(0);
    expect(rows.households).toHaveLength(0);
    expect(rows.guardianDevices).toHaveLength(0);
  });

  test("blocks disabled and deleting Guardian Accounts without mutating related records", async () => {
    for (const status of ["disabled", "deleting"] as const) {
      const t = testBackend();
      await t.run(async (ctx) => {
        await ctx.db.insert("guardianAccounts", {
          clerkTokenIdentifier: identity.tokenIdentifier,
          clerkUserId: identity.subject,
          primaryEmail: identity.email,
          status,
          createdAt: 1,
          updatedAt: 1,
        });
      });

      await expectAppError(
        t.withIdentity(identity).mutation(bootstrap, validArgs),
        status === "disabled" ? "ACCOUNT_DISABLED" : "ACCOUNT_DELETING",
      );

      const rows = await t.run(listRows);
      expect(rows.households).toHaveLength(0);
      expect(rows.guardianDevices).toHaveLength(0);
    }
  });

  test("repairs a missing Household for an active Guardian Account", async () => {
    const t = testBackend();
    const guardianAccountId = await t.run(async (ctx) => {
      return await ctx.db.insert("guardianAccounts", {
        clerkTokenIdentifier: identity.tokenIdentifier,
        clerkUserId: identity.subject,
        status: "active",
        createdAt: 1,
        updatedAt: 1,
      });
    });

    const result = await t.withIdentity(identity).mutation(bootstrap, validArgs);

    const rows = await t.run(listRows);
    expect(result.guardianAccountId).toBe(guardianAccountId);
    expect(rows.households).toHaveLength(1);
    expect(rows.guardianDevices).toHaveLength(1);
  });

  test("blocks deleting Household without creating a replacement", async () => {
    const t = testBackend();
    await t.run(async (ctx) => {
      const guardianAccountId = await ctx.db.insert("guardianAccounts", {
        clerkTokenIdentifier: identity.tokenIdentifier,
        clerkUserId: identity.subject,
        status: "active",
        createdAt: 1,
        updatedAt: 1,
      });
      await ctx.db.insert("households", {
        guardianAccountId,
        status: "deleting",
        timezone: "Asia/Kolkata",
        country: "IN",
        createdAt: 1,
        updatedAt: 1,
      });
    });

    await expectAppError(
      t.withIdentity(identity).mutation(bootstrap, validArgs),
      "HOUSEHOLD_DELETING",
    );

    const rows = await t.run(listRows);
    expect(rows.households).toHaveLength(1);
    expect(rows.guardianDevices).toHaveLength(0);
  });

  test("blocks revoked Guardian Device installations", async () => {
    const t = testBackend();
    const { guardianAccountId, householdId } = await seedGuardianAccountAndHousehold(t);
    await t.run(async (ctx) => {
      await ctx.db.insert("guardianDevices", {
        guardianAccountId,
        householdId,
        guardianInstallationId: validArgs.guardianInstallationId,
        platform: "android",
        appBuild: "old-build",
        environment: "dev",
        status: "revoked",
        lastSeenAt: 1,
        createdAt: 1,
        updatedAt: 1,
        revokedAt: 2,
      });
    });

    await expectAppError(
      t.withIdentity(identity).mutation(bootstrap, validArgs),
      "DEVICE_REVOKED",
    );

    const rows = await t.run(listRows);
    expect(rows.guardianDevices).toHaveLength(1);
    expect(rows.guardianDevices[0].status).toBe("revoked");
  });

  test("blocks a new third Guardian Device but allows an existing active device at the limit", async () => {
    const t = testBackend();
    const { guardianAccountId, householdId } = await seedGuardianAccountAndHousehold(t);
    await seedGuardianDevice(t, guardianAccountId, householdId, "018f2e36-3d2c-78d8-b7bd-847f0f562222");
    await seedGuardianDevice(t, guardianAccountId, householdId, validArgs.guardianInstallationId);

    const existingDeviceResult = await t.withIdentity(identity).mutation(bootstrap, validArgs);
    expect(existingDeviceResult.guardianAccountId).toBe(guardianAccountId);

    await expectAppError(
      t.withIdentity(identity).mutation(bootstrap, {
        ...validArgs,
        guardianInstallationId: "018f2e36-3d2c-78d8-b7bd-847f0f563333",
      }),
      "DEVICE_LIMIT_REACHED",
    );

    const rows = await t.run(listRows);
    expect(rows.guardianDevices).toHaveLength(2);
    expect(rows.guardianDevices.every((device) => device.status === "active")).toBe(true);
  });

  test("reports Child Profile routing state using only active Child Profiles", async () => {
    const noProfileBackend = testBackend();
    const noProfileResult = await noProfileBackend
      .withIdentity(identity)
      .mutation(bootstrap, validArgs);
    expect(noProfileResult.hasChildProfiles).toBe(false);

    const deletingOnlyBackend = testBackend();
    const deletingOnly = await seedGuardianAccountAndHousehold(deletingOnlyBackend);
    await seedChildProfile(deletingOnlyBackend, deletingOnly.householdId, "deleting");
    const deletingOnlyResult = await deletingOnlyBackend
      .withIdentity(identity)
      .mutation(bootstrap, validArgs);
    expect(deletingOnlyResult.hasChildProfiles).toBe(false);

    const activeBackend = testBackend();
    const active = await seedGuardianAccountAndHousehold(activeBackend);
    await seedChildProfile(activeBackend, active.householdId, "deleting");
    await seedChildProfile(activeBackend, active.householdId, "active");
    const activeResult = await activeBackend
      .withIdentity(identity)
      .mutation(bootstrap, validArgs);
    expect(activeResult.hasChildProfiles).toBe(true);
  });
});

async function seedGuardianAccountAndHousehold(t: ReturnType<typeof testBackend>) {
  return await t.run(async (ctx) => {
    const guardianAccountId = await ctx.db.insert("guardianAccounts", {
      clerkTokenIdentifier: identity.tokenIdentifier,
      clerkUserId: identity.subject,
      status: "active",
      createdAt: 1,
      updatedAt: 1,
    });
    const householdId = await ctx.db.insert("households", {
      guardianAccountId,
      status: "active",
      timezone: "Asia/Kolkata",
      country: "IN",
      createdAt: 1,
      updatedAt: 1,
    });
    return { guardianAccountId, householdId };
  });
}

async function seedGuardianDevice(
  t: ReturnType<typeof testBackend>,
  guardianAccountId: Id<"guardianAccounts">,
  householdId: Id<"households">,
  guardianInstallationId: string,
) {
  await t.run(async (ctx) => {
    await ctx.db.insert("guardianDevices", {
      guardianAccountId,
      householdId,
      guardianInstallationId,
      platform: "android",
      appBuild: "seed-build",
      environment: "dev",
      status: "active",
      lastSeenAt: 1,
      createdAt: 1,
      updatedAt: 1,
    });
  });
}

async function seedChildProfile(
  t: ReturnType<typeof testBackend>,
  householdId: Id<"households">,
  status: "active" | "deleting",
) {
  await t.run(async (ctx) => {
    await ctx.db.insert("childProfiles", {
      householdId,
      displayName: "Child",
      birthMonth: 1,
      birthYear: 2015,
      status,
      createdAt: 1,
      updatedAt: 1,
    });
  });
}
