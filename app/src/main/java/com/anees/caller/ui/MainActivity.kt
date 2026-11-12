package com.anees.caller.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anees.caller.R
import com.anees.caller.data.CallEntry
import com.anees.caller.data.Contact
import com.anees.caller.data.Repo
import com.anees.caller.data.T9
import com.anees.caller.update.Updater
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var tabs: List<View>
    private lateinit var recentsList: RecyclerView
    private lateinit var contactsList: RecyclerView
    private lateinit var keypad: View
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private lateinit var title: TextView
    private lateinit var numberView: TextView
    private lateinit var banner: View

    private val recentsAdapter = RowAdapter(::open, ::call, ::longPress)
    private val contactsAdapter = RowAdapter(::open, ::call, ::longPress)
    private val matchAdapter = RowAdapter(::open, ::call, ::longPress)

    private var contacts: List<Contact> = emptyList()
    private var calls: List<CallEntry> = emptyList()
    private var tab = 0
    private val typed = StringBuilder()
    private var tone: ToneGenerator? = null

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(reload); main.postDelayed(reload, 400)
        }
    }
    private val reload = Runnable { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        padForSystemBars()

        banner = findViewById(R.id.banner)
        title = findViewById(R.id.title)
        search = findViewById(R.id.search)
        empty = findViewById(R.id.empty)
        recentsList = findViewById(R.id.recents)
        contactsList = findViewById(R.id.contacts)
        keypad = findViewById(R.id.keypad)
        numberView = keypad.findViewById(R.id.number)

        recentsList.layoutManager = LinearLayoutManager(this)
        recentsList.adapter = recentsAdapter
        contactsList.layoutManager = LinearLayoutManager(this)
        contactsList.adapter = contactsAdapter
        keypad.findViewById<RecyclerView>(R.id.matches).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = matchAdapter
        }

        tabs = listOf(findViewById(R.id.tabRecents), findViewById(R.id.tabContacts), findViewById(R.id.tabKeypad))
        tabs.forEachIndexed { i, t -> t.setOnClickListener { select(i) } }

        findViewById<View>(R.id.addContact).setOnClickListener { Dialer.addContact(this, null) }
        findViewById<View>(R.id.menu).setOnClickListener { v ->
            val pm = PopupMenu(this, v, Gravity.END)
            pm.menu.add(0, 1, 0, "Check for updates")
            if (!Dialer.isDefault(this)) pm.menu.add(0, 2, 0, "Set as default phone app")
            pm.menu.add(0, 3, 0, "Version ${com.anees.caller.BuildConfig.VERSION_NAME}").isEnabled = false
            pm.setOnMenuItemClickListener {
                when (it.itemId) { 1 -> Updater.check(this, manual = true); 2 -> Dialer.requestDefault(this) }
                true
            }
            pm.show()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })

        buildKeypad()
        val prefs = getPreferences(MODE_PRIVATE)
        select(savedInstanceState?.getInt("tab") ?: prefs.getInt("tab", 0))
        handleIntent(intent)
        Updater.checkOnLaunch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val data = i?.data ?: return
        if (data.scheme == "tel" && (i.action == Intent.ACTION_DIAL || i.action == Intent.ACTION_VIEW)) {
            typed.setLength(0)
            typed.append(data.schemeSpecificPart ?: "")
            select(2)
            updateTyped()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        refreshBanner()
        if (hasCore()) {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            load()
        }
        tone = try { ToneGenerator(AudioManager.STREAM_DTMF, 70) } catch (_: Exception) { null }
    }

    override fun onPause() {
        super.onPause()
        try { contentResolver.unregisterContentObserver(observer) } catch (_: Exception) {}
        tone?.release(); tone = null
        getPreferences(MODE_PRIVATE).edit().putInt("tab", tab).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    // ---------- permissions + default dialer ----------

    private val corePerms: Array<String>
        get() {
            val p = mutableListOf(
                Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG,
                Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
            )
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
            return p.toTypedArray()
        }

    private fun hasCore() = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshBanner() {
        val text = findViewById<TextView>(R.id.bannerText)
        val btn = findViewById<TextView>(R.id.bannerBtn)
        when {
            !Dialer.isDefault(this) -> {
                banner.visibility = View.VISIBLE
                text.setText(R.string.set_default)
                btn.setText(R.string.set_default_btn)
                btn.setOnClickListener { Dialer.requestDefault(this) }
            }
            !hasCore() -> {
                banner.visibility = View.VISIBLE
                text.setText(R.string.perm_needed)
                btn.setText(R.string.perm_btn)
                btn.setOnClickListener { askPerms() }
            }
            else -> banner.visibility = View.GONE
        }
    }

    private fun askPerms() {
        val missing = corePerms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        val prefs = getPreferences(MODE_PRIVATE)
        val asked = prefs.getBoolean("asked", false)
        // After a hard deny the dialog no longer shows, so send user to app settings.
        if (asked && missing.none { shouldShowRequestPermissionRationale(it) }) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
        } else {
            prefs.edit().putBoolean("asked", true).apply()
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Dialer.REQ_ROLE) {
            refreshBanner()
            if (Dialer.isDefault(this) && !hasCore()) askPerms()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshBanner()
        if (hasCore()) load()
    }

    // ---------- data ----------

    private fun load() {
        if (!hasCore()) return
        io.execute {
            val c = Repo.loadContacts(this)
            val l = Repo.group(Repo.loadCalls(this))
            main.post {
                if (isFinishing) return@post
                contacts = c; calls = l
                render()
            }
        }
    }

    private fun nameFor(number: String): String? {
        if (number.isBlank()) return null
        val d = T9.digitsOnly(number)
        return contacts.firstOrNull { c -> c.digits.any { it == d || (d.length >= 7 && it.endsWith(d.takeLast(9))) } }?.name
    }

    private fun render() {
        val q = search.text.toString().trim()
        when (tab) {
            0 -> {
                val src = if (q.isEmpty()) calls else calls.filter {
                    val n = nameFor(it.number) ?: it.name ?: ""
                    n.contains(q, true) || T9.digitsOnly(it.number).contains(T9.digitsOnly(q).ifEmpty { "\u0000" })
                }
                val rows = ArrayList<Item>()
                var lastSec = ""
                for (e in src) {
                    val sec = RowAdapter.section(e.date)
                    if (sec != lastSec) { rows.add(Item.Header(sec)); lastSec = sec }
                    rows.add(Item.CallRow(e, nameFor(e.number)))
                }
                recentsAdapter.items = rows
                showEmpty(src.isEmpty(), if (hasCore()) getString(R.string.no_recents) else "")
            }
            1 -> {
                val items = ArrayList<Item>()
                if (q.isEmpty()) {
                    val fav = contacts.filter { it.starred }
                    if (fav.isNotEmpty()) {
                        items.add(Item.Header("Favorites"))
                        fav.forEach { items.add(Item.ContactRow(it)) }
                    }
                    var last = ""
                    for (c in contacts) {
                        val h = Repo.initial(c.name)
                        if (h != last) { items.add(Item.Header(h)); last = h }
                        items.add(Item.ContactRow(c))
                    }
                } else {
                    val qd = T9.digitsOnly(q)
                    contacts.filter { c ->
                        c.name.contains(q, true) || (qd.isNotEmpty() && c.digits.any { it.contains(qd) })
                    }.forEach { items.add(Item.ContactRow(it)) }
                }
                contactsAdapter.items = items
                showEmpty(items.isEmpty(), if (hasCore()) getString(R.string.no_contacts) else "")
            }
            2 -> {
                val d = T9.digitsOnly(typed.toString())
                matchAdapter.items = if (d.isEmpty()) emptyList()
                else contacts.filter { T9.matches(it, d) }.take(30).map { Item.ContactRow(it) }
                showEmpty(false, "")
            }
        }
    }

    private fun showEmpty(show: Boolean, text: String) {
        empty.visibility = if (show && text.isNotEmpty()) View.VISIBLE else View.GONE
        empty.text = text
    }

    // ---------- tabs ----------

    private fun select(i: Int) {
        tab = i.coerceIn(0, 2)
        tabs.forEachIndexed { idx, t -> t.isSelected = idx == tab }
        recentsList.visibility = if (tab == 0) View.VISIBLE else View.GONE
        contactsList.visibility = if (tab == 1) View.VISIBLE else View.GONE
        keypad.visibility = if (tab == 2) View.VISIBLE else View.GONE
        findViewById<View>(R.id.header).visibility = if (tab == 2) View.GONE else View.VISIBLE
        findViewById<View>(R.id.searchBox).visibility = if (tab == 2) View.GONE else View.VISIBLE
        findViewById<View>(R.id.addContact).visibility = if (tab == 1) View.VISIBLE else View.GONE
        title.text = getString(if (tab == 0) R.string.tab_recents else R.string.tab_contacts)
        if (tab == 2) {
            search.clearFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        }
        render()
    }

    // ---------- actions ----------

    private fun call(number: String) = Dialer.call(this, number)

    private fun open(item: Item) {
        val i = Intent(this, ContactActivity::class.java)
        when (item) {
            is Item.ContactRow -> i.putExtra("contact", item.c.uri.toString())
            is Item.CallRow -> i.putExtra("number", item.e.number)
            else -> return
        }
        startActivity(i)
    }

    private fun longPress(item: Item, anchor: View) {
        val number = when (item) {
            is Item.ContactRow -> item.c.numbers.firstOrNull()?.number
            is Item.CallRow -> item.e.number
            else -> null
        } ?: return
        if (number.isBlank()) return
        val pm = PopupMenu(this, anchor, Gravity.END)
        pm.menu.add(0, 1, 0, "Call")
        pm.menu.add(0, 2, 0, "Send text")
        pm.menu.add(0, 3, 0, "Copy number")
        pm.menu.add(0, 4, 0, "Edit before call")
        if (item is Item.CallRow) {
            if (nameFor(number) == null) pm.menu.add(0, 5, 0, getString(R.string.add_contact))
            pm.menu.add(0, 6, 0, "Delete from history")
        }
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> call(number)
                2 -> Dialer.sms(this, number)
                3 -> {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("number", number))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
                4 -> { typed.setLength(0); typed.append(number); select(2); updateTyped() }
                5 -> Dialer.addContact(this, number)
                6 -> (item as? Item.CallRow)?.let { row -> io.execute { Repo.deleteCall(this, row.e.id); main.post { load() } } }
            }
            true
        }
        pm.show()
    }

    // ---------- keypad ----------

    private fun buildKeypad() {
        val grid = keypad.findViewById<LinearLayout>(R.id.grid)
        val keys = listOf(
            "1" to "", "2" to "ABC", "3" to "DEF",
            "4" to "GHI", "5" to "JKL", "6" to "MNO",
            "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
            "*" to "", "0" to "+", "#" to "",
        )
        buildKeyGrid(this, grid, keys, R.color.ink, R.color.ink_2, R.drawable.key_round, 76) { k, long ->
            if (long && k == "0") press("+")
            else if (long && k == "1") callVoicemail()
            else if (!long) press(k)
        }
        keypad.findViewById<View>(R.id.backspace).apply {
            setOnClickListener { if (typed.isNotEmpty()) { typed.setLength(typed.length - 1); updateTyped() } }
            setOnLongClickListener { typed.setLength(0); updateTyped(); true }
        }
        keypad.findViewById<View>(R.id.callBtn).setOnClickListener {
            if (typed.isEmpty()) {
                // Empty keypad + call = redial last outgoing number.
                calls.firstOrNull { it.type == CallLog.Calls.OUTGOING_TYPE }?.let { typed.append(it.number); updateTyped() }
            } else call(typed.toString())
        }
        keypad.findViewById<View>(R.id.keypadAdd).setOnClickListener {
            if (typed.isNotEmpty()) Dialer.addContact(this, typed.toString())
        }
        numberView.setOnLongClickListener {
            val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            val clean = text?.filter { it.isDigit() || it in "+*#," }
            if (!clean.isNullOrEmpty()) { typed.setLength(0); typed.append(clean); updateTyped() }
            true
        }
        updateTyped()
    }

    private fun press(k: String) {
        typed.append(k)
        val t = when (k) {
            "*" -> ToneGenerator.TONE_DTMF_S; "#" -> ToneGenerator.TONE_DTMF_P; "+" -> -1
            else -> ToneGenerator.TONE_DTMF_0 + k.toInt()
        }
        if (t >= 0 && Settings.System.getInt(contentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1) {
            tone?.startTone(t, 120)
        }
        updateTyped()
    }

    private fun updateTyped() {
        numberView.text = typed
        keypad.findViewById<View>(R.id.keypadAdd).visibility = if (typed.isEmpty()) View.INVISIBLE else View.VISIBLE
        keypad.findViewById<View>(R.id.backspace).visibility = if (typed.isEmpty()) View.INVISIBLE else View.VISIBLE
        val sims = Dialer.accounts(this).size
        keypad.findViewById<TextView>(R.id.keypadSim).text = if (sims > 1) "$sims SIMs" else ""
        if (tab == 2) render()
    }

    private fun callVoicemail() {
        try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            @Suppress("MissingPermission")
            val vm = try { (getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager).voiceMailNumber } catch (_: Exception) { null }
            if (!vm.isNullOrBlank()) call(vm)
            else Toast.makeText(this, "No voicemail number", Toast.LENGTH_SHORT).show()
            tm.hashCode()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Shared by keypad and in-call DTMF pad. */
        fun buildKeyGrid(
            ctx: Context, grid: LinearLayout, keys: List<Pair<String, String>>,
            digitColor: Int, letterColor: Int, bg: Int, rowDp: Int,
            onKey: (String, Boolean) -> Unit,
        ) {
            val dp = ctx.resources.displayMetrics.density
            grid.removeAllViews()
            keys.chunked(3).forEach { row ->
                val r = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (rowDp * dp).toInt())
                }
                row.forEach { (digit, letters) ->
                    val slot = android.widget.FrameLayout(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    }
                    val size = ((rowDp - 8) * dp).toInt()
                    val cell = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setBackgroundResource(bg)
                        isClickable = true; isFocusable = true
                        contentDescription = digit
                        layoutParams = android.widget.FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                    }
                    slot.addView(cell)
                    cell.addView(TextView(ctx).apply {
                        text = digit
                        textSize = if (digit == "*") 34f else 28f
                        setTextColor(ContextCompat.getColor(ctx, digitColor))
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                    })
                    cell.addView(TextView(ctx).apply {
                        text = letters
                        textSize = 10f
                        letterSpacing = 0.12f
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                        setTextColor(ContextCompat.getColor(ctx, letterColor))
                        gravity = Gravity.CENTER
                        visibility = if (letters.isEmpty() && digit != "1") View.GONE else View.VISIBLE
                        if (digit == "1") text = " "
                    })
                    cell.setOnClickListener { v ->
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onKey(digit, false)
                    }
                    cell.setOnLongClickListener { onKey(digit, true); true }
                    r.addView(slot)
                }
                grid.addView(r)
            }
        }
    }
}
