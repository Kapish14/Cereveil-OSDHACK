# Android Clerk and Convex auth wiring

Date: 2026-07-14

## Decision recovered from the repository

- Guardian Mode authenticates to Convex with Clerk and uses the Convex Android client for protected mutations and realtime subscriptions.
- Child enrollment preview and completion remain Device Identity HTTP endpoints because a prepared Child Device has no Convex identity yet (ADR-0065).
- Enrollment completion issues the first short-lived Child Device JWT. Proof-of-possession token refresh also remains HTTP (ADR-0067 and ADR-0089).
- After enrollment, that ES256 JWT authenticates Child Mode's Convex Android client through the `customJwt` provider for realtime operations. Existing non-realtime Child HTTP feature endpoints are not required to move to realtime (ADR-0089 and the Remote Audio PRD).

The temporary Guardian HTTP polling client contradicted the first point. It was a recovery workaround, not the intended transport architecture.

## Primary-source findings

- [Convex Android authentication](https://docs.convex.dev/client/android/auth) uses `ConvexClientWithAuth`; protected subscriptions should run through the authenticated client.
- [Clerk's Android Convex integration](https://clerk.com/docs/android/reference/native-mobile/integrations/convex) automatically synchronizes Clerk session changes into Convex auth.
- The [Clerk Convex provider implementation](https://github.com/clerk/clerk-convex-kotlin/blob/main/source/clerk-convex-kotlin/src/main/kotlin/com/clerk/convex/ClerkConvexAuthProvider.kt) listens to `Clerk.sessionFlow` and calls `loginFromCache()` when an active session appears.
- The official provider currently requests Clerk's default token and does not expose a JWT-template option. Cereveil's Convex provider is configured with `applicationID: "convex"`, and its verified token path uses Clerk's named `convex` template. Cereveil therefore keeps a small project-owned provider with the official session-sync behavior and an explicit template until the adapter supports that option or the Clerk integration makes the default token equivalent.
- Convex Android decodes responses with Kotlin serialization. Concrete `@Serializable` response types are required; `Map<String, Any?>` cannot be decoded because `Any` has no serializer.

## Failure and repair

The Guardian Convex client was created while Clerk was still restoring persisted state. The project-owned auth provider attempted a token request at that instant but did not observe the later active Clerk session, leaving Convex unauthenticated. HTTP calls appeared to fix the screens because each call fetched a fresh Clerk token independently, but they replaced realtime subscriptions with five-second polling.

The permanent repair:

1. observes Clerk session transitions and authenticates or clears the Convex client;
2. retains the required `convex` Clerk JWT template;
3. gates protected child-profile and enrollment subscriptions on authenticated Convex state;
4. restores native Convex mutations and subscriptions and removes HTTP polling; and
5. uses concrete serializable wire DTOs, with Android `Double` values for Convex `v.number()` fields.

An on-device regression test proves automatic restored-session authentication followed by a protected realtime child-profile query.
