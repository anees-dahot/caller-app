package com.anees.caller.ui

import android.content.Context
import android.content.res.ColorStateList
import android.provider.CallLog
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.anees.caller.R
import com.anees.caller.data.CallEntry
import com.anees.caller.data.Contact
import java.util.Calendar
import java.util.Date

sealed class Item {
    data class Header(val text: String) : Item()
    data class ContactRow(val c: Contact) : Item()
    data class CallRow(val e: CallEntry, val name: String?) : Item()
}

class RowAdapter(
    private val onOpen: (Item) -> Unit,
    private val onCall: (String) -> Unit,
    private val onLong: (Item, View) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<Item> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_header, parent, false))
        else RowVH(inf.inflate(R.layout.item_row, parent, false))
    }

    /** Rows between headers render as one rounded card. */
    private fun cardBg(pos: Int): Int {
        val top = pos == 0 || items[pos - 1] is Item.Header
        val bot = pos == items.size - 1 || items[pos + 1] is Item.Header
        return when {
            top && bot -> R.drawable.row_single
            top -> R.drawable.row_top
            bot -> R.drawable.row_bot
            else -> R.drawable.row_mid
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        when (val it = items[position]) {
            is Item.Header -> (h as HeaderVH).t.text = it.text
            is Item.ContactRow -> (h as RowVH).bindContact(it, cardBg(position))
            is Item.CallRow -> (h as RowVH).bindCall(it, cardBg(position))
        }
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) { val t = v as TextView }

    inner class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        private val avatar: TextView = v.findViewById(R.id.avatar)
        private val name: TextView = v.findViewById(R.id.name)
        private val sub: TextView = v.findViewById(R.id.sub)
        private val meta: TextView = v.findViewById(R.id.meta)
        private val typeIcon: ImageView = v.findViewById(R.id.typeIcon)
        private val call: ImageButton = v.findViewById(R.id.call)
        private val ctx: Context get() = itemView.context

        fun bindContact(item: Item.ContactRow, bg: Int) {
            val c = item.c
            itemView.setBackgroundResource(bg)
            Avatar.bind(avatar, c.name)
            name.text = c.name
            name.setTextColor(ContextCompat.getColor(ctx, R.color.ink))
            val first = c.numbers.firstOrNull()
            sub.text = when {
                first == null -> ""
                c.numbers.size > 1 -> "${first.label.ifEmpty { first.number }} · ${c.numbers.size} numbers"
                else -> first.number
            }
            typeIcon.visibility = View.GONE
            meta.visibility = View.GONE
            call.visibility = if (first != null) View.VISIBLE else View.GONE
            call.setOnClickListener { first?.let { onCall(it.number) } }
            itemView.setOnClickListener { onOpen(item) }
            itemView.setOnLongClickListener { onLong(item, it); true }
        }

        fun bindCall(item: Item.CallRow, bg: Int) {
            val e = item.e
            itemView.setBackgroundResource(bg)
            val known = item.name ?: e.name
            val display = known ?: e.number.ifBlank { ctx.getString(R.string.private_number) }
            Avatar.bind(avatar, known)
            name.text = if (e.count > 1) "$display (${e.count})" else display
            val red = ContextCompat.getColor(ctx, R.color.missed)
            name.setTextColor(if (e.missed) red else ContextCompat.getColor(ctx, R.color.ink))

            typeIcon.visibility = View.VISIBLE
            typeIcon.setImageResource(
                when (e.type) {
                    CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_out
                    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> R.drawable.ic_missed
                    else -> R.drawable.ic_in
                },
            )
            typeIcon.imageTintList = ColorStateList.valueOf(
                if (e.missed) red else ContextCompat.getColor(ctx, R.color.ink_3),
            )
            val kind = typeLabel(e.type)
            val what = if (known != null) kind else kind
            sub.text = if (e.duration > 0 && !e.missed) "$what · ${duration(e.duration)}" else what
            meta.visibility = View.VISIBLE
            meta.text = when_(ctx, e.date)
            call.visibility = if (e.number.isNotBlank()) View.VISIBLE else View.GONE
            call.setOnClickListener { onCall(e.number) }
            itemView.setOnClickListener { onOpen(item) }
            itemView.setOnLongClickListener { onLong(item, it); true }
        }
    }

    companion object {
        fun typeLabel(t: Int) = when (t) {
            CallLog.Calls.INCOMING_TYPE -> "Incoming"
            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
            CallLog.Calls.MISSED_TYPE -> "Missed"
            CallLog.Calls.REJECTED_TYPE -> "Declined"
            CallLog.Calls.BLOCKED_TYPE -> "Blocked"
            CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
            else -> "Call"
        }

        fun duration(s: Long): String = when {
            s >= 3600 -> String.format("%dh %dm", s / 3600, (s % 3600) / 60)
            s >= 60 -> String.format("%dm %ds", s / 60, s % 60)
            else -> "${s}s"
        }

        /** Section label for a call date. */
        fun section(t: Long): String = when {
            DateUtils.isToday(t) -> "Today"
            DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
            System.currentTimeMillis() - t < 7 * DateUtils.DAY_IN_MILLIS -> "This week"
            else -> "Older"
        }

        fun when_(ctx: Context, t: Long): String {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = t }
            val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
            return when {
                DateUtils.isToday(t) || DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) ->
                    DateFormat.getTimeFormat(ctx).format(Date(t))
                System.currentTimeMillis() - t < 6 * DateUtils.DAY_IN_MILLIS ->
                    DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
                sameYear -> DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
                else -> DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE)
            }
        }
    }
}
