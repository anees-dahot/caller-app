package com.anees.caller.ui

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.widget.TextView
import com.anees.caller.data.Repo

/** Stable, muted per-person tint. Unknown numbers get neutral grey. */
object Avatar {
    // (light bg, light fg, dark bg, dark fg)
    private val tones = arrayOf(
        intArrayOf(0xFFE4EBFF.toInt(), 0xFF3552B5.toInt(), 0xFF1C2747.toInt(), 0xFF9DB3FF.toInt()),
        intArrayOf(0xFFFFE6DE.toInt(), 0xFFB23E1C.toInt(), 0xFF3D2018.toInt(), 0xFFFFAA8F.toInt()),
        intArrayOf(0xFFDDF2E6.toInt(), 0xFF17774A.toInt(), 0xFF143323.toInt(), 0xFF7FDBAA.toInt()),
        intArrayOf(0xFFFFF0CF.toInt(), 0xFF8D5B00.toInt(), 0xFF372A10.toInt(), 0xFFF2C66D.toInt()),
        intArrayOf(0xFFEEE5FF.toInt(), 0xFF6539B5.toInt(), 0xFF2A1F45.toInt(), 0xFFC4A8FF.toInt()),
        intArrayOf(0xFFDCF2F5.toInt(), 0xFF116D7A.toInt(), 0xFF123238.toInt(), 0xFF7ED3DE.toInt()),
        intArrayOf(0xFFFBE3EE.toInt(), 0xFFA52F66.toInt(), 0xFF3A1A29.toInt(), 0xFFF59BC3.toInt()),
    )
    private val neutral = intArrayOf(0xFFE7E9ED.toInt(), 0xFF5A6370.toInt(), 0xFF23272D.toInt(), 0xFFA2AAB5.toInt())

    fun bind(v: TextView, name: String?, forceDark: Boolean = false) {
        val night = forceDark ||
            (v.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val t = if (name.isNullOrBlank()) neutral else tones[(name.hashCode() and 0x7fffffff) % tones.size]
        v.backgroundTintList = ColorStateList.valueOf(if (night) t[2] else t[0])
        v.setTextColor(if (night) t[3] else t[1])
        v.text = if (name.isNullOrBlank()) "#" else Repo.initial(name)
    }

    @Suppress("unused")
    private fun alpha(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
}
