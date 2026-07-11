package com.cereveil

import android.app.Application
import com.cereveil.child.enrollment.ChildPushTokenRegistrar
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dev.convex.android.ConvexClient
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
      CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        ChildPushTokenRegistrar(application).register(token)
      }
    }
  }

  fun createConvexClient(application: Application): ConvexClient = ConvexClient(BuildConfig.CONVEX_URL)
}
