package com.cereveil.guardian.auth

import java.util.UUID
import kotlinx.coroutines.CancellationException

class GuardianBootstrapCoordinator(
  private val authSessionProvider: GuardianAuthSessionProvider,
  private val localStateRepository: GuardianLocalStateRepository,
  private val metadataProvider: GuardianInstallationMetadataProvider,
  private val authClient: GuardianAuthClient,
  private val generateInstallationId: () -> String = { UUID.randomUUID().toString() },
  private val endAuthSession: suspend () -> Boolean = { true },
) {
  var canRetry: Boolean = false
    private set

  private var lastRoute: GuardianStartupRoute = GuardianStartupRoute.Loading

  suspend fun start(): GuardianStartupRoute {
    canRetry = false

    val authState = authSessionProvider.currentState()
    when (authState) {
      GuardianAuthState.Unauthenticated -> {
        if (localStateRepository.isSessionEndPending()) localStateRepository.completeLogout()
        return remember(GuardianStartupRoute.Auth)
      }
      GuardianAuthState.TemporarilyUnavailable -> {
        canRetry = true
        return remember(GuardianStartupRoute.RetryableError)
      }
      is GuardianAuthState.Authenticated -> Unit
    }

    val pendingLogoutAuthSessionKey = localStateRepository.getPendingLogoutAuthSessionKey()
    if (pendingLogoutAuthSessionKey != null && pendingLogoutAuthSessionKey != authState.authSessionKey) {
      if (!localStateRepository.completeLogout()) {
        canRetry = true
        return remember(GuardianStartupRoute.RetryableError)
      }
    }
    if (
      localStateRepository.getPendingRetirementInstallationId() != null ||
      localStateRepository.isSessionEndPending()
    ) {
      return remember(finishPendingLogout(GuardianStartupRoute.RetryableError))
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

  suspend fun signOut(): GuardianStartupRoute {
    canRetry = false
    val authState = authSessionProvider.currentState()
    if (authState !is GuardianAuthState.Authenticated) {
      localStateRepository.completeLogout()
      return remember(GuardianStartupRoute.Auth)
    }
    val installationId = localStateRepository.getOrCreateInstallationId(generateInstallationId)
    if (!localStateRepository.beginDeviceRetirement(installationId, authState.authSessionKey)) return lastRoute
    return remember(finishPendingLogout(lastRoute))
  }

  private suspend fun finishPendingLogout(failureRoute: GuardianStartupRoute): GuardianStartupRoute {
    val installationId = localStateRepository.getPendingRetirementInstallationId()
    if (installationId != null) {
      val retirement = try {
        authClient.requestGuardianDeviceRetirement(installationId)
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        GuardianDeviceRetirementResult.RetryableFailure
      }
      if (retirement != GuardianDeviceRetirementResult.Completed) {
        canRetry = true
        return failureRoute
      }
      if (!localStateRepository.markDeviceRetiredAwaitingSessionEnd()) {
        canRetry = true
        return failureRoute
      }
    }
    val sessionEnded = try {
      endAuthSession()
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      false
    }
    if (!sessionEnded) {
      canRetry = true
      return failureRoute
    }
    if (!localStateRepository.completeLogout()) {
      canRetry = true
      return failureRoute
    }
    return GuardianStartupRoute.Auth
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
