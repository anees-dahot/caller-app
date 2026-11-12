package com.anees.caller.call

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.view.View
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.anees.caller.R
import com.anees.caller.data.Repo
import com.anees.caller.ui.Avatar
import com.anees.caller.ui.Dialer
import com.anees.caller.ui.MainActivity
import com.anees.caller.ui.padForSystemBars

class CallActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val names = HashMap<String, String?>()
    private var proximity: PowerManager.WakeLock? = null
    private var dtmf = StringBuilder()
    private var timerBase = 0L
    private var accountDialogShown = false

    private lateinit var state: TextView
    private lateinit var name: TextView
    private lateinit var number: TextView
    private lateinit var timer: Chronometer
    private lateinit var second: TextView
    private lateinit var mute: View
    private lateinit var speaker: View
    private lateinit var keys: View
    private lateinit var hold: View
    private lateinit var swap: View
    private lateinit var merge: View
    private lateinit var dtmfPanel: View
    private lateinit var speakerLabel: TextView
    private lateinit var avatar: TextView

    private val listener: () -> Unit = { main.post { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContentView(R.layout.activity_call)
        padForSystemBars()

        state = findViewById(R.id.state)
        name = findViewById(R.id.callName)
        number = findViewById(R.id.callNumber)
        timer = findViewById(R.id.timer)
        second = findViewById(R.id.second)
        mute = findViewById(R.id.mute)
        speaker = findViewById(R.id.speaker)
        keys = findViewById(R.id.keys)
        hold = findViewById(R.id.hold)
        swap = findViewById(R.id.swap)
        merge = findViewById(R.id.merge)
        dtmfPanel = findViewById(R.id.dtmfPanel)
        speakerLabel = findViewById(R.id.speakerLabel)
        avatar = findViewById(R.id.callAvatar)

        findViewById<View>(R.id.answer).setOnClickListener { CallManager.answer() }
        findViewById<View>(R.id.decline).setOnClickListener { CallManager.ringing()?.reject(false, null) }
        findViewById<View>(R.id.smsReply).setOnClickListener { declineWithMessage() }
        findViewById<View>(R.id.end).setOnClickListener { CallManager.primary()?.disconnect() }

        mute.setOnClickListener {
            val m = CallManager.audio?.isMuted ?: false
            @Suppress("DEPRECATION") CallManager.service?.setMuted(!m)
        }
        speaker.setOnClickListener { audioRoute() }
        keys.setOnClickListener {
            dtmfPanel.visibility = if (dtmfPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            render()
        }
        hold.setOnClickListener {
            val c = CallManager.primary() ?: return@setOnClickListener
            if (CallManager.stateOf(c) == Call.STATE_HOLDING) c.unhold() else c.hold()
        }
        swap.setOnClickListener {
            // Unholding the held call puts the active one on hold automatically.
            CallManager.secondary()?.let { if (CallManager.stateOf(it) == Call.STATE_HOLDING) it.unhold() }
        }
        merge.setOnClickListener {
            val p = CallManager.primary() ?: return@setOnClickListener
            val other = p.conferenceableCalls.firstOrNull()
            if (other != null) p.conference(other)
            else if (p.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)) p.mergeConference()
        }

        val grid = findViewById<LinearLayout>(R.id.dtmfGrid)
        val pad = listOf(
            "1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "",
        )
        MainActivity.buildKeyGrid(this, grid, pad, R.color.call_ink, R.color.call_ink_2, R.drawable.key_round_dark, 72) { k, long ->
            if (long) return@buildKeyGrid
            val c = CallManager.primary() ?: return@buildKeyGrid
            c.playDtmfTone(k[0])
            main.postDelayed({ c.stopDtmfTone() }, 150)
            dtmf.append(k)
            findViewById<TextView>(R.id.dtmfText).text = dtmf
        }

        @Suppress("DEPRECATION")
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "caller:proximity")
        }
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        CallManager.listen(listener)
        render()
    }

    override fun onStop() {
        super.onStop()
        CallManager.unlisten(listener)
        releaseProximity()
    }

    @SuppressLint("MissingSuperCall")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Don't end the call; just leave the screen. Ringing calls stay up.
        if (CallManager.ringing() != null) return
        moveTaskToBack(true)
    }

    private fun lookup(num: String): String? = names.getOrPut(num) { Repo.lookupName(this, num)?.first }

    private fun render() {
        val call = CallManager.primary()
        if (call == null || !CallManager.hasLive()) {
            state.text = "Call ended"
            timer.stop()
            releaseProximity()
            main.postDelayed({ if (!CallManager.hasLive()) finishAndRemoveTask() }, 900)
            return
        }
        val s = CallManager.stateOf(call)
        val isConf = call.children.isNotEmpty()
        val num = call.details.handle?.schemeSpecificPart ?: ""
        val known = if (num.isNotBlank()) lookup(num) else null
        name.text = when {
            isConf -> "Conference (${call.children.size})"
            known != null -> known
            num.isBlank() -> getString(R.string.private_number)
            else -> num
        }
        Avatar.bind(avatar, if (isConf) "Conference" else known, forceDark = true)
        number.text = if (known != null && !isConf) num else ""
        number.visibility = if (number.text.isEmpty()) View.GONE else View.VISIBLE

        val label = accountLabel(call)
        state.text = CallService.stateText(s) + (label?.let { "  ·  $it" } ?: "")

        val ringing = s == Call.STATE_RINGING
        findViewById<View>(R.id.incoming).visibility = if (ringing) View.VISIBLE else View.GONE
        findViewById<View>(R.id.smsReply).visibility =
            if (ringing && call.details.can(Call.Details.CAPABILITY_RESPOND_VIA_TEXT)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.end).visibility = if (ringing) View.GONE else View.VISIBLE
        findViewById<View>(R.id.controls).visibility = if (ringing) View.INVISIBLE else View.VISIBLE
        if (ringing) dtmfPanel.visibility = View.GONE

        // Timer from real connect time so it survives screen re-creation.
        val connected = call.details.connectTimeMillis
        if (s == Call.STATE_ACTIVE && connected > 0) {
            val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - connected)
            if (base != timerBase) { timerBase = base; timer.base = base }
            timer.visibility = View.VISIBLE
            timer.start()
        } else if (s != Call.STATE_HOLDING) {
            timer.stop()
            timer.visibility = View.GONE
        }

        val other = CallManager.secondary()
        if (other != null) {
            val on = other.details.handle?.schemeSpecificPart ?: ""
            val n = lookup(on) ?: on.ifBlank { getString(R.string.private_number) }
            second.visibility = View.VISIBLE
            second.text = "$n  ·  ${CallService.stateText(CallManager.stateOf(other))}"
        } else second.visibility = View.GONE

        val audio = CallManager.audio
        mute.isActivated = audio?.isMuted == true
        val route = audio?.route ?: CallAudioState.ROUTE_EARPIECE
        val btAvail = (audio?.supportedRouteMask ?: 0) and CallAudioState.ROUTE_BLUETOOTH != 0
        speakerLabel.text = when {
            route == CallAudioState.ROUTE_BLUETOOTH -> "Bluetooth"
            route == CallAudioState.ROUTE_WIRED_HEADSET -> "Headset"
            btAvail -> "Audio"
            else -> "Speaker"
        }
        speaker.isActivated = route == CallAudioState.ROUTE_SPEAKER || route == CallAudioState.ROUTE_BLUETOOTH
        keys.isActivated = dtmfPanel.visibility == View.VISIBLE

        val canHold = call.details.can(Call.Details.CAPABILITY_HOLD) || call.details.can(Call.Details.CAPABILITY_SUPPORT_HOLD)
        hold.able(canHold && (s == Call.STATE_ACTIVE || s == Call.STATE_HOLDING))
        hold.isActivated = s == Call.STATE_HOLDING
        swap.able(other != null && CallManager.stateOf(other) == Call.STATE_HOLDING)
        merge.able(call.conferenceableCalls.isNotEmpty() || call.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE))

        // Proximity: only during an earpiece call, so the cheek doesn't press buttons.
        if (!ringing && route == CallAudioState.ROUTE_EARPIECE && s != Call.STATE_DISCONNECTED) acquireProximity() else releaseProximity()

        if (s == Call.STATE_SELECT_PHONE_ACCOUNT && !accountDialogShown) pickAccount(call)
    }

    @SuppressLint("MissingPermission")
    private fun accountLabel(call: Call): String? {
        val h = call.details.accountHandle ?: return null
        if (Dialer.accounts(this).size < 2) return null
        return Dialer.accountLabel(this, h)
    }

    private fun audioRoute() {
        val audio = CallManager.audio ?: return
        val svc = CallManager.service ?: return
        val mask = audio.supportedRouteMask
        val options = mutableListOf<Pair<String, Int>>()
        if (mask and CallAudioState.ROUTE_BLUETOOTH != 0) options.add("Bluetooth" to CallAudioState.ROUTE_BLUETOOTH)
        if (mask and CallAudioState.ROUTE_SPEAKER != 0) options.add("Speaker" to CallAudioState.ROUTE_SPEAKER)
        if (mask and CallAudioState.ROUTE_WIRED_HEADSET != 0) options.add("Wired headset" to CallAudioState.ROUTE_WIRED_HEADSET)
        else if (mask and CallAudioState.ROUTE_EARPIECE != 0) options.add("Phone" to CallAudioState.ROUTE_EARPIECE)

        @Suppress("DEPRECATION")
        if (options.size <= 2 && mask and CallAudioState.ROUTE_BLUETOOTH == 0) {
            // Simple toggle: speaker on/off.
            svc.setAudioRoute(if (audio.route == CallAudioState.ROUTE_SPEAKER) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER)
        } else {
            AlertDialog.Builder(this, R.style.Dialog_Call)
                .setItems(options.map { it.first }.toTypedArray()) { _, i -> svc.setAudioRoute(options[i].second) }
                .show()
        }
    }

    private fun pickAccount(call: Call) {
        val accts: List<PhoneAccountHandle> = Dialer.accounts(this)
        if (accts.isEmpty()) return
        accountDialogShown = true
        AlertDialog.Builder(this, R.style.Dialog_Call)
            .setTitle("Call with")
            .setItems(accts.map { Dialer.accountLabel(this, it) }.toTypedArray()) { _, i -> call.phoneAccountSelected(accts[i], false) }
            .setOnCancelListener { call.disconnect() }
            .show()
    }

    private fun declineWithMessage() {
        val call = CallManager.ringing() ?: return
        val canned = arrayOf("Can't talk now. Call you later.", "I'm on my way.", "Busy, text me.", "Custom…")
        AlertDialog.Builder(this, R.style.Dialog_Call)
            .setItems(canned) { _, i ->
                if (i < canned.size - 1) call.reject(true, canned[i])
                else {
                    val input = EditText(this)
                    AlertDialog.Builder(this, R.style.Dialog_Call)
                        .setView(input)
                        .setPositiveButton("Send") { _, _ -> call.reject(true, input.text.toString()) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }.show()
    }

    private fun acquireProximity() {
        val p = proximity ?: return
        if (!p.isHeld) p.acquire(3 * 60 * 60 * 1000L)
    }

    private fun releaseProximity() {
        val p = proximity ?: return
        if (p.isHeld) p.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
    }

    private fun View.able(on: Boolean) { isEnabled = on; alpha = if (on) 1f else 0.35f }

    @Suppress("unused")
    private fun isLocked() = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    @Suppress("unused")
    private fun telecom() = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
}
