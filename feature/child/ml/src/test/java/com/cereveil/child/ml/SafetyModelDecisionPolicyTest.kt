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
  fun scamSensitivityDoesNotOverrideEnfoldWinningLabelDecision() {
    val probabilities = floatArrayOf(.94f, .02f, .012f, .008f, .006f, .005f, .004f, .005f)
    assertFalse(SafetyModelDecisionPolicy.scamIsPositive(probabilities, ModelSensitivity.Standard))
    assertFalse(SafetyModelDecisionPolicy.scamIsPositive(probabilities, ModelSensitivity.Higher))
    assertFalse(SafetyModelDecisionPolicy.scamIsPositive(probabilities, ModelSensitivity.Lower))
  }

  @Test
  fun standardNsfwPreservesReviveSensitivitySixty() {
    assertEquals(.40f, SafetyModelDecisionPolicy.nsfwThreshold(ModelSensitivity.Standard))
    assertTrue(SafetyModelDecisionPolicy.nsfwThreshold(ModelSensitivity.Higher) < .40f)
    assertTrue(SafetyModelDecisionPolicy.nsfwThreshold(ModelSensitivity.Lower) > .40f)
  }
}
