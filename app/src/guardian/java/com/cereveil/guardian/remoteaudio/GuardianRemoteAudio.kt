package com.cereveil.guardian.remoteaudio

import android.app.Application
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cereveil.CereveilApplication
import com.cereveil.guardian.arrayOrEmpty
import com.cereveil.guardian.long
import com.cereveil.guardian.objectOrNull
import com.cereveil.guardian.string
import com.cereveil.guardian.stringOrNull
import com.cereveil.guardian.auth.AndroidGuardianOperationBootstrapper
import com.cereveil.guardian.auth.SharedPreferencesGuardianInstallationIdProvider
import com.cereveil.guardian.ui.GuardianCard as CereveilCard
import com.cereveil.guardian.ui.GuardianPrimaryButton as CereveilPrimaryButton
import com.cereveil.guardian.ui.GuardianSecondaryButton as CereveilSecondaryButton
import dev.convex.android.ConvexClient
import dev.convex.android.ConvexClientWithAuth
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

data class GuardianRemoteAudioState(
  val availability: String = "loading",
  val reason: String? = null,
  val requestId: String? = null,
  val phase: String? = null,
  val expiresAt: Long? = null,
  val remainingSeconds: Long = 0,
  val cooldownUntil: Long? = null,
  val message: String? = null,
)

class GuardianRemoteAudioViewModel(application: Application, private val childProfileId: String) : AndroidViewModel(application) {
  private val app = application as CereveilApplication
  private val convex: ConvexClient = app.convex
  private val installation = SharedPreferencesGuardianInstallationIdProvider(app)
  private val mutable = MutableStateFlow(GuardianRemoteAudioState())
  val state = mutable.asStateFlow()
  private var installationId: String? = null
  private var peer: GuardianWebRtcSession? = null
  private var timer: Job? = null
  private var screenVisible = false

  init { viewModelScope.launch { subscribe() } }

  private suspend fun subscribe() {
    @Suppress("UNCHECKED_CAST")
    if ((convex as? ConvexClientWithAuth<String>)?.loginFromCache()?.isFailure == true) return
    installationId = installation.getInstallationId().also {
      if (it == null) AndroidGuardianOperationBootstrapper(app).ensureBootstrapped()
    } ?: installation.getInstallationId()
    val id = installationId ?: return
    convex.subscribe<JsonElement>("modules/remoteAudio/guardian:getRemoteAudioState", mapOf(
      "guardianInstallationId" to id, "childProfileId" to childProfileId,
    )).collect { result ->
      result.onFailure { mutable.value = mutable.value.copy(message = "Remote Audio is unavailable.") }
      result.onSuccess { acceptState(it.jsonObject) }
    }
  }

  private fun acceptState(value: JsonObject) {
    val availability = value.string("availability")
    val request = value.objectOrNull("request")
    if (request == null) {
      closePeer()
      mutable.value = GuardianRemoteAudioState(
        availability = availability,
        reason = value.stringOrNull("reason"),
        cooldownUntil = value.stringOrNull("cooldownUntil")?.toDoubleOrNull()?.toLong(),
      )
      return
    }
    val requestId = request.string("requestId")
    val expiresAt = request.long("expiresAt")
    val serverNow = value.long("serverNow")
    mutable.value = GuardianRemoteAudioState(
      availability = availability,
      requestId = requestId,
      phase = request.string("status"),
      expiresAt = expiresAt,
      remainingSeconds = ((expiresAt - serverNow).coerceAtLeast(0) + 999) / 1000,
    )
    timer?.cancel()
    timer = viewModelScope.launch {
      val anchor = android.os.SystemClock.elapsedRealtime()
      val initial = (expiresAt - serverNow).coerceAtLeast(0)
      while (true) {
        val left = (initial - (android.os.SystemClock.elapsedRealtime() - anchor)).coerceAtLeast(0)
        mutable.value = mutable.value.copy(remainingSeconds = (left + 999) / 1000)
        if (left == 0L) { closePeer(); break }
        delay(250)
      }
    }
    if (screenVisible && request.string("status") != "awaiting_child") {
      val signals = value.arrayOrEmpty("signals").map { it.jsonObject }
      val stun = value.arrayOrEmpty("stunUrls").map { it.jsonPrimitive.content }
      ensurePeer(requestId, stun, signals)
      signals.forEach { peer?.receive(it) }
    }
  }

  fun request() {
    val id = installationId ?: return
    viewModelScope.launch {
      runCatching {
        convex.mutation<JsonElement>("modules/remoteAudio/guardian:createRemoteAudioRequest", mapOf(
          "guardianInstallationId" to id,
          "childProfileId" to childProfileId,
          "operationId" to UUID.randomUUID().toString(),
        ))
      }.onFailure { mutable.value = mutable.value.copy(message = "Couldn’t request Remote Audio.") }
    }
  }

  fun setScreenVisible(visible: Boolean) {
    screenVisible = visible
    // Guardian and Child debug flavors may be exercised on one phone. Backgrounding Guardian to
    // accept the disclosed Child notification must not cancel the bounded request. Explicit UI
    // dismissal still calls stop(), and the backend always expires the request at its deadline.
  }

  fun stop() {
    val id = installationId ?: return
    val requestId = mutable.value.requestId ?: return
    closePeer()
    mutable.value = mutable.value.copy(requestId = null, phase = null)
    viewModelScope.launch {
      convex.mutation<JsonElement>("modules/remoteAudio/guardian:terminateRemoteAudioRequest", mapOf(
        "guardianInstallationId" to id, "requestId" to requestId,
      ))
    }
  }

  fun stopOtherDeviceSession() {
    val id = installationId ?: return
    viewModelScope.launch {
      convex.mutation<JsonElement>("modules/remoteAudio/guardian:terminateRemoteAudioForChild", mapOf(
        "guardianInstallationId" to id, "childProfileId" to childProfileId,
      ))
    }
  }

  private fun ensurePeer(requestId: String, stun: List<String>, signals: List<JsonObject>) {
    if (peer != null) return
    val offer = signals.firstOrNull { it.string("type") == "offer" } ?: return
    val id = installationId ?: return
    peer = runCatching { GuardianWebRtcSession(app, stun, publish = { type, key, payload ->
      viewModelScope.launch(Dispatchers.IO) {
        convex.mutation<JsonElement>("modules/remoteAudio/guardian:publishRemoteAudioSignal", mapOf(
          "guardianInstallationId" to id, "requestId" to requestId, "type" to type,
          "idempotencyKey" to key, "payload" to payload,
        ))
      }
    }, failed = ::stop).also { it.receive(offer) } }.getOrElse {
      stop()
      return
    }
  }

  private fun closePeer() {
    timer?.cancel()
    val closingPeer = peer
    peer = null
    closingPeer?.close()
  }
  override fun onCleared() { closePeer(); super.onCleared() }
}

@Composable
fun GuardianRemoteAudioCard(childProfileId: String) {
  val app = LocalContext.current.applicationContext as Application
  val factory = remember(childProfileId) { viewModelFactory { initializer { GuardianRemoteAudioViewModel(app, childProfileId) } } }
  val model: GuardianRemoteAudioViewModel = viewModel(key = "remote-audio-$childProfileId", factory = factory)
  val state by model.state.collectAsStateWithLifecycle()
  var screenOpen by remember { mutableStateOf(false) }
  CereveilCard {
    Text("Remote Audio", style = MaterialTheme.typography.titleLarge)
    Text("The Child must choose Start audio. Live audio is never recorded and stops within two minutes.")
    when (state.availability) {
      "ready" -> CereveilPrimaryButton("Open Remote Audio", onClick = { screenOpen = true })
      "unavailable" -> Text("Unavailable: ${state.reason?.replace('_', ' ') ?: "Child Device unavailable"}")
      "busy" -> {
        Text("A Remote Audio request is already in progress on another Guardian Device.")
        CereveilSecondaryButton("Stop Remote Audio", model::stopOtherDeviceSession)
      }
      "cooldown" -> Text("Available again in ${((state.cooldownUntil ?: 0) - System.currentTimeMillis()).coerceAtLeast(0) / 1000}s")
      "live" -> Text("Request in progress")
      else -> Text("Checking availability…")
    }
    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  }
  if (screenOpen || state.requestId != null) Dialog(
    onDismissRequest = { model.stop(); screenOpen = false },
    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false),
  ) {
    LifecycleStartEffect(model) {
      model.setScreenVisible(true)
      onStopOrDispose { model.setScreenVisible(false) }
    }
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Remote Audio", style = MaterialTheme.typography.headlineMedium)
        if (state.requestId == null) {
          Text("The Child will see a disclosed request and must choose Start audio.")
          Text("No audio is recorded or retained.")
          if (state.availability == "ready") CereveilPrimaryButton("Request live audio", model::request)
          else Text("Remote Audio is not currently ready.")
          CereveilSecondaryButton("Close", onClick = { screenOpen = false })
          return@Column
        }
        Text(when (state.phase) {
          "awaiting_child" -> "Waiting for the Child to choose Start audio"
          "connecting" -> "Connecting live audio…"
          "active" -> "Live audio is playing through this device’s speaker"
          else -> "Audio ended"
        })
        Text("${state.remainingSeconds}s remaining")
        Text("No audio is recorded or retained.")
        CereveilSecondaryButton("${if (state.phase == "awaiting_child") "Cancel" else "Stop audio"}", model::stop)
      }
    }
  }
}

private class GuardianWebRtcSession(
  context: Application,
  stunUrls: List<String>,
  private val publish: (String, String, String) -> Unit,
  private val failed: () -> Unit,
) : PeerConnection.Observer {
  private val factory: PeerConnectionFactory
  private val peer: PeerConnection
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private val seen = mutableSetOf<String>()
  private var disconnected: Job? = null
  private var closedLocally = false
  init {
    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
    factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    peer = requireNotNull(factory.createPeerConnection(
      PeerConnection.RTCConfiguration(stunUrls.map { PeerConnection.IceServer.builder(it).createIceServer() }).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
      }, this,
    ))
    audioManager.mode = AudioManager.MODE_NORMAL
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val speaker = audioManager.availableCommunicationDevices
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        ?: error("Built-in speaker unavailable")
      check(audioManager.setCommunicationDevice(speaker) &&
        audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
        "Built-in speaker route unavailable"
      }
    } else {
      @Suppress("DEPRECATION")
      audioManager.isSpeakerphoneOn = true
      @Suppress("DEPRECATION")
      check(audioManager.isSpeakerphoneOn) { "Built-in speaker route unavailable" }
    }
  }
  fun receive(signal: JsonObject) {
    val id = signal.stringOrNull("signalId") ?: return
    if (!seen.add(id)) return
    when (signal.stringOrNull("type")) {
      "offer" -> peer.setRemoteDescription(SetObserver {
        peer.createAnswer(SdpSetObserver { answer ->
          val recvOnly = SessionDescription(answer.type, answer.description.replace("a=sendrecv", "a=recvonly"))
          peer.setLocalDescription(EmptySdpObserver(), recvOnly)
          publish("answer", UUID.randomUUID().toString(), recvOnly.description)
        }, MediaConstraints())
      }, SessionDescription(SessionDescription.Type.OFFER, signal.string("payload")))
      "ice_candidate" -> decodeCandidate(signal.string("payload"))?.let(peer::addIceCandidate)
    }
  }
  override fun onIceCandidate(candidate: IceCandidate) = publish("ice_candidate", UUID.randomUUID().toString(), encodeCandidate(candidate))
  override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
    if (closedLocally) return
    when (state) {
      PeerConnection.PeerConnectionState.CONNECTED -> disconnected?.cancel()
      PeerConnection.PeerConnectionState.DISCONNECTED -> {
        disconnected?.cancel(); disconnected = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { delay(5_000); failed() }
      }
      PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> failed()
      else -> Unit
    }
  }
  fun close() {
    closedLocally = true
    peer.close()
    factory.dispose()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
  }
  override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) { (receiver.track() as? AudioTrack)?.setEnabled(true) }
  override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
  override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
  override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
  override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
  override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
  override fun onAddStream(stream: MediaStream) = Unit
  override fun onRemoveStream(stream: MediaStream) = Unit
  override fun onDataChannel(channel: DataChannel) = Unit
  override fun onRenegotiationNeeded() = Unit
}

private class EmptySdpObserver : SdpObserver {
  override fun onCreateSuccess(description: SessionDescription) = Unit
  override fun onSetSuccess() = Unit
  override fun onCreateFailure(error: String) = Unit
  override fun onSetFailure(error: String) = Unit
}
private class SdpSetObserver(private val success: (SessionDescription) -> Unit) : SdpObserver {
  override fun onCreateSuccess(description: SessionDescription) = success(description)
  override fun onSetSuccess() = Unit
  override fun onCreateFailure(error: String) = Unit
  override fun onSetFailure(error: String) = Unit
}
private class SetObserver(private val success: () -> Unit) : SdpObserver {
  override fun onCreateSuccess(description: SessionDescription) = Unit
  override fun onSetSuccess() = success()
  override fun onCreateFailure(error: String) = Unit
  override fun onSetFailure(error: String) = Unit
}
private fun encodeCandidate(candidate: IceCandidate) = "${candidate.sdpMid.orEmpty()}\n${candidate.sdpMLineIndex}\n${candidate.sdp}"
private fun decodeCandidate(raw: String): IceCandidate? {
  val parts = raw.split('\n', limit = 3)
  return if (parts.size == 3) IceCandidate(parts[0], parts[1].toIntOrNull() ?: return null, parts[2]) else null
}
