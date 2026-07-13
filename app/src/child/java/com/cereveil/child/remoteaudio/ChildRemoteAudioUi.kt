package com.cereveil.child.remoteaudio

import android.app.Application
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

private data class ChildRemoteAudioUiState(val requestId: String? = null, val phase: String? = null)

private class ChildRemoteAudioViewModel(application: Application) : AndroidViewModel(application) {
  private val app = application as CereveilApplication
  private val mutable = MutableStateFlow(ChildRemoteAudioUiState())
  val state = mutable.asStateFlow()
  init { viewModelScope.launch { observe() } }

  private suspend fun observe() {
    @Suppress("UNCHECKED_CAST") val client = app.convex as? ConvexClientWithAuth<String> ?: return
    while (true) {
      if (client.loginFromCache().isSuccess) {
        val args = mapOf("childInstallationId" to ChildInstallationMetadata(app).installationId())
        client.subscribe<Map<String, Any?>>("modules/remoteAudio/child:getRemoteAudioState", args).first().getOrNull()?.let { value ->
          @Suppress("UNCHECKED_CAST") val request = value["request"] as? Map<String, Any?>
          mutable.value = ChildRemoteAudioUiState(request?.get("requestId")?.toString(), request?.get("status")?.toString())
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
