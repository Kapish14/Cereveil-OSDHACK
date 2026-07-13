package com.cereveil.child.enrollment

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cereveil.BuildConfig
import com.cereveil.child.protection.ChildAccessGrantStore
import java.util.concurrent.TimeUnit

interface ChildFeatureCommandProcessor {
  suspend fun process(accessJwt: String, command: ChildDeviceCommand): ChildEnrollmentResult<Unit>
  suspend fun maintain(accessJwt: String, policy: ChildSupervisionPolicy?): ChildEnrollmentResult<Unit> =
    ChildEnrollmentResult.Success(Unit)
}

object ChildSupervisionWork {
  fun enqueueNow(context: Context) {
    WorkManager.getInstance(context).enqueue(
      OneTimeWorkRequestBuilder<ChildSupervisionWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build(),
    )
  }
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
    val activeEnrollmentId = store.load()?.activeEnrollmentId
    val client = HttpChildDeviceIdentityClient(BuildConfig.CONVEX_SITE_URL)
    val outcome = ChildSupervisionSyncCoordinator(
      client = client,
      store = store,
      capabilities = AndroidProtectionCapabilities(applicationContext)::current,
      refreshToken = { ChildDeviceTokenProvider(client, AndroidChildDeviceKeyStore(), store).refresh() },
      runtime = AndroidPolicyControlledRuntime(applicationContext),
      featureProcessor = AndroidChildFeatureCommandProcessor(applicationContext, client),
    ).sync()
    return when (outcome) {
      ChildSupervisionSyncOutcome.Complete -> Result.success()
      ChildSupervisionSyncOutcome.Stop -> {
        activeEnrollmentId?.let {
          WorkManager.getInstance(applicationContext).cancelUniqueWork("child-supervision-$it")
        }
        ChildAccessGrantStore(applicationContext).clear()
        ChildLocationMovementRegistration.configure(applicationContext, false)
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
  private val runtime: PolicyControlledRuntime = PolicyControlledRuntime { PolicyActivationResult.Success },
  private val featureProcessor: ChildFeatureCommandProcessor? = null,
  private val now: () -> Long = System::currentTimeMillis,
) {
  suspend fun sync(): ChildSupervisionSyncOutcome {
    val state = store.load() ?: return ChildSupervisionSyncOutcome.Stop
    val token = if (state.accessJwtExpiresAt > now() + 30_000) state.accessJwt else when (val result = refreshToken()) {
      is ChildEnrollmentResult.Success -> result.value
      is ChildEnrollmentResult.Failure -> return outcomeFor(result.error)
    }
    var retry = false
    var acknowledgedPolicyVersion = state.acknowledgedPolicyVersion
    when (val commands = client.reconcileCommands(token)) {
      is ChildEnrollmentResult.Success -> {
        for (command in commands.value) {
          if (command.type != "apply_policy_version") {
            val processed = featureProcessor?.process(token, command)
              ?: client.rejectCommand(token, command.commandId, "unsupported_command")
            when (processed) {
              is ChildEnrollmentResult.Success -> Unit
              is ChildEnrollmentResult.Failure -> {
                if (processed.error == ChildEnrollmentError.Unauthorized) return stopAndClear()
                retry = true
              }
            }
            continue
          }
          var applied = store.loadPolicy()
          if (applied?.version != command.policyVersion) {
            when (val fetched = client.fetchPolicy(token)) {
              is ChildEnrollmentResult.Success -> {
                if (fetched.value.version != command.policyVersion) {
                  retry = true
                  continue
                }
                val activation = try {
                  runtime.start(fetched.value)
                } catch (_: Exception) {
                  PolicyActivationResult.RetryableFailure
                }
                when (activation) {
                  PolicyActivationResult.Success -> store.savePolicy(fetched.value)
                  PolicyActivationResult.RetryableFailure -> {
                    retry = true
                    continue
                  }
                  is PolicyActivationResult.PermanentFailure -> {
                    when (val rejected = client.rejectCommand(token, command.commandId, activation.reason.wireValue)) {
                    is ChildEnrollmentResult.Success -> Unit
                    is ChildEnrollmentResult.Failure -> if (rejected.error == ChildEnrollmentError.Unauthorized) {
                      return stopAndClear()
                    }
                    }
                    continue
                  }
                }
                applied = fetched.value
              }
              is ChildEnrollmentResult.Failure -> {
                if (fetched.error == ChildEnrollmentError.Unauthorized) return stopAndClear()
                if (fetched.error == ChildEnrollmentError.InvalidPolicy) {
                  client.rejectCommand(token, command.commandId, "invalid_command")
                  continue
                }
                retry = true
                continue
              }
            }
          }
          if (applied != null && acknowledgedPolicyVersion != applied.version) {
            when (val acknowledged = client.acknowledgePolicy(token, applied.version)) {
              is ChildEnrollmentResult.Success -> {
                store.markPolicyAcknowledged(applied.version)
                acknowledgedPolicyVersion = applied.version
              }
              is ChildEnrollmentResult.Failure -> {
                if (acknowledged.error == ChildEnrollmentError.Unauthorized) return stopAndClear()
                retry = true
              }
            }
          }
        }
      }
      is ChildEnrollmentResult.Failure -> {
        if (commands.error == ChildEnrollmentError.Unauthorized) return stopAndClear()
        retry = true
      }
    }
    val policy = store.loadPolicy()
    featureProcessor?.maintain(token, policy)?.let { result ->
      if (result is ChildEnrollmentResult.Failure) {
        if (result.error == ChildEnrollmentError.Unauthorized) return stopAndClear()
        retry = true
      }
    }
    if (policy != null && acknowledgedPolicyVersion != policy.version) {
      when (val result = client.acknowledgePolicy(token, policy.version)) {
        is ChildEnrollmentResult.Success -> store.markPolicyAcknowledged(policy.version)
        is ChildEnrollmentResult.Failure -> if (result.error == ChildEnrollmentError.Unauthorized) return stopAndClear() else retry = true
      }
    }
    when (val result = client.heartbeat(token, capabilities())) {
      is ChildEnrollmentResult.Success -> Unit
      is ChildEnrollmentResult.Failure -> if (result.error == ChildEnrollmentError.Unauthorized) return stopAndClear() else retry = true
    }
    return if (retry) ChildSupervisionSyncOutcome.Retry else ChildSupervisionSyncOutcome.Complete
  }

  private fun outcomeFor(error: ChildEnrollmentError) = if (error == ChildEnrollmentError.Unauthorized) {
    stopAndClear()
  } else {
    ChildSupervisionSyncOutcome.Retry
  }

  private fun stopAndClear(): ChildSupervisionSyncOutcome {
    store.clear()
    return ChildSupervisionSyncOutcome.Stop
  }
}
