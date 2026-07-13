package com.cereveil.remoteaudio

import android.os.SystemClock

enum class RemoteAudioPhase { AwaitingChild, Connecting, Active, Ended }

data class RemoteAudioClock(
  val expiresAtEpochMs: Long,
  val serverNowEpochMs: Long,
  val anchoredElapsedMs: Long,
) {
  fun remainingMs(elapsedMs: Long): Long =
    (expiresAtEpochMs - serverNowEpochMs - (elapsedMs - anchoredElapsedMs)).coerceAtLeast(0)

  companion object {
    fun anchored(expiresAtEpochMs: Long, serverNowEpochMs: Long) =
      RemoteAudioClock(expiresAtEpochMs, serverNowEpochMs, SystemClock.elapsedRealtime())
  }
}

/** Owns only ephemeral endpoint state. It deliberately has no persistence seam. */
class RemoteAudioSessionCoordinator(
  private val closeLocalMedia: () -> Unit,
  private val terminateRemote: suspend () -> Unit,
) {
  var phase: RemoteAudioPhase = RemoteAudioPhase.AwaitingChild
    private set
  private var ending = false

  fun connecting() {
    check(phase == RemoteAudioPhase.AwaitingChild)
    phase = RemoteAudioPhase.Connecting
  }

  fun active() {
    check(phase == RemoteAudioPhase.Connecting)
    phase = RemoteAudioPhase.Active
  }

  suspend fun stop() {
    if (ending || phase == RemoteAudioPhase.Ended) return
    ending = true
    closeLocalMedia()
    phase = RemoteAudioPhase.Ended
    runCatching { terminateRemote() }
  }
}
