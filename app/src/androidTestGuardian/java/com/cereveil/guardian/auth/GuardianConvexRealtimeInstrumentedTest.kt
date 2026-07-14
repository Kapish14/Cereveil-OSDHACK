package com.cereveil.guardian.auth

import androidx.test.core.app.ApplicationProvider
import com.cereveil.CereveilApplication
import dev.convex.android.AuthState
import dev.convex.android.ConvexClientWithAuth
import dev.convex.android.ConvexError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianConvexRealtimeInstrumentedTest {
  @Test
  fun restoredClerkSessionAuthenticatesRealtimeGuardianQuery() = runBlocking {
    val application = ApplicationProvider.getApplicationContext<CereveilApplication>()
    @Suppress("UNCHECKED_CAST")
    val convex = application.convex as ConvexClientWithAuth<String>
    val installationId = requireNotNull(
      SharedPreferencesGuardianInstallationIdProvider(application).getInstallationId(),
    )

    withTimeout(15_000) {
      convex.authState.first { it is AuthState.Authenticated }
    }
    val result = withTimeout(15_000) {
      convex.subscribe<List<ChildProfileResult>>(
        "modules/childProfiles/public:listChildProfiles",
        mapOf("guardianInstallationId" to installationId),
      ).first()
    }

    assertTrue(
      "Authenticated realtime query failed: ${result.exceptionOrNull()?.javaClass?.simpleName}",
      result.isSuccess,
    )
    val childProfile = requireNotNull(result.getOrThrow().firstOrNull())
    val enrollment = withTimeout(15_000) {
      convex.subscribe<EnrollmentSummaryResult>(
        "modules/deviceIdentity/guardian:getEnrollmentSummary",
        mapOf(
          "guardianInstallationId" to installationId,
          "childProfileId" to childProfile.childProfileId,
        ),
      ).first()
    }
    assertTrue(
      "Authenticated enrollment subscription failed: ${enrollment.exceptionOrNull()?.javaClass?.simpleName}",
      enrollment.isSuccess && enrollment.getOrThrow().enrollmentStatus == "active",
    )

    val commonArgs = mapOf(
      "guardianInstallationId" to installationId,
      "childProfileId" to childProfile.childProfileId,
    )
    val protectedQueries = listOf(
      "policy" to "modules/policies/guardian:getPolicyState",
      "catalog" to "modules/appCatalog/guardian:getLatestAppCatalog",
      "access" to "modules/access/guardian:listPendingAccessRequests",
      "location" to "modules/location/guardian:getLatestLocation",
      "safety" to "modules/safetyAlerts/guardian:listSafetyAlerts",
      "remote_audio" to "modules/remoteAudio/guardian:getRemoteAudioState",
    )
    protectedQueries.forEach { (label, function) ->
      val query = withTimeout(15_000) {
        convex.subscribe<JsonElement>(function, commonArgs).first()
      }
      assertTrue(
        "$label protected query failed: ${query.exceptionOrNull()?.javaClass?.simpleName}",
        query.isSuccess,
      )
    }

    val screenTime = runCatching {
      withTimeout(15_000) {
        convex.mutation<JsonElement>(
          "modules/screenTime/guardian:getOrRequestScreenTime",
          commonArgs,
        )
      }
    }
    assertTrue(
      "screen_time protected mutation failed: ${screenTime.exceptionOrNull()?.javaClass?.simpleName}",
      screenTime.isSuccess || screenTime.exceptionOrNull() is ConvexError,
    )
  }

}

@Serializable
private data class ChildProfileResult(val childProfileId: String)

@Serializable
private data class EnrollmentSummaryResult(val enrollmentStatus: String)
