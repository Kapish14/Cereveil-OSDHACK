package com.cereveil.guardian.auth

import android.os.Build
import java.util.TimeZone

fun buildDeviceLabel(brand: String?, model: String?): String? {
  val cleanBrand = brand.cleanDevicePart()
  val cleanModel = model.cleanDevicePart()

  return when {
    cleanBrand == null && cleanModel == null -> null
    cleanBrand == null -> cleanModel
    cleanModel == null -> cleanBrand
    cleanModel.startsWith(cleanBrand, ignoreCase = true) -> cleanModel
    else -> "$cleanBrand $cleanModel"
  }?.take(128)
}

fun buildAppBuild(role: String, versionName: String, versionCode: Long): String =
  "$role-$versionName-$versionCode".take(128)

fun timezoneId(timeZone: TimeZone): String? = timeZone.id.takeIf { it.isNotBlank() }?.take(128)

class AndroidGuardianInstallationMetadataProvider(
  private val role: String,
  private val versionName: String,
  private val versionCode: Long,
) : GuardianInstallationMetadataProvider {
  override fun buildRequest(guardianInstallationId: String): GuardianBootstrapRequest =
    GuardianBootstrapRequest(
      guardianInstallationId = guardianInstallationId,
      deviceLabel = buildDeviceLabel(Build.BRAND, Build.MODEL),
      appBuild = buildAppBuild(role, versionName, versionCode),
      timezone = timezoneId(TimeZone.getDefault()),
    )
}

private fun String?.cleanDevicePart(): String? =
  this
    ?.trim()
    ?.replace(Regex("\\s+"), " ")
    ?.takeIf { it.isNotBlank() }
    ?.takeIf { !it.equals("unknown", ignoreCase = true) }
