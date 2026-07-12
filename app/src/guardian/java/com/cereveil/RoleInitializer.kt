package com.cereveil

import android.app.Application
import android.content.Context
import android.util.Log
import com.clerk.api.Clerk
import com.clerk.api.ClerkConfigurationOptions
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.session.GetTokenOptions
import com.cereveil.guardian.auth.GuardianAuthSessionProvider
import com.cereveil.guardian.auth.GuardianAuthState
import com.cereveil.guardian.messaging.GuardianPushTokenRegistrar
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dev.convex.android.AuthProvider
import dev.convex.android.ConvexClient
import dev.convex.android.ConvexClientWithAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object RoleInitializer {
  fun initialize(application: Application) {
    check(BuildConfig.CLERK_PUBLISHABLE_KEY.isNotBlank()) {
      "CLERK_PUBLISHABLE_KEY is required for Guardian Mode."
    }

    Clerk.initialize(
      context = application,
      publishableKey = BuildConfig.CLERK_PUBLISHABLE_KEY,
      options =
        ClerkConfigurationOptions(
          enableDebugMode = BuildConfig.DEBUG,
          proxyUrl = null,
          telemetryEnabled = true,
        ),
    )
    if (
      BuildConfig.FIREBASE_APPLICATION_ID.isNotBlank() &&
        BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
        BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
        BuildConfig.FIREBASE_GCM_SENDER_ID.isNotBlank()
    ) {
      if (FirebaseApp.getApps(application).isEmpty()) {
        FirebaseApp.initializeApp(
          application,
          FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FIREBASE_GCM_SENDER_ID)
            .build(),
        )
      }
      FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
        Log.i("CereveilFCM", "Guardian FCM token acquired; registering delivery state")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
          GuardianPushTokenRegistrar(application).register(token)
        }
      }.addOnFailureListener { error ->
        Log.e("CereveilFCM", "Guardian FCM token acquisition failed: ${error.javaClass.simpleName}: ${error.message}")
      }
    }
  }

  fun createConvexClient(application: Application): ConvexClient =
    ConvexClientWithAuth(
      BuildConfig.CONVEX_URL,
      ClerkConvexAuthProvider(),
      CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

  fun guardianAuthSessionProvider(): GuardianAuthSessionProvider = ClerkGuardianAuthSessionProvider()

  suspend fun guardianConvexToken(): String? =
    when (val result = Clerk.auth.getToken(GetTokenOptions(skipCache = true))) {
      is ClerkResult.Success -> result.value
      is ClerkResult.Failure -> null
    }
}

private class ClerkGuardianAuthSessionProvider : GuardianAuthSessionProvider {
  override fun states(): Flow<GuardianAuthState> =
    Clerk.isInitialized
      .combine(Clerk.initializationError) { initialized, error -> initialized to error }
      .combine(Clerk.userFlow) { readiness, user ->
        val (initialized, error) = readiness
        when {
          !initialized && error == null -> null
          user?.id != null -> GuardianAuthState.Authenticated(user.id)
          Clerk.session?.id != null -> GuardianAuthState.Authenticated(Clerk.session!!.id)
          else -> GuardianAuthState.Unauthenticated
        }
      }
      .filterNotNull()

  override suspend fun currentState(): GuardianAuthState {
    val clerkReady =
      if (Clerk.isInitialized.value) {
        true
      } else {
        Clerk.isInitialized
          .combine(Clerk.initializationError) { initialized, error -> initialized to error }
          .first { (initialized, error) -> initialized || error != null }
          .first
      }

    if (!clerkReady) {
      return GuardianAuthState.Unauthenticated
    }

    if (!Clerk.isSignedIn) {
      return GuardianAuthState.Unauthenticated
    }

    val authSessionKey = Clerk.user?.id ?: Clerk.session?.id ?: return GuardianAuthState.Unauthenticated
    return GuardianAuthState.Authenticated(authSessionKey)
  }
}

private class ClerkConvexAuthProvider : AuthProvider<String> {
  private var needsFreshToken = true

  override suspend fun login(context: Context, onIdToken: (String?) -> Unit): Result<String> =
    fetchToken(onIdToken)

  override suspend fun loginFromCache(onIdToken: (String?) -> Unit): Result<String> =
    fetchToken(onIdToken)

  override suspend fun logout(context: Context): Result<Void?> = Result.success(null)

  override fun extractIdToken(authResult: String): String = authResult

  private suspend fun fetchToken(onIdToken: (String?) -> Unit): Result<String> =
    when (
      val result = Clerk.auth.getToken(GetTokenOptions(skipCache = needsFreshToken))
    ) {
      is ClerkResult.Success -> {
        val token = result.value
        needsFreshToken = false
        Result.success(token)
      }
      is ClerkResult.Failure -> Result.failure(result.throwable ?: IllegalStateException("Clerk token unavailable."))
    }
}
