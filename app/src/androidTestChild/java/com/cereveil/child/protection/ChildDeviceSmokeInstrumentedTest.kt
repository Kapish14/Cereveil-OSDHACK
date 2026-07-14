package com.cereveil.child.protection

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.cereveil.CereveilApplication
import com.cereveil.child.enrollment.ActiveScreenSafetyPolicy
import com.cereveil.child.enrollment.AndroidProtectionCapabilities
import com.cereveil.child.enrollment.ChildInstallationMetadata
import com.cereveil.child.enrollment.SafetyDetectorPolicy
import com.cereveil.child.enrollment.SafetySensitivity
import dev.convex.android.ConvexClientWithAuth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildDeviceSmokeInstrumentedTest {
  @Test
  fun enrolledChildAuthenticatesRealtimeAndReportsCompleteProtection() = runBlocking {
    val application = ApplicationProvider.getApplicationContext<CereveilApplication>()
    val status = AndroidProtectionCapabilities(application).currentSetupStatus()
    assertTrue("Protection capabilities are missing: ${status.missingSettings().joinToString()}", status.complete)

    @Suppress("UNCHECKED_CAST")
    val convex = application.convex as ConvexClientWithAuth<String>
    assertTrue("Child custom-JWT login failed", convex.loginFromCache().isSuccess)
    val realtime = withTimeout(15_000) {
      convex.subscribe<JsonElement>(
        "modules/remoteAudio/child:getRemoteAudioState",
        mapOf("childInstallationId" to ChildInstallationMetadata(application).installationId()),
      ).first()
    }
    assertTrue("Child protected realtime query failed", realtime.isSuccess)
  }

  @Test
  fun productionSafetyModelsInitializeAndRunOnDevice() {
    val application = ApplicationProvider.getApplicationContext<CereveilApplication>()
    val enabled = SafetyDetectorPolicy(true, setOf("com.android.chrome"), SafetySensitivity.Standard)
    try {
      assertTrue(
        "Production safety models failed to initialize",
        ChildSafetyModels.configure(application, ActiveScreenSafetyPolicy(enabled, enabled)),
      )
      val scam = ChildSafetyModels.classifyScam(
        "Your OTP is 123456, send it now to receive payment",
        SafetySensitivity.Standard,
      )
      assertTrue("Scam confidence was not finite", scam.confidence.isFinite())
      val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
      try {
        val nsfw = ChildSafetyModels.classifyNsfw(bitmap, SafetySensitivity.Standard)
        assertTrue("NSFW confidence was not finite", nsfw.confidence.isFinite())
      } finally {
        bitmap.recycle()
      }
    } finally {
      ChildSafetyModels.release()
    }
  }
}
