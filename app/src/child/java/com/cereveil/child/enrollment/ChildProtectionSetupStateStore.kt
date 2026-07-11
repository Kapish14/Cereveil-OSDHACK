package com.cereveil.child.enrollment

import android.content.Context

class ChildProtectionSetupStateStore(context: Context) {
  private val preferences = context.getSharedPreferences("child_protection_setup", Context.MODE_PRIVATE)

  fun isPrepared() = preferences.getBoolean("prepared", false)

  fun markPrepared() {
    check(preferences.edit().putBoolean("prepared", true).commit()) {
      "Prepared Child Device state could not be persisted."
    }
  }
}
