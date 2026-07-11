package com.cereveil.guardian.auth

import junit.framework.TestCase.assertEquals
import org.junit.Test

class ConvexGuardianAuthClientTest {
  @Test
  fun mapConvexErrorDataToGuardianBootstrapError_mapsSafeAppErrorCodes() {
    assertEquals(
      GuardianBootstrapError.Unauthenticated,
      mapConvexErrorDataToGuardianBootstrapError("""{"code":"UNAUTHENTICATED","message":"secret"}"""),
    )
    assertEquals(
      GuardianBootstrapError.DeviceRevoked,
      mapConvexErrorDataToGuardianBootstrapError("""{"code":"DEVICE_REVOKED","message":"device id"}"""),
    )
    assertEquals(
      GuardianBootstrapError.DeviceLimitReached,
      mapConvexErrorDataToGuardianBootstrapError("""{"code":"DEVICE_LIMIT_REACHED","message":"account id"}"""),
    )
  }

  @Test
  fun mapConvexErrorDataToGuardianBootstrapError_treatsUnknownPayloadsAsRetryable() {
    assertEquals(
      GuardianBootstrapError.Retryable,
      mapConvexErrorDataToGuardianBootstrapError("""{"message":"raw backend payload"}"""),
    )
    assertEquals(GuardianBootstrapError.Retryable, mapConvexErrorDataToGuardianBootstrapError("not json"))
  }
}
