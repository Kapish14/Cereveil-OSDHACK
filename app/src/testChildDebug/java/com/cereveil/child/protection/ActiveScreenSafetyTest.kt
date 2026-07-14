package com.cereveil.child.protection

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ActiveScreenSafetyTest {
  @Test
  fun incidentMemorySuppressesOnlyTheSameDetectorItemAndPackageForTenMinutes() {
    var now = 1_000L
    val memory = SafetyIncidentMemory(now = { now })

    assertTrue(memory.shouldCreate("scam_text", "com.example.messages", "same text"))
    assertFalse(memory.shouldCreate("scam_text", "com.example.messages", "same text"))
    assertTrue(memory.shouldCreate("scam_text", "com.example.other", "same text"))
    assertTrue(memory.shouldCreate("nsfw_screen", "com.example.messages", "same text"))
    now += 10 * 60 * 1000 + 1
    assertTrue(memory.shouldCreate("scam_text", "com.example.messages", "same text"))
  }

  @Test
  fun disablingOneDetectorClearsOnlyThatDetectorsFingerprints() {
    val memory = SafetyIncidentMemory()
    assertTrue(memory.shouldCreate("scam_text", "com.example.messages", "same item"))
    assertTrue(memory.shouldCreate("nsfw_screen", "com.example.messages", "same item"))

    memory.clear("scam_text")

    assertTrue(memory.shouldCreate("scam_text", "com.example.messages", "same item"))
    assertFalse(memory.shouldCreate("nsfw_screen", "com.example.messages", "same item"))
  }
}
