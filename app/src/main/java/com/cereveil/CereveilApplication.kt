package com.cereveil

import android.app.Application
import dev.convex.android.ConvexClient

class CereveilApplication : Application() {
  lateinit var convex: ConvexClient
    private set

  override fun onCreate() {
    super.onCreate()
    RoleInitializer.initialize(this)
    convex = RoleInitializer.createConvexClient(this)
  }
}
