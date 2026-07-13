package com.cereveil.remoteaudio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteAudioCoreTest {
  @Test fun `deadline uses monotonic elapsed time`() {
    val clock = RemoteAudioClock(expiresAtEpochMs = 120_000, serverNowEpochMs = 20_000, anchoredElapsedMs = 5_000)
    assertEquals(70_000, clock.remainingMs(35_000))
    assertEquals(0, clock.remainingMs(200_000))
  }

  @Test fun `stop closes local media before remote termination and is idempotent`() = runTest {
    val events = mutableListOf<String>()
    val coordinator = RemoteAudioSessionCoordinator(
      closeLocalMedia = { events += "local" },
      terminateRemote = { events += "remote"; error("network") },
    )
    coordinator.connecting(); coordinator.active(); coordinator.stop(); coordinator.stop()
    assertEquals(listOf("local", "remote"), events)
    assertEquals(RemoteAudioPhase.Ended, coordinator.phase)
  }
}
