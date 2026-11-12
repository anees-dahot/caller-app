package com.anees.caller.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Receives ACTION_CALL from other apps and places the call through Telecom. */
class CallRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent?.data?.schemeSpecificPart
        if (intent?.action == Intent.ACTION_CALL && !number.isNullOrBlank()) {
            Dialer.call(this, number)
        }
        finish()
    }
}
