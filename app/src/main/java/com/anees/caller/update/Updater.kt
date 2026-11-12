package com.anees.caller.update

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.anees.caller.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer APK, downloads it and hands it to the
 * system installer. Same signing key = installs over the top, data kept.
 */
object Updater {
    private const val PREFS = "updater"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    fun checkOnLaunch(a: Activity) {
        val p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("last", 0) < CHECK_EVERY_MS) return
        p.edit().putLong("last", System.currentTimeMillis()).apply()
        check(a, manual = false)
    }

    fun check(a: Activity, manual: Boolean) {
        if (running) return
        running = true
        Thread {
            val r = try { fetchLatest() } catch (_: Exception) { null }
            running = false
            main.post {
                if (a.isFinishing) return@post
                val skipped = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("skip", null)
                when {
                    r == null -> if (manual) Toast.makeText(a, "Couldn't check for updates", Toast.LENGTH_SHORT).show()
                    !newer(r.version, BuildConfig.VERSION_NAME) -> if (manual) Toast.makeText(a, "Up to date (${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                    !manual && skipped == r.version -> Unit
                    else -> prompt(a, r)
                }
            }
        }.start()
    }

    private fun fetchLatest(): Release? {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Caller-Android")
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(tag, json.optString("body"), a.getString("browser_download_url"), a.optLong("size"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** "1.2.10" > "1.2.9". Non-numeric parts ignored. */
    fun newer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun prompt(a: Activity, r: Release) {
        val mb = if (r.size > 0) " · %.1f MB".format(r.size / 1_048_576.0) else ""
        AlertDialog.Builder(a)
            .setTitle("Update to ${r.version}")
            .setMessage((r.notes.ifBlank { "A new version is ready." }).take(1200) + "\n\nInstalled ${BuildConfig.VERSION_NAME}$mb")
            .setPositiveButton("Update") { _, _ -> download(a, r) }
            .setNegativeButton("Later", null)
            .setNeutralButton("Skip") { _, _ ->
                a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("skip", r.version).apply()
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun download(a: Activity, r: Release) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "Allow Caller to install updates, then tap Update again", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last", 0).apply()
            return
        }
        val dlg = ProgressDialog(a).apply {
            setTitle("Downloading ${r.version}")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }
        Thread {
            val dir = File(a.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val out = File(dir, "caller-${r.version}.apk")
            val ok = try {
                var url = URL(r.apkUrl)
                var conn: HttpURLConnection
                var hops = 0
                // GitHub assets redirect to a CDN; follow manually (cross-host on old Android).
                while (true) {
                    conn = url.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.setRequestProperty("User-Agent", "Caller-Android")
                    val code = conn.responseCode
                    if (code in 300..399 && hops++ < 5) {
                        url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
                    }
                    if (code != 200) throw IllegalStateException("HTTP $code")
                    break
                }
                val total = conn.contentLength.toLong().takeIf { it > 0 } ?: r.size
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(32 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; main.post { dlg.progress = pct } }
                            }
                        }
                    }
                }
                conn.disconnect()
                true
            } catch (_: Exception) {
                false
            }
            main.post {
                try { dlg.dismiss() } catch (_: Exception) {}
                if (ok) install(a, out) else Toast.makeText(a, "Download failed", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(ctx, "Installer not available", Toast.LENGTH_LONG).show()
        }
    }
}
