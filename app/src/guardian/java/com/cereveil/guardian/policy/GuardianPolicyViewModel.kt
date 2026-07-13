package com.cereveil.guardian.policy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GuardianPolicyViewModel(
  private val childProfileId: String,
  private val client: GuardianPolicyClient,
) : ViewModel() {
  private val mutableState = MutableStateFlow<GuardianPolicyUiState>(GuardianPolicyUiState.Loading)
  val state: StateFlow<GuardianPolicyUiState> = mutableState.asStateFlow()
  private var observation: Job? = null
  private var catalogObservation: Job? = null

  init { observe(); observeCatalog() }

  fun observe() {
    observation?.cancel()
    observation = viewModelScope.launch {
      client.observe(childProfileId).collect { result ->
        mutableState.value = when (result) {
          is GuardianPolicyResult.Success -> GuardianPolicyUiState.Ready(
            result.value,
            (mutableState.value as? GuardianPolicyUiState.Ready)?.catalogApps.orEmpty(),
          )
          is GuardianPolicyResult.Failure -> GuardianPolicyUiState.Error(result.error)
        }
      }
    }
  }

  private fun observeCatalog() {
    catalogObservation?.cancel()
    catalogObservation = viewModelScope.launch {
      client.observeCatalog(childProfileId).collect { result ->
        val current = mutableState.value as? GuardianPolicyUiState.Ready ?: return@collect
        if (result is GuardianPolicyResult.Success) mutableState.value = current.copy(catalogApps = result.value)
      }
    }
  }

  fun update(feature: PolicyFeature, enabled: Boolean, safeSearchEnabled: Boolean = false) {
    val current = (mutableState.value as? GuardianPolicyUiState.Ready) ?: return
    mutableState.value = current.copy(savingFeature = feature)
    viewModelScope.launch {
      when (val result = client.update(
        childProfileId,
        current.policy.desired.version,
        policyOperationId(
          childProfileId,
          current.policy.desired.version,
          feature,
          enabled,
          safeSearchEnabled,
        ),
        feature,
        enabled,
        safeSearchEnabled,
      )) {
        is GuardianPolicyResult.Success -> mutableState.value = GuardianPolicyUiState.Ready(result.value)
        is GuardianPolicyResult.Failure -> {
          mutableState.value = current.copy(savingFeature = null, updateError = result.error)
          if (result.error == GuardianPolicyError.Conflict) observe()
        }
      }
    }
  }

  fun updateSafety(detector: GuardianSafetyDetector, value: GuardianSafetyDetectorPolicy) {
    val current = (mutableState.value as? GuardianPolicyUiState.Ready) ?: return
    val desired = current.policy.desired
    val scam = if (detector == GuardianSafetyDetector.ScamText) value else desired.scamTextSafety
    val nsfw = if (detector == GuardianSafetyDetector.NsfwScreen) value else desired.nsfwScreenSafety
    val feature = if (detector == GuardianSafetyDetector.ScamText) PolicyFeature.ScamTextSafety else PolicyFeature.NsfwScreenSafety
    mutableState.value = current.copy(savingFeature = feature)
    viewModelScope.launch {
      when (val result = client.updateSafety(
        childProfileId, desired.version, safetyPolicyOperationId(childProfileId, desired.version, scam, nsfw), scam, nsfw,
      )) {
        is GuardianPolicyResult.Success -> mutableState.value = GuardianPolicyUiState.Ready(result.value, current.catalogApps)
        is GuardianPolicyResult.Failure -> mutableState.value = current.copy(savingFeature = null, updateError = result.error)
      }
    }
  }
}
