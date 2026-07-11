package com.cereveil.child.enrollment

import android.content.Context
import java.util.UUID

class ChildInstallationMetadata(context: Context) {
  private val preferences = context.getSharedPreferences("child_installation", Context.MODE_PRIVATE)

  fun installationId(): String {
    preferences.getString("installation_id", null)?.let { return it }
    val created = UUID.randomUUID().toString()
    check(preferences.edit().putString("installation_id", created).commit()) {
      "Child installation identity could not be persisted."
    }
    return created
  }
}
