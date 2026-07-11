import { AppErrorCode } from "./errors";

export type RequestOutcomeLog = {
  requestId: string;
  operation: string;
  actorKind: "guardian" | "child_device";
  outcome: "success" | "failure";
  durationMs: number;
  errorCode?: AppErrorCode;
};

export function createRequestMetadata(operation: string) {
  return { requestId: crypto.randomUUID(), operation, startedAt: Date.now() };
}

export function logRequestOutcome(entry: RequestOutcomeLog) {
  console.info(JSON.stringify({ event: "authenticated_request", ...entry }));
}
