package com.cereveil.child.enrollment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ChildLocationMovementReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    ChildSupervisionWork.enqueueNow(context)
  }
}
