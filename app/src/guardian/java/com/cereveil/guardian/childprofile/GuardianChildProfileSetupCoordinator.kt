package com.cereveil.guardian.childprofile

import kotlinx.coroutines.flow.map

class GuardianChildProfileSetupCoordinator(
  private val client: GuardianChildProfileClient,
  private val onSetUpChildDevice: () -> Unit = {},
) {
  fun observe() = client.observeChildProfiles().map { result ->
      when (result) {
        is GuardianChildProfileListResult.Success -> stateFor(result.profiles)
        is GuardianChildProfileListResult.Failure -> GuardianChildProfileSetupState.LoadError(result.error)
      }
  }
  suspend fun load(): GuardianChildProfileSetupState =
    when (val result = client.listChildProfiles()) {
      is GuardianChildProfileListResult.Success -> stateFor(result.profiles)
      is GuardianChildProfileListResult.Failure -> GuardianChildProfileSetupState.LoadError(result.error)
    }

  suspend fun submit(
    displayName: String,
    birthMonth: Int,
    birthYear: Int,
  ): GuardianChildProfileSetupState {
    val normalizedName = displayName.trim().replace(Regex("\\s+"), " ")
    if (normalizedName.isBlank() || birthMonth !in 1..12 || birthYear <= 0) {
      return GuardianChildProfileSetupState.FormError(GuardianChildProfileError.ValidationFailed)
    }

    return when (
      val result =
        client.createChildProfile(
          CreateChildProfileRequest(
            displayName = normalizedName,
            birthMonth = birthMonth,
            birthYear = birthYear,
          )
        )
    ) {
      is GuardianChildProfileResult.Success ->
        when (val listResult = client.listChildProfiles()) {
          is GuardianChildProfileListResult.Success -> stateFor(listResult.profiles)
          is GuardianChildProfileListResult.Failure ->
            GuardianChildProfileSetupState.ProfileSetup(listOf(result.profile))
        }
      is GuardianChildProfileResult.Failure -> GuardianChildProfileSetupState.FormError(result.error)
    }
  }

  fun setUpChildDevice() {
    onSetUpChildDevice()
  }

  private fun stateFor(profiles: List<ChildProfileSummary>): GuardianChildProfileSetupState =
    if (profiles.isEmpty()) {
      GuardianChildProfileSetupState.FirstChildForm
    } else {
      GuardianChildProfileSetupState.ProfileSetup(profiles)
    }
}
