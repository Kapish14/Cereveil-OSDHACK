package com.cereveil.guardian.auth

import java.util.UUID

class GuardianBootstrapCoordinator(
  private val authSessionProvider: GuardianAuthSessionProvider,
  private val localStateRepository: GuardianLocalStateRepository,
  private val metadataProvider: GuardianInstallationMetadataProvider,
  private val authClient: GuardianAuthClient,
  private val generateInstallationId: () -> String = { UUID.randomUUID().toString() },
) {
  var canRetry: Boolean = false
    private set

  private var lastRoute: GuardianStartupRoute = GuardianStartupRoute.Loading

  suspend fun start(): GuardianStartupRoute {
    canRetry = false

    val authState = authSessionProvider.currentState()
    when (authState) {
      GuardianAuthState.Unauthenticated -> return remember(GuardianStartupRoute.Auth)
      GuardianAuthState.TemporarilyUnavailable -> {
        canRetry = true
        return remember(GuardianStartupRoute.RetryableError)
      }
      is GuardianAuthState.Authenticated -> Unit
    }

    val storedState = localStateRepository.getBootstrapState()
    when {
      storedState != null && storedState.authSessionKey == authState.authSessionKey -> {
        return remember(routeFor(storedState.state))
      }
      storedState != null -> {
        localStateRepository.clearBootstrapState()
      }
    }

    val installationId = localStateRepository.getOrCreateInstallationId(generateInstallationId)
    val request = metadataProvider.buildRequest(installationId)

    return when (val result = authClient.bootstrapGuardian(request)) {
      is GuardianBootstrapResult.Success -> {
        localStateRepository.saveBootstrapState(result.state, authState.authSessionKey)
        remember(routeFor(result.state))
      }
      is GuardianBootstrapResult.Failure -> remember(routeFor(result.error))
    }
  }

  suspend fun retry(): GuardianStartupRoute {
    if (!canRetry) {
      return lastRoute
    }
    return start()
  }

  suspend fun signOut() {
    canRetry = false
    localStateRepository.clearBootstrapState()
  }

  private fun routeFor(state: GuardianBootstrapState): GuardianStartupRoute =
    if (state.hasChildProfiles) GuardianStartupRoute.Dashboard else GuardianStartupRoute.Setup

  private fun routeFor(error: GuardianBootstrapError): GuardianStartupRoute =
    when (error) {
      GuardianBootstrapError.Unauthenticated -> GuardianStartupRoute.Auth
      GuardianBootstrapError.DeviceRevoked -> GuardianStartupRoute.DeviceRevoked
      GuardianBootstrapError.DeviceLimitReached -> GuardianStartupRoute.DeviceLimitReached
      GuardianBootstrapError.Retryable -> {
        canRetry = true
        GuardianStartupRoute.RetryableError
      }
    }

  private fun remember(route: GuardianStartupRoute): GuardianStartupRoute {
    lastRoute = route
    return route
  }
}
