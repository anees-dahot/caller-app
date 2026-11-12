package com.anees.caller.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Android 15+ forces edge-to-edge; pad root so content clears bars and keyboard. No-op visually on older versions. */
fun Activity.padForSystemBars() {
    val root = (findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) ?: return
    val l = root.paddingLeft; val t = root.paddingTop; val r = root.paddingRight; val b = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { v: View, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + maxOf(bars.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
}
