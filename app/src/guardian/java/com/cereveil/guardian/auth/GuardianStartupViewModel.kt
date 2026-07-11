package com.cereveil.guardian.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cereveil.BuildConfig
import com.cereveil.CereveilApplication
import com.cereveil.RoleInitializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GuardianStartupViewModel(application: Application) : AndroidViewModel(application) {
  private val authSessionProvider = RoleInitializer.guardianAuthSessionProvider()

  private val coordinator =
    GuardianBootstrapCoordinator(
      authSessionProvider = authSessionProvider,
      localStateRepository = SharedPreferencesGuardianLocalStateRepository(application),
      metadataProvider =
        AndroidGuardianInstallationMetadataProvider(
          role = BuildConfig.CEREVEIL_ROLE,
          versionName = BuildConfig.VERSION_NAME,
          versionCode = BuildConfig.VERSION_CODE.toLong(),
        ),
      authClient = ConvexGuardianAuthClient(BuildConfig.CONVEX_URL, RoleInitializer::guardianConvexToken),
    )

  private val mutableRoute = MutableStateFlow(GuardianStartupRoute.Loading)
  val route: StateFlow<GuardianStartupRoute> = mutableRoute.asStateFlow()

  private val mutableSetupAuthSessionKey = MutableStateFlow<String?>(null)
  val setupAuthSessionKey: StateFlow<String?> = mutableSetupAuthSessionKey.asStateFlow()

  init {
    viewModelScope.launch {
      authSessionProvider.states().collect {
        runStart()
      }
    }
  }

  fun start() {
    viewModelScope.launch { runStart() }
  }

  fun retry() {
    viewModelScope.launch {
      mutableRoute.value = GuardianStartupRoute.Loading
      mutableRoute.value = coordinator.retry()
    }
  }

  private suspend fun runStart() {
    mutableRoute.value = GuardianStartupRoute.Loading
    val route = coordinator.start()
    val authState = authSessionProvider.currentState()
    mutableSetupAuthSessionKey.value =
      if (
        route in setOf(GuardianStartupRoute.Setup, GuardianStartupRoute.Dashboard) &&
          authState is GuardianAuthState.Authenticated
      ) {
        authState.authSessionKey
      } else {
        null
      }
    mutableRoute.value = route
  }
}
