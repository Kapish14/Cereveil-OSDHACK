package com.cereveil.guardian.childprofile

import junit.framework.TestCase.assertEquals
import org.junit.Test

class ConvexGuardianChildProfileClientTest {
  @Test
  fun mapConvexErrorDataToGuardianChildProfileError_mapsSafeAppErrorCodes() {
    assertEquals(
      GuardianChildProfileError.Unauthenticated,
      mapConvexErrorDataToGuardianChildProfileError("""{"code":"UNAUTHENTICATED"}"""),
    )
    assertEquals(
      GuardianChildProfileError.ValidationFailed,
      mapConvexErrorDataToGuardianChildProfileError("""{"code":"VALIDATION_FAILED"}"""),
    )
    assertEquals(
      GuardianChildProfileError.AgeOutOfRange,
      mapConvexErrorDataToGuardianChildProfileError("""{"code":"CHILD_AGE_OUT_OF_RANGE"}"""),
    )
  }

  @Test
  fun mapConvexErrorDataToGuardianChildProfileError_mapsAccountLifecycleErrorsAsUnavailable() {
    for (code in listOf("ACCOUNT_DISABLED", "ACCOUNT_DELETING", "HOUSEHOLD_DELETING")) {
      assertEquals(
        GuardianChildProfileError.AccountUnavailable,
        mapConvexErrorDataToGuardianChildProfileError("""{"code":"$code"}"""),
      )
    }
  }

  @Test
  fun mapConvexErrorDataToGuardianChildProfileError_treatsUnknownPayloadsAsRetryable() {
    assertEquals(
      GuardianChildProfileError.Retryable,
      mapConvexErrorDataToGuardianChildProfileError("""{"message":"raw backend payload"}"""),
    )
    assertEquals(
      GuardianChildProfileError.Retryable,
      mapConvexErrorDataToGuardianChildProfileError("not json"),
    )
  }
}
