package com.cereveil.child.protection

import android.accessibilityservice.AccessibilityService
import android.net.VpnService
import android.service.notification.NotificationListenerService
import android.view.accessibility.AccessibilityEvent

class CereveilAccessibilityService : AccessibilityService() {
  override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
  override fun onInterrupt() = Unit
}

class CereveilNotificationListenerService : NotificationListenerService()

class CereveilVpnService : VpnService()
