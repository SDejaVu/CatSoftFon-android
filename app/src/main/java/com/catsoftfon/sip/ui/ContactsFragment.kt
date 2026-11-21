package com.catsoftfon.sip.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.catsoftfon.sip.R
import com.catsoftfon.sip.databinding.FragmentContactsBinding
import com.catsoftfon.sip.catApp
import com.catsoftfon.sip.model.Contact

/** Contacts for ONE line - which one is passed in via [ARG_LINE_INDEX] (0-based). Each line
 * has its own separate address book (ContactRepository instance), per the user's decision
 * that contacts/history are not shared across the 3 accounts. */
class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ContactsAdapter
    private var lineIndex = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lineIndex = arguments?.getInt(ARG_LINE_INDEX, 0) ?: 0
        val repo = requireContext().catApp().contactRepositories[lineIndex]
        val sipManager = requireContext().catApp().sipManager

        adapter = ContactsAdapter(
            onCall = { contact -> sipManager.call(lineIndex, contact.number) },
            onEdit = { contact -> showEditDialog(contact) }
        )
        binding.contactsList.layoutManager = LinearLayoutManager(requireContext())
        binding.contactsList.adapter = adapter

        repo.contacts.observe(viewLifecycleOwner) { adapter.submitList(it) }

        binding.addContactButton.setOnClickListener { showEditDialog(null) }
    }

    private fun showEditDialog(existing: Contact?) {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val nameField = EditText(context).apply {
            hint = getString(R.string.contact_name)
            setText(existing?.name.orEmpty())
        }
        val numberField = EditText(context).apply {
            hint = getString(R.string.contact_number)
            setText(existing?.number.orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val presenceCheck = CheckBox(context).apply {
            text = getString(R.string.contact_subscribe_presence)
            isChecked = existing?.subscribeToPresence ?: true
        }
        container.addView(nameField)
        container.addView(numberField)
        container.addView(presenceCheck)

        val builder = AlertDialog.Builder(context)
            .setTitle(getString(R.string.add_contact))
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val repo = context.catApp().contactRepositories[lineIndex]
                val contact = existing ?: Contact()
                contact.name = nameField.text.toString().trim()
                contact.number = numberField.text.toString().trim()
                contact.subscribeToPresence = presenceCheck.isChecked
                if (contact.number.isNotEmpty()) repo.addOrUpdateContact(contact)
            }
            .setNegativeButton(R.string.cancel, null)

        if (existing != null) {
            builder.setNeutralButton(R.string.delete) { _, _ ->
                context.catApp().contactRepositories[lineIndex].deleteContact(existing.id)
            }
        }
        builder.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
