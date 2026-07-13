/* eslint-disable */
/**
 * Generated `api` utility.
 *
 * THIS CODE IS AUTOMATICALLY GENERATED.
 *
 * To regenerate, run `npx convex dev`.
 * @module
 */

import type * as crons from "../crons.js";
import type * as fcmDelivery from "../fcmDelivery.js";
import type * as featureCleanup from "../featureCleanup.js";
import type * as http from "../http.js";
import type * as lib_actors from "../lib/actors.js";
import type * as lib_auth from "../lib/auth.js";
import type * as lib_authorize from "../lib/authorize.js";
import type * as lib_childDeviceHttpAction from "../lib/childDeviceHttpAction.js";
import type * as lib_encoding from "../lib/encoding.js";
import type * as lib_errors from "../lib/errors.js";
import type * as lib_functionWrappers from "../lib/functionWrappers.js";
import type * as lib_requestLogging from "../lib/requestLogging.js";
import type * as lib_sensitive from "../lib/sensitive.js";
import type * as lib_time from "../lib/time.js";
import type * as messagingCleanup from "../messagingCleanup.js";
import type * as modules_access_guardian from "../modules/access/guardian.js";
import type * as modules_access_internal from "../modules/access/internal.js";
import type * as modules_appCatalog_guardian from "../modules/appCatalog/guardian.js";
import type * as modules_appCatalog_internal from "../modules/appCatalog/internal.js";
import type * as modules_childProfiles_data from "../modules/childProfiles/data.js";
import type * as modules_childProfiles_public from "../modules/childProfiles/public.js";
import type * as modules_childProfiles_useCases from "../modules/childProfiles/useCases.js";
import type * as modules_childProfiles_validators from "../modules/childProfiles/validators.js";
import type * as modules_commands_internal from "../modules/commands/internal.js";
import type * as modules_deviceIdentity_guardian from "../modules/deviceIdentity/guardian.js";
import type * as modules_deviceIdentity_internal from "../modules/deviceIdentity/internal.js";
import type * as modules_deviceIdentity_jwt from "../modules/deviceIdentity/jwt.js";
import type * as modules_deviceIdentity_lifecycle from "../modules/deviceIdentity/lifecycle.js";
import type * as modules_featureLifecycle_internal from "../modules/featureLifecycle/internal.js";
import type * as modules_guardianAuth_data from "../modules/guardianAuth/data.js";
import type * as modules_guardianAuth_public from "../modules/guardianAuth/public.js";
import type * as modules_guardianAuth_useCases from "../modules/guardianAuth/useCases.js";
import type * as modules_guardianAuth_validators from "../modules/guardianAuth/validators.js";
import type * as modules_location_guardian from "../modules/location/guardian.js";
import type * as modules_location_internal from "../modules/location/internal.js";
import type * as modules_notifications_internal from "../modules/notifications/internal.js";
import type * as modules_notifications_public from "../modules/notifications/public.js";
import type * as modules_policies_guardian from "../modules/policies/guardian.js";
import type * as modules_policies_internal from "../modules/policies/internal.js";
import type * as modules_screenTime_guardian from "../modules/screenTime/guardian.js";
import type * as modules_screenTime_internal from "../modules/screenTime/internal.js";

import type {
  ApiFromModules,
  FilterApi,
  FunctionReference,
} from "convex/server";

declare const fullApi: ApiFromModules<{
  crons: typeof crons;
  fcmDelivery: typeof fcmDelivery;
  featureCleanup: typeof featureCleanup;
  http: typeof http;
  "lib/actors": typeof lib_actors;
  "lib/auth": typeof lib_auth;
  "lib/authorize": typeof lib_authorize;
  "lib/childDeviceHttpAction": typeof lib_childDeviceHttpAction;
  "lib/encoding": typeof lib_encoding;
  "lib/errors": typeof lib_errors;
  "lib/functionWrappers": typeof lib_functionWrappers;
  "lib/requestLogging": typeof lib_requestLogging;
  "lib/sensitive": typeof lib_sensitive;
  "lib/time": typeof lib_time;
  messagingCleanup: typeof messagingCleanup;
  "modules/access/guardian": typeof modules_access_guardian;
  "modules/access/internal": typeof modules_access_internal;
  "modules/appCatalog/guardian": typeof modules_appCatalog_guardian;
  "modules/appCatalog/internal": typeof modules_appCatalog_internal;
  "modules/childProfiles/data": typeof modules_childProfiles_data;
  "modules/childProfiles/public": typeof modules_childProfiles_public;
  "modules/childProfiles/useCases": typeof modules_childProfiles_useCases;
  "modules/childProfiles/validators": typeof modules_childProfiles_validators;
  "modules/commands/internal": typeof modules_commands_internal;
  "modules/deviceIdentity/guardian": typeof modules_deviceIdentity_guardian;
  "modules/deviceIdentity/internal": typeof modules_deviceIdentity_internal;
  "modules/deviceIdentity/jwt": typeof modules_deviceIdentity_jwt;
  "modules/deviceIdentity/lifecycle": typeof modules_deviceIdentity_lifecycle;
  "modules/featureLifecycle/internal": typeof modules_featureLifecycle_internal;
  "modules/guardianAuth/data": typeof modules_guardianAuth_data;
  "modules/guardianAuth/public": typeof modules_guardianAuth_public;
  "modules/guardianAuth/useCases": typeof modules_guardianAuth_useCases;
  "modules/guardianAuth/validators": typeof modules_guardianAuth_validators;
  "modules/location/guardian": typeof modules_location_guardian;
  "modules/location/internal": typeof modules_location_internal;
  "modules/notifications/internal": typeof modules_notifications_internal;
  "modules/notifications/public": typeof modules_notifications_public;
  "modules/policies/guardian": typeof modules_policies_guardian;
  "modules/policies/internal": typeof modules_policies_internal;
  "modules/screenTime/guardian": typeof modules_screenTime_guardian;
  "modules/screenTime/internal": typeof modules_screenTime_internal;
}>;

/**
 * A utility for referencing Convex functions in your app's public API.
 *
 * Usage:
 * ```js
 * const myFunctionReference = api.myModule.myFunction;
 * ```
 */
export declare const api: FilterApi<
  typeof fullApi,
  FunctionReference<any, "public">
>;

/**
 * A utility for referencing Convex functions in your app's internal API.
 *
 * Usage:
 * ```js
 * const myFunctionReference = internal.myModule.myFunction;
 * ```
 */
export declare const internal: FilterApi<
  typeof fullApi,
  FunctionReference<any, "internal">
>;

export declare const components: {};
