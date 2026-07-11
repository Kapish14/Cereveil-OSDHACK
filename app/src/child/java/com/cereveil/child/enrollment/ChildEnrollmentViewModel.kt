package com.cereveil.child.enrollment

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cereveil.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChildEnrollmentViewModel(application: Application) : AndroidViewModel(application) {
  private val store = SharedPreferencesChildEnrollmentStateStore(application)
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
  )

  private var protectionSetupComplete = false
  private val mutableState = MutableStateFlow<ChildEnrollmentUiState>(ChildEnrollmentUiState.ProtectionSetup)
  val state: StateFlow<ChildEnrollmentUiState> = mutableState.asStateFlow()

  init {
    val enrolled = store.load()
    if (enrolled != null) {
      ChildSupervisionWork.schedule(application, enrolled.activeEnrollmentId)
      viewModelScope.launch {
        mutableState.value = coordinator.resume(enrolled)
        pushTokenRegistrar.registerPending()
      }
    } else if (protectionSetupStore.isPrepared() && protectionCapabilities.current().protectionSetupComplete) {
      protectionSetupComplete = true
      mutableState.value = ChildEnrollmentUiState.ReadyToScan
    }
  }

  fun completeProtectionSetup() {
    if (!protectionCapabilities.current().protectionSetupComplete) {
      mutableState.value = ChildEnrollmentUiState.ProtectionSetup
      return
    }
    protectionSetupStore.markPrepared()
    protectionSetupComplete = true
    mutableState.value = ChildEnrollmentUiState.ReadyToScan
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
        store.load()?.let { ChildSupervisionWork.schedule(getApplication(), it.activeEnrollmentId) }
        pushTokenRegistrar.registerPending()
      }
    }
  }

  fun refreshProtectionSetup() {
    if (protectionCapabilities.current().protectionSetupComplete) completeProtectionSetup()
  }

  fun retryScan() {
    mutableState.value = if (protectionSetupComplete) ChildEnrollmentUiState.ReadyToScan else ChildEnrollmentUiState.ProtectionSetup
  }
}
