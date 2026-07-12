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

  init { observe() }

  fun observe() {
    observation?.cancel()
    observation = viewModelScope.launch {
      client.observe(childProfileId).collect { result ->
        mutableState.value = when (result) {
          is GuardianPolicyResult.Success -> GuardianPolicyUiState.Ready(result.value)
          is GuardianPolicyResult.Failure -> GuardianPolicyUiState.Error(result.error)
        }
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
}
