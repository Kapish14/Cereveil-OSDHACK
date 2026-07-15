package com.cereveil.child.enrollment

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChildEnrollmentCoordinatorTest {
  @Test
  fun protectionSetupStatusIdentifiesDeviceLocationServicesAsTheOnlyMissingSetting() {
    val status = ProtectionSetupStatus(
      accessibilityService = true,
      usageAccess = true,
      foregroundLocation = true,
      backgroundLocation = true,
      locationServices = false,
      microphone = true,
      notifications = true,
      batteryOptimizationExempt = true,
      trustedDeviceTime = true,
    )

    assertEquals(6, status.completedSettings)
    assertEquals(listOf("Location Services"), status.missingSettings())
    assertTrue(!status.complete)
  }

  @Test
  fun hackathonProtectionSetupDoesNotRequireVpn() {
    val capabilities = ChildCapabilities(
      accessibilityService = true,
      usageAccess = true,
      location = true,
      microphone = true,
      notificationAccess = true,
      batteryOptimizationExempt = true,
    )

    assertTrue(capabilities.protectionSetupComplete)
  }

  @Test
  fun malformedQrIsRejectedLocallyAfterProtectionSetup() = runTest {
    val harness = Harness()

    assertEquals(
      ChildEnrollmentUiState.Failure(ChildEnrollmentError.InvalidCode),
      harness.coordinator.preview("not an enrollment qr", protectionSetupComplete = true),
    )
    assertTrue(harness.events.isEmpty())
  }

  @Test
  fun scanningIsUnavailableBeforeProtectionSetup() = runTest {
    val harness = Harness()
    val payload = """{"type":"cereveil.child-enrollment","version":1,"code":"AAAAAAAAAAAAAAAAAAAAAA"}"""

    assertEquals(
      ChildEnrollmentUiState.ProtectionSetup,
      harness.coordinator.preview(payload, protectionSetupComplete = false),
    )
    assertTrue(harness.events.isEmpty())
  }

  @Test
  fun completionPersistsRoleLockBeforeFetchingAndAcceptsPolicyAfterStartingRuntime() = runTest {
    val harness = Harness()

    val result = harness.coordinator.complete(EnrollmentQrPayload("AAAAAAAAAAAAAAAAAAAAAA"))

    assertTrue(result is ChildEnrollmentUiState.Enrolled && result.policyApplied)
    assertEquals(
      listOf(
        "key:create",
        "key:public",
        "key:sign:enrollment",
        "client:complete",
        "store:enrollment",
        "client:fetch-policy",
        "runtime:start",
        "store:policy",
        "client:ack",
        "store:acknowledged",
        "client:heartbeat",
      ),
      harness.events,
    )
    assertTrue(harness.store.state?.roleLockActive == true)
    assertEquals(null, harness.store.rawEnrollmentCode)
  }

  @Test
  fun completionRegistersTheCurrentPushTokenEvenWhenNoneIsPending() = runTest {
    val harness = Harness(onEnrollmentActivated = { harnessEvents ->
      harnessEvents += "push:register-current"
    })

    val result = harness.coordinator.complete(EnrollmentQrPayload("AAAAAAAAAAAAAAAAAAAAAA"))

    assertTrue(result is ChildEnrollmentUiState.Enrolled)
    assertEquals("push:register-current", harness.events.last())
  }

  @Test
  fun tokenRefreshSignsBackendChallengeAndUpdatesOnlyBearerState() = runTest {
    val harness = Harness().apply { store.save(completionState()) }
    harness.events.clear()
    val provider = ChildDeviceTokenProvider(harness.client, harness.keyStore, harness.store)

    assertEquals(ChildEnrollmentResult.Success("jwt-refreshed"), provider.refresh())
    assertEquals(
      listOf("client:challenge", "key:sign:refresh", "client:exchange", "store:token"),
      harness.events,
    )
    assertEquals("jwt-refreshed", harness.store.state?.accessJwt)
  }

  @Test
  fun resumedPolicyFlowRefreshesExpiredJwtBeforeAcknowledgementAndHeartbeat() = runTest {
    val harness = Harness().apply {
      store.save(completionState())
      store.savePolicy(testPolicy())
      events.clear()
    }

    val result = harness.coordinator.resume(harness.store.state!!)

    assertTrue(result is ChildEnrollmentUiState.Enrolled && result.policyApplied)
    assertEquals(
      listOf(
        "client:challenge",
        "key:sign:refresh",
        "client:exchange",
        "store:token",
        "runtime:start",
        "store:policy",
        "client:ack",
        "store:acknowledged",
        "client:heartbeat",
      ),
      harness.events,
    )
  }

  @Test
  fun schemaV2ParserRejectsDuplicateAndEmptyAppRulesBeforeActivation() {
    val invalid = testPolicy().rawJson.replace(
      "\"appBlocking\":{\"enabled\":false,\"rules\":[]}",
      "\"appBlocking\":{\"enabled\":true,\"rules\":[{\"packageName\":\"com.example.reader\",\"manualBlocked\":false,\"schedules\":[]}]}",
    )

    assertTrue(runCatching { ChildSupervisionPolicy.parse(invalid) }.isFailure)
  }

  @Test
  fun schemaV3ParserPreservesIndependentSafetySectionsAndRejectsEmptyEnabledSelection() {
    val raw = """{"version":2,"schemaVersion":3,"appBlocking":{"enabled":false,"rules":[]},"safeBrowsing":{"enabled":false,"safeSearchEnabled":false},"activeScreenSafety":{"scamText":{"enabled":true,"monitoredPackageNames":["com.example.messages"],"sensitivity":"higher"},"nsfwScreen":{"enabled":false,"monitoredPackageNames":["com.example.browser"],"sensitivity":"lower"}},"locationSharing":{"enabled":false},"screenTime":{"enabled":false}}"""

    val policy = ChildSupervisionPolicy.parse(raw)

    assertEquals(setOf("com.example.messages"), policy.activeScreenSafety.scamText.monitoredPackageNames)
    assertEquals(SafetySensitivity.Higher, policy.activeScreenSafety.scamText.sensitivity)
    assertEquals(setOf("com.example.browser"), policy.activeScreenSafety.nsfwScreen.monitoredPackageNames)
    assertTrue(runCatching {
      ChildSupervisionPolicy.parse(raw.replace(
        "\"monitoredPackageNames\":[\"com.example.messages\"]",
        "\"monitoredPackageNames\":[]",
      ))
    }.isFailure)
  }

  @Test
  fun heartbeatFailureDoesNotUndoLocalPolicyApplication() = runTest {
    val harness = Harness().apply { client.heartbeatFails = true }

    val result = harness.coordinator.complete(EnrollmentQrPayload("AAAAAAAAAAAAAAAAAAAAAA"))

    assertTrue(result is ChildEnrollmentUiState.Enrolled && result.policyApplied)
  }

  @Test
  fun supervisionSyncPreservesPolicyAcknowledgementWhenHeartbeatNeedsRetry() = runTest {
    val harness = Harness().apply {
      store.save(completionState().copy(accessJwtExpiresAt = Long.MAX_VALUE))
      store.savePolicy(testPolicy())
      client.heartbeatFails = true
      events.clear()
    }
    val sync = ChildSupervisionSyncCoordinator(
      client = harness.client,
      store = harness.store,
      capabilities = { ChildCapabilities(true, true, true, true, true, true) },
      refreshToken = { ChildEnrollmentResult.Failure(ChildEnrollmentError.EnrollmentFailed) },
      now = { 1L },
    )

    assertEquals(ChildSupervisionSyncOutcome.Retry, sync.sync())
    assertEquals(1, harness.store.state?.acknowledgedPolicyVersion)
    assertEquals(listOf("client:ack", "store:acknowledged", "client:heartbeat"), harness.events)
  }

  @Test
  fun supervisionSyncFetchesAppliesAndAcknowledgesPolicyCommand() = runTest {
    val harness = Harness().apply {
      store.save(completionState().copy(accessJwtExpiresAt = Long.MAX_VALUE))
      client.commands = listOf(ChildDeviceCommand("command-1", "apply_policy_version", 1, Long.MAX_VALUE))
      events.clear()
    }
    val sync = ChildSupervisionSyncCoordinator(
      client = harness.client,
      store = harness.store,
      capabilities = { ChildCapabilities(true, true, true, true, true, true) },
      refreshToken = { ChildEnrollmentResult.Failure(ChildEnrollmentError.EnrollmentFailed) },
      runtime = PolicyControlledRuntime {
        harness.events += "runtime:start"
        PolicyActivationResult.Success
      },
      now = { 1L },
    )

    assertEquals(ChildSupervisionSyncOutcome.Complete, sync.sync())
    assertEquals(1, harness.store.state?.acknowledgedPolicyVersion)
    assertEquals(
      listOf("client:commands", "client:fetch-policy", "runtime:start", "store:policy", "client:ack", "store:acknowledged", "client:heartbeat"),
      harness.events,
    )
  }

  @Test
  fun supervisionSyncRejectsPermanentActivationFailureWithoutAcceptingPolicy() = runTest {
    val harness = Harness().apply {
      store.save(completionState().copy(accessJwtExpiresAt = Long.MAX_VALUE))
      client.commands = listOf(ChildDeviceCommand("command-1", "apply_policy_version", 1, Long.MAX_VALUE))
      events.clear()
    }
    val sync = ChildSupervisionSyncCoordinator(
      client = harness.client,
      store = harness.store,
      capabilities = { ChildCapabilities(true, true, true, true, true, true) },
      refreshToken = { ChildEnrollmentResult.Failure(ChildEnrollmentError.EnrollmentFailed) },
      runtime = PolicyControlledRuntime {
        PolicyActivationResult.PermanentFailure(PolicyPermanentFailureReason.UnableToApply)
      },
      now = { 1L },
    )

    assertEquals(ChildSupervisionSyncOutcome.Complete, sync.sync())
    assertEquals(null, harness.store.loadPolicy())
    assertEquals(null, harness.store.state?.acknowledgedPolicyVersion)
    assertEquals(
      listOf("client:commands", "client:fetch-policy", "client:reject", "client:heartbeat"),
      harness.events,
    )
  }

  @Test
  fun supervisionSyncRetriesTransientActivationFailureWithoutRejectingOrAccepting() = runTest {
    val harness = Harness().apply {
      store.save(completionState().copy(accessJwtExpiresAt = Long.MAX_VALUE))
      client.commands = listOf(ChildDeviceCommand("command-1", "apply_policy_version", 1, Long.MAX_VALUE))
      events.clear()
    }
    val sync = ChildSupervisionSyncCoordinator(
      client = harness.client,
      store = harness.store,
      capabilities = { ChildCapabilities(true, true, true, true, true, true) },
      refreshToken = { ChildEnrollmentResult.Failure(ChildEnrollmentError.EnrollmentFailed) },
      runtime = PolicyControlledRuntime { PolicyActivationResult.RetryableFailure },
      now = { 1L },
    )

    assertEquals(ChildSupervisionSyncOutcome.Retry, sync.sync())
    assertEquals(null, harness.store.loadPolicy())
    assertTrue("client:reject" !in harness.events)
    assertTrue("client:ack" !in harness.events)
  }

  @Test
  fun supervisionSyncDoesNotEraseEnrollmentOnAmbiguousUnauthorizedResponse() = runTest {
    val harness = Harness().apply {
      store.save(completionState().copy(accessJwtExpiresAt = Long.MAX_VALUE))
      client.commandsUnauthorized = true
      events.clear()
    }
    val sync = ChildSupervisionSyncCoordinator(
      client = harness.client,
      store = harness.store,
      capabilities = { ChildCapabilities(true, true, true, true, true, true) },
      refreshToken = { ChildEnrollmentResult.Failure(ChildEnrollmentError.Unauthorized) },
      now = { 1L },
    )

    assertEquals(ChildSupervisionSyncOutcome.Retry, sync.sync())
    assertEquals("enrollment-1", harness.store.load()?.activeEnrollmentId)
  }

  @Test
  fun supervisionSyncDelegatesTypedFeatureCommandsAndThenMaintainsLatestState() = runTest {
    val harness = Harness().apply {
      store.save(completionState().copy(accessJwtExpiresAt = Long.MAX_VALUE))
      store.savePolicy(testPolicy())
      store.markPolicyAcknowledged(1)
      client.commands = listOf(ChildDeviceCommand("command-2", "refresh_location", null, Long.MAX_VALUE, "refresh-1"))
      events.clear()
    }
    val processor = object : ChildFeatureCommandProcessor {
      override suspend fun process(accessJwt: String, command: ChildDeviceCommand) =
        ChildEnrollmentResult.Success(Unit).also { harness.events += "feature:${command.type}:${command.referenceId}" }
      override suspend fun maintain(accessJwt: String, policy: ChildSupervisionPolicy?) =
        ChildEnrollmentResult.Success(Unit).also { harness.events += "feature:maintain" }
    }
    val sync = ChildSupervisionSyncCoordinator(
      client = harness.client,
      store = harness.store,
      capabilities = { ChildCapabilities(true, true, true, true, true, true) },
      refreshToken = { ChildEnrollmentResult.Failure(ChildEnrollmentError.EnrollmentFailed) },
      featureProcessor = processor,
      now = { 1L },
    )

    assertEquals(ChildSupervisionSyncOutcome.Complete, sync.sync())
    assertEquals(
      listOf("client:commands", "feature:refresh_location:refresh-1", "feature:maintain", "client:heartbeat"),
      harness.events,
    )
  }

  private class Harness(
    onEnrollmentActivated: (MutableList<String>) -> Unit = {},
  ) {
    val events = mutableListOf<String>()
    val store = FakeStore(events)
    val keyStore = FakeKeyStore(events)
    val client = FakeClient(events)
    val coordinator = ChildEnrollmentCoordinator(
      client,
      keyStore,
      store,
      PolicyControlledRuntime {
        events += "runtime:start"
        PolicyActivationResult.Success
      },
      installationId = "install-1",
      deviceLabel = "Pixel",
      appBuild = "1",
      capabilities = { ChildCapabilities(true, true, true, true, true, true) },
      onEnrollmentActivated = { onEnrollmentActivated(events) },
    )
  }
}

private class FakeKeyStore(private val events: MutableList<String>) : ChildDeviceKeyStore {
  override fun createKeyAlias() = "key-1".also { events += "key:create" }
  override fun publicKeySpki(alias: String) = "public-key".also { events += "key:public" }
  override fun sign(alias: String, message: String): String = "proof".also {
    events += if (message.startsWith("cereveil-child-enrollment")) "key:sign:enrollment" else "key:sign:refresh"
  }
}

private class FakeStore(private val events: MutableList<String>) : ChildEnrollmentStateStore {
  var state: LocalChildEnrollmentState? = null
  var policy: ChildSupervisionPolicy? = null
  val rawEnrollmentCode: String? = null
  override fun load() = state
  override fun save(state: LocalChildEnrollmentState) { events += "store:enrollment"; this.state = state }
  override fun updateAccessToken(accessJwt: String, expiresAt: Long) {
    events += "store:token"
    state = state?.copy(accessJwt = accessJwt, accessJwtExpiresAt = expiresAt)
  }
  override fun markPolicyAcknowledged(version: Int) {
    state = state?.copy(acknowledgedPolicyVersion = version)
    events += "store:acknowledged"
  }
  override fun savePolicy(policy: ChildSupervisionPolicy) { events += "store:policy"; this.policy = policy }
  override fun loadPolicy() = policy
}

private class FakeClient(private val events: MutableList<String>) : ChildDeviceIdentityClient {
  var heartbeatFails = false
  var commandsUnauthorized = false
  var commands = emptyList<ChildDeviceCommand>()
  override suspend fun preview(code: String) = ChildEnrollmentResult.Success(ChildEnrollmentPreview("Aarav", 2, 1))
  override suspend fun complete(
    code: String,
    publicKeySpki: String,
    proof: String,
    installationId: String,
    deviceLabel: String?,
    appBuild: String,
  ) = ChildEnrollmentResult.Success(completion()).also { events += "client:complete" }
  override suspend fun fetchPolicy(accessJwt: String) =
    ChildEnrollmentResult.Success(testPolicy()).also { events += "client:fetch-policy" }
  override suspend fun acknowledgePolicy(accessJwt: String, version: Int) =
    ChildEnrollmentResult.Success(Unit).also { events += "client:ack" }
  override suspend fun heartbeat(accessJwt: String, capabilities: ChildCapabilities) =
    if (heartbeatFails) {
      ChildEnrollmentResult.Failure(ChildEnrollmentError.NetworkUnavailable).also { events += "client:heartbeat" }
    } else {
      ChildEnrollmentResult.Success(Unit).also { events += "client:heartbeat" }
    }
  override suspend fun registerPushToken(accessJwt: String, token: String) = ChildEnrollmentResult.Success(Unit)
  override suspend fun reconcileCommands(accessJwt: String) =
    if (commandsUnauthorized) ChildEnrollmentResult.Failure(ChildEnrollmentError.Unauthorized)
    else ChildEnrollmentResult.Success(commands).also { if (commands.isNotEmpty()) events += "client:commands" }
  override suspend fun rejectCommand(accessJwt: String, commandId: String, reason: String) =
    ChildEnrollmentResult.Success(Unit).also { events += "client:reject" }
  override suspend fun createTokenChallenge(credentialId: String) =
    ChildEnrollmentResult.Success("challenge").also { events += "client:challenge" }
  override suspend fun exchangeTokenChallenge(credentialId: String, challenge: String, proof: String) =
    ChildEnrollmentResult.Success("jwt-refreshed" to 999L).also { events += "client:exchange" }
}

private fun completion() = ChildEnrollmentCompletion(
  "device-1", "enrollment-1", "credential-1", "Aarav", 1, "jwt", Long.MAX_VALUE, 1, "dev", 1,
)

private fun completionState() = LocalChildEnrollmentState(
  "device-1", "enrollment-1", "credential-1", "Aarav", 1, "jwt", 100, 1, "dev", "key-1",
)

private fun testPolicy() = ChildSupervisionPolicy(
  version = 1,
  schemaVersion = 2,
  appBlocking = AppBlockingPolicy(false),
  safeBrowsing = SafeBrowsingPolicy(false, false),
  activeScreenSafety = ActiveScreenSafetyPolicy(false),
  locationSharing = LocationSharingPolicy(false),
  screenTime = ScreenTimePolicy(false),
  rawJson = """{"version":1,"schemaVersion":2,"appBlocking":{"enabled":false,"rules":[]},"safeBrowsing":{"enabled":false,"safeSearchEnabled":false},"activeScreenSafety":{"enabled":false},"locationSharing":{"enabled":false},"screenTime":{"enabled":false}}""",
)
