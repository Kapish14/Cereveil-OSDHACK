package com.cereveil

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.cereveil.child.enrollment.ChildEnrollmentContent
import com.cereveil.child.remoteaudio.ChildRemoteAudioBanner

@Composable
fun RoleStartupContent(modifier: Modifier = Modifier) {
  Box(modifier = modifier.fillMaxSize()) {
    ChildEnrollmentContent(modifier = Modifier.fillMaxSize())
    ChildRemoteAudioBanner(modifier = Modifier.fillMaxSize())
  }
}
