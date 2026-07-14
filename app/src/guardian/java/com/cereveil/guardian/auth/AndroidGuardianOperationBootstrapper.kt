package com.cereveil.guardian.auth

import android.content.Context
import com.cereveil.BuildConfig
import com.cereveil.RoleInitializer

class AndroidGuardianOperationBootstrapper(context: Context) : GuardianOperationBootstrapper {
  private val coordinator =
    GuardianBootstrapCoordinator(
      authSessionProvider = RoleInitializer.guardianAuthSessionProvider(context),
      localStateRepository = SharedPreferencesGuardianLocalStateRepository(context),
      metadataProvider =
        AndroidGuardianInstallationMetadataProvider(
          role = BuildConfig.CEREVEIL_ROLE,
          versionName = BuildConfig.VERSION_NAME,
          versionCode = BuildConfig.VERSION_CODE.toLong(),
        ),
      authClient =
        ConvexGuardianAuthClient(
          BuildConfig.CONVEX_URL,
          RoleInitializer::guardianConvexToken,
        ),
    )

  override suspend fun ensureBootstrapped(): Boolean =
    coordinator.start() in setOf(GuardianStartupRoute.Setup, GuardianStartupRoute.Dashboard)
}
