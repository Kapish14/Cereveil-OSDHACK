package com.cereveil.guardian.childprofile

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class GuardianChildProfileSetupContentTest {
  @Test
  fun accountLogoutIsAvailableFromEveryStableTopLevelState() {
    assertFalse(guardianTopLevelLogoutVisible(GuardianChildProfileSetupState.Loading))
    assertTrue(guardianTopLevelLogoutVisible(GuardianChildProfileSetupState.FirstChildForm))
    assertTrue(
      guardianTopLevelLogoutVisible(
        GuardianChildProfileSetupState.ProfileSetup(emptyList()),
      ),
    )
    assertTrue(
      guardianTopLevelLogoutVisible(
        GuardianChildProfileSetupState.FormError(GuardianChildProfileError.ValidationFailed),
      ),
    )
    assertTrue(
      guardianTopLevelLogoutVisible(
        GuardianChildProfileSetupState.LoadError(GuardianChildProfileError.Retryable),
      ),
    )
  }
}
