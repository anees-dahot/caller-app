package com.anees.caller.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.anees.caller.R
import com.anees.caller.data.CallEntry
import com.anees.caller.data.Contact
import com.anees.caller.data.PhoneNum
import com.anees.caller.data.Repo

/** Shows a saved contact, or a bare number from history. */
class ContactActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var contact: Contact? = null
    private var bareNumber: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact)
        padForSystemBars()
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        bareNumber = intent.getStringExtra("number")
    }

    override fun onResume() {
        super.onResume()
        val uriStr = intent.getStringExtra("contact")
        Thread {
            val c = when {
                uriStr != null -> Repo.loadContact(this, Uri.parse(uriStr))
                bareNumber != null -> Repo.lookupName(this, bareNumber!!)?.second?.let { Repo.loadContact(this, it) }
                else -> null
            }
            val nums = c?.numbers?.map { it.number } ?: listOfNotNull(bareNumber)
            val hist = nums.flatMap { Repo.loadCalls(this, 50, it) }.sortedByDescending { it.date }.take(30)
            main.post { if (!isFinishing) bind(c, hist) }
        }.start()
    }

    private fun bind(c: Contact?, history: List<CallEntry>) {
        contact = c
        val name = c?.name ?: bareNumber?.ifBlank { getString(R.string.private_number) } ?: getString(R.string.unknown)
        findViewById<TextView>(R.id.cName).text = name
        Avatar.bind(findViewById(R.id.bigAvatar), c?.name)
        val primary = c?.numbers?.firstOrNull()?.number ?: bareNumber?.takeIf { it.isNotBlank() }
        findViewById<TextView>(R.id.cSub).apply {
            text = if (c == null) "Not in contacts" else c.numbers.firstOrNull()?.label ?: ""
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        findViewById<View>(R.id.actCall).apply {
            isEnabled = primary != null; alpha = if (primary != null) 1f else 0.4f
            setOnClickListener { primary?.let { Dialer.call(this@ContactActivity, it) } }
        }
        findViewById<View>(R.id.actSms).apply {
            isEnabled = primary != null; alpha = if (primary != null) 1f else 0.4f
            setOnClickListener { primary?.let { Dialer.sms(this@ContactActivity, it) } }
        }

        val edit = findViewById<android.widget.ImageButton>(R.id.edit)
        val starTile = findViewById<View>(R.id.actStar)
        val starIcon = findViewById<android.widget.ImageView>(R.id.starIcon)
        val starText = findViewById<TextView>(R.id.starText)
        fun paintStar(on: Boolean) {
            starIcon.setImageResource(if (on) R.drawable.ic_star else R.drawable.ic_star_border)
            starText.text = if (on) "Favorite" else "Add favorite"
        }
        if (c != null) {
            edit.setImageResource(R.drawable.ic_edit)
            edit.contentDescription = "Edit"
            edit.setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_EDIT, c.uri).putExtra("finishActivityOnSaveCompleted", true)) } catch (_: Exception) {}
            }
            paintStar(c.starred)
            starTile.setOnClickListener {
                val now = !(contact?.starred ?: false)
                Thread { Repo.setStarred(this, c, now) }.start()
                contact = c.copy(starred = now)
                paintStar(now)
            }
        } else {
            edit.setImageResource(R.drawable.ic_person_add)
            edit.contentDescription = getString(R.string.add_contact)
            edit.visibility = if (bareNumber.isNullOrBlank()) View.GONE else View.VISIBLE
            edit.setOnClickListener { Dialer.addContact(this, bareNumber) }
            starIcon.setImageResource(R.drawable.ic_person_add)
            starText.text = "Save"
            starTile.setOnClickListener { Dialer.addContact(this, bareNumber) }
            starTile.isEnabled = !bareNumber.isNullOrBlank()
            starTile.alpha = if (starTile.isEnabled) 1f else 0.4f
        }

        val box = findViewById<LinearLayout>(R.id.numbers)
        box.removeAllViews()
        val nums = c?.numbers ?: bareNumber?.takeIf { it.isNotBlank() }?.let { listOf(PhoneNum(it, "")) } ?: emptyList()
        findViewById<View>(R.id.numbersLabel).visibility = if (nums.isEmpty()) View.GONE else View.VISIBLE
        box.visibility = if (nums.isEmpty()) View.GONE else View.VISIBLE
        val inf = LayoutInflater.from(this)
        for (n in nums) {
            val row = inf.inflate(R.layout.item_number, box, false)
            row.findViewById<TextView>(R.id.num).text = n.number
            row.findViewById<TextView>(R.id.label).apply { text = n.label; visibility = if (n.label.isEmpty()) View.GONE else View.VISIBLE }
            row.findViewById<View>(R.id.dial).setOnClickListener { Dialer.call(this, n.number) }
            row.findViewById<View>(R.id.sms).setOnClickListener { Dialer.sms(this, n.number) }
            row.setOnClickListener { Dialer.call(this, n.number) }
            box.addView(row)
        }

        val hb = findViewById<LinearLayout>(R.id.history)
        hb.removeAllViews()
        findViewById<View>(R.id.historyLabel).visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        hb.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        val dp = resources.displayMetrics.density
        for (e in history) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            }
            val icon = android.widget.ImageView(this).apply {
                setImageResource(
                    when (e.type) {
                        android.provider.CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_out
                        android.provider.CallLog.Calls.MISSED_TYPE, android.provider.CallLog.Calls.REJECTED_TYPE -> R.drawable.ic_missed
                        else -> R.drawable.ic_in
                    },
                )
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@ContactActivity, if (e.missed) R.color.missed else R.color.ink_3),
                )
                layoutParams = LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt())
            }
            val label = TextView(this).apply {
                val dur = if (e.duration > 0) " · ${RowAdapter.duration(e.duration)}" else ""
                text = "${RowAdapter.typeLabel(e.type)}$dur"
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@ContactActivity, if (e.missed) R.color.missed else R.color.ink))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (12 * dp).toInt() }
            }
            val time = TextView(this).apply {
                text = android.text.format.DateUtils.formatDateTime(
                    this@ContactActivity, e.date,
                    android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_ALL,
                )
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@ContactActivity, R.color.ink_3))
            }
            row.addView(icon); row.addView(label); row.addView(time)
            hb.addView(row)
        }
    }
}
