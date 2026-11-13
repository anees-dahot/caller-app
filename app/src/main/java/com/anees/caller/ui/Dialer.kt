package com.anees.caller.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

object Dialer {
    const val REQ_ROLE = 41

    fun isDefault(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) return rm.isRoleHeld(RoleManager.ROLE_DIALER)
        }
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        return tm.defaultDialerPackage == ctx.packageName
    }

    fun requestDefault(a: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = a.getSystemService(RoleManager::class.java)
                a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_ROLE)
            } else {
                val i = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, a.packageName)
                a.startActivityForResult(i, REQ_ROLE)
            }
        } catch (_: Exception) {
            // Some OEM ROMs (MIUI, old EMUI) lack the dialog: send user to default apps settings.
            try {
                a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(a, "Set Caller as default phone app in Settings", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun accounts(ctx: Context): List<PhoneAccountHandle> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        return try { tm.callCapablePhoneAccounts } catch (_: Exception) { emptyList() }
    }

    @SuppressLint("MissingPermission")
    fun accountLabel(ctx: Context, h: PhoneAccountHandle): String {
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        return try { tm.getPhoneAccount(h)?.label?.toString() } catch (_: Exception) { null } ?: "SIM"
    }

    const val REQ_CALL = 7
    private var pendingNumber: String? = null

    /** Call from onRequestPermissionsResult: places the call that was waiting for CALL_PHONE. */
    fun onPermissionResult(a: Activity, requestCode: Int, grants: IntArray) {
        if (requestCode != REQ_CALL) return
        val n = pendingNumber
        pendingNumber = null
        if (n != null && grants.isNotEmpty() && grants[0] == PackageManager.PERMISSION_GRANTED) call(a, n, ask = a !is CallRouterActivity)
    }

    /**
     * Place call. With 2+ SIMs and no default, ask first.
     * [ask] = false skips our SIM dialog (Telecom then asks), for activities that finish at once.
     */
    fun call(a: Activity, number: String, ask: Boolean = true) {
        val clean = number.trim()
        if (clean.isEmpty()) return
        if (ContextCompat.checkSelfPermission(a, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingNumber = clean
            a.requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
            return
        }
        val accts = accounts(a)
        val tm = a.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        @SuppressLint("MissingPermission")
        val def = try { tm.getDefaultOutgoingPhoneAccount("tel") } catch (_: Exception) { null }
        if (ask && accts.size > 1 && def == null && !a.isFinishing) {
            val labels = accts.mapIndexed { i, h -> "${i + 1}  ${accountLabel(a, h)}" }.toTypedArray()
            AlertDialog.Builder(a)
                .setTitle("Call with")
                .setItems(labels) { _, which -> place(a, clean, accts[which]) }
                .show()
        } else place(a, clean, null)
    }

    @SuppressLint("MissingPermission")
    private fun place(ctx: Context, number: String, handle: PhoneAccountHandle?) {
        val uri = Uri.fromParts("tel", number, null)
        val extras = Bundle()
        if (handle != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.placeCall(uri, extras)
        } catch (e: Exception) {
            try {
                // Never route ACTION_CALL back to ourselves (CallRouterActivity) or it loops.
                val i = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val target = i.resolveActivity(ctx.packageManager)
                if (target == null || target.packageName == ctx.packageName) throw e
                ctx.startActivity(i)
            } catch (_: Exception) {
                Toast.makeText(ctx, "Can't place call", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun sms(ctx: Context, number: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
        } catch (_: Exception) {
            Toast.makeText(ctx, "No messaging app", Toast.LENGTH_SHORT).show()
        }
    }

    fun addContact(ctx: Context, number: String?) {
        val i = Intent(Intent.ACTION_INSERT, android.provider.ContactsContract.Contacts.CONTENT_URI)
        if (!number.isNullOrBlank()) i.putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, number)
        try { ctx.startActivity(i) } catch (_: Exception) { Toast.makeText(ctx, "No contacts editor", Toast.LENGTH_SHORT).show() }
    }
}
