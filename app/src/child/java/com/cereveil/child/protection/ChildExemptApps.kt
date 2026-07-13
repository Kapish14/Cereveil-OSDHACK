package com.cereveil.child.protection

import android.content.Context
import android.content.Intent

object ChildExemptApps {
  fun isExempt(context: Context, packageName: String): Boolean {
    val home = context.packageManager.resolveActivity(
      Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
    )?.activityInfo?.packageName
    val dialer = context.packageManager.resolveActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:")), 0)
      ?.activityInfo?.packageName
    return packageName == context.packageName || packageName.startsWith("com.cereveil") ||
      packageName == "com.android.systemui" || packageName == "com.android.settings" ||
      packageName == home || packageName == dialer ||
      packageName.contains("launcher", true) || packageName.contains("dialer", true) ||
      packageName.contains("emergency", true)
  }
}
