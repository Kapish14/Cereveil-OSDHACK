package com.cereveil.child.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyModelDecisionPolicyTest {
  @Test
  fun standardScamPreservesEnfoldTopClassGrouping() {
    assertFalse(SafetyModelDecisionPolicy.scamIsPositive(
      floatArrayOf(.60f, .05f, .35f, 0f, 0f, 0f, 0f, 0f), ModelSensitivity.Standard,
    ))
    assertTrue(SafetyModelDecisionPolicy.scamIsPositive(
      floatArrayOf(.30f, .05f, .65f, 0f, 0f, 0f, 0f, 0f), ModelSensitivity.Standard,
    ))
  }

  @Test
  fun standardNsfwPreservesReviveSensitivitySixty() {
    assertEquals(.40f, SafetyModelDecisionPolicy.nsfwThreshold(ModelSensitivity.Standard))
    assertTrue(SafetyModelDecisionPolicy.nsfwThreshold(ModelSensitivity.Higher) < .40f)
    assertTrue(SafetyModelDecisionPolicy.nsfwThreshold(ModelSensitivity.Lower) > .40f)
  }
}
