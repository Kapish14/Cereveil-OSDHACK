package com.cereveil.guardian.auth

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GuardianBootstrapCoordinatorTest {
  @Test
  fun unauthenticatedGuardianRoutesToAuthWithoutBootstrapping() = runTest {
    val harness = Harness(authState = GuardianAuthState.Unauthenticated)

    assertEquals(GuardianStartupRoute.Auth, harness.coordinator.start())
    assertTrue(harness.client.requests.isEmpty())
  }

  @Test
  fun unavailableGuardianAuthRoutesToRetryableConnectionState() = runTest {
    val harness = Harness(authState = GuardianAuthState.TemporarilyUnavailable)

    assertEquals(GuardianStartupRoute.RetryableError, harness.coordinator.start())
    assertTrue(harness.coordinator.canRetry)
    assertTrue(harness.client.requests.isEmpty())
  }

  @Test
  fun firstAuthenticatedLaunchGeneratesInstallationIdBootstrapsAndRoutesToSetup() = runTest {
    val harness = Harness(
      authState = GuardianAuthState.Authenticated(authSessionKey = "clerk-user-1"),
      bootstrapResult = bootstrapState(hasChildProfiles = false),
    )

    assertEquals(GuardianStartupRoute.Setup, harness.coordinator.start())

    assertEquals("generated-installation-id", harness.repository.installationId)
    assertEquals(
      GuardianBootstrapRequest(
        guardianInstallationId = "generated-installation-id",
        deviceLabel = "Google Pixel 8",
        appBuild = "guardian-1.0-1",
        timezone = "Asia/Kolkata",
      ),
      harness.client.requests.single(),
    )
    assertEquals(bootstrapState(hasChildProfiles = false), harness.repository.bootstrapState)
    assertEquals("clerk-user-1", harness.repository.bootstrapAuthSessionKey)
  }

  @Test
  fun laterAuthenticatedLaunchReusesInstallationIdAndRoutesToDashboard() = runTest {
    val harness = Harness(
      authState = GuardianAuthState.Authenticated(authSessionKey = "clerk-user-1"),
      existingInstallationId = "existing-installation-id",
      bootstrapResult = bootstrapState(hasChildProfiles = true),
    )

    assertEquals(GuardianStartupRoute.Dashboard, harness.coordinator.start())

    assertEquals("existing-installation-id", harness.client.requests.single().guardianInstallationId)
    assertEquals(bootstrapState(hasChildProfiles = true), harness.repository.bootstrapState)
  }

  @Test
  fun matchingStoredBootstrapStateRoutesWithoutNetworkBootstrap() = runTest {
    val harness =
      Harness(
        authState = GuardianAuthState.Authenticated(authSessionKey = "clerk-user-1"),
        existingInstallationId = "existing-installation-id",
      )
    harness.repository.bootstrapState = bootstrapState(hasChildProfiles = true)
    harness.repository.bootstrapAuthSessionKey = "clerk-user-1"

    assertEquals(GuardianStartupRoute.Dashboard, harness.coordinator.start())
    assertTrue(harness.client.requests.isEmpty())
  }

  @Test
  fun signOutRetiresTheSessionAndNextLoginUsesAFreshInstallationId() = runTest {
    val harness = Harness(existingInstallationId = "same-installation-id")
    harness.repository.bootstrapState = bootstrapState(hasChildProfiles = true)
    harness.repository.bootstrapAuthSessionKey = "clerk-user-1"

    assertEquals(GuardianStartupRoute.Auth, harness.coordinator.signOut())

    assertNull(harness.repository.installationId)
    assertNull(harness.repository.bootstrapState)
    assertNull(harness.repository.bootstrapAuthSessionKey)
    assertEquals(1, harness.retireAttempts)
    assertEquals(1, harness.signOutAttempts)

    assertEquals(GuardianStartupRoute.Setup, harness.coordinator.start())
    assertEquals("generated-installation-id", harness.client.requests.last().guardianInstallationId)
  }

  @Test
  fun failedSessionSignOutKeepsARecoveryMarkerAfterDeviceRetirement() = runTest {
    val harness = Harness(
      bootstrapError = GuardianBootstrapError.DeviceLimitReached,
      sessionSignOutSucceeds = false,
    )
    assertEquals(GuardianStartupRoute.DeviceLimitReached, harness.coordinator.start())
    harness.repository.bootstrapState = bootstrapState(hasChildProfiles = true)
    harness.repository.bootstrapAuthSessionKey = "clerk-user-1"
    assertEquals(GuardianStartupRoute.DeviceLimitReached, harness.coordinator.signOut())

    assertNull(harness.repository.installationId)
    assertNull(harness.repository.bootstrapState)
    assertNull(harness.repository.bootstrapAuthSessionKey)
    assertTrue(harness.repository.sessionEndPending)
    assertEquals(1, harness.retireAttempts)
    assertEquals(1, harness.signOutAttempts)

    harness.clerkSessionSignOutSucceeds = true
    assertEquals(GuardianStartupRoute.Auth, harness.coordinator.start())
    assertFalse(harness.repository.sessionEndPending)
    assertEquals(2, harness.signOutAttempts)
  }

  @Test
  fun failedDeviceRetirementDoesNotEndTheClerkSession() = runTest {
    val harness = Harness(existingInstallationId = "existing-installation-id", deviceRetireSucceeds = false)

    assertEquals(GuardianStartupRoute.Setup, harness.coordinator.start())
    assertEquals(GuardianStartupRoute.Setup, harness.coordinator.signOut())

    assertEquals(1, harness.retireAttempts)
    assertEquals(0, harness.signOutAttempts)
    assertEquals("existing-installation-id", harness.repository.installationId)
    assertEquals("existing-installation-id", harness.repository.pendingRetirementInstallationId)
  }

  @Test
  fun startupResumesRetirementInterruptedByProcessDeathBeforeBootstrapping() = runTest {
    val harness = Harness(existingInstallationId = "existing-installation-id")
    harness.repository.pendingRetirementInstallationId = "existing-installation-id"
    harness.repository.pendingLogoutAuthSessionKey = "clerk-user-1"
    harness.repository.bootstrapState = bootstrapState(hasChildProfiles = true)
    harness.repository.bootstrapAuthSessionKey = "clerk-user-1"

    assertEquals(GuardianStartupRoute.Auth, harness.coordinator.start())

    assertEquals(1, harness.retireAttempts)
    assertEquals(1, harness.signOutAttempts)
    assertNull(harness.repository.installationId)
    assertNull(harness.repository.bootstrapState)
    assertFalse(harness.repository.sessionEndPending)
  }

  @Test
  fun differentAccountAbandonsAnInterruptedLogoutWithoutRetiringOrSigningItOut() = runTest {
    val harness = Harness(
      authState = GuardianAuthState.Authenticated("clerk-user-2"),
      existingInstallationId = "old-account-installation-id",
    )
    harness.repository.pendingRetirementInstallationId = "old-account-installation-id"
    harness.repository.pendingLogoutAuthSessionKey = "clerk-user-1"
    harness.repository.bootstrapState = bootstrapState(hasChildProfiles = true)
    harness.repository.bootstrapAuthSessionKey = "clerk-user-1"

    assertEquals(GuardianStartupRoute.Setup, harness.coordinator.start())

    assertEquals(0, harness.retireAttempts)
    assertEquals(0, harness.signOutAttempts)
    assertNull(harness.repository.pendingRetirementInstallationId)
    assertNull(harness.repository.pendingLogoutAuthSessionKey)
    assertEquals("generated-installation-id", harness.client.requests.single().guardianInstallationId)
  }

  @Test
  fun revokedDeviceCanStillFinishLogoutAfterAPriorClerkFailure() = runTest {
    val harness = Harness(bootstrapError = GuardianBootstrapError.DeviceRevoked)

    assertEquals(GuardianStartupRoute.DeviceRevoked, harness.coordinator.start())
    assertEquals(GuardianStartupRoute.Auth, harness.coordinator.signOut())

    assertEquals(1, harness.retireAttempts)
    assertEquals(1, harness.signOutAttempts)
  }

  @Test
  fun thrownClerkFailureKeepsTheCurrentRouteRetryable() = runTest {
    val harness = Harness(
      bootstrapError = GuardianBootstrapError.DeviceRevoked,
      sessionSignOutThrows = true,
    )

    assertEquals(GuardianStartupRoute.DeviceRevoked, harness.coordinator.start())
    assertEquals(GuardianStartupRoute.DeviceRevoked, harness.coordinator.signOut())

    assertEquals(1, harness.retireAttempts)
    assertEquals(1, harness.signOutAttempts)
  }

  @Test
  fun changedAuthenticatedGuardianClearsStaleBootstrapStateBeforeBootstrapping() = runTest {
    val harness = Harness(
      authState = GuardianAuthState.Authenticated(authSessionKey = "clerk-user-2"),
      existingInstallationId = "existing-installation-id",
      bootstrapResult = bootstrapState(hasChildProfiles = false),
    )
    harness.repository.bootstrapState = bootstrapState(hasChildProfiles = true)
    harness.repository.bootstrapAuthSessionKey = "clerk-user-1"

    assertEquals(GuardianStartupRoute.Setup, harness.coordinator.start())

    assertEquals("clerk-user-2", harness.repository.bootstrapAuthSessionKey)
    assertEquals(bootstrapState(hasChildProfiles = false), harness.repository.bootstrapState)
  }

  @Test
  fun permanentErrorsMapToStableRoutesAndAreNotRetriedAutomatically() = runTest {
    val cases =
      listOf(
        GuardianBootstrapError.Unauthenticated to GuardianStartupRoute.Auth,
        GuardianBootstrapError.DeviceRevoked to GuardianStartupRoute.DeviceRevoked,
        GuardianBootstrapError.DeviceLimitReached to GuardianStartupRoute.DeviceLimitReached,
      )

    for ((error, route) in cases) {
      val harness =
        Harness(
          authState = GuardianAuthState.Authenticated(authSessionKey = "clerk-user-1"),
          bootstrapError = error,
        )

      assertEquals(route, harness.coordinator.start())
      assertEquals(1, harness.client.requests.size)
      assertFalse(harness.coordinator.canRetry)
      assertEquals(route, harness.coordinator.retry())
      assertEquals(1, harness.client.requests.size)
    }
  }

  @Test
  fun retryableFailureCanBeExplicitlyRetriedWithSameInstallationId() = runTest {
    val harness =
      Harness(
        authState = GuardianAuthState.Authenticated(authSessionKey = "clerk-user-1"),
        bootstrapError = GuardianBootstrapError.Retryable,
      )

    assertEquals(GuardianStartupRoute.RetryableError, harness.coordinator.start())
    assertTrue(harness.coordinator.canRetry)

    harness.client.bootstrapError = null
    harness.client.bootstrapResult = bootstrapState(hasChildProfiles = true)

    assertEquals(GuardianStartupRoute.Dashboard, harness.coordinator.retry())
    assertEquals("generated-installation-id", harness.client.requests[0].guardianInstallationId)
    assertEquals("generated-installation-id", harness.client.requests[1].guardianInstallationId)
  }

  private class Harness(
    authState: GuardianAuthState = GuardianAuthState.Authenticated("clerk-user-1"),
    existingInstallationId: String? = null,
    bootstrapResult: GuardianBootstrapState = bootstrapState(hasChildProfiles = false),
    bootstrapError: GuardianBootstrapError? = null,
    sessionSignOutSucceeds: Boolean = true,
    deviceRetireSucceeds: Boolean = true,
    sessionSignOutThrows: Boolean = false,
  ) {
    val repository = InMemoryGuardianLocalStateRepository(existingInstallationId)
    val client = FakeGuardianAuthClient(bootstrapResult, bootstrapError)
    var retireAttempts = 0
    init {
      client.retireResult =
        if (deviceRetireSucceeds) GuardianDeviceRetirementResult.Completed
        else GuardianDeviceRetirementResult.RetryableFailure
      client.onRetire = { retireAttempts += 1 }
    }
    var signOutAttempts = 0
    var clerkSessionSignOutSucceeds = sessionSignOutSucceeds
    val coordinator =
      GuardianBootstrapCoordinator(
        authSessionProvider = FakeGuardianAuthSessionProvider(authState),
        localStateRepository = repository,
        metadataProvider = FakeGuardianInstallationMetadataProvider(),
        authClient = client,
        generateInstallationId = { "generated-installation-id" },
        endAuthSession = {
          signOutAttempts += 1
          if (sessionSignOutThrows) error("Clerk sign-out failed")
          clerkSessionSignOutSucceeds
        },
      )
  }

  private class FakeGuardianAuthSessionProvider(private val state: GuardianAuthState) :
    GuardianAuthSessionProvider {
    override suspend fun currentState(): GuardianAuthState = state
  }

  private class FakeGuardianInstallationMetadataProvider : GuardianInstallationMetadataProvider {
    override fun buildRequest(guardianInstallationId: String): GuardianBootstrapRequest =
      GuardianBootstrapRequest(
        guardianInstallationId = guardianInstallationId,
        deviceLabel = "Google Pixel 8",
        appBuild = "guardian-1.0-1",
        timezone = "Asia/Kolkata",
      )
  }

  private class FakeGuardianAuthClient(
    var bootstrapResult: GuardianBootstrapState,
    var bootstrapError: GuardianBootstrapError?,
  ) : GuardianAuthClient {
    val requests = mutableListOf<GuardianBootstrapRequest>()
    var retireResult = GuardianDeviceRetirementResult.Completed
    var onRetire: () -> Unit = {}

    override suspend fun bootstrapGuardian(
      request: GuardianBootstrapRequest
    ): GuardianBootstrapResult {
      requests += request
      return bootstrapError?.let { GuardianBootstrapResult.Failure(it) }
        ?: GuardianBootstrapResult.Success(bootstrapResult)
    }

    override suspend fun requestGuardianDeviceRetirement(
      guardianInstallationId: String,
    ): GuardianDeviceRetirementResult {
      onRetire()
      return retireResult
    }
  }

  private class InMemoryGuardianLocalStateRepository(initialInstallationId: String? = null) :
    GuardianLocalStateRepository {
    var installationId: String? = initialInstallationId
    var pendingRetirementInstallationId: String? = null
    var pendingLogoutAuthSessionKey: String? = null
    var sessionEndPending = false
    var bootstrapState: GuardianBootstrapState? = null
    var bootstrapAuthSessionKey: String? = null

    override suspend fun getOrCreateInstallationId(generate: () -> String): String {
      val existing = installationId
      if (existing != null) return existing

      val generated = generate()
      installationId = generated
      return generated
    }

    override suspend fun getPendingRetirementInstallationId() = pendingRetirementInstallationId

    override suspend fun getPendingLogoutAuthSessionKey() = pendingLogoutAuthSessionKey

    override suspend fun beginDeviceRetirement(installationId: String, authSessionKey: String): Boolean {
      pendingRetirementInstallationId = installationId
      pendingLogoutAuthSessionKey = authSessionKey
      return true
    }

    override suspend fun markDeviceRetiredAwaitingSessionEnd(): Boolean {
      installationId = null
      pendingRetirementInstallationId = null
      sessionEndPending = true
      bootstrapState = null
      bootstrapAuthSessionKey = null
      return true
    }

    override suspend fun isSessionEndPending() = sessionEndPending

    override suspend fun completeLogout(): Boolean {
      installationId = null
      pendingRetirementInstallationId = null
      pendingLogoutAuthSessionKey = null
      sessionEndPending = false
      bootstrapState = null
      bootstrapAuthSessionKey = null
      return true
    }

    override suspend fun getBootstrapState(): StoredGuardianBootstrapState? {
      val state = bootstrapState ?: return null
      val authSessionKey = bootstrapAuthSessionKey ?: return null
      return StoredGuardianBootstrapState(state, authSessionKey)
    }

    override suspend fun saveBootstrapState(state: GuardianBootstrapState, authSessionKey: String) {
      bootstrapState = state
      bootstrapAuthSessionKey = authSessionKey
    }

    override suspend fun clearBootstrapState() {
      bootstrapState = null
      bootstrapAuthSessionKey = null
    }
  }
}

private fun bootstrapState(hasChildProfiles: Boolean) =
  GuardianBootstrapState(
    guardianAccountId = "guardian-account-id",
    householdId = "household-id",
    guardianDeviceId = "guardian-device-id",
    guardianDeviceStatus = "active",
    hasChildProfiles = hasChildProfiles,
    serverNow = 1234,
  )
