package com.anees.caller.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ANSWER -> {
                CallManager.answer()
                CallManager.service?.openUi()
            }
            DECLINE -> CallManager.ringing()?.reject(false, null)
            HANGUP -> CallManager.primary()?.disconnect()
        }
    }

    companion object {
        const val ANSWER = "com.anees.caller.ANSWER"
        const val DECLINE = "com.anees.caller.DECLINE"
        const val HANGUP = "com.anees.caller.HANGUP"
    }
}
