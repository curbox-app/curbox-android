package neth.iecal.curbox.ui.fragments.main.reducers.analytics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.databinding.FragmentIntentsLogBinding
import com.google.android.material.datepicker.MaterialDatePicker

class IntentsLogFragment : Fragment() {
    private var _binding: FragmentIntentsLogBinding? = null
    private val binding get() = _binding!!

    private val viewModel: IntentsLogViewModel by viewModels {
        IntentsLogViewModelFactory(
            AppDatabase.getInstance(requireContext()).intentLogDao(),
            getString(R.string.label_all_time),
            getString(R.string.analytics_range_label)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentIntentsLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val adapter = IntentsLogAdapter { logId ->
            viewModel.deleteLog(logId)
        }
        
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressed()
        }

        binding.btnFilterDate.setOnClickListener {
            val datePicker = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText(getString(R.string.dialog_title_select_date_range))
                .build()

            datePicker.addOnPositiveButtonClickListener { selection ->
                viewModel.setDateRange(selection.first, selection.second + 86399999L)
            }
            datePicker.show(childFragmentManager, "DateRangePicker")
        }
        
        binding.btnClearFilters.setOnClickListener {
            viewModel.clearFilters()
            binding.searchEdit.setText("")
        }

        binding.searchEdit.addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.filteredLogs.collectLatest { logs ->
                adapter.submitList(logs)
                binding.emptyView.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.dateRangeText.collectLatest { text ->
                binding.btnFilterDate.text = text
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "intents_log_fragment"
    }
}
