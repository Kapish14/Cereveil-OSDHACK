package com.cereveil.child.enrollment

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cereveil.BuildConfig
import java.util.concurrent.TimeUnit

object ChildSupervisionWork {
  fun schedule(context: Context, activeEnrollmentId: String) {
    val request = PeriodicWorkRequestBuilder<ChildSupervisionWorker>(15, TimeUnit.MINUTES)
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
      .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
      "child-supervision-$activeEnrollmentId",
      ExistingPeriodicWorkPolicy.UPDATE,
      request,
    )
  }
}

class ChildSupervisionWorker(context: Context, parameters: WorkerParameters) :
  CoroutineWorker(context, parameters) {
  override suspend fun doWork(): Result {
    val store = SharedPreferencesChildEnrollmentStateStore(applicationContext)
    val client = HttpChildDeviceIdentityClient(BuildConfig.CONVEX_SITE_URL)
    val outcome = ChildSupervisionSyncCoordinator(
      client = client,
      store = store,
      capabilities = AndroidProtectionCapabilities(applicationContext)::current,
      refreshToken = { ChildDeviceTokenProvider(client, AndroidChildDeviceKeyStore(), store).refresh() },
    ).sync()
    return when (outcome) {
      ChildSupervisionSyncOutcome.Complete -> Result.success()
      ChildSupervisionSyncOutcome.Stop -> {
        WorkManager.getInstance(applicationContext).cancelUniqueWork("child-supervision-${store.load()?.activeEnrollmentId}")
        Result.success()
      }
      ChildSupervisionSyncOutcome.Retry -> Result.retry()
    }
  }
}

enum class ChildSupervisionSyncOutcome { Complete, Retry, Stop }

class ChildSupervisionSyncCoordinator(
  private val client: ChildDeviceIdentityClient,
  private val store: ChildEnrollmentStateStore,
  private val capabilities: () -> ChildCapabilities,
  private val refreshToken: suspend () -> ChildEnrollmentResult<String>,
  private val now: () -> Long = System::currentTimeMillis,
) {
  suspend fun sync(): ChildSupervisionSyncOutcome {
    val state = store.load() ?: return ChildSupervisionSyncOutcome.Stop
    val token = if (state.accessJwtExpiresAt > now() + 30_000) state.accessJwt else when (val result = refreshToken()) {
      is ChildEnrollmentResult.Success -> result.value
      is ChildEnrollmentResult.Failure -> return outcomeFor(result.error)
    }
    var retry = false
    val policy = store.loadPolicy()
    if (policy != null && state.acknowledgedPolicyVersion != policy.version) {
      when (val result = client.acknowledgePolicy(token, policy.version)) {
        is ChildEnrollmentResult.Success -> store.markPolicyAcknowledged(policy.version)
        is ChildEnrollmentResult.Failure -> if (result.error == ChildEnrollmentError.Unauthorized) return ChildSupervisionSyncOutcome.Stop else retry = true
      }
    }
    when (val result = client.heartbeat(token, capabilities())) {
      is ChildEnrollmentResult.Success -> Unit
      is ChildEnrollmentResult.Failure -> if (result.error == ChildEnrollmentError.Unauthorized) return ChildSupervisionSyncOutcome.Stop else retry = true
    }
    return if (retry) ChildSupervisionSyncOutcome.Retry else ChildSupervisionSyncOutcome.Complete
  }

  private fun outcomeFor(error: ChildEnrollmentError) = if (error == ChildEnrollmentError.Unauthorized) {
    ChildSupervisionSyncOutcome.Stop
  } else {
    ChildSupervisionSyncOutcome.Retry
  }
}
