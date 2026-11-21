package com.catsoftfon.sip.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.catsoftfon.sip.R
import com.catsoftfon.sip.model.Contact
import com.catsoftfon.sip.model.PresenceState

class ContactsAdapter(
    private val onCall: (Contact) -> Unit,
    private val onEdit: (Contact) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.ViewHolder>() {

    private var items: List<Contact> = emptyList()

    fun submitList(newItems: List<Contact>) {
        items = newItems.sortedBy { it.name.lowercase() }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: android.widget.TextView = itemView.findViewById(R.id.contactName)
        private val numberView: android.widget.TextView = itemView.findViewById(R.id.contactNumber)
        private val dot: View = itemView.findViewById(R.id.presenceDot)
        private val callButton: View = itemView.findViewById(R.id.callContactButton)

        fun bind(contact: Contact) {
            nameView.text = contact.name.ifBlank { contact.number }
            numberView.text = contact.number
            dot.setBackgroundResource(presenceDotRes(contact.presence))
            callButton.setOnClickListener { onCall(contact) }
            itemView.setOnClickListener { onEdit(contact) }
        }

        private fun presenceDotRes(state: PresenceState): Int = when (state) {
            PresenceState.IDLE -> R.drawable.presence_dot_idle
            PresenceState.BUSY -> R.drawable.presence_dot_busy
            PresenceState.RINGING -> R.drawable.presence_dot_ringing
            PresenceState.OFFLINE -> R.drawable.presence_dot_offline
            PresenceState.UNKNOWN -> R.drawable.presence_dot_offline
        }
    }
}
