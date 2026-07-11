package com.cereveil

import android.app.Application
import android.content.Context
import com.clerk.api.Clerk
import com.clerk.api.ClerkConfigurationOptions
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.session.GetTokenOptions
import com.cereveil.guardian.auth.GuardianAuthSessionProvider
import com.cereveil.guardian.auth.GuardianAuthState
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
