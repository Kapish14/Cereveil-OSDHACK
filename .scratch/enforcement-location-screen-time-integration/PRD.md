Status: ready-for-agent

# Enforcement, Latest Location, and Screen Time Integration

## Problem Statement

Cereveil now has Guardian authentication, Child Device enrollment and identity, Supervision Heartbeats and health notices, encrypted owner-bound FCM delivery, typed schema-v1 Supervision Policy lifecycle, desired-versus-applied Policy Application State, and an end-to-end `apply_policy_version` Child Device Command. Those foundations do not yet deliver the product behaviors a Guardian and Child expect from App Blocking, location, or Screen Time.

App Blocking is only a boolean policy section. Guardian Mode cannot obtain a privacy-bounded list of installed apps, configure Manual Blocks or Scheduled Blocks, or see rule changes move through the existing pending/applied lifecycle. Child Mode does not detect visible blocked apps, render a Block Screen, create Access Requests, or persist authoritative Access Grants for offline enforcement.

Location is currently only a Protection Setup capability. Child Mode does not send separate Location Heartbeats, Convex does not retain latest-only location state, Guardian Mode cannot request one fresh measurement, and there is no map UI. Earlier Live Location Session decisions are now superseded: the product must not continuously track movement or retain location points.

The existing policy names and architecture still describe daily Screen Time Summaries with session counts and 30-day history. The desired product is instead an on-demand, latest-only Screen Time Snapshot containing Android's today-so-far per-app foreground totals. Guardian Mode should refresh it automatically when the selected Child's Screen Time surface becomes visible, without building daily or weekly history.

Without this integration, the existing policy, command, FCM, authorization, and health architecture remains scaffolding. Guardian actions cannot be shown truthfully as applied, Child Mode cannot enforce the last accepted policy offline, sensitive location and usage state lack explicit privacy lifecycles, and missed or duplicated FCM messages could tempt feature implementations to treat push transport as authority.

## Solution

Introduce Supervision Policy schema v2 with bounded App Blocking rules, policy-controlled Location Sharing, and policy-controlled Screen Time. Preserve the existing immutable complete-snapshot workflow: Guardian changes create desired policy, one replaceable `apply_policy_version` command wakes Child Mode, Child Mode validates and activates the entire snapshot atomically, and Guardian Mode derives final UI state only from acknowledgement.

Build a latest-only App Catalog from user-launchable apps on the Child Device. Guardian Mode uses the catalog to select packages for Manual and Scheduled Blocks, while package-based rules survive temporary uninstall/reinstall. Child Mode uses Accessibility as the real-time enforcement source, renders an app-owned full-screen accessibility Block Screen over any visible interactive blocked window, and uses Usage Access for Android usage statistics rather than as a second enforcement engine.

Process temporary access through authoritative Convex Access Requests and Access Grants. Guardian Devices receive and resolve one deduplicated pending request; approval creates a bounded absolute-time grant, denial creates no grant, and FCM remains a generic wake-up. Child Mode persists active grants in operational local storage so process death, restart, and offline operation neither cancel nor extend them.

When Location Sharing is enabled, Child Mode submits low-power latest-only Location Heartbeats and never queues missed points while offline. Guardian Mode may create a one-time Location Refresh Request, which uses a typed command and high-priority FCM with an immediate Child-visible notification to obtain one fresh measurement. Convex stores one latest location only. Guardian Mode renders it with the standard Google Maps SDK, while map rendering remains presentation rather than authority.

Replace Screen Time Summaries with latest-only Screen Time Snapshots. When the selected Child Profile's Screen Time surface becomes visible and its snapshot is absent, expired, or at least two minutes old, Convex creates one deduplicated two-minute Screen Time Refresh Request and sends a normal-priority FCM wake-up. Child Mode queries Android's current local-day per-package `totalTimeInForeground`, filters to positive-duration user-launchable non-exempt apps, uploads bounded staging batches, and atomically publishes the replacement snapshot. It uploads no raw usage events, session counts, open/close timestamps, daily summaries, weekly summaries, or history.

Extend the existing Child Device Command lifecycle with typed `refresh_location`, `refresh_screen_time`, and `reconcile_access_grants` variants. Feature modules own their authoritative records and commands only reference them. All new Guardian and Child operations use the established authenticated actor wrappers, lifecycle validation, privacy-safe logging, explicit expiries, indexed bounded cleanup, and End Supervision deletion behavior.

## User Stories

1. As a Guardian, I want to see the latest user-launchable apps installed on a Child Device, so that I can configure app-specific supervision without guessing package names.
2. As a Guardian, I want app names resolved from the Child Device, so that the selection UI uses labels the Child recognizes.
3. As a Guardian, I want App Catalog freshness displayed, so that I know when the installed-app view was last synchronized.
4. As a Guardian, I do not want system components mixed into app selection, so that I cannot create unsafe or meaningless rules.
5. As a Guardian, I do not want Cereveil, emergency surfaces, the launcher, System UI, dialer, or required repair surfaces to be blockable, so that the Child Device remains safe and recoverable.
6. As a Guardian, I want a newly enrolled Child Device to upload its App Catalog promptly, so that app configuration is available without waiting for a periodic cycle.
7. As a Guardian, I want installed and removed apps reconciled later, so that the App Catalog stays current.
8. As a Guardian, I want an app that is temporarily uninstalled to remain represented by its existing App Block rules, so that reinstalling does not bypass supervision.
9. As a Guardian, I want a reinstalled package to resume its prior rules automatically, so that policy intent survives app lifecycle changes.
10. As a Child, I want Protection Setup to disclose that latest installed app names and package identities are shared, so that App Catalog behavior is transparent.
11. As a Child, I do not want icons, permissions, APK metadata, install timestamps, or uninstall history uploaded, so that app selection does not become installation profiling.
12. As a Guardian, I want to create a Manual Block with no schedule, so that I can block an app immediately until I remove that rule.
13. As a Guardian, I want Manual Blocks modeled independently from schedules, so that an immediate block is not represented as a fake infinite schedule.
14. As a Guardian, I want to configure recurring Scheduled Blocks by weekday and local clock time, so that app availability follows routines.
15. As a Guardian, I want a Scheduled Block to cross midnight, so that windows such as 9 PM to 7 AM are expressible.
16. As a Guardian, I want multiple schedules for the same app, so that different routines can coexist.
17. As a Guardian, I want Manual and Scheduled Blocks to coexist for one app, so that removing one kind does not silently remove the other.
18. As a Guardian, I want overlapping schedules to compose into continuous coverage, so that rule boundaries do not create accidental gaps.
19. As a Guardian, I want schedules evaluated in the Child Device's current local time zone, so that supervision follows the Child while travelling.
20. As a Guardian, I want daylight-saving changes handled as local civil time, so that recurring schedules remain understandable.
21. As a Guardian, I want at most 100 configured apps and eight schedules per app, so that policy snapshots remain bounded and predictable.
22. As a Guardian, I want invalid days, times, duplicate packages, duplicate schedule identities, and Exempt Apps rejected, so that malformed rules never reach Child Mode.
23. As a Guardian, I want creating the first block rule to enable App Blocking in the same policy change, so that one action expresses one clear intent.
24. As a Guardian, I want the App Blocking master switch to suspend rules without deleting them, so that I can temporarily disable enforcement.
25. As a Guardian, I want removing the last rule not to toggle the master switch implicitly, so that unrelated state does not change behind my back.
26. As a Guardian, I want an App Block save to show the existing inline acknowledgement spinner, so that I know the Child Device has not applied it yet.
27. As a Guardian, I want a long App Block wait to say “Waiting for Child Device,” so that an Offline device is not presented as frozen.
28. As a Guardian, I want pending state attached to the affected app rule, so that adding a rule while the master boolean is already enabled still appears pending.
29. As a Guardian, I want the final rule state rendered from the applied policy, so that a backend save is not mistaken for enforcement.
30. As a Guardian, I want a permanent policy rejection to show “Couldn’t apply,” so that failure is not left as indefinite progress.
31. As a Child, I want Child Mode to keep enforcing the last accepted complete policy while offline, so that a pending policy cannot weaken known protection.
32. As a Child, I want policy schema v2 activated atomically, so that App Blocking, Location Sharing, and Screen Time never become partially applied.
33. As a Child, I want Accessibility events to drive real-time App Block enforcement, so that the Block Screen appears promptly.
34. As a Child, I want Usage Access used for usage statistics and reconciliation rather than as a second enforcement engine, so that degraded enforcement is reported honestly.
35. As a Child, I want losing Accessibility to produce Protection Degraded and a deduplicated Tamper Alert, so that the Guardian is not told blocking remains reliable.
36. As a Child, I want the Block Screen rendered as an accessibility overlay, so that a blocked app cannot be operated underneath it.
37. As a Child, I want the Block Screen to explain why the app is unavailable, so that enforcement is understandable rather than silent.
38. As a Child, I want the Block Screen to offer “Ask Guardian,” so that temporary access has an explicit route.
39. As a Child, I want the Block Screen to offer a Home action, so that I can leave the blocked app safely.
40. As a Child, I want a valid Access Grant to remove the Block Screen for the permitted interval, so that approved access works immediately after reconciliation.
41. As a Child, I want the Block Screen to cover split-screen and picture-in-picture when a blocked app owns any visible interactive window, so that alternate window modes cannot bypass enforcement.
42. As a Child, I want the overlay removed for Home, Cereveil, Exempt Apps, emergency/system surfaces, inactive blocks, and valid grants, so that enforcement stays narrowly scoped.
43. As a Guardian, I want Android Settings and required repair surfaces reachable, so that Protection Degraded can be understood and repaired.
44. As a Guardian, I want ordinary capability-revocation flows to retain the existing disclosed protection behavior, so that Settings availability does not silently abandon tamper resistance.
45. As a Child, I want an Access Request created only when the visible app is actually blocked under my applied policy, so that requests correspond to real enforcement.
46. As a Child, I want an offline Block Screen to say the Guardian is unreachable rather than queueing a request, so that stale requests do not appear later.
47. As a Child, I want repeated “Ask Guardian” taps to return the same pending request, so that I cannot accidentally spam Guardian Devices.
48. As a Guardian, I want one Access Request notice on every active Guardian Device, so that either authorized phone can respond.
49. As a Guardian, I want the first valid approve or deny response to resolve the request, so that two Guardian Devices cannot produce contradictory outcomes.
50. As a Guardian, I want a pending Access Request to expire after at most 15 minutes, so that old requests cannot be approved unexpectedly.
51. As a Guardian, I want a request to expire earlier when its block, policy eligibility, or Active Enrollment ends, so that authority never outlives context.
52. As a Child, I want a five-minute cooldown after denial, so that I can retry later without generating immediate notification spam.
53. As a Guardian, I want unrelated policy changes not to invalidate a request when the same block remains, so that normal settings edits do not break access decisions.
54. As a Guardian, I want a materially changed or removed block to invalidate its request, so that approval is revalidated against current intent.
55. As a Guardian, I want available Access Grant durations of 15, 30, 45, and 60 minutes, so that temporary access remains bounded.
56. As a Guardian, I want schedule-only duration choices capped by the remaining continuous scheduled coverage, so that a grant cannot outlive the block that produced it.
57. As a Guardian, I want an “until this block ends” option for a Scheduled Block, so that I can align access with its natural boundary.
58. As a Guardian, I want a Manual Block grant to use only its selected duration, so that an overlapping schedule does not shorten manual access unexpectedly.
59. As a Guardian, I want approval time to start the Access Grant, so that FCM delivery cannot extend authority.
60. As a Child, I want a delayed grant fetch to provide only the remaining time, so that delivery delay never restarts the duration.
61. As a Child, I want an expired grant ignored even if fetched late, so that stale commands cannot unlock an app.
62. As a Child, I want an active grant persisted across process death and reboot, so that restart neither cancels nor renews approved access.
63. As a Child, I want a locally expired grant to re-evaluate the visible app immediately without network access, so that the Block Screen returns on time.
64. As a Guardian, I accept that active Access Grants are non-revocable in v1, so that the UI does not promise an immediate cancellation that an Offline Child Device cannot receive.
65. As a Guardian, I want Access Request and approval wake-ups to be prompt and user-visible, so that temporary access is useful while its timer runs.
66. As a Guardian, I want denial delivered at normal priority, so that unchanged enforcement does not misuse urgent push delivery.
67. As a Guardian, I want Location Sharing off in the Initial Supervision Policy, so that exact-location collection begins only after an explicit policy choice.
68. As a Guardian, I want enabling Location Sharing to use the desired/applied policy lifecycle, so that collection is not claimed before Child acknowledgement.
69. As a Guardian, I want disabling Location Sharing to immediately delete backend location and pending refresh state, so that privacy intent takes effect server-side without waiting.
70. As a Child, I want Location Sharing disablement to stop local location work and clear unsent work, so that disabled collection does not continue.
71. As a Guardian, I want low-power Location Heartbeats approximately every 15 minutes while Location Sharing is enabled, so that the latest known location normally remains useful.
72. As a Guardian, I want significant movement of roughly 250 metres to trigger an additional best-effort update, so that meaningful movement can refresh sooner.
73. As a Child, I want movement-triggered uploads limited to at most one every five minutes, so that location freshness does not become continuous GPS use.
74. As a Child, I do not want Location Heartbeats queued while offline, so that reconnecting cannot upload a hidden route history.
75. As a Guardian, I want reconnecting Child Mode to measure again rather than upload missed points, so that the map receives current state.
76. As a Guardian, I want Convex to accept only a location measurement newer than its stored latest state, so that delayed messages cannot move the map backward in time.
77. As a Guardian, I want measurement time distinct from upload time, so that stale location is never presented as current.
78. As a Guardian, I want only one latest location row, so that Cereveil does not retain movement history.
79. As a Guardian, I want to tap “Refresh location,” so that I can ask for one fresh high-accuracy measurement when the latest point is old.
80. As a Guardian, I do not want a Live Location Session or moving route, so that the feature remains one-time and privacy bounded.
81. As a Guardian, I want a Location Refresh Request to expire after 60 seconds, so that high-accuracy work cannot run indefinitely.
82. As a Guardian, I want at most one Location Refresh Request every two minutes per Child Profile, so that repeated taps cannot recreate continuous tracking.
83. As a Guardian, I want duplicate requests from the second Guardian Device to converge on the same pending work, so that one Household cannot create duplicate location jobs.
84. As a Guardian, I want refresh rejected when the Child Device is Offline, so that the UI does not pretend an impossible request is pending.
85. As a Guardian, I want refresh rejected when location capability is unavailable, so that known Protection Degraded state is explained immediately.
86. As a Guardian, I want refresh rejected when notification capability is unavailable, so that high-priority location access never operates invisibly.
87. As a Child, I want every Location Refresh Request accompanied by an immediate visible notification, so that one-time high-accuracy access is transparent.
88. As a Child, I want a refresh completed only by a measurement captured at or after its backend request time, so that a cached old point cannot masquerade as fresh.
89. As a Guardian, I want a fresh but low-accuracy result displayed with its real accuracy radius, so that Cereveil does not claim exactness it does not have.
90. As a Guardian, I want a failed refresh to preserve the prior marker and its true age, so that failure does not erase the latest known safety information.
91. As a Guardian, I want location older than 30 minutes labeled “Location may be outdated,” so that freshness is obvious in plain language.
92. As a Guardian, I want location age, accuracy, connectivity, and capability state shown independently, so that one status does not imply another.
93. As a Guardian, I want the latest point shown on a Google Map with one marker and accuracy circle, so that the state is spatially understandable.
94. As a Guardian, I want location details still available if Google Maps cannot load, so that presentation failure cannot hide authoritative state.
95. As a Guardian, I want an Android `geo:` fallback action, so that I can open the point in any compatible installed map app.
96. As a developer, I want only the standard Google Maps SDK enabled, so that Places, Routes, Street View, reverse geocoding, and separately billable map features remain excluded.
97. As a developer, I want development and production map keys restricted by package and signing-certificate fingerprint, so that an extracted key cannot be reused by another app.
98. As a Guardian, I want automatic date/time and automatic time zone required during Protection Setup, so that time-based supervision does not silently trust a manually manipulated clock.
99. As a Guardian, I want disabling either automatic time setting to produce Protection Degraded and a deduplicated Tamper Alert, so that schedule and current-day boundaries remain trustworthy.
100. As a Guardian, I want system location service disablement treated as unavailable location capability, so that permissions alone do not imply working location.
101. As a Child, I do not want poor satellite or network conditions called tampering, so that inability to acquire a fix remains distinct from disabling a capability.
102. As a Guardian, I want Screen Time off in the Initial Supervision Policy, so that app-usage disclosure is explicitly enabled.
103. As a Guardian, I want the Screen Time surface to request an update automatically when opened, so that I do not need to press Refresh for ordinary use.
104. As a Guardian, I want automatic refresh only for the selected Child Profile, so that opening Guardian Mode does not wake every Child Device in the Household.
105. As a Guardian, I want a snapshot refreshed when absent, expired, or at least two minutes old, so that today-so-far usage is reasonably current without continuous streaming.
106. As a Guardian, I want one pending Screen Time Refresh Request per Child Profile, so that navigation, recomposition, retries, and a second Guardian Device do not create a push storm.
107. As a Guardian, I want a Screen Time Refresh Request to expire after two minutes, so that abandoned work does not remain pending.
108. As a Guardian, I want normal-priority FCM used for Screen Time refresh, so that silent background synchronization follows Firebase priority guidance.
109. As a Guardian, I want the previous valid snapshot shown with “Updating…” while replacement is pending, so that refresh does not cause an empty flicker.
110. As a Guardian, I want honest stale, Offline, Usage Access unavailable, failure, and timeout states, so that “live” does not imply a delivery guarantee.
111. As a Child, I want Child Mode to query Android's existing usage statistics only in response to authoritative refresh work, so that Cereveil does not build a parallel continuous usage tracker.
112. As a Guardian, I want Android-reported today-so-far `totalTimeInForeground` per app, so that the snapshot reflects the platform's existing measurement.
113. As a Guardian, I do not want Cereveil-derived session counts, so that the app does not reconstruct an unnecessary activity model.
114. As a Child, I do not want raw usage events or exact open/close timestamps uploaded, so that Screen Time does not become a usage timeline.
115. As a Guardian, I want only positive-duration user-launchable non-exempt apps in the snapshot, so that system noise and unused apps do not clutter the view.
116. As a Guardian, I want an empty but successful snapshot when Android reports no positive usage, so that zero usage is not mistaken for failure.
117. As a Guardian, I want labels resolved from the latest App Catalog, so that Screen Time rows do not duplicate mutable app metadata.
118. As a Guardian, I want usage from local midnight through the measurement time, so that “today so far” has one clear meaning.
119. As a Guardian, I want the first enabled snapshot to include usage earlier that local day, including before policy application or enrollment, so that the result reflects Android's whole current-day total.
120. As a Guardian, I want re-enabling Screen Time on the same day to include the disabled interval, so that a current-day snapshot is not split into hidden segments.
121. As a Guardian, I want the enable/re-enable UI to disclose the full-current-day behavior, so that retroactive same-day retrieval is not surprising.
122. As a Child, I want Screen Time transparency provided through Protection Setup and the persistent Child Mode status surface, so that no notification is needed for every enablement or refresh.
123. As a Guardian, I want bounded upload batches but only one atomically published snapshot, so that a large app set cannot expose partial results.
124. As a Guardian, I want staging rows invisible until the expected batch set completes, so that the UI never treats incomplete upload as current truth.
125. As a Child, I do not want a failed Screen Time response queued for later, so that stale on-demand results cannot appear after timeout or midnight.
126. As a Guardian, I want a later retry to query Android again, so that recovery produces a fresher snapshot.
127. As a Guardian, I want a Screen Time Snapshot to expire at the Child Device's next local midnight, so that yesterday's state does not become history.
128. As a Guardian, I want Screen Time disablement to immediately delete snapshot, staging, and request state, so that disclosure stops server-side immediately.
129. As a Child, I want Screen Time refresh accepted only when desired and applied policy both enable the feature, so that pending policy never authorizes collection early.
130. As a developer, I want policy schema v2 to replace `screenTimeSummariesEnabled` with a truthful Screen Time section, so that obsolete domain language does not survive in new contracts.
131. As a developer, I want schema-v2 support reported before Guardian Mode saves new settings, so that older Child releases cannot receive an unsupported complete policy.
132. As a developer, I want current development policies and fixtures reset or migrated without a production backfill, so that development can move cleanly to the confirmed schema.
133. As a developer, I want `apply_policy_version`, `refresh_location`, `refresh_screen_time`, and `reconcile_access_grants` represented as strongly typed Child Device Commands, so that unrelated work remains explicit.
134. As a developer, I want feature state stored in its owning domain module rather than embedded in commands, so that command delivery cannot become authority.
135. As a developer, I want every FCM Child payload to remain generic `child_command` wake-up metadata, so that transport does not expose policy, location, usage, or grant details.
136. As a developer, I want duplicate or missed FCM delivery reconciled through the existing authenticated command endpoint, so that correctness does not depend on push guarantees.
137. As a developer, I want commands acknowledged only at their type-specific success points, so that fetch or FCM acceptance is never mistaken for completion.
138. As a developer, I want command rejection reasons bounded and privacy safe, so that operational failure is diagnosable without raw device data.
139. As a developer, I want Guardian feature operations authorized through the established Guardian actor wrapper, so that a Guardian Device cannot address another Household.
140. As a developer, I want Child uploads and request processing authorized through the full ChildDeviceActor chain, so that revoked credentials, devices, enrollments, profiles, or Households cannot act.
141. As a developer, I want backend identity derived from authenticated actors rather than client-supplied ownership IDs, so that payloads cannot redirect state.
142. As a developer, I want package labels derived from authorized App Catalog state where practical, so that Child inputs cannot rewrite unrelated presentation metadata.
143. As a developer, I want location and usage timestamps validated against backend-owned request windows and monotonic state, so that stale or future data cannot replace current state.
144. As a developer, I want all expiring rows indexed by lifecycle fields, so that cleanup never scans an unbounded table.
145. As a developer, I want request expiry scheduled at creation and backed by bounded cleanup, so that correctness does not wait for a daily cron.
146. As a developer, I want Access Requests and Grants deleted 24 hours after terminal state, so that temporary access does not become long-term history.
147. As a developer, I want terminal Location and Screen Time refresh records and abandoned staging rows deleted after 15 minutes, so that on-demand operations remain transient.
148. As a developer, I want latest App Catalog, location, and Screen Time state deleted during End Supervision, so that Child Profile deletion remains complete.
149. As a developer, I want existing seven-day command and FCM delivery retention preserved, so that operational reconciliation is not conflated with feature history.
150. As a Cereveil operator, I want logs to exclude exact coordinates, per-app usage totals, installed-app names, policy contents, Access Grant details, tokens, and request bodies, so that observability does not become a sensitive datastore.
151. As a Cereveil operator, I want safe outcome metrics for request creation, delivery, completion, failure, expiry, and cleanup, so that feature reliability can be monitored without content.
152. As a tester, I want real-device smoke coverage for Accessibility overlays, Usage Access, Location Refresh, Google Maps, and FCM wake-ups, so that Android platform integration is verified beyond unit tests.
153. As a tester, I want missed, duplicated, delayed, stale, concurrent, offline, revoked, and disabled-policy scenarios covered, so that authoritative state converges under realistic failure.

## Implementation Decisions

- Introduce Supervision Policy schema v2. Preserve immutable complete versions, feature-specific Guardian mutations, optimistic concurrency, retry identity, desired/applied Policy Application State, typed schema support, atomic Child activation, and acknowledgement semantics.
- Schema v2 contains bounded `appBlocking`, `locationSharing`, and `screenTime` sections. Replace the obsolete `screenTimeSummariesEnabled` concept rather than retaining misleading compatibility terminology in the new schema.
- App Blocking contains a master enabled flag and at most 100 package rules. Each package rule contains an independent Manual Block flag and at most eight recurring Scheduled Blocks.
- Scheduled Blocks store stable schedule identity, non-empty weekdays, and local start/end minute values. Equal start/end is invalid. An end earlier than start represents a window crossing midnight.
- Scheduled Blocks follow the Child Device's current local time zone and daylight-saving behavior. Child Mode is the single evaluator of current effective blocks; Convex does not duplicate the Android schedule evaluator.
- Add Trusted Device Time to Protection Setup and Supervision Health. Automatic date/time and automatic time zone are both required; disabling either produces Protection Degraded and one deduplicated Tamper Alert.
- Extend heartbeat capability contracts, stored capability snapshots, Guardian capability presentation, setup readiness, tests, and tamper deduplication for Trusted Device Time. Location capability means fine/background permission plus system location service availability. Poor measurement conditions are not capability tampering.
- Build an App Catalog feature module with latest-only generation state and one row per user-launchable package. Use bounded generation uploads, atomic publication of a completed generation, and bounded deletion of absent entries.
- Upload App Catalog after enrollment and reconcile on package add/remove, restart, and a safe fallback cycle. Never require `QUERY_ALL_PACKAGES`; discover only user-launchable packages through Android package visibility mechanisms.
- App Catalog payload contains package name and device-resolved display label only. Do not upload icons, permissions, APK metadata, installers, install timestamps, signing details, or history.
- App Catalog removal and policy rules have independent lifecycles. A missing package remains visible as not currently installed when referenced by policy, and the rule resumes on reinstall.
- Maintain a non-overridable Child-side Exempt App classifier. Guardian UI omits Exempt Apps, Convex rejects known exemptions, and Child Mode remains the final authority for OEM/default-handler-specific exemptions.
- Preserve access to Cereveil, active launcher/Home, System UI, current dialer and emergency surfaces, and settings/package-management surfaces necessary for honest protection and repair. Continue the existing disclosed ordinary revocation-flow protection rather than blocking Settings wholesale.
- Use Accessibility window events and visible-window inspection as the primary App Block enforcement source. Usage Access may support state reconciliation but must not claim reliable blocking when Accessibility is unavailable.
- Render one app-owned full-screen `TYPE_ACCESSIBILITY_OVERLAY`. Keep the safety-critical Block Screen simple and owned by Cereveil. It intercepts underlying touch, explains the block, offers Access Request and Home actions, and handles configuration/process lifecycle safely.
- Enforce when any blocked package owns a visible interactive window, including focused, split-screen, and picture-in-picture windows. Exempt or system surfaces and valid grants remove the overlay.
- Persist the last accepted schema-v2 policy, evaluated rule state needed for restart, and active Access Grants in the Child operational Room boundary. Guardian Room state remains cache only.
- Model one pending Access Request per active enrollment, package, and effective block. Store applied policy version, block kind, and scheduled continuous-coverage end when applicable. Do not trust client ownership fields.
- Child Mode submits its effective-block evaluation. Convex validates the authenticated actor and that the referenced/current policy still contains the applicable package rule. A false request cannot create access without Guardian approval.
- Access Requests remain actionable for at most 15 minutes and no later than their effective block or Active Enrollment. Repeated taps return the existing pending request. Explicit denial starts a five-minute backend-owned cooldown.
- Create one Guardian Notice for each Access Request with independent receipts for active Guardian Devices. First valid resolution wins transactionally. An approved request creates one grant; denial creates none.
- Access Grants start at backend approval and use absolute server-owned expiry. Manual Block grants use 15, 30, 45, or 60 minutes. Schedule-only grants are capped by the remaining continuous scheduled coverage and may offer “until block ends.”
- Active Access Grants are non-revocable in v1. Child Mode persists and evaluates them across process death, reboot, and offline state; delayed fetch never restarts time. Local expiry re-evaluates the visible app immediately.
- Revalidate approval against latest desired policy. Preserve a request across unrelated policy versions when the same effective block remains; expire it if the producing block materially changes or disappears. If desired policy removes the block but is pending application, create no grant and present the pending policy state.
- Add a Location Sharing schema-v2 policy section, default off. Require both desired and applied enablement for refresh creation. Disablement immediately deletes backend latest/request state and instructs Child Mode to stop/clear local work.
- Keep Supervision Heartbeat and Location Heartbeat distinct. Location failure never changes device liveness. Attempt low-power Location Heartbeats approximately every 15 minutes and after roughly 250 metres of significant movement, rate-limited to one movement upload per five minutes.
- Never queue Location Heartbeats offline. On reconnection, obtain a current measurement. Every accepted update includes latitude, longitude, accuracy, and measurement time; backend accepts only a newer measurement and never substitutes receipt time.
- Store one mutable latest Location State per active enrollment. Do not store points, routes, sessions, or history. Mark measurements older than 30 minutes stale in presentation while keeping actual age and accuracy visible.
- Replace all Live Location Session concepts with a one-time Location Refresh Request. Request creation requires desired/applied Location Sharing, Online connectivity, last-reported location capability, and last-reported notification capability.
- Permit one Location Refresh Request per Child Profile every two minutes. A request expires in 60 seconds and is completed only by a measurement captured at or after backend `requestedAt`. Cached older points may remain visible but cannot complete it.
- Route Location Refresh through `refresh_location` and high-priority FCM. Immediately display a Child-visible notification, run one bounded high-accuracy measurement, upload the result or safe failure, and return to low-power behavior.
- Do not reject a fresh result solely for poor accuracy. Store and display actual accuracy. Failed/expired refresh retains the previous latest marker and its true age.
- Use the standard Google Maps SDK for Android for one marker and accuracy circle. Exclude Places, Routes, Street View, reverse geocoding, advanced map IDs, and other separate services. Restrict separate development and production keys by package and signing fingerprints and keep raw values outside Git.
- Keep the location status card authoritative independently of map rendering. Include coordinates, accuracy, measurement age, connectivity, capability state, and refresh status. Provide a provider-neutral Android `geo:` fallback.
- Replace Screen Time Summaries with policy-controlled Screen Time Snapshots. Default Screen Time off and delete latest, pending, and staging state immediately when desired policy disables it.
- A refresh may be created only when desired and applied schema-v2 policies enable Screen Time, the Child Device is Online, and Usage Access was last reported available.
- Trigger automatic refresh only when the selected Child Profile's Screen Time surface becomes visible and the current snapshot is missing, expired, or at least two minutes old. Do not refresh all Children on app startup.
- Permit one pending Screen Time Refresh Request per Child Profile. It expires after two minutes and deduplicates repeated UI triggers, manual retry, recomposition, navigation, and the second Guardian Device.
- Route Screen Time Refresh through `refresh_screen_time` and normal-priority FCM. Do not display a Child notification for each enablement or refresh. Protection Setup and persistent Child Mode status provide transparency.
- Query Android UsageStatsManager on demand. Use Android's per-package current-local-day foreground total without deriving session counts. Do not continuously aggregate Cereveil events or access Digital Wellbeing/OEM private stores.
- The query interval is local midnight through measurement time even when Screen Time was enabled, re-enabled, or the Child Device was enrolled later that same day. Guardian enable/re-enable UI must explicitly disclose that whole-current-day retrieval includes earlier and disabled intervals.
- Filter to positive-duration packages in the current user-launchable App Catalog and exclude Exempt Apps, Cereveil, launcher, System UI, Settings/repair surfaces, and Block Screen presentation.
- Store Screen Time Snapshot headers separately from per-app usage rows. Upload bounded staging batches, verify expected distinct rows, and atomically switch the visible current header only when complete. Queries never expose staging or superseded rows.
- Per-app snapshot rows contain package identity and Android-reported foreground duration only. Resolve labels from App Catalog. Do not upload session count, raw events, class names, open/close timestamps, or minute-by-minute data.
- Publish a valid empty snapshot when no app has positive duration. Do not treat an empty result as transport failure.
- Do not queue a failed snapshot upload. Expire partial staging and let the next refresh query Android again. Keep the previous valid same-day snapshot visible with “Updating…” until replacement; show explicit stale/failure state on terminal failure.
- Snapshot validity ends at the Child Device's next local midnight. Guardian queries hide invalid rows immediately; cleanup physically deletes them. There is no daily, weekly, monthly, or rolling history.
- Extend Child Device Command schema as a validated discriminated union for `apply_policy_version`, `refresh_location`, `refresh_screen_time`, and `reconcile_access_grants`. Each command references feature-owned authoritative state and never embeds sensitive feature payloads.
- Keep one generic `child_command` FCM category. Policy and Screen Time commands use normal priority. Location Refresh uses high priority plus immediate Child-visible notification. Access Request Guardian Notices and approval that updates a visible Block Screen use high priority; denial uses normal priority.
- Use the existing authenticated Child command reconciliation endpoint with bounded pagination and independent processing. Extend acknowledgement/rejection to type-specific outcomes while keeping safe stable error reasons and idempotency.
- Add domain-oriented Convex modules for App Catalog, Access Requests/Grants, Location, and Screen Time. Preserve notification delivery ownership in the notifications module and policy construction/application ownership in policies.
- All Guardian public operations use Guardian wrappers and active Guardian Device authorization. All Child feature routes use childDeviceHttpAction, full ChildDeviceActor resolution, transactional actor-chain revalidation, server request IDs, safe errors, and typed allowlisted logging.
- Add focused authenticated Child HTTP routes for App Catalog generations, Access Request creation/state and grant reconciliation, Location Heartbeat/result, and bounded Screen Time staging/completion. Keep parsing and response envelopes consistent with existing Child routes.
- Use explicit lifecycle fields and indexed bounded queries. Schedule validity transitions at creation and retain cleanup as a backstop. Never use unbounded arrays, filters, full collects, or large single-transaction deletes.
- Delete terminal Access Request and expired Access Grant rows after 24 hours. Delete terminal Location/Screen Time refresh and abandoned staging rows after 15 minutes. Preserve existing seven-day command/FCM operational retention.
- End Supervision and Replace Child Device lifecycle paths cancel or delete feature work, latest state, catalog state, and local operational state consistently with existing ownership decisions. Package policy belongs to the retained Child Profile during Replace Child Device; device-produced latest state does not silently transfer as current for a new enrollment.
- Update Protection Setup copy for App Catalog sharing and Trusted Device Time. Keep capability changes reported on the existing Supervision Heartbeat cadence rather than adding immediate capability event transport.
- Logs, metrics, crash data, FCM payloads, and request logs must exclude exact coordinates, map viewport, installed-app labels, package lists, per-app durations, policy bodies, grant details, Child identity, tokens, and raw exceptions. Record only allowlisted operation/outcome metadata.

## Testing Decisions

- Test externally observable authorization, state transitions, idempotency, expiry, privacy, reconciliation, and UI truthfulness. Do not assert private helper calls, internal coroutine structure, exact Compose implementation, SDK internals, or incidental row ordering beyond public contracts.
- Use authenticated Guardian Convex queries/mutations and authenticated Child HTTP routes as the principal backend integration seams. Exercise real validators, actor resolution, authorization chains, transactions, indexes, scheduler transitions, and response envelopes.
- Extend the existing fake FCM gateway boundary rather than mocking HTTP throughout feature tests. Assert priority, generic payload category, opaque record reference, retry classification, and the absence of policy, package, location, usage, grant, or Child data.
- Extend the existing Child supervision/command coordinator seam. Use fake high-level App Catalog, policy runtime, accessibility enforcement, location measurement, UsageStats, local operational store, and authenticated client boundaries. Assert ordering and outcomes rather than Android framework call sequences.
- Extend the existing Guardian Policy ViewModel seam for schema-v2 desired/applied section comparison. Test immediate progress, three-second Waiting state, applied convergence, failure, conflict reload, recreation, two-device observation, and unrelated feature changes.
- Add Guardian feature ViewModel/repository seams for App Catalog/App Blocks, Access Requests, latest location, and Screen Time. Drive authoritative flows through fake repositories and test loading, cached/stale state, pending, completion, failure, expiry, and retry.
- Use Compose tests for user-visible text, control enablement, pending indicators, stale warnings, map fallback card, Block Screen actions, and privacy disclosure. Avoid pixel-level map SDK assertions.
- Backend policy tests cover schema-v2 validation, schema support gating, development reset/migration, complete snapshot preservation, 100-app/eight-schedule bounds, duplicates, Exempt Apps, overnight/overlapping schedules, first-rule auto-enable, master suspension, and stale concurrent saves.
- Child policy tests cover schema-v2 parse/validation, atomic activation, rollback to prior accepted policy, section-level acknowledgement, process-death candidate recovery, and unsupported-schema rejection.
- App Catalog backend tests cover actor authorization, bounded generations, duplicate packages, atomic publication, stale/abandoned generation cleanup, removed-entry deletion, latest freshness, Replace Child Device, and End Supervision.
- App Catalog Android tests cover launchable-only discovery, Exempt App filtering, initial sync, package add/remove reconciliation, restart recovery, bounded batching, retry without history, and privacy-bounded payloads.
- App Blocking evaluator tests use controlled local date/time and time zone. Cover Manual only, Scheduled only, both, overlapping windows, cross-midnight windows, weekday boundaries, daylight-saving transitions, disabled master, missing/reinstalled packages, grants, expiry, and Trusted Device Time degradation.
- Accessibility enforcement tests cover visible package changes, duplicate/noisy events, own-overlay recursion, Home/system exemptions, split-screen, picture-in-picture, overlay replacement/removal, grant arrival, local grant expiry, process restart, and Accessibility loss.
- Block Screen tests cover explanation, app identity, Ask Guardian, pending, denial/cooldown, approval, Offline/unreachable state, Home action, and no interaction with the covered app through the Cereveil surface.
- Access backend tests cover one pending request, repeated-tap deduplication, every-Guardian-Device notice targeting, first-resolution wins, approve/deny races, 15-minute expiry, five-minute denial cooldown, Manual/Scheduled grant limits, absolute approval time, non-revocation, stale policy, unrelated policy versions, actor revocation, and 24-hour cleanup.
- Access Child tests cover applied-policy validation before creation, no offline queue, token refresh, missed/duplicate FCM, `reconcile_access_grants`, local persistence, delayed fetch, restart, offline remaining time, and immediate re-block on expiry.
- Supervision Health tests extend current heartbeat coverage for system location service, automatic date/time, and automatic time zone. Cover first degraded grouping, persistent deduplication, recovery, re-degradation, and the distinction between measurement failure and unavailable capability.
- Location backend tests cover policy desired/applied gating, Online and capability gating, two-minute rate limit, two-Guardian deduplication, 60-second expiry, command creation, high-priority visible semantics, captured-after-request validation, future/stale timestamps, monotonic latest replacement, poor accuracy acceptance, failure preservation, disable deletion, and cleanup.
- Location Android tests use a fake location source and controlled clock. Cover periodic and movement triggers, five-minute movement rate limit, no offline queue, reconnect remeasurement, one-time refresh, cached-point rejection, Child-visible notification lifecycle, timeout, safe failure, and return to low-power mode.
- Guardian location tests cover no state, fresh/stale threshold, exact age, accuracy, Online/Offline independence, Protection Degraded, refresh eligibility, pending/cooldown/failure, previous-marker preservation, map-ready state, map failure fallback, and `geo:` intent construction.
- Google Maps integration is verified through a focused development smoke test with a restricted development key. Automated tests stop at map-state inputs and fallback behavior rather than requiring network tiles.
- Screen Time backend tests cover desired/applied/Online/Usage Access gating, one pending request, two-minute staleness and expiry, two-Guardian deduplication, normal-priority FCM, bounded staging, duplicate/missing app rows, positive-duration filtering contract, atomic publication, valid empty snapshot, prior-snapshot visibility during upload, no-queue failure, midnight invalidation, disable deletion, and cleanup.
- Screen Time Android tests use a fake UsageStats source. Cover local-midnight-to-now query, whole-day behavior before enrollment/enablement and across same-day disabled intervals, time-zone boundaries, Android duration passthrough, launchable/exempt filtering, zero usage, batching, token refresh, timeout, retry re-query, and absence of raw events/session counts.
- Guardian Screen Time tests cover automatic refresh only on the selected screen, freshness under/over two minutes, previous snapshot plus Updating, empty success, sorted duration presentation, stale/Offline/Usage Access/failure states, midnight expiry, manual retry, recreation, and no Household-wide refresh.
- Typed command tests cover discriminated validation, independent intent keys, creation idempotency, expiry, cancellation, safe rejection, type-specific acknowledgement, unrelated-command independence, missed/duplicate push reconciliation, and `apply_policy_version` regression behavior.
- Cleanup tests use controlled scheduler time and indexed bounded batches. Prove request validity transitions occur at their deadlines, cleanup self-reschedules safely, terminal retention windows are respected, staging cannot leak, and no full-table scans or unbounded mutations are required.
- Privacy tests inspect serialized HTTP, Convex function arguments/results, FCM payloads, logs, exceptions, Room entities exposed to Guardian cache, and crash-safe error mapping for forbidden coordinates, package lists, app labels, per-app durations, policy contents, grants, tokens, actor/database IDs, and raw request bodies.
- Add a real-device development smoke document covering: schema-v2 apply/ack spinner; App Catalog initial/reinstall behavior; Accessibility Block Screen including split/PiP; Access Request on both Guardian Devices; approval/denial and restart grant persistence; Location Heartbeat and one-time visible refresh; Google Maps marker/accuracy/fallback; automatic-time tamper; Screen Time auto-refresh from Android UsageStats; missed/duplicate FCM; Offline and capability-unavailable states; and retention cleanup.
- Real Firebase, Accessibility, Usage Access, Android location background behavior, OEM windows, and Google Maps require manual/device validation in addition to deterministic automated tests. The smoke path supplements rather than replaces the default suite.

## Out of Scope

- Live Location Sessions, repeated 5–10 second tracking, route trails, transient point collections, geofences, location history, or movement analytics.
- A one-time refresh that silently operates without a Child-visible notification.
- Reverse geocoding, addresses, Places, Routes, navigation, Street View, advanced map IDs, or other separately billable Google Maps services.
- Self-hosted maps, OpenStreetMap/OpenFreeMap production integration, or a provider migration beyond the small presentation boundary.
- Screen Time session counts, raw usage events, exact app open/close timestamps, current foreground-app streaming, minute-by-minute timelines, completed daily summaries, weekly summaries, trends, comparisons, limits, or 30-day retention.
- Continuous Screen Time upload, Screen Time heartbeat/polling, refreshing every Child when Guardian Mode launches, or queued stale snapshot responses.
- Access Grant early revocation, arbitrary durations, permanent app allowlisting through grants, queued Offline Access Requests, or Access Request history UI.
- Blocking Exempt Apps, disabling emergency/system functionality, replacing Android's package manager, or claiming guaranteed uninstall prevention.
- Using Usage Access as a claimed reliable fallback enforcement engine when Accessibility is unavailable.
- `QUERY_ALL_PACKAGES`, app icons, APK/permission inventory, signing metadata, install/uninstall timestamps, or App Catalog history.
- Safe Browsing, Safe Search runtime implementation, Scam Text Detection, NSFW Screen Detection, Safety Alert implementation, Remote Audio, or other supervision features beyond compatibility with complete policy snapshots and independent commands.
- Production data migration for existing schema-v1 development policies. This PRD permits development policy/test reset while preserving the production-grade versioning architecture.
- Production Google Maps billing/account administration beyond documented setup, key restriction, and repository secret wiring. Human account actions remain required.
- Guaranteed immediate FCM delivery, high-priority silent background sync, SMS/email fallback, or a new persistent Child polling channel.
- A generic workflow engine, arbitrary remote execution, untyped command payloads, or embedding authoritative feature state in FCM.
- New location, installed-app, or usage analytics beyond privacy-safe operational outcomes.

## Further Notes

- This PRD depends on the completed Supervision Policy lifecycle, authenticated function-wrapper hardening, Child Device enrollment/JWT flow, Supervision Heartbeat health lifecycle, and authoritative messaging/FCM delivery.
- ADR-0077 through ADR-0085 record the new design. ADR-0080 supersedes the Live Location parts of ADR-0015, ADR-0016, and ADR-0048. ADR-0082 supersedes ADR-0017, ADR-0018, and ADR-0049.
- Existing ADR-0033 remains controlling: Convex is authoritative and FCM is only wake-up/delivery evidence. Existing ADR-0043 controls Guardian cache versus Child operational Room state. Existing ADR-0050 controls explicit lifecycle fields and bounded cleanup. ADR-0071 controls command acknowledgement at type-specific success points.
- The current implementation has only schema-v1 policy fields, one `apply_policy_version` command variant, no App Catalog/Access/Location/Screen Time domain tables, no production enforcement service, and development-only policy controls. Implementation must preserve current behavior until each schema-v2 vertical slice is genuinely applied and acknowledged.
- The existing App Blocking boolean and Screen Time Summaries toggle are lifecycle scaffolding, not proof that enforcement or Screen Time data collection exists.
- Standard Google Maps SDK usage is currently listed as a no-cost SKU but still requires a billing-enabled Google Cloud project and restricted API keys. Pricing may change; no paid adjunct services are part of this PRD.
- FCM priority follows current Firebase guidance: normal priority for silent sync such as policy and Screen Time; high priority only for time-sensitive user-visible work such as Access Requests/approval and Child-visible Location Refresh.
- Recommended delivery order is: schema-v2 and Trusted Device Time foundation; App Catalog; App Block enforcement; Access Request/Grant lifecycle; latest Location and one-time refresh including Maps setup; latest-only Screen Time; cross-feature cleanup, privacy, and real-device hardening. Each slice should remain vertically testable through the established public seams.
