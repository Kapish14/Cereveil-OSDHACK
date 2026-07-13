package com.cereveil.child.protection

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.cereveil.child.enrollment.ActiveScreenSafetyPolicy
import com.cereveil.child.enrollment.SafetySensitivity
import com.cereveil.child.ml.ModelSensitivity
import com.cereveil.child.ml.NsfwClassification
import com.cereveil.child.ml.OnDeviceNsfwImageClassifier
import com.cereveil.child.ml.OnDeviceScamTextClassifier
import com.cereveil.child.ml.ScamClassification

object ChildSafetyModels {
  private var scamClassifier: OnDeviceScamTextClassifier? = null
  private var nsfwClassifier: OnDeviceNsfwImageClassifier? = null

  @Synchronized
  fun configure(context: Context, policy: ActiveScreenSafetyPolicy): Boolean {
    val existingScam = scamClassifier
    val existingNsfw = nsfwClassifier
    var createdScam: OnDeviceScamTextClassifier? = null
    var createdNsfw: OnDeviceNsfwImageClassifier? = null
    return try {
      if (policy.scamText.enabled && existingScam == null) {
        createdScam = OnDeviceScamTextClassifier(context).also {
          it.initialize()
          it.classify("This is a harmless model self check message", ModelSensitivity.Standard)
        }
      }
      if (policy.nsfwScreen.enabled) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "NSFW screenshots require Android 11" }
        if (existingNsfw == null) {
          createdNsfw = OnDeviceNsfwImageClassifier(context).also {
            it.initialize()
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            try { it.classify(bitmap, ModelSensitivity.Standard) } finally { bitmap.recycle() }
          }
        }
      }
      if (createdScam != null) scamClassifier = createdScam
      if (createdNsfw != null) nsfwClassifier = createdNsfw
      if (!policy.scamText.enabled) { scamClassifier?.close(); scamClassifier = null }
      if (!policy.nsfwScreen.enabled) { nsfwClassifier?.close(); nsfwClassifier = null }
      true
    } catch (_: Exception) {
      createdScam?.close()
      createdNsfw?.close()
      false
    }
  }

  @Synchronized
  fun classifyScam(text: String, sensitivity: SafetySensitivity): ScamClassification =
    checkNotNull(scamClassifier).classify(text, sensitivity.modelValue())

  @Synchronized
  fun classifyNsfw(bitmap: Bitmap, sensitivity: SafetySensitivity): NsfwClassification =
    checkNotNull(nsfwClassifier).classify(bitmap, sensitivity.modelValue())

  @Synchronized
  fun release() {
    scamClassifier?.close(); scamClassifier = null
    nsfwClassifier?.close(); nsfwClassifier = null
  }

  private fun SafetySensitivity.modelValue() = when (this) {
    SafetySensitivity.Lower -> ModelSensitivity.Lower
    SafetySensitivity.Standard -> ModelSensitivity.Standard
    SafetySensitivity.Higher -> ModelSensitivity.Higher
  }
}
