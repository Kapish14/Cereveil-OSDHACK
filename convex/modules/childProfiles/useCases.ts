import { Id } from "../../_generated/dataModel";
import { MutationCtx, QueryCtx } from "../../_generated/server";
import { GuardianActor } from "../../lib/actors";
import {
  loadActiveChildProfilesForHousehold,
  loadCurrentPolicyForChildProfile,
} from "./data";
import {
  CreateChildProfileArgs,
  normalizeAndValidateCreateChildProfileArgs,
} from "./validators";

type ChildProfileSummary = {
  childProfileId: Id<"childProfiles">;
  displayName: string;
  birthMonth: number;
  birthYear: number;
  status: "active";
  enrollmentStatus: "unenrolled" | "active";
  currentPolicyVersionId: Id<"supervisionPolicies">;
  currentPolicyVersion: number;
  connectivityStatus: "not_applicable" | "pending" | "online" | "offline";
  protectionHealthStatus: "not_applicable" | "pending" | "fully_protected" | "protection_degraded";
  capabilities?: {
    accessibilityService: boolean;
    usageAccess: boolean;
    location: boolean;
    microphone: boolean;
    notificationAccess: boolean;
    batteryOptimizationExempt: boolean;
  };
  lastHeartbeatAt?: number;
  serverNow: number;
};

export async function createChildProfile(
  ctx: MutationCtx,
  actor: GuardianActor,
  args: CreateChildProfileArgs,
  serverNow: number,
): Promise<ChildProfileSummary> {
  const normalizedArgs = normalizeAndValidateCreateChildProfileArgs(args, serverNow);
  const { guardianAccountId, householdId } = actor;

  const childProfileId = await ctx.db.insert("childProfiles", {
    householdId,
    displayName: normalizedArgs.displayName,
    birthMonth: normalizedArgs.birthMonth,
    birthYear: normalizedArgs.birthYear,
    status: "active",
    createdAt: serverNow,
    updatedAt: serverNow,
  });

  const policyVersion = 1;
  const currentPolicyVersionId = await ctx.db.insert("supervisionPolicies", {
    householdId,
    childProfileId,
    version: policyVersion,
    schemaVersion: 2,
    status: "active",
    appBlocking: {
      enabled: false,
      rules: [],
    },
    safeBrowsing: {
      enabled: false,
      safeSearchEnabled: false,
    },
    activeScreenSafety: {
      enabled: false,
    },
    locationSharing: { enabled: false },
    screenTime: { enabled: false },
    createdByGuardianAccountId: guardianAccountId,
    createdAt: serverNow,
  });

  return {
    childProfileId,
    displayName: normalizedArgs.displayName,
    birthMonth: normalizedArgs.birthMonth,
    birthYear: normalizedArgs.birthYear,
    status: "active",
    enrollmentStatus: "unenrolled",
    currentPolicyVersionId,
    currentPolicyVersion: policyVersion,
    connectivityStatus: "not_applicable",
    protectionHealthStatus: "not_applicable",
    serverNow,
  };
}

export async function listChildProfiles(
  ctx: QueryCtx,
  actor: GuardianActor,
): Promise<Omit<ChildProfileSummary, "serverNow">[]> {
  const { householdId } = actor;
  const childProfiles = await loadActiveChildProfilesForHousehold(ctx, householdId);

  return await Promise.all(
    childProfiles.map(async (profile) => {
      const currentPolicy = await loadCurrentPolicyForChildProfile(ctx, profile._id);
      if (currentPolicy === null) {
        throw new Error("Active Child Profile is missing a Supervision Policy.");
      }

      const activeEnrollment = await ctx.db
        .query("activeEnrollments")
        .withIndex("by_child_profile_id_and_status", (q) =>
          q.eq("childProfileId", profile._id).eq("status", "active"),
        )
        .unique();
      const health = activeEnrollment === null ? null : await ctx.db
        .query("supervisionHealth")
        .withIndex("by_active_enrollment_id", (q) => q.eq("activeEnrollmentId", activeEnrollment._id))
        .unique();

      return {
        childProfileId: profile._id,
        displayName: profile.displayName,
        birthMonth: profile.birthMonth,
        birthYear: profile.birthYear,
        status: "active" as const,
        enrollmentStatus: activeEnrollment === null ? ("unenrolled" as const) : ("active" as const),
        connectivityStatus: health?.connectivityStatus ?? (activeEnrollment === null ? "not_applicable" as const : "pending" as const),
        protectionHealthStatus: health?.protectionStatus ?? (activeEnrollment === null ? "not_applicable" as const : "pending" as const),
        capabilities: health?.capabilities,
        lastHeartbeatAt: health?.lastHeartbeatAt,
        serverNow: Date.now(),
        currentPolicyVersionId: currentPolicy._id,
        currentPolicyVersion: currentPolicy.version,
      };
    }),
  );
}
