package com.cereveil.child.remoteaudio

import android.app.Application
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cereveil.CereveilApplication
import com.cereveil.child.enrollment.ChildInstallationMetadata
import com.cereveil.ui.CereveilCard
import com.cereveil.ui.CereveilPrimaryButton
import com.cereveil.ui.CereveilSecondaryButton
import dev.convex.android.ConvexClientWithAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

private data class ChildRemoteAudioUiState(val requestId: String? = null, val phase: String? = null)

private class ChildRemoteAudioViewModel(application: Application) : AndroidViewModel(application) {
  private val app = application as CereveilApplication
  private val mutable = MutableStateFlow(ChildRemoteAudioUiState())
  val state = mutable.asStateFlow()
  init { viewModelScope.launch { observe() } }

  private suspend fun observe() {
    @Suppress("UNCHECKED_CAST") val client = app.convex as? ConvexClientWithAuth<String> ?: return
    while (true) {
      val login = client.loginFromCache()
      if (login.isSuccess) {
        val args = mapOf("childInstallationId" to ChildInstallationMetadata(app).installationId())
        val result = client.subscribe<JsonElement>("modules/remoteAudio/child:getRemoteAudioState", args).first()
        result.getOrNull()?.let { value ->
          val request = value.jsonObject.remoteObjectOrNull("request")
          if (request == null) {
            ChildRemoteAudioPendingStore.clear(app)
            mutable.value = ChildRemoteAudioUiState()
          } else {
            mutable.value = ChildRemoteAudioUiState(
              request.remoteStringOrNull("requestId"),
              request.remoteStringOrNull("status"),
            )
          }
        } ?: Log.w(TAG, "Remote Audio state subscription failed", result.exceptionOrNull())
      } else {
        Log.w(TAG, "Child Convex authentication unavailable for Remote Audio", login.exceptionOrNull())
      }
      if (mutable.value.requestId == null) {
        ChildRemoteAudioPendingStore.load(app)?.let { pending ->
          mutable.value = ChildRemoteAudioUiState(pending.requestId, "awaiting_child")
        }
      }
      delay(2_000)
    }
  }

  fun decline() {
    val id = mutable.value.requestId ?: return
    val reason = if (mutable.value.phase == "awaiting_child") "declined" else "stopped"
    mutable.value = ChildRemoteAudioUiState()
    ChildRemoteAudioNotice.terminate(app, id, reason)
  }

  fun start() {
    val id = mutable.value.requestId ?: return
    app.startActivity(ChildRemoteAudioStartActivity.intent(app, id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  private companion object { const val TAG = "CereveilRemoteAudio" }
}

@Composable
fun ChildRemoteAudioBanner(modifier: Modifier = Modifier) {
  val app = LocalContext.current.applicationContext as Application
  val factory = remember { viewModelFactory { initializer { ChildRemoteAudioViewModel(app) } } }
  val model: ChildRemoteAudioViewModel = viewModel(key = "child-remote-audio", factory = factory)
  val state by model.state.collectAsStateWithLifecycle()
  state.requestId ?: return
  Box(modifier, contentAlignment = Alignment.BottomCenter) {
    CereveilCard(Modifier.padding(16.dp)) {
      Text(if (state.phase == "awaiting_child") "Share live audio?" else "Live audio sharing", style = MaterialTheme.typography.titleLarge)
      Text(if (state.phase == "awaiting_child")
        "Your guardian requested up to two minutes of live microphone audio. You can decline or start explicitly."
      else "Your microphone audio is disclosed and live. You can stop immediately.")
      if (state.phase == "awaiting_child") CereveilPrimaryButton("Start audio", model::start)
      CereveilSecondaryButton(if (state.phase == "awaiting_child") "Decline" else "Stop", model::decline)
    }
  }
}
