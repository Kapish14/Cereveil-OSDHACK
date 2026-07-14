package com.cereveil.child.remoteaudio

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cereveil.CereveilApplication
import com.cereveil.R
import com.cereveil.child.enrollment.ChildInstallationMetadata
import com.cereveil.child.enrollment.SharedPreferencesChildEnrollmentStateStore
import dev.convex.android.ConvexClientWithAuth
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

private const val REQUEST_CHANNEL = "remote_audio_requests"
private const val SESSION_CHANNEL = "remote_audio_session"
private const val REQUEST_NOTIFICATION = 3601
private const val SESSION_NOTIFICATION = 3602
private const val EXTRA_REQUEST_ID = "remote_audio_request_id"
private const val REQUEST_STATE_PREFERENCES = "remote_audio_request_state"

internal data class PendingRemoteAudioRequest(val requestId: String, val expiresAt: Long)

internal object ChildRemoteAudioPendingStore {
  fun save(context: Context, requestId: String, expiresAt: Long) {
    context.getSharedPreferences(REQUEST_STATE_PREFERENCES, Context.MODE_PRIVATE).edit()
      .putString("requestId", requestId)
      .putLong("expiresAt", expiresAt)
      .apply()
  }

  fun load(context: Context): PendingRemoteAudioRequest? {
    val preferences = context.getSharedPreferences(REQUEST_STATE_PREFERENCES, Context.MODE_PRIVATE)
    val requestId = preferences.getString("requestId", null) ?: return null
    val expiresAt = preferences.getLong("expiresAt", 0L)
    if (expiresAt <= System.currentTimeMillis()) {
      clear(context)
      return null
    }
    return PendingRemoteAudioRequest(requestId, expiresAt)
  }

  fun clear(context: Context) {
    context.getSharedPreferences(REQUEST_STATE_PREFERENCES, Context.MODE_PRIVATE).edit().clear().apply()
  }
}

object ChildRemoteAudioNotice {
  fun present(context: Context, requestId: String, expiresAt: Long): Boolean {
    if (expiresAt <= System.currentTimeMillis()) return false
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      terminate(context, requestId, "notification_unavailable")
      return false
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    ChildRemoteAudioPendingStore.save(context, requestId, expiresAt)
    manager.createNotificationChannel(NotificationChannel(
      REQUEST_CHANNEL,
      "Remote audio requests",
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = "Disclosed requests to share live microphone audio"
      lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
      enableVibration(true)
    })
    val decline = PendingIntent.getBroadcast(
      context, requestId.hashCode(),
      Intent(context, ChildRemoteAudioActionReceiver::class.java).setAction("DECLINE").putExtra(EXTRA_REQUEST_ID, requestId),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val start = PendingIntent.getActivity(
      context, requestId.hashCode() xor 1,
      Intent(context, ChildRemoteAudioStartActivity::class.java).putExtra(EXTRA_REQUEST_ID, requestId),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    manager.notify(REQUEST_NOTIFICATION, NotificationCompat.Builder(context, REQUEST_CHANNEL)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Share live audio?")
      .setContentText("Your guardian requested up to two minutes of live microphone audio.")
      .setStyle(NotificationCompat.BigTextStyle().bigText(
        "Your guardian requested up to two minutes of live microphone audio. Audio starts only if you choose Start audio, and you can stop at any time.",
      ))
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setTimeoutAfter((expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
      .addAction(0, "Decline", decline)
      .addAction(0, "Start audio", start)
      .build())
    return true
  }

  fun dismiss(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(REQUEST_NOTIFICATION)

  fun terminate(context: Context, requestId: String, reason: String) {
    dismiss(context)
    ChildRemoteAudioPendingStore.clear(context)
    val app = context.applicationContext as CereveilApplication
    val installationId = ChildInstallationMetadata(app).installationId()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      app.convex.mutation<JsonElement>("modules/remoteAudio/child:terminateRemoteAudioRequest", mapOf(
        "childInstallationId" to installationId,
        "requestId" to requestId,
        "reason" to reason,
      ))
    }
  }
}

object ChildRemoteAudioRecovery {
  fun reconcile(context: Context, client: ConvexClientWithAuth<String>) {
    val app = context.applicationContext
    if (SharedPreferencesChildEnrollmentStateStore(app).load() == null) return
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      if (client.loginFromCache().isFailure) return@launch
      val installation = ChildInstallationMetadata(app).installationId()
      val state = client.subscribe<JsonElement>("modules/remoteAudio/child:getRemoteAudioState", mapOf(
        "childInstallationId" to installation,
      )).first().getOrNull()?.jsonObject ?: return@launch
      val request = state.remoteObjectOrNull("request") ?: return@launch
      if (request.remoteString("status") == "connecting" || request.remoteString("status") == "active") {
        client.mutation<JsonElement>("modules/remoteAudio/child:terminateRemoteAudioRequest", mapOf(
          "childInstallationId" to installation,
          "requestId" to request.remoteString("requestId"),
          "reason" to "interrupted",
        ))
      }
    }
  }
}

class ChildRemoteAudioActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
    when (intent.action) {
      "DECLINE" -> ChildRemoteAudioNotice.terminate(context, requestId, "declined")
      "STOP" -> ContextCompat.startForegroundService(context, ChildRemoteAudioService.stopIntent(context, requestId))
    }
  }
}

class ChildRemoteAudioStartActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return finish()
    val keyguard = getSystemService(KeyguardManager::class.java)
    if (!keyguard.isDeviceLocked) return start(requestId)
    keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
      override fun onDismissSucceeded() = start(requestId)
      override fun onDismissCancelled() = finish()
      override fun onDismissError() = finish()
    })
  }

  private fun start(requestId: String) {
    runCatching { ContextCompat.startForegroundService(this, ChildRemoteAudioService.startIntent(this, requestId)) }
      .onFailure { ChildRemoteAudioNotice.terminate(this, requestId, "microphone_unavailable") }
    finish()
  }

  companion object {
    fun intent(context: Context, requestId: String) = Intent(context, ChildRemoteAudioStartActivity::class.java)
      .putExtra(EXTRA_REQUEST_ID, requestId)
  }
}

class ChildRemoteAudioService : Service() {
  private val serviceJob = SupervisorJob()
  private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
  private var session: ChildWebRtcSession? = null
  private var requestId: String? = null
  private var deadlineJob: Job? = null
  private val audioManager by lazy { getSystemService(AudioManager::class.java) }
  private val audioFocus by lazy {
    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
      .setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build())
      .setOnAudioFocusChangeListener { change ->
        if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
          requestId?.let { stopLocalThenTerminate(it, "interrupted") }
        }
      }
      .build()
  }

  override fun onBind(intent: Intent?): IBinder? = null
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val id = intent?.getStringExtra(EXTRA_REQUEST_ID) ?: return START_NOT_STICKY
    if (intent.action == "STOP") {
      stopLocalThenTerminate(id, "stopped")
      return START_NOT_STICKY
    }
    if (requestId != null) return START_NOT_STICKY
    requestId = id
    try {
      startDisclosedForeground(id)
    } catch (_: Exception) {
      requestId = null
      ChildRemoteAudioNotice.terminate(this, id, "notification_unavailable")
      stopSelf()
      return START_NOT_STICKY
    }
    ChildRemoteAudioNotice.dismiss(this)
    scope.launch { connect(id) }
    return START_NOT_STICKY
  }

  private fun startDisclosedForeground(id: String) {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(
      SESSION_CHANNEL, "Live audio sharing", NotificationManager.IMPORTANCE_HIGH,
    ).apply { lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC })
    val stop = PendingIntent.getBroadcast(
      this, id.hashCode() xor 2,
      Intent(this, ChildRemoteAudioActionReceiver::class.java).setAction("STOP").putExtra(EXTRA_REQUEST_ID, id),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(this, SESSION_CHANNEL)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Sharing live microphone audio")
      .setContentText("Connecting… You can stop at any time.")
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .addAction(0, "Stop", stop)
      .build()
    ServiceCompat.startForeground(this, SESSION_NOTIFICATION, notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
  }

  private suspend fun connect(id: String) {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      stopLocalThenTerminate(id, "microphone_unavailable"); return
    }
    if (audioManager.requestAudioFocus(audioFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      stopLocalThenTerminate(id, "interrupted"); return
    }
    val app = application as CereveilApplication
    val authenticated = app.convex as? ConvexClientWithAuth<String>
    if (authenticated == null || authenticated.loginFromCache().isFailure) {
      stopLocalThenTerminate(id, "interrupted"); return
    }
    val installation = ChildInstallationMetadata(this).installationId()
    val args = mapOf("childInstallationId" to installation)
    val state = app.convex.subscribe<JsonElement>("modules/remoteAudio/child:getRemoteAudioState", args)
      .first().getOrNull()?.jsonObject
    val request = state?.remoteObjectOrNull("request")
    if (request?.remoteString("requestId") != id) { stopLocal(); return }
    val expiresAt = request.remoteLong("expiresAt")
    val serverNow = state.remoteLong("serverNow")
    val remaining = (expiresAt - serverNow).coerceAtMost(TimeUnit.MINUTES.toMillis(2))
    if (remaining <= 0) { stopLocalThenTerminate(id, "interrupted"); return }
    deadlineJob = scope.launch { delay(remaining); stopLocalThenTerminate(id, "interrupted") }
    val stunUrls = state.remoteArray("stunUrls").map { it.jsonPrimitive.content }
    if (!microphoneReady()) { stopLocalThenTerminate(id, "microphone_unavailable"); return }
    val createdSession = runCatching { ChildWebRtcSession(this, stunUrls,
      publish = { type, key, payload -> scope.launch {
        app.convex.mutation<JsonElement>("modules/remoteAudio/child:publishRemoteAudioSignal", mapOf(
          "childInstallationId" to installation, "requestId" to id, "type" to type,
          "idempotencyKey" to key, "payload" to payload,
        ))
      } },
      connected = {
        showActiveNotification(id)
        scope.launch { app.convex.mutation<JsonElement>("modules/remoteAudio/child:markRemoteAudioActive", mapOf(
          "childInstallationId" to installation, "requestId" to id,
        )) }
      },
      failed = { stopLocalThenTerminate(id, "webrtc_failed") },
    ) }.getOrElse { stopLocalThenTerminate(id, "webrtc_failed"); return }
    val started = runCatching { app.convex.mutation<JsonElement>("modules/remoteAudio/child:startRemoteAudioRequest", mapOf(
      "childInstallationId" to installation, "requestId" to id,
    )) }
    if (started.isFailure) { createdSession.close(); stopLocalThenTerminate(id, "webrtc_failed"); return }
    session = createdSession
    runCatching { createdSession.start() }
      .onFailure { stopLocalThenTerminate(id, "webrtc_failed"); return }
    app.convex.subscribe<JsonElement>("modules/remoteAudio/child:getRemoteAudioState", args).collect { result ->
      val value = result.getOrNull()?.jsonObject ?: return@collect
      val live = value.remoteObjectOrNull("request")
      if (live?.remoteString("requestId") != id) { stopLocal(); return@collect }
      val signals = value.remoteArray("signals").map { it.jsonObject }
      signals.forEach { session?.receive(it) }
    }
  }

  private fun microphoneReady(): Boolean =
    ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
      packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

  private fun showActiveNotification(id: String) {
    val stop = PendingIntent.getBroadcast(
      this, id.hashCode() xor 2,
      Intent(this, ChildRemoteAudioActionReceiver::class.java).setAction("STOP").putExtra(EXTRA_REQUEST_ID, id),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    getSystemService(NotificationManager::class.java).notify(SESSION_NOTIFICATION,
      NotificationCompat.Builder(this, SESSION_CHANNEL)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Sharing live microphone audio")
        .setContentText("Live audio is active. You can stop at any time.")
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOngoing(true).setOnlyAlertOnce(true)
        .addAction(0, "Stop", stop).build())
  }

  private fun stopLocalThenTerminate(id: String, reason: String) {
    stopLocal()
    ChildRemoteAudioNotice.terminate(this, id, reason)
  }

  private fun stopLocal() {
    deadlineJob?.cancel(); deadlineJob = null
    val closingSession = session
    session = null
    closingSession?.close()
    audioManager.abandonAudioFocusRequest(audioFocus)
    requestId = null
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    val interruptedRequest = requestId
    stopLocal()
    if (interruptedRequest != null) ChildRemoteAudioNotice.terminate(this, interruptedRequest, "interrupted")
    scope.cancel()
    super.onDestroy()
  }

  companion object {
    fun startIntent(context: Context, id: String) = Intent(context, ChildRemoteAudioService::class.java)
      .setAction("START").putExtra(EXTRA_REQUEST_ID, id)
    fun stopIntent(context: Context, id: String) = Intent(context, ChildRemoteAudioService::class.java)
      .setAction("STOP").putExtra(EXTRA_REQUEST_ID, id)
  }
}

private class ChildWebRtcSession(
  context: Context,
  stunUrls: List<String>,
  private val publish: (String, String, String) -> Unit,
  private val connected: () -> Unit,
  private val failed: () -> Unit,
) : PeerConnection.Observer {
  private val factory: PeerConnectionFactory
  private val source: AudioSource
  private val track: AudioTrack
  private val peer: PeerConnection
  private val seenSignals = mutableSetOf<String>()
  private var disconnected: Job? = null
  private var closedLocally = false
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  init {
    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
    val egl = EglBase.create()
    factory = PeerConnectionFactory.builder()
      .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, false, false))
      .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
      .createPeerConnectionFactory()
    val constraints = MediaConstraints().apply {
      mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
      mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
      mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "false")
    }
    source = factory.createAudioSource(constraints)
    track = factory.createAudioTrack("cereveil-live-audio", source)
    track.setEnabled(false)
    peer = requireNotNull(factory.createPeerConnection(
      PeerConnection.RTCConfiguration(stunUrls.map { PeerConnection.IceServer.builder(it).createIceServer() }).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
      }, this,
    ))
    peer.addTrack(track, listOf("cereveil-live"))
    context.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
  }

  fun start() {
    track.setEnabled(true)
    peer.createOffer(SdpSetObserver { description ->
      val sendOnly = configureOpus(description.description.replace("a=sendrecv", "a=sendonly"))
      val local = SessionDescription(description.type, sendOnly)
      peer.setLocalDescription(EmptySdpObserver(), local)
      publish("offer", UUID.randomUUID().toString(), sendOnly)
    }, MediaConstraints())
  }

  fun receive(signal: JsonObject) {
    val id = signal.remoteStringOrNull("signalId") ?: return
    if (!seenSignals.add(id)) return
    when (signal.remoteStringOrNull("type")) {
      "answer" -> peer.setRemoteDescription(EmptySdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, signal.remoteString("payload")))
      "ice_candidate" -> decodeCandidate(signal.remoteString("payload"))?.let(peer::addIceCandidate)
    }
  }

  override fun onIceCandidate(candidate: IceCandidate) = publish("ice_candidate", UUID.randomUUID().toString(), encodeCandidate(candidate))
  override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
    if (closedLocally) return
    when (state) {
      PeerConnection.PeerConnectionState.CONNECTED -> { disconnected?.cancel(); connected() }
      PeerConnection.PeerConnectionState.DISCONNECTED -> {
        disconnected?.cancel(); disconnected = scope.launch { delay(5_000); failed() }
      }
      PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> failed()
      else -> Unit
    }
  }
  fun close() { closedLocally = true; track.setEnabled(false); peer.close(); source.dispose(); factory.dispose() }
  override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
  override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
  override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
  override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
  override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
  override fun onAddStream(stream: MediaStream) = Unit
  override fun onRemoveStream(stream: MediaStream) = Unit
  override fun onDataChannel(channel: DataChannel) = Unit
  override fun onRenegotiationNeeded() = Unit
  override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
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
private fun encodeCandidate(candidate: IceCandidate) = "${candidate.sdpMid.orEmpty()}\n${candidate.sdpMLineIndex}\n${candidate.sdp}"
private fun decodeCandidate(raw: String): IceCandidate? {
  val parts = raw.split('\n', limit = 3)
  return if (parts.size == 3) IceCandidate(parts[0], parts[1].toIntOrNull() ?: return null, parts[2]) else null
}
private fun configureOpus(sdp: String): String {
  val opusPayload = Regex("a=rtpmap:(\\d+) opus/48000", RegexOption.IGNORE_CASE).find(sdp)?.groupValues?.get(1)
    ?: return sdp
  val fmtp = Regex("a=fmtp:$opusPayload ([^\\r\\n]*)")
  val parameters = "minptime=10;useinbandfec=1;stereo=0;sprop-stereo=0;maxaveragebitrate=32000"
  return if (fmtp.containsMatchIn(sdp)) fmtp.replace(sdp) { "a=fmtp:$opusPayload ${it.groupValues[1]};$parameters" }
  else sdp.replace("a=rtpmap:$opusPayload opus/48000/2", "a=rtpmap:$opusPayload opus/48000/2\r\na=fmtp:$opusPayload $parameters")
}
