package com.cereveil.child.protection

import com.cereveil.child.enrollment.SafetyDetectorPolicy
import com.cereveil.child.enrollment.SafetySensitivity
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ActiveScreenSafetyTest {
  private val policy = SafetyDetectorPolicy(
    enabled = true,
    monitoredPackageNames = setOf("com.example.messages"),
    sensitivity = SafetySensitivity.Standard,
  )

  @Test
  fun scamCandidatesAreVisibleIndividualNonEditableMessageText() {
    assertTrue(ScamCandidatePolicy.accepts(
      ScamNode("Your OTP is 123456, send it now to receive payment", visible = true),
      "com.example.messages",
      policy,
    ))
    assertFalse(ScamCandidatePolicy.accepts(
      ScamNode("Draft payment message long enough to otherwise classify", visible = true, editable = true),
      "com.example.messages",
      policy,
    ))
    assertFalse(ScamCandidatePolicy.accepts(
      ScamNode("Your OTP is 123456, send it now to receive payment", visible = true),
      "com.example.other",
      policy,
    ))
    assertFalse(ScamCandidatePolicy.accepts(ScamNode("10:42 PM", visible = true), "com.example.messages", policy))
  }

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
