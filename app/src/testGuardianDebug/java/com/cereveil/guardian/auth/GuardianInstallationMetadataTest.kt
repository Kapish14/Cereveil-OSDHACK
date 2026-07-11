package com.cereveil.guardian.auth

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import java.util.TimeZone
import org.junit.Test

class GuardianInstallationMetadataTest {
  @Test
  fun deviceLabel_usesBrandAndModel() {
    assertEquals("Google Pixel 8", buildDeviceLabel("Google", "Pixel 8"))
  }

  @Test
  fun deviceLabel_avoidsDuplicatedBrandAndModel() {
    assertEquals("Samsung Galaxy S24", buildDeviceLabel("Samsung", "Samsung Galaxy S24"))
  }

  @Test
  fun deviceLabel_ignoresBlankAndUnknownValues() {
    assertEquals("Pixel 8", buildDeviceLabel("", "Pixel 8"))
    assertEquals("Google", buildDeviceLabel("Google", "unknown"))
    assertNull(buildDeviceLabel("unknown", ""))
  }

  @Test
  fun appBuild_usesRoleVersionNameAndVersionCode() {
    assertEquals("guardian-1.2.3-42", buildAppBuild("guardian", "1.2.3", 42))
  }

  @Test
  fun timezoneId_usesIanaTimezoneId() {
    assertEquals("Asia/Kolkata", timezoneId(TimeZone.getTimeZone("Asia/Kolkata")))
  }
}
