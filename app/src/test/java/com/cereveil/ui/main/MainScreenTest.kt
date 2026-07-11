package com.cereveil.ui.main

import junit.framework.TestCase.assertEquals
import org.junit.Test

class MainScreenTest {
  @Test
  fun roleDisplayName_mapsKnownRoles() {
    assertEquals("Guardian Mode", roleDisplayName("guardian"))
    assertEquals("Child Mode", roleDisplayName("child"))
  }

  @Test
  fun roleDisplayName_handlesUnknownRoles() {
    assertEquals("Unknown Mode", roleDisplayName("other"))
  }
}
