import { ConvexError } from "convex/values";

export type AppErrorCode =
  | "UNAUTHENTICATED"
  | "VALIDATION_FAILED"
  | "CHILD_AGE_OUT_OF_RANGE"
  | "ACCOUNT_DISABLED"
  | "ACCOUNT_DELETING"
  | "HOUSEHOLD_DELETING"
  | "DEVICE_REVOKED"
  | "DEVICE_LIMIT_REACHED"
  | "CHILD_ALREADY_ENROLLED"
  | "ENROLLMENT_CODE_INVALID"
  | "ENROLLMENT_FAILED"
  | "CHILD_DEVICE_UNAUTHORIZED"
  | "POLICY_VERSION_MISMATCH"
  | "INTERNAL_ERROR";

const defaultMessages: Record<AppErrorCode, string> = {
  UNAUTHENTICATED: "Authentication is required.",
  VALIDATION_FAILED: "The request is invalid.",
  CHILD_AGE_OUT_OF_RANGE: "The Child is outside Cereveil's supported age range.",
  ACCOUNT_DISABLED: "The Guardian Account is disabled.",
  ACCOUNT_DELETING: "The Guardian Account is being deleted.",
  HOUSEHOLD_DELETING: "The Household is being deleted.",
  DEVICE_REVOKED: "This Guardian Device has been revoked.",
  DEVICE_LIMIT_REACHED: "This Guardian Account already has two active Guardian Devices.",
  CHILD_ALREADY_ENROLLED: "This Child Profile already has an active enrollment.",
  ENROLLMENT_CODE_INVALID: "The enrollment code is invalid or expired.",
  ENROLLMENT_FAILED: "Enrollment could not be completed.",
  CHILD_DEVICE_UNAUTHORIZED: "The Child Device is not authorized.",
  POLICY_VERSION_MISMATCH: "The applied policy version is not current.",
  INTERNAL_ERROR: "The request could not be completed.",
};

export function appError(code: AppErrorCode, message = defaultMessages[code]) {
  return new ConvexError({ code, message });
}

export function throwAppError(code: AppErrorCode, message?: string): never {
  throw appError(code, message);
}

export function appErrorData(error: unknown): { code: AppErrorCode; message: string } | null {
  if (!(error instanceof ConvexError) || typeof error.data !== "object" || error.data === null) {
    return null;
  }
  const data = error.data as { code?: unknown; message?: unknown };
  if (typeof data.code !== "string" || !(data.code in defaultMessages)) return null;
  return {
    code: data.code as AppErrorCode,
    message: typeof data.message === "string" ? data.message : defaultMessages[data.code as AppErrorCode],
  };
}
