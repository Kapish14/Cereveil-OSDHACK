import { v } from "convex/values";
import { throwAppError } from "../../lib/errors";

export const bootstrapGuardianArgs = {
  guardianInstallationId: v.string(),
  deviceLabel: v.optional(v.string()),
  appBuild: v.string(),
  timezone: v.optional(v.string()),
};

export function requireBoundedString(
  value: string,
  fieldName: string,
  minLength: number,
  maxLength: number,
) {
  if (value.length < minLength || value.length > maxLength) {
    throwAppError(
      "VALIDATION_FAILED",
      `${fieldName} must be between ${minLength} and ${maxLength} characters.`,
    );
  }
}

export function validateBootstrapGuardianArgs(args: {
  guardianInstallationId: string;
  deviceLabel?: string;
  appBuild: string;
  timezone?: string;
}) {
  requireBoundedString(args.guardianInstallationId, "guardianInstallationId", 16, 128);
  requireBoundedString(args.appBuild, "appBuild", 1, 128);

  if (args.deviceLabel !== undefined) {
    requireBoundedString(args.deviceLabel, "deviceLabel", 1, 128);
  }

  if (args.timezone !== undefined) {
    requireBoundedString(args.timezone, "timezone", 1, 128);
  }
}

export function normalizeTimezone(timezone?: string): string {
  if (timezone === undefined) {
    return "Asia/Kolkata";
  }

  try {
    new Intl.DateTimeFormat("en-US", { timeZone: timezone });
    return timezone;
  } catch {
    return "Asia/Kolkata";
  }
}
