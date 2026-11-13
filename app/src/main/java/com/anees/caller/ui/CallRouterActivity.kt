package com.anees.caller.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** Receives ACTION_CALL from other apps and places the call through Telecom. */
class CallRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent?.data?.schemeSpecificPart
        if (intent?.action != Intent.ACTION_CALL || number.isNullOrBlank()) { finish(); return }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            // No SIM dialog here: this activity closes at once, so Telecom asks instead.
            Dialer.call(this, number, ask = false)
            finish()
        } else if (savedInstanceState == null) {
            // Stay open until the permission answer arrives.
            Dialer.call(this, number, ask = false)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Dialer.onPermissionResult(this, requestCode, grantResults)
        finish()
    }
}
