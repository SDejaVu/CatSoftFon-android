package com.catsoftfon.sip.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.catsoftfon.sip.R
import com.catsoftfon.sip.model.CallDirection
import com.catsoftfon.sip.model.CallHistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(private val onCall: (CallHistoryEntry) -> Unit) :
    RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var items: List<CallHistoryEntry> = emptyList()
    private val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    fun submitList(newItems: List<CallHistoryEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: android.widget.TextView = itemView.findViewById(R.id.historyName)
        private val detailsView: android.widget.TextView = itemView.findViewById(R.id.historyDetails)
        private val icon: android.widget.ImageView = itemView.findViewById(R.id.directionIcon)
        private val callButton: View = itemView.findViewById(R.id.callHistoryButton)

        fun bind(entry: CallHistoryEntry) {
            nameView.text = entry.displayName.ifBlank { entry.number }

            val directionLabel = when (entry.direction) {
                CallDirection.INCOMING -> "входящий"
                CallDirection.OUTGOING -> "исходящий"
                CallDirection.MISSED -> "пропущенный"
            }
            val duration = if (entry.durationSeconds > 0) {
                val m = entry.durationSeconds / 60
                val s = entry.durationSeconds % 60
                String.format(Locale.getDefault(), ", %d:%02d", m, s)
            } else {
                ""
            }
            detailsView.text = "$directionLabel · ${dateFormat.format(Date(entry.timestampMillis))}$duration"

            icon.setColorFilter(
                itemView.context.getColor(
                    when (entry.direction) {
                        CallDirection.MISSED -> R.color.cat_danger_red
                        else -> R.color.cat_text_secondary
                    }
                )
            )

            callButton.setOnClickListener { onCall(entry) }
        }
    }
}
