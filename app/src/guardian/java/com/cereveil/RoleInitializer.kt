package com.cereveil

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.clerk.api.Clerk
import com.clerk.api.ClerkConfigurationOptions
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.session.GetTokenOptions
import com.clerk.api.session.Session
import com.clerk.api.session.Session.SessionStatus
import com.cereveil.guardian.auth.GuardianAuthSessionProvider
import com.cereveil.guardian.auth.GuardianAuthState
import com.cereveil.guardian.auth.guardianAuthSessionKey
import com.cereveil.guardian.auth.resolveGuardianAuthState
import com.cereveil.guardian.auth.stableAuthStates
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

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
          enableDebugMode = false,
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

  fun createConvexClient(application: Application): ConvexClient {
    val provider = ClerkConvexAuthProvider()
    return ConvexClientWithAuth(
      BuildConfig.CONVEX_URL,
      provider,
      CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ).also { provider.bind(it, application) }
  }

  fun guardianAuthSessionProvider(context: Context): GuardianAuthSessionProvider =
    ClerkGuardianAuthSessionProvider(context)

  suspend fun guardianConvexToken(): String? =
    when (val result = Clerk.auth.getToken(GetTokenOptions(template = "convex", skipCache = true))) {
      is ClerkResult.Success -> result.value
      is ClerkResult.Failure -> null
    }

  suspend fun signOutGuardian(): Boolean =
    when (Clerk.auth.signOut()) {
      is ClerkResult.Success -> true
      is ClerkResult.Failure -> false
    }
}

private class ClerkGuardianAuthSessionProvider(context: Context) : GuardianAuthSessionProvider {
  private val context = context.applicationContext

  override fun states(): Flow<GuardianAuthState> =
    Clerk.isInitialized
      .combine(Clerk.initializationError) { initialized, error -> initialized to error }
      .combine(
        Clerk.userFlow.combine(Clerk.sessionFlow) { user, session ->
          guardianAuthSessionKey(
            clerkUserId = user?.id,
            clerkSessionId = session?.id,
            sessionIsActive = session?.status == SessionStatus.ACTIVE,
          )
        },
      ) { readiness, authSessionKey -> readiness to authSessionKey }
      .combine(context.validatedInternetFlow()) { (readiness, authSessionKey), internetAvailable ->
        val (initialized, error) = readiness
        resolveGuardianAuthState(
          clerkInitialized = initialized,
          clerkInitializationFailed = error != null,
          authSessionKey = authSessionKey,
          internetAvailable = internetAvailable,
        )
      }
      .filterNotNull()
      .stableAuthStates()

  override suspend fun currentState(): GuardianAuthState {
    val immediate = resolveGuardianAuthState(
      clerkInitialized = Clerk.isInitialized.value,
      clerkInitializationFailed = Clerk.initializationError.value != null,
      authSessionKey = guardianAuthSessionKey(
        clerkUserId = Clerk.user?.id,
        clerkSessionId = Clerk.session?.id,
        sessionIsActive = Clerk.session?.status == SessionStatus.ACTIVE,
      ),
      internetAvailable = context.hasValidatedInternet(),
    )
    if (immediate != null) return immediate

    val readiness = Clerk.isInitialized
      .combine(Clerk.initializationError) { initialized, error -> initialized to error }
      .first { (initialized, error) -> initialized || error != null }
    return requireNotNull(resolveGuardianAuthState(
      clerkInitialized = readiness.first,
      clerkInitializationFailed = readiness.second != null,
      authSessionKey = guardianAuthSessionKey(
        clerkUserId = Clerk.user?.id,
        clerkSessionId = Clerk.session?.id,
        sessionIsActive = Clerk.session?.status == SessionStatus.ACTIVE,
      ),
      internetAvailable = context.hasValidatedInternet(),
    ))
  }
}

private fun Context.hasValidatedInternet(): Boolean {
  val connectivity = getSystemService(ConnectivityManager::class.java)
  val network = connectivity.activeNetwork ?: return false
  val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
  return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun Context.validatedInternetFlow(): Flow<Boolean> = callbackFlow {
  val connectivity = getSystemService(ConnectivityManager::class.java)
  fun publish() { trySend(hasValidatedInternet()) }
  val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) = publish()
    override fun onLost(network: Network) = publish()
    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()
  }
  publish()
  connectivity.registerDefaultNetworkCallback(callback)
  awaitClose { connectivity.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()

private class ClerkConvexAuthProvider : AuthProvider<String> {
  private var client: WeakReference<ConvexClientWithAuth<String>>? = null
  private var applicationContext: Context? = null
  private var needsFreshToken = true

  fun bind(client: ConvexClientWithAuth<String>, context: Context) {
    this.client = WeakReference(client)
    applicationContext = context.applicationContext
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
      var previousSession: Session? = null
      Clerk.sessionFlow.collect { session ->
        val convexClient = this@ClerkConvexAuthProvider.client?.get() ?: return@collect
        if (
          session?.status == SessionStatus.ACTIVE &&
            (previousSession?.status != SessionStatus.ACTIVE || previousSession?.id != session.id)
        ) {
          convexClient.loginFromCache()
        } else if (previousSession?.id != null && session == null) {
          applicationContext?.let { convexClient.logout(it) }
        }
        previousSession = session
      }
    }
  }

  override suspend fun login(context: Context, onIdToken: (String?) -> Unit): Result<String> =
    fetchToken(onIdToken)

  override suspend fun loginFromCache(onIdToken: (String?) -> Unit): Result<String> =
    fetchToken(onIdToken)

  override suspend fun logout(context: Context): Result<Void?> = Result.success(null)

  override fun extractIdToken(authResult: String): String = authResult

  private suspend fun fetchToken(onIdToken: (String?) -> Unit): Result<String> =
    when (
      val result = Clerk.auth.getToken(
        GetTokenOptions(template = "convex", skipCache = needsFreshToken),
      )
    ) {
      is ClerkResult.Success -> {
        val token = result.value
        needsFreshToken = false
        Result.success(token)
      }
      is ClerkResult.Failure -> Result.failure(result.throwable ?: IllegalStateException("Clerk token unavailable."))
    }
}
