package com.catsoftfon.sip.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.catsoftfon.sip.R
import com.catsoftfon.sip.databinding.FragmentDialerBinding
import com.catsoftfon.sip.catApp
import org.linphone.core.Call

/** Dialer for ONE line - which one is passed in via [ARG_LINE_INDEX] (0-based), set by
 * MainActivity.showFragment() whenever the line tab or the bottom-nav selection changes. */
class DialerFragment : Fragment() {

    private var _binding: FragmentDialerBinding? = null
    private val binding get() = _binding!!
    private var lineIndex = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDialerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lineIndex = arguments?.getInt(ARG_LINE_INDEX, 0) ?: 0
        val sipManager = requireContext().catApp().sipManager

        val keys = mapOf(
            binding.key0 to '0', binding.key1 to '1', binding.key2 to '2', binding.key3 to '3',
            binding.key4 to '4', binding.key5 to '5', binding.key6 to '6', binding.key7 to '7',
            binding.key8 to '8', binding.key9 to '9', binding.keyStar to '*', binding.keyHash to '#'
        )
        for ((button, digit) in keys) {
            button.setOnClickListener {
                if (sipManager.activeCall[lineIndex].value != null) {
                    sipManager.sendDtmf(lineIndex, digit)
                } else {
                    binding.numberField.append(digit.toString())
                }
            }
        }

        binding.backspaceButton.setOnClickListener {
            val text = binding.numberField.text
            if (text.isNotEmpty()) binding.numberField.setText(text.dropLast(1))
        }

        binding.callButton.setOnClickListener {
            val number = binding.numberField.text.toString().trim()
            if (number.isNotEmpty()) sipManager.call(lineIndex, number)
        }

        binding.acceptButton.setOnClickListener { sipManager.answer(lineIndex) }
        binding.hangupButton.setOnClickListener { sipManager.declineOrHangup(lineIndex) }
        binding.holdButton.setOnClickListener { sipManager.toggleHold(lineIndex) }
        binding.muteButton.setOnClickListener { sipManager.toggleMute(lineIndex) }
        binding.speakerButton.setOnClickListener { sipManager.toggleSpeaker(lineIndex) }

        sipManager.activeCall[lineIndex].observe(viewLifecycleOwner) { call -> updateInCallUi(call, sipManager.callState[lineIndex].value) }
        sipManager.callState[lineIndex].observe(viewLifecycleOwner) { state -> updateInCallUi(sipManager.activeCall[lineIndex].value, state) }
        sipManager.micMuted[lineIndex].observe(viewLifecycleOwner) { muted ->
            binding.muteButton.setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        }
        sipManager.speakerOn[lineIndex].observe(viewLifecycleOwner) { on ->
            binding.speakerButton.setImageResource(if (on) R.drawable.ic_volume_up else R.drawable.ic_volume_off)
        }
    }

    private fun updateInCallUi(call: Call?, state: Call.State?) {
        if (call == null) {
            binding.inCallPanel.visibility = View.GONE
            return
        }
        binding.inCallPanel.visibility = View.VISIBLE

        val remote = call.remoteAddress
        val label = remote.displayName?.takeIf { it.isNotBlank() } ?: remote.username ?: remote.asStringUriOnly()
        binding.inCallRemoteText.text = label
        binding.inCallStateText.text = callStateLabel(state)

        val isIncomingRinging = call.dir == Call.Dir.Incoming &&
            (state == Call.State.IncomingReceived || state == Call.State.IncomingEarlyMedia)
        binding.acceptButton.visibility = if (isIncomingRinging) View.VISIBLE else View.GONE
        binding.muteButton.visibility = if (isIncomingRinging) View.GONE else View.VISIBLE
        binding.speakerButton.visibility = if (isIncomingRinging) View.GONE else View.VISIBLE
        binding.holdButton.visibility = if (isIncomingRinging) View.GONE else View.VISIBLE
    }

    private fun callStateLabel(state: Call.State?): String = when (state) {
        Call.State.OutgoingInit, Call.State.OutgoingProgress -> "Вызов…"
        Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> "Звонит…"
        Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> "Входящий звонок"
        Call.State.Connected, Call.State.StreamsRunning -> "Разговор"
        Call.State.Paused, Call.State.Pausing -> "На удержании"
        Call.State.PausedByRemote -> "Удержан собеседником"
        Call.State.End, Call.State.Released -> "Завершён"
        Call.State.Error -> "Ошибка"
        else -> ""
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
