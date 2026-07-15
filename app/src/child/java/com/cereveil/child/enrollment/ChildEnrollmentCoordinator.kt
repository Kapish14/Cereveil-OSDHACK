package com.cereveil.child.enrollment

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay

class ChildEnrollmentCoordinator(
  private val client: ChildDeviceIdentityClient,
  private val keyStore: ChildDeviceKeyStore,
  private val stateStore: ChildEnrollmentStateStore,
  private val policyRuntime: PolicyControlledRuntime,
  private val installationId: String,
  private val deviceLabel: String?,
  private val appBuild: String,
  private val capabilities: () -> ChildCapabilities,
  private val onEnrollmentActivated: () -> Unit = {},
) {
  private val tokenProvider = ChildDeviceTokenProvider(client, keyStore, stateStore)

  suspend fun preview(rawQrPayload: String, protectionSetupComplete: Boolean): ChildEnrollmentUiState {
    if (!protectionSetupComplete) return ChildEnrollmentUiState.ProtectionSetup
    val payload = EnrollmentQrPayload.parse(rawQrPayload)
      ?: return ChildEnrollmentUiState.Failure(ChildEnrollmentError.InvalidCode)
    return when (val result = client.preview(payload.code)) {
      is ChildEnrollmentResult.Success -> ChildEnrollmentUiState.Preview(payload, result.value)
      is ChildEnrollmentResult.Failure -> ChildEnrollmentUiState.Failure(result.error)
    }
  }

  suspend fun complete(payload: EnrollmentQrPayload): ChildEnrollmentUiState {
    return try {
      val alias = keyStore.createKeyAlias()
      val publicKey = keyStore.publicKeySpki(alias)
      val proof = keyStore.sign(alias, "cereveil-child-enrollment-v1\n${payload.code}\n$publicKey")
      when (
        val result = client.complete(payload.code, publicKey, proof, installationId, deviceLabel, appBuild)
      ) {
        is ChildEnrollmentResult.Failure -> ChildEnrollmentUiState.Failure(result.error)
        is ChildEnrollmentResult.Success -> finishEnrollment(result.value, alias).also {
          runCatching(onEnrollmentActivated)
        }
      }
    } catch (_: Exception) {
      ChildEnrollmentUiState.Failure(ChildEnrollmentError.EnrollmentFailed)
    }
  }

  suspend fun resume(state: LocalChildEnrollmentState): ChildEnrollmentUiState {
    val cachedPolicy = stateStore.loadPolicy()
    return if (cachedPolicy?.version == state.desiredPolicyVersion) {
      activateAndAcknowledgePolicy(state, cachedPolicy)
    } else {
      applyInitialPolicy(state)
    }
  }

  suspend fun registerPushToken(token: String): ChildEnrollmentResult<Unit> {
    val state = stateStore.load() ?: return ChildEnrollmentResult.Failure(ChildEnrollmentError.EnrollmentFailed)
    return withTokenRetry(state) { accessJwt -> client.registerPushToken(accessJwt, token) }
  }

  private suspend fun finishEnrollment(
    completion: ChildEnrollmentCompletion,
    alias: String,
  ): ChildEnrollmentUiState {
    val localState = LocalChildEnrollmentState(
      childDeviceId = completion.childDeviceId,
      activeEnrollmentId = completion.activeEnrollmentId,
      credentialId = completion.credentialId,
      childDisplayName = completion.childDisplayName,
      desiredPolicyVersion = completion.desiredPolicyVersion,
      accessJwt = completion.accessJwt,
      accessJwtExpiresAt = completion.accessJwtExpiresAt,
      enrolledAt = completion.enrolledAt,
      environment = completion.environment,
      keyAlias = alias,
    )
    stateStore.save(localState)
    return applyInitialPolicy(localState)
  }

  private suspend fun applyInitialPolicy(
    state: LocalChildEnrollmentState,
    policyMismatchRetried: Boolean = false,
  ): ChildEnrollmentUiState {
    val policy = when (val result = withTokenRetry(state) { token -> client.fetchPolicy(token) }) {
      is ChildEnrollmentResult.Success -> result.value
      is ChildEnrollmentResult.Failure -> return ChildEnrollmentUiState.Enrolled(state, policyApplied = false)
    }
    return activateAndAcknowledgePolicy(state, policy, policyMismatchRetried)
  }

  private suspend fun activateAndAcknowledgePolicy(
    state: LocalChildEnrollmentState,
    policy: ChildSupervisionPolicy,
    policyMismatchRetried: Boolean = false,
  ): ChildEnrollmentUiState {
    val activeToken = currentAccessToken(state)
    if (activeToken === null) {
      return ChildEnrollmentUiState.Enrolled(state, policyApplied = false)
    }
    try {
      if (policyRuntime.start(policy) != PolicyActivationResult.Success) {
        return ChildEnrollmentUiState.Enrolled(state, policyApplied = false)
      }
      stateStore.savePolicy(policy)
    } catch (_: Exception) {
      return ChildEnrollmentUiState.Enrolled(state, policyApplied = false)
    }
    val acknowledgement = withTokenRetry(state, activeToken) { token ->
      client.acknowledgePolicy(token, policy.version)
    }
    if (acknowledgement is ChildEnrollmentResult.Success) {
      stateStore.markPolicyAcknowledged(policy.version)
    } else if (
      acknowledgement is ChildEnrollmentResult.Failure &&
      acknowledgement.error == ChildEnrollmentError.PolicyVersionMismatch &&
      !policyMismatchRetried
    ) {
      return applyInitialPolicy(state, policyMismatchRetried = true)
    }
    withTokenRetry(state, activeToken) { token -> client.heartbeat(token, capabilities()) }
    return ChildEnrollmentUiState.Enrolled(
      state,
      policyApplied = true,
    )
  }

  private suspend fun currentAccessToken(state: LocalChildEnrollmentState): String? {
    val current = stateStore.load() ?: state
    if (current.accessJwtExpiresAt > System.currentTimeMillis() + 30_000) return current.accessJwt
    return when (val refreshed = tokenProvider.refresh()) {
      is ChildEnrollmentResult.Success -> refreshed.value
      is ChildEnrollmentResult.Failure -> null
    }
  }

  private suspend fun <T> withTokenRetry(
    state: LocalChildEnrollmentState,
    initialAccessJwt: String? = null,
    operation: suspend (String) -> ChildEnrollmentResult<T>,
  ): ChildEnrollmentResult<T> {
    val currentState = stateStore.load() ?: state
    val accessJwt = initialAccessJwt ?: currentAccessToken(currentState)
      ?: return ChildEnrollmentResult.Failure(ChildEnrollmentError.Unauthorized)
    val first = operation(accessJwt)
    if (first is ChildEnrollmentResult.Failure && first.error == ChildEnrollmentError.InternalError) {
      delay(500)
      return operation(accessJwt)
    }
    if (first !is ChildEnrollmentResult.Failure || first.error != ChildEnrollmentError.Unauthorized) {
      return first
    }
    val refreshed = tokenProvider.refresh()
    return when (refreshed) {
      is ChildEnrollmentResult.Success -> {
        val retried = operation(refreshed.value)
        if (retried is ChildEnrollmentResult.Failure && retried.error == ChildEnrollmentError.InternalError) {
          delay(500)
          operation(refreshed.value)
        } else retried
      }
      is ChildEnrollmentResult.Failure -> refreshed
    }
  }
}

class ChildDeviceTokenProvider(
  private val client: ChildDeviceIdentityClient,
  private val keyStore: ChildDeviceKeyStore,
  private val stateStore: ChildEnrollmentStateStore,
) {
  suspend fun refresh(): ChildEnrollmentResult<String> = tokenRefreshMutex.withLock {
    val state = stateStore.load() ?: return ChildEnrollmentResult.Failure(ChildEnrollmentError.EnrollmentFailed)
    if (state.accessJwtExpiresAt > System.currentTimeMillis() + 30_000) {
      return ChildEnrollmentResult.Success(state.accessJwt)
    }
    val challenge = when (val result = client.createTokenChallenge(state.credentialId)) {
      is ChildEnrollmentResult.Success -> result.value
      is ChildEnrollmentResult.Failure -> return result
    }
    val proof = try {
      keyStore.sign(
        state.keyAlias,
        "cereveil-child-token-refresh-v1\n${state.credentialId}\n$challenge",
      )
    } catch (_: Exception) {
      return ChildEnrollmentResult.Failure(ChildEnrollmentError.EnrollmentFailed)
    }
    return when (val result = client.exchangeTokenChallenge(state.credentialId, challenge, proof)) {
      is ChildEnrollmentResult.Success -> {
        stateStore.updateAccessToken(result.value.first, result.value.second)
        ChildEnrollmentResult.Success(result.value.first)
      }
      is ChildEnrollmentResult.Failure -> result
    }
  }
}

private val tokenRefreshMutex = Mutex()
