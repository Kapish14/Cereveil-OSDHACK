package com.cereveil.guardian.enrollment

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuardianEnrollmentViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun showsQrThenStopsPresentingItWhenActiveEnrollmentAppears() = runTest(dispatcher) {
    val client = FakeGuardianEnrollmentClient()
    val model = GuardianEnrollmentViewModel("child-1", client)
    advanceUntilIdle()

    assertEquals(GuardianEnrollmentUiState.ShowingCode(client.code), model.state.value)

    client.summaries.emit(
      GuardianEnrollmentResult.Success(
        GuardianEnrollmentSummary(
          enrollmentActive = true,
          policyStatus = GuardianPolicyStatus.Pending,
          protectionHealthStatus = GuardianProtectionHealthStatus.Pending,
          serverNow = 2,
        )
      )
    )
    advanceUntilIdle()

    assertEquals(
      GuardianEnrollmentUiState.Enrolled(
        GuardianPolicyStatus.Pending,
        GuardianProtectionHealthStatus.Pending,
      ),
      model.state.value,
    )
  }

  @Test
  fun returningToCancelledSetupCreatesAFreshCode() = runTest(dispatcher) {
    val client = FakeGuardianEnrollmentClient()
    val model = GuardianEnrollmentViewModel("child-1", client)
    advanceUntilIdle()

    model.cancel()
    advanceUntilIdle()
    assertEquals(GuardianEnrollmentUiState.Cancelled, model.state.value)

    model.resumeSetup()
    advanceUntilIdle()

    assertEquals(GuardianEnrollmentUiState.ShowingCode(client.code), model.state.value)
  }

  @Test
  fun replacementRevokesTheStaleDeviceBeforeStartingFreshEnrollment() = runTest(dispatcher) {
    val client = FakeGuardianEnrollmentClient()
    val model = GuardianDeviceReplacementViewModel("child-1", client)

    assertEquals(GuardianDeviceReplacementUiState.Confirming, model.state.value)

    model.replace()
    advanceUntilIdle()

    assertEquals(GuardianDeviceReplacementUiState.Replaced, model.state.value)
    assertEquals(listOf("child-1"), client.replacementRequests)
  }
}

private class FakeGuardianEnrollmentClient : GuardianEnrollmentClient {
  val code = GuardianEnrollmentCode(
    enrollmentCodeId = "code-1",
    qrPayload = """{"type":"cereveil.child-enrollment","version":1,"code":"AAAAAAAAAAAAAAAAAAAAAA"}""",
    expiresAt = 301_000,
    serverNow = 1_000,
  )
  val summaries = MutableSharedFlow<GuardianEnrollmentResult<GuardianEnrollmentSummary>>(replay = 1).apply {
    tryEmit(
      GuardianEnrollmentResult.Success(
        GuardianEnrollmentSummary(
          enrollmentActive = false,
          policyStatus = GuardianPolicyStatus.NotApplicable,
          protectionHealthStatus = GuardianProtectionHealthStatus.NotApplicable,
          serverNow = 1,
        )
      )
    )
  }
  val replacementRequests = mutableListOf<String>()

  override suspend fun createCode(childProfileId: String) = GuardianEnrollmentResult.Success(code)
  override suspend fun cancelCode(enrollmentCodeId: String) = GuardianEnrollmentResult.Success(Unit)
  override suspend fun replaceChildDevice(childProfileId: String): GuardianEnrollmentResult<Unit> {
    replacementRequests += childProfileId
    return GuardianEnrollmentResult.Success(Unit)
  }
  override fun observeSummary(childProfileId: String): Flow<GuardianEnrollmentResult<GuardianEnrollmentSummary>> = summaries
}
