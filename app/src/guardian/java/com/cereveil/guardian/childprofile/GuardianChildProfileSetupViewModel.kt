package com.cereveil.guardian.childprofile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cereveil.CereveilApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import com.cereveil.guardian.auth.SharedPreferencesGuardianInstallationIdProvider

class GuardianChildProfileSetupViewModel(application: Application) : AndroidViewModel(application) {
  private val coordinator =
    GuardianChildProfileSetupCoordinator(
      client = ConvexGuardianChildProfileClient(
        (application as CereveilApplication).convex,
        SharedPreferencesGuardianInstallationIdProvider(application),
      )
    )

  private val mutableState =
    MutableStateFlow<GuardianChildProfileSetupState>(GuardianChildProfileSetupState.Loading)
  val state: StateFlow<GuardianChildProfileSetupState> = mutableState.asStateFlow()

  private var loadedAuthSessionKey: String? = null
  private var observation: Job? = null

  fun load() {
    loadForSession(loadedAuthSessionKey)
  }

  fun loadForSession(authSessionKey: String?) {
    if (authSessionKey == null) {
      loadedAuthSessionKey = null
      mutableState.value = GuardianChildProfileSetupState.Loading
      return
    }

    loadedAuthSessionKey = authSessionKey
    observation?.cancel()
    observation = viewModelScope.launch {
      mutableState.value = GuardianChildProfileSetupState.Loading
      coordinator.observe().collect { mutableState.value = it }
    }
  }

  fun submit(displayName: String, birthMonth: String, birthYear: String) {
    val month = birthMonth.toIntOrNull()
    val year = birthYear.toIntOrNull()
    if (month == null || year == null) {
      mutableState.value =
        GuardianChildProfileSetupState.FormError(GuardianChildProfileError.ValidationFailed)
      return
    }

    viewModelScope.launch {
      mutableState.value = GuardianChildProfileSetupState.Loading
      mutableState.value = coordinator.submit(displayName, month, year)
    }
  }

  fun setUpChildDevice() {
    coordinator.setUpChildDevice()
  }
}
