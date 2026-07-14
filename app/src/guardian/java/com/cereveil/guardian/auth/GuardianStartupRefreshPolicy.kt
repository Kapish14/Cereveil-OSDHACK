package com.cereveil.guardian.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

internal fun routeWhileRefreshing(current: GuardianStartupRoute): GuardianStartupRoute =
  if (current in setOf(GuardianStartupRoute.Setup, GuardianStartupRoute.Dashboard)) {
    current
  } else {
    GuardianStartupRoute.Loading
  }

internal fun Flow<GuardianAuthState>.stableAuthStates(): Flow<GuardianAuthState> = distinctUntilChanged()
