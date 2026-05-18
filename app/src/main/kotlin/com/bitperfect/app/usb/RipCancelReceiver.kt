package com.bitperfect.app.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RipCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RipSession.getInstance(context).cancel()
    }
}
