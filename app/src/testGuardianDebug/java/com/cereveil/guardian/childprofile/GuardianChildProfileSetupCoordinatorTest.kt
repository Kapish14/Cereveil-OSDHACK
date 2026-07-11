package com.cereveil.guardian.childprofile

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GuardianChildProfileSetupCoordinatorTest {
  @Test
  fun loadWithNoProfilesShowsFirstChildForm() = runTest {
    val harness = Harness(profiles = emptyList())

    assertEquals(GuardianChildProfileSetupState.FirstChildForm, harness.coordinator.load())
    assertEquals(1, harness.client.listCalls)
  }

  @Test
  fun submitCreatesChildProfileWithMinimalFactsAndShowsUnenrolledProfile() = runTest {
    val created = childProfileSummary("child-1", "Aarav")
    val harness = Harness(createResult = GuardianChildProfileResult.Success(created))

    assertEquals(
      GuardianChildProfileSetupState.ProfileSetup(listOf(created)),
      harness.coordinator.submit(displayName = " Aarav ", birthMonth = 7, birthYear = 2015),
    )

    assertEquals(CreateChildProfileRequest("Aarav", 7, 2015), harness.client.createRequests.single())
    assertEquals(1, harness.client.listCalls)
    assertFalse(harness.enrollmentStarted)
  }

  @Test
  fun invalidFormInputDoesNotCallBackend() = runTest {
    val harness = Harness()

    assertEquals(
      GuardianChildProfileSetupState.FormError(GuardianChildProfileError.ValidationFailed),
      harness.coordinator.submit(displayName = "", birthMonth = 7, birthYear = 2015),
    )
    assertEquals(
      GuardianChildProfileSetupState.FormError(GuardianChildProfileError.ValidationFailed),
      harness.coordinator.submit(displayName = "Aarav", birthMonth = 13, birthYear = 2015),
    )

    assertTrue(harness.client.createRequests.isEmpty())
  }

  @Test
  fun backendErrorsMapToStableSetupStates() = runTest {
    val harness =
      Harness(createResult = GuardianChildProfileResult.Failure(GuardianChildProfileError.AgeOutOfRange))

    assertEquals(
      GuardianChildProfileSetupState.FormError(GuardianChildProfileError.AgeOutOfRange),
      harness.coordinator.submit(displayName = "Aarav", birthMonth = 1, birthYear = 2021),
    )
  }

  private class Harness(
    profiles: List<ChildProfileSummary> = emptyList(),
    createResult: GuardianChildProfileResult = GuardianChildProfileResult.Success(
      childProfileSummary("child-1", "Aarav")
    ),
  ) {
    val client = FakeGuardianChildProfileClient(profiles, createResult)
    var enrollmentStarted = false
    val coordinator =
      GuardianChildProfileSetupCoordinator(
        client = client,
        onSetUpChildDevice = { enrollmentStarted = true },
      )
  }

  private class FakeGuardianChildProfileClient(
    private var profiles: List<ChildProfileSummary>,
    private val createResult: GuardianChildProfileResult,
  ) : GuardianChildProfileClient {
    var listCalls = 0
    val createRequests = mutableListOf<CreateChildProfileRequest>()

    override suspend fun createChildProfile(
      request: CreateChildProfileRequest
    ): GuardianChildProfileResult {
      createRequests += request
      return createResult.also {
        if (it is GuardianChildProfileResult.Success) {
          profiles = listOf(it.profile)
        }
      }
    }

    override suspend fun listChildProfiles(): GuardianChildProfileListResult {
      listCalls += 1
      return GuardianChildProfileListResult.Success(profiles)
    }
  }
}

private fun childProfileSummary(id: String, displayName: String) =
  ChildProfileSummary(
    childProfileId = id,
    displayName = displayName,
    birthMonth = 7,
    birthYear = 2015,
    status = ChildProfileStatus.Active,
    enrollmentStatus = ChildProfileEnrollmentStatus.Unenrolled,
    currentPolicyVersionId = "policy-1",
    currentPolicyVersion = 1,
  )
