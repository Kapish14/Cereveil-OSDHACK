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
  fun signOutClearsBootstrapStateButKeepsInstallationId() = runTest {
    val harness = Harness(existingInstallationId = "same-installation-id")
    harness.repository.bootstrapState = bootstrapState(hasChildProfiles = true)
    harness.repository.bootstrapAuthSessionKey = "clerk-user-1"

    harness.coordinator.signOut()

    assertEquals("same-installation-id", harness.repository.installationId)
    assertNull(harness.repository.bootstrapState)
    assertNull(harness.repository.bootstrapAuthSessionKey)
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
  ) {
    val repository = InMemoryGuardianLocalStateRepository(existingInstallationId)
    val client = FakeGuardianAuthClient(bootstrapResult, bootstrapError)
    val coordinator =
      GuardianBootstrapCoordinator(
        authSessionProvider = FakeGuardianAuthSessionProvider(authState),
        localStateRepository = repository,
        metadataProvider = FakeGuardianInstallationMetadataProvider(),
        authClient = client,
        generateInstallationId = { "generated-installation-id" },
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

    override suspend fun bootstrapGuardian(
      request: GuardianBootstrapRequest
    ): GuardianBootstrapResult {
      requests += request
      return bootstrapError?.let { GuardianBootstrapResult.Failure(it) }
        ?: GuardianBootstrapResult.Success(bootstrapResult)
    }
  }

  private class InMemoryGuardianLocalStateRepository(initialInstallationId: String? = null) :
    GuardianLocalStateRepository {
    var installationId: String? = initialInstallationId
    var bootstrapState: GuardianBootstrapState? = null
    var bootstrapAuthSessionKey: String? = null

    override suspend fun getOrCreateInstallationId(generate: () -> String): String {
      val existing = installationId
      if (existing != null) return existing

      val generated = generate()
      installationId = generated
      return generated
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
