package com.cereveil.child.protection

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
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
      // Keep initialized sessions warm across policy toggles. Re-parsing the fraud tokenizer and
      // rebuilding ONNX sessions made every off -> on cycle take several seconds. Detection is
      // still governed by the committed policy; sessions are released when supervision stops.
      Log.i(TAG, "Safety models configured: scam=${scamClassifier != null}, nsfw=${nsfwClassifier != null}")
      true
    } catch (error: Exception) {
      createdScam?.close()
      createdNsfw?.close()
      Log.e(TAG, "Safety model initialization failed", error)
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

  private const val TAG = "CereveilSafetyModels"
}
