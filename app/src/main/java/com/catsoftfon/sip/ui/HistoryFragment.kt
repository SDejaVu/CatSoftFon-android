package com.catsoftfon.sip.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.catsoftfon.sip.databinding.FragmentHistoryBinding
import com.catsoftfon.sip.catApp

/** Call history for ONE line - which one is passed in via [ARG_LINE_INDEX] (0-based); each
 * line keeps its own separate history, per the user's decision. */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val lineIndex = arguments?.getInt(ARG_LINE_INDEX, 0) ?: 0
        val repo = requireContext().catApp().contactRepositories[lineIndex]
        val sipManager = requireContext().catApp().sipManager

        val adapter = HistoryAdapter(onCall = { entry -> sipManager.call(lineIndex, entry.number) })
        binding.historyList.layoutManager = LinearLayoutManager(requireContext())
        binding.historyList.adapter = adapter

        repo.history.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            binding.emptyHistoryText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        binding.clearHistoryButton.setOnClickListener { repo.clearHistory() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
