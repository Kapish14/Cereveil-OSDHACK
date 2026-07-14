package com.cereveil.guardian.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GuardianEnrollmentViewModel(
  private val childProfileId: String,
  private val client: GuardianEnrollmentClient,
) : ViewModel() {
  private val mutableState = MutableStateFlow<GuardianEnrollmentUiState>(GuardianEnrollmentUiState.Loading)
  val state: StateFlow<GuardianEnrollmentUiState> = mutableState.asStateFlow()

  init {
    observeEnrollment()
    regenerate()
  }

  fun regenerate() {
    viewModelScope.launch {
      mutableState.value = GuardianEnrollmentUiState.Loading
      val nextState = when (val result = client.createCode(childProfileId)) {
        is GuardianEnrollmentResult.Success -> GuardianEnrollmentUiState.ShowingCode(result.value)
        is GuardianEnrollmentResult.Failure -> GuardianEnrollmentUiState.Failure(result.error)
      }
      if (mutableState.value !is GuardianEnrollmentUiState.Enrolled) mutableState.value = nextState
    }
  }

  fun cancel() {
    val code = (mutableState.value as? GuardianEnrollmentUiState.ShowingCode)?.code ?: return
    viewModelScope.launch {
      val nextState = when (val result = client.cancelCode(code.enrollmentCodeId)) {
        is GuardianEnrollmentResult.Success -> GuardianEnrollmentUiState.Cancelled
        is GuardianEnrollmentResult.Failure -> GuardianEnrollmentUiState.Failure(result.error)
      }
      if (mutableState.value !is GuardianEnrollmentUiState.Enrolled) mutableState.value = nextState
    }
  }

  private fun observeEnrollment() {
    viewModelScope.launch {
      client.observeSummary(childProfileId).collect { result ->
        if (result is GuardianEnrollmentResult.Success && result.value.enrollmentActive) {
          mutableState.value = GuardianEnrollmentUiState.Enrolled(
            policyStatus = result.value.policyStatus,
            protectionHealthStatus = result.value.protectionHealthStatus,
            connectivityStatus = result.value.connectivityStatus,
          )
        }
      }
    }
  }
}

class GuardianDeviceReplacementViewModel(
  private val childProfileId: String,
  private val client: GuardianEnrollmentClient,
) : ViewModel() {
  private val mutableState =
    MutableStateFlow<GuardianDeviceReplacementUiState>(GuardianDeviceReplacementUiState.Confirming)
  val state: StateFlow<GuardianDeviceReplacementUiState> = mutableState.asStateFlow()

  fun replace() {
    if (mutableState.value == GuardianDeviceReplacementUiState.Replacing) return
    viewModelScope.launch {
      mutableState.value = GuardianDeviceReplacementUiState.Replacing
      mutableState.value = when (val result = client.replaceChildDevice(childProfileId)) {
        is GuardianEnrollmentResult.Success -> GuardianDeviceReplacementUiState.Replaced
        is GuardianEnrollmentResult.Failure -> GuardianDeviceReplacementUiState.Failure(result.error)
      }
    }
  }

  fun retry() {
    mutableState.value = GuardianDeviceReplacementUiState.Confirming
  }
}
