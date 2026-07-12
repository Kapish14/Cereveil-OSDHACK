import { cronJobs } from "convex/server";
import { internal } from "./_generated/api";

const crons = cronJobs();

crons.interval("clean up expired messaging records", { hours: 24 }, internal.messagingCleanup.run, {
});

export default crons;
