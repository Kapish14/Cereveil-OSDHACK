package com.cereveil.guardian.enrollment

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.cereveil.BuildConfig
import com.cereveil.DebugEnrollmentContract

internal enum class DebugChildEnrollmentLaunchResult {
  Launched,
  ChildNotInstalled,
  SignatureMismatch,
  Disabled,
  Failed,
}

internal class DebugChildEnrollmentLauncher(private val context: Context) {
  fun launch(qrPayload: String): DebugChildEnrollmentLaunchResult {
    if (!BuildConfig.DEBUG) return DebugChildEnrollmentLaunchResult.Disabled
    val guardianSignatures = signatures(context.packageName)
    val childSignatures = try {
      signatures(DebugEnrollmentContract.CHILD_PACKAGE)
    } catch (_: PackageManager.NameNotFoundException) {
      return DebugChildEnrollmentLaunchResult.ChildNotInstalled
    }
    if (guardianSignatures.isEmpty() || guardianSignatures != childSignatures) {
      return DebugChildEnrollmentLaunchResult.SignatureMismatch
    }

    return try {
      context.startActivity(
        Intent(DebugEnrollmentContract.ACTION).apply {
          component = ComponentName(
            DebugEnrollmentContract.CHILD_PACKAGE,
            DebugEnrollmentContract.CHILD_ACTIVITY,
          )
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          putExtra(DebugEnrollmentContract.EXTRA_QR_PAYLOAD, qrPayload)
        },
      )
      DebugChildEnrollmentLaunchResult.Launched
    } catch (_: Exception) {
      DebugChildEnrollmentLaunchResult.Failed
    }
  }

  @Suppress("DEPRECATION")
  private fun signatures(packageName: String): Set<Signature> {
    val packageInfo = when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        context.packageManager.getPackageInfo(
          packageName,
          PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
        context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
      else -> context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      packageInfo.signingInfo?.apkContentsSigners.orEmpty().toSet()
    } else {
      packageInfo.signatures.orEmpty().toSet()
    }
  }
}
