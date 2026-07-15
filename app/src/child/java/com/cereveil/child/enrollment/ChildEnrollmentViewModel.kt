package com.cereveil.child.enrollment

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cereveil.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ChildEnrollmentViewModel(application: Application) : AndroidViewModel(application) {
  private val store = SharedPreferencesChildEnrollmentStateStore(application)
  private val initialEnrollment = store.load()
  private val protectionCapabilities = AndroidProtectionCapabilities(application)
  private val protectionSetupStore = ChildProtectionSetupStateStore(application)
  private val pushTokenRegistrar = ChildPushTokenRegistrar(application)
  private val coordinator = ChildEnrollmentCoordinator(
    client = HttpChildDeviceIdentityClient(BuildConfig.CONVEX_SITE_URL),
    keyStore = AndroidChildDeviceKeyStore(),
    stateStore = store,
    policyRuntime = AndroidPolicyControlledRuntime(application),
    installationId = ChildInstallationMetadata(application).installationId(),
    deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    appBuild = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    capabilities = protectionCapabilities::current,
    onEnrollmentActivated = pushTokenRegistrar::registerCurrent,
  )

  private var protectionSetupComplete = false
  private val mutableProtectionSetupStatus = MutableStateFlow(protectionCapabilities.currentSetupStatus())
  val protectionSetupStatus: StateFlow<ProtectionSetupStatus> = mutableProtectionSetupStatus.asStateFlow()
  private val mutableState = MutableStateFlow<ChildEnrollmentUiState>(
    initialEnrollment?.let { ChildEnrollmentUiState.Enrolled(it, policyApplied = store.loadPolicy() != null) }
      ?: ChildEnrollmentUiState.ProtectionSetup,
  )
  val state: StateFlow<ChildEnrollmentUiState> = mutableState.asStateFlow()
  private var enrollmentRecoveryJob: Job? = null

  init {
    val enrolled = initialEnrollment
    if (enrolled != null) {
      ChildSupervisionWork.schedule(application, enrolled.activeEnrollmentId)
      ChildSupervisionWork.enqueueNow(application)
      enrollmentRecoveryJob = viewModelScope.launch {
        mutableState.value = coordinator.resume(enrolled)
        pushTokenRegistrar.registerPending()
      }
    } else if (protectionSetupStore.isPrepared() && protectionCapabilities.current().protectionSetupComplete) {
      protectionSetupComplete = true
      mutableState.value = ChildEnrollmentUiState.ReadyToScan
    }
  }

  fun completeProtectionSetup() {
    val status = protectionCapabilities.currentSetupStatus()
    mutableProtectionSetupStatus.value = status
    if (!status.complete) {
      if (store.load() == null) mutableState.value = ChildEnrollmentUiState.ProtectionSetup
      return
    }
    protectionSetupStore.markPrepared()
    protectionSetupComplete = true
    // Permission refresh runs on every Activity resume. Once enrollment authority exists it must
    // never replace the enrolled UI with the pre-enrollment QR route.
    if (store.load() == null) mutableState.value = ChildEnrollmentUiState.ReadyToScan
  }

  fun scanned(rawPayload: String) {
    viewModelScope.launch {
      mutableState.value = ChildEnrollmentUiState.PreviewLoading
      mutableState.value = coordinator.preview(rawPayload, protectionSetupComplete)
    }
  }

  fun confirmEnrollment() {
    val preview = mutableState.value as? ChildEnrollmentUiState.Preview ?: return
    viewModelScope.launch {
      mutableState.value = ChildEnrollmentUiState.Enrolling
      mutableState.value = coordinator.complete(preview.payload)
      if (mutableState.value is ChildEnrollmentUiState.Enrolled) {
        store.load()?.let {
          ChildSupervisionWork.schedule(getApplication(), it.activeEnrollmentId)
          ChildSupervisionWork.enqueueNow(getApplication())
        }
        pushTokenRegistrar.registerPending()
      }
    }
  }

  fun refreshProtectionSetup() {
    val status = protectionCapabilities.currentSetupStatus()
    mutableProtectionSetupStatus.value = status
    if (status.complete) completeProtectionSetup()
  }

  fun refreshPersistedEnrollment() {
    if (mutableState.value is ChildEnrollmentUiState.Enrolled || enrollmentRecoveryJob?.isActive == true) return
    val enrolled = store.load() ?: return
    enrollmentRecoveryJob = viewModelScope.launch {
      val resumed = coordinator.resume(enrolled)
      // A transient backend/auth failure still returns Enrolled with protection retrying. Never
      // route a device with persisted credential authority back to the QR enrollment flow.
      mutableState.value = resumed
      ChildSupervisionWork.schedule(getApplication(), enrolled.activeEnrollmentId)
      ChildSupervisionWork.enqueueNow(getApplication())
      pushTokenRegistrar.registerPending()
    }
  }

  fun retryScan() {
    mutableState.value = if (protectionSetupComplete) ChildEnrollmentUiState.ReadyToScan else ChildEnrollmentUiState.ProtectionSetup
  }
}
