package com.cereveil.guardian.policy

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class GuardianPolicyViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  @Before fun setUp() = Dispatchers.setMain(dispatcher)
  @After fun tearDown() = Dispatchers.resetMain()

  @Test
  fun saveIdentityIsStableForTheSameIntentAcrossProcessDeath() {
    val first = policyOperationId("child-1", 3, PolicyFeature.SafeBrowsing, true, true)
    val retried = policyOperationId("child-1", 3, PolicyFeature.SafeBrowsing, true, true)
    val laterIntent = policyOperationId("child-1", 4, PolicyFeature.SafeBrowsing, true, true)

    assertEquals(first, retried)
    junit.framework.TestCase.assertTrue(first != laterIntent)
  }

  @Test
  fun showsOptimisticProgressThenUsesAuthoritativeResult() = runTest(dispatcher) {
    val client = FakePolicyClient(policyState(applied = false, desired = false))
    val model = GuardianPolicyViewModel("child-1", client)
    advanceUntilIdle()

    model.update(PolicyFeature.ScreenTime, true)
    assertEquals(
      PolicyFeature.ScreenTime,
      (model.state.value as GuardianPolicyUiState.Ready).savingFeature,
    )
    advanceUntilIdle()

    val ready = model.state.value as GuardianPolicyUiState.Ready
    assertEquals(true, ready.policy.desired.screenTimeEnabled)
    assertEquals(false, ready.policy.applied?.screenTimeEnabled)
    assertEquals(PolicyApplicationStatus.Pending, ready.policy.status)
  }

  @Test
  fun reconstructsPermanentFailureFromAuthoritativeState() = runTest(dispatcher) {
    val client = FakePolicyClient(policyState(applied = false, desired = true).copy(
      status = PolicyApplicationStatus.Failed,
      failureReason = PolicyFailureReason.ActivationFailed,
    ))
    val model = GuardianPolicyViewModel("child-1", client)
    advanceUntilIdle()

    val ready = model.state.value as GuardianPolicyUiState.Ready
    assertEquals(PolicyApplicationStatus.Failed, ready.policy.status)
    assertEquals(PolicyFailureReason.ActivationFailed, ready.policy.failureReason)
  }
}

private class FakePolicyClient(initial: GuardianPolicyState) : GuardianPolicyClient {
  private val states = MutableSharedFlow<GuardianPolicyResult<GuardianPolicyState>>(replay = 1).apply {
    tryEmit(GuardianPolicyResult.Success(initial))
  }
  override fun observe(childProfileId: String) = states
  override fun observeCatalog(childProfileId: String) = MutableSharedFlow<GuardianPolicyResult<List<GuardianSelectableApp>>>(replay = 1).apply {
    tryEmit(GuardianPolicyResult.Success(emptyList()))
  }
  override suspend fun update(
    childProfileId: String,
    expectedVersion: Int,
    operationId: String,
    feature: PolicyFeature,
    enabled: Boolean,
    safeSearchEnabled: Boolean,
  ): GuardianPolicyResult<GuardianPolicyState> {
    val current = (states.replayCache.single() as GuardianPolicyResult.Success).value
    val desired = current.desired.copy(screenTimeEnabled = enabled, version = current.desired.version + 1)
    return GuardianPolicyResult.Success(current.copy(desired = desired, status = PolicyApplicationStatus.Pending))
  }
  override suspend fun updateSafety(
    childProfileId: String,
    expectedVersion: Int,
    operationId: String,
    scamText: GuardianSafetyDetectorPolicy,
    nsfwScreen: GuardianSafetyDetectorPolicy,
  ): GuardianPolicyResult<GuardianPolicyState> {
    val current = (states.replayCache.single() as GuardianPolicyResult.Success).value
    return GuardianPolicyResult.Success(current.copy(desired = current.desired.copy(
      version = current.desired.version + 1,
      schemaVersion = 3,
      scamTextSafety = scamText,
      nsfwScreenSafety = nsfwScreen,
    ), status = PolicyApplicationStatus.Pending))
  }
}

private fun policyState(applied: Boolean, desired: Boolean): GuardianPolicyState {
  fun policy(version: Int, screenTime: Boolean) = GuardianPolicy(
    version = version,
    schemaVersion = 2,
    appBlockingEnabled = false,
    safeBrowsingEnabled = false,
    safeSearchEnabled = false,
    scamTextSafety = GuardianSafetyDetectorPolicy(false, emptySet(), GuardianSafetySensitivity.Standard),
    nsfwScreenSafety = GuardianSafetyDetectorPolicy(false, emptySet(), GuardianSafetySensitivity.Standard),
    locationSharingEnabled = false,
    screenTimeEnabled = screenTime,
  )
  return GuardianPolicyState(
    desired = policy(if (desired == applied) 1 else 2, desired),
    applied = policy(1, applied),
    status = if (desired == applied) PolicyApplicationStatus.Applied else PolicyApplicationStatus.Pending,
    failureReason = null,
  )
}
