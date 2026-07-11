import { v } from "convex/values";
import { throwAppError } from "../../lib/errors";

const minDisplayNameLength = 1;
const maxDisplayNameLength = 80;
const minBirthYear = 1900;

export const createChildProfileArgs = {
  displayName: v.string(),
  birthMonth: v.number(),
  birthYear: v.number(),
};

export type CreateChildProfileArgs = {
  displayName: string;
  birthMonth: number;
  birthYear: number;
};

export function normalizeAndValidateCreateChildProfileArgs(
  args: CreateChildProfileArgs,
  serverNow: number,
): CreateChildProfileArgs {
  const displayName = args.displayName.trim().replace(/\s+/g, " ");

  if (
    displayName.length < minDisplayNameLength ||
    displayName.length > maxDisplayNameLength
  ) {
    throwAppError(
      "VALIDATION_FAILED",
      `displayName must be between ${minDisplayNameLength} and ${maxDisplayNameLength} characters.`,
    );
  }

  if (!Number.isInteger(args.birthMonth) || args.birthMonth < 1 || args.birthMonth > 12) {
    throwAppError("VALIDATION_FAILED", "birthMonth must be a month from 1 to 12.");
  }

  const currentYear = new Date(serverNow).getUTCFullYear();
  if (
    !Number.isInteger(args.birthYear) ||
    args.birthYear < minBirthYear ||
    args.birthYear > currentYear
  ) {
    throwAppError("VALIDATION_FAILED", "birthYear is invalid.");
  }

  validateSupportedAge(args.birthMonth, args.birthYear, serverNow);

  return {
    displayName,
    birthMonth: args.birthMonth,
    birthYear: args.birthYear,
  };
}

function validateSupportedAge(birthMonth: number, birthYear: number, serverNow: number) {
  const nowDate = new Date(serverNow);
  const currentYear = nowDate.getUTCFullYear();
  const currentMonth = nowDate.getUTCMonth() + 1;
  let age = currentYear - birthYear;
  if (currentMonth < birthMonth) {
    age -= 1;
  }

  if (age < 8 || age > 15) {
    throwAppError("CHILD_AGE_OUT_OF_RANGE");
  }
}
