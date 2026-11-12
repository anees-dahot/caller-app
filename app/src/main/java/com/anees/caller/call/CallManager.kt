package com.anees.caller.call

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/** Single source of truth for live calls. Main thread only. */
object CallManager {
    val calls = CopyOnWriteArrayList<Call>()
    var service: CallService? = null
    var audio: CallAudioState? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    fun changed() { listeners.forEach { it() } }

    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) { changed(); service?.refreshNotification() }
        override fun onDetailsChanged(call: Call, details: Call.Details) = changed()
        override fun onChildrenChanged(call: Call, children: MutableList<Call>) = changed()
        override fun onConferenceableCallsChanged(call: Call, c: MutableList<Call>) = changed()
    }

    fun add(call: Call) {
        calls.add(call)
        call.registerCallback(cb)
        changed()
    }

    fun remove(call: Call) {
        call.unregisterCallback(cb)
        calls.remove(call)
        changed()
    }

    @Suppress("DEPRECATION")
    fun stateOf(c: Call): Int = c.state

    /** Top-level calls only (conference children are hidden inside their parent). */
    private fun top() = calls.filter { it.parent == null && stateOf(it) != Call.STATE_DISCONNECTED }

    fun ringing(): Call? = top().firstOrNull { stateOf(it) == Call.STATE_RINGING }

    /** Call shown big on screen. */
    fun primary(): Call? {
        val t = top()
        return t.firstOrNull { stateOf(it) == Call.STATE_RINGING }
            ?: t.firstOrNull { stateOf(it) in setOf(Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT) }
            ?: t.firstOrNull()
            ?: calls.lastOrNull()
    }

    fun secondary(): Call? {
        val p = primary() ?: return null
        return top().firstOrNull { it != p }
    }

    fun answer() = ringing()?.answer(VideoProfile.STATE_AUDIO_ONLY)

    fun hangup() {
        val r = ringing()
        if (r != null) { r.reject(false, null); return }
        primary()?.disconnect()
    }

    fun hasLive() = top().isNotEmpty()
}
