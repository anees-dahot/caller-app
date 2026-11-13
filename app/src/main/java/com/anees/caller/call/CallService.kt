package com.anees.caller.call

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.anees.caller.R
import com.anees.caller.data.Repo

class CallService : InCallService() {

    override fun onCreate() {
        super.onCreate()
        CallManager.service = this
        channels()
    }

    override fun onDestroy() {
        if (CallManager.service === this) CallManager.service = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.add(call)
        val ringing = CallManager.stateOf(call) == Call.STATE_RINGING
        if (!ringing || canShowDirectly()) openUi()
        refreshNotification()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallManager.remove(call)
        if (!CallManager.hasLive()) {
            nm().cancel(NOTIF_ID)
            CallManager.audio = null
        } else refreshNotification()
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallManager.audio = audioState
        CallManager.changed()
    }

    /** Screen on + unlocked: show call screen at once. Locked/off: rely on full-screen intent. */
    private fun canShowDirectly(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return pm.isInteractive && !km.isKeyguardLocked
    }

    fun openUi() {
        try {
            startActivity(
                Intent(this, CallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            )
        } catch (_: Exception) {
        }
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun channels() {
        if (Build.VERSION.SDK_INT < 26) return
        val incoming = NotificationChannel(CH_INCOMING, getString(R.string.channel_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // Telecom plays the ringtone.
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val ongoing = NotificationChannel(CH_ONGOING, getString(R.string.channel_ongoing), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
        }
        nm().createNotificationChannels(listOf(incoming, ongoing))
    }

    private fun pi(action: String, req: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this, req, Intent(this, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun refreshNotification() {
        val call = CallManager.primary() ?: return
        val state = CallManager.stateOf(call)
        if (state == Call.STATE_DISCONNECTED) return
        val number = call.details.handle?.schemeSpecificPart ?: ""
        val name = Repo.lookupName(this, number)?.first ?: number.ifBlank { getString(R.string.private_number) }
        val ringing = state == Call.STATE_RINGING

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, if (ringing) CH_INCOMING else CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(name)
            .setContentText(if (ringing) "Incoming call" else stateText(state))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(!ringing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .setContentIntent(open)

        if (ringing) {
            b.priority = NotificationCompat.PRIORITY_MAX
            b.setFullScreenIntent(open, true)
            b.addAction(0, getString(R.string.decline), pi(CallActionReceiver.DECLINE, 1))
            // Answer opens the call screen directly: Android 12+ blocks starting
            // activities from a broadcast fired by a notification action.
            val answer = PendingIntent.getActivity(
                this, 2,
                Intent(this, CallActivity::class.java).setAction(CallActionReceiver.ANSWER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, getString(R.string.answer), answer)
        } else {
            b.priority = NotificationCompat.PRIORITY_LOW
            val connected = call.details.connectTimeMillis
            if (state == Call.STATE_ACTIVE && connected > 0) {
                b.setWhen(connected).setUsesChronometer(true).setShowWhen(true)
            }
            b.addAction(0, getString(R.string.hang_up), pi(CallActionReceiver.HANGUP, 3))
        }
        try {
            nm().notify(NOTIF_ID, b.build())
        } catch (_: Exception) {
            // FSI not permitted / notifications blocked: at least try to show UI.
            if (ringing) openUi()
        }
    }

    companion object {
        const val NOTIF_ID = 1001
        const val CH_INCOMING = "incoming"
        const val CH_ONGOING = "ongoing"

        fun stateText(s: Int) = when (s) {
            Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling…"
            Call.STATE_RINGING -> "Incoming call"
            Call.STATE_ACTIVE -> "On call"
            Call.STATE_HOLDING -> "On hold"
            Call.STATE_DISCONNECTING -> "Ending…"
            Call.STATE_DISCONNECTED -> "Call ended"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose SIM"
            else -> "Call"
        }
    }
}
