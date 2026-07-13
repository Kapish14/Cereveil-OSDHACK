package com.cereveil

import android.app.Application
import android.content.Context
import android.util.Log
import com.cereveil.child.enrollment.ChildPushTokenRegistrar
import com.cereveil.child.enrollment.AndroidChildDeviceKeyStore
import com.cereveil.child.enrollment.ChildDeviceTokenProvider
import com.cereveil.child.enrollment.ChildEnrollmentResult
import com.cereveil.child.enrollment.HttpChildDeviceIdentityClient
import com.cereveil.child.enrollment.SharedPreferencesChildEnrollmentStateStore
import com.cereveil.child.remoteaudio.ChildRemoteAudioRecovery
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dev.convex.android.ConvexClient
import dev.convex.android.AuthProvider
import dev.convex.android.ConvexClientWithAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object RoleInitializer {
  fun initialize(application: Application) {
    if (
      BuildConfig.FIREBASE_APPLICATION_ID.isBlank() ||
      BuildConfig.FIREBASE_API_KEY.isBlank() ||
      BuildConfig.FIREBASE_PROJECT_ID.isBlank() ||
      BuildConfig.FIREBASE_GCM_SENDER_ID.isBlank()
    ) {
      return
    }
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
      Log.i("CereveilFCM", "Child FCM token acquired; registering delivery state")
      CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        ChildPushTokenRegistrar(application).register(token)
      }
    }.addOnFailureListener { error ->
      Log.e("CereveilFCM", "Child FCM token acquisition failed: ${error.javaClass.simpleName}: ${error.message}")
    }
  }

  fun createConvexClient(application: Application): ConvexClient {
    val client = ConvexClientWithAuth(
      BuildConfig.CONVEX_URL,
      ChildConvexAuthProvider(application),
      CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
    ChildRemoteAudioRecovery.reconcile(application, client)
    return client
  }
}

private class ChildConvexAuthProvider(context: Context) : AuthProvider<String> {
  private val app = context.applicationContext
  private val store = SharedPreferencesChildEnrollmentStateStore(app)
  private val tokenProvider = ChildDeviceTokenProvider(
    HttpChildDeviceIdentityClient(BuildConfig.CONVEX_SITE_URL),
    AndroidChildDeviceKeyStore(),
    store,
  )

  override suspend fun login(context: Context, onIdToken: (String?) -> Unit) = token(forceRefresh = true)
  override suspend fun loginFromCache(onIdToken: (String?) -> Unit) = token(forceRefresh = false)
  override suspend fun logout(context: Context): Result<Void?> = Result.success(null)
  override fun extractIdToken(authResult: String): String = authResult

  private suspend fun token(forceRefresh: Boolean): Result<String> {
    val state = store.load() ?: return Result.failure(IllegalStateException("Child Device is not enrolled."))
    if (!forceRefresh && state.accessJwtExpiresAt > System.currentTimeMillis() + 30_000) {
      return Result.success(state.accessJwt)
    }
    return when (val result = tokenProvider.refresh()) {
      is ChildEnrollmentResult.Success -> Result.success(result.value)
      is ChildEnrollmentResult.Failure -> Result.failure(IllegalStateException("Child Device token unavailable."))
    }
  }
}
