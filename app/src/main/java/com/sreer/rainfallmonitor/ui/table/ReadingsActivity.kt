package com.sreer.rainfallmonitor.ui.table

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import com.sreer.rainfallmonitor.databinding.ActivityReadingsBinding

import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen readings table activity.
 *
 * Displays all readings for the selected date in a scrollable RecyclerView,
 * newest-first, with intensity-coloured row backgrounds and gap separators.
 *
 * Date navigation mirrors [com.sreer.rainfallmonitor.ui.graph.GraphFragment]:
 * ← → arrows skip dates without data, 📅 opens a calendar picker.
 */
class ReadingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReadingsBinding
    private val viewModel: ReadingsViewModel by viewModels()

    private val adapter = ReadingsAdapter()
    private var availableDates: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReadingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupNavigation()
        observeData()
    }

    private fun setupRecyclerView() {
        binding.recyclerReadings.layoutManager = LinearLayoutManager(this)
        binding.recyclerReadings.adapter = adapter
    }

    // ═══════════════════════════════════════════════════════════════════
    // NAVIGATION
    // ═══════════════════════════════════════════════════════════════════

    private fun setupNavigation() {
        binding.btnClose.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.btnPrevDate.setOnClickListener {
            viewModel.navigatePrevious(availableDates)
        }

        binding.btnNextDate.setOnClickListener {
            viewModel.navigateNext(availableDates)
        }

        binding.btnCalendar.setOnClickListener {
            showDatePicker()
        }

        binding.chipToday.setOnClickListener {
            viewModel.navigateToToday()
        }
    }

    private fun showDatePicker() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select Date")
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            // MaterialDatePicker returns UTC midnight — convert to local timezone
            // so the date string matches getTodayDate() and Room datestamps.
            val localOffset = java.util.TimeZone.getDefault().getOffset(selection)
            val localFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            val dateStr = localFormat.format(Date(selection + localOffset))
            viewModel.navigateToDate(dateStr)
        }

        picker.show(supportFragmentManager, "readings_date_picker")
    }

    // ═══════════════════════════════════════════════════════════════════
    // DATA OBSERVATION
    // ═══════════════════════════════════════════════════════════════════

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.datesWithData.collect { dates ->
                        availableDates = dates
                        updateNavigationButtons()
                    }
                }

                launch {
                    viewModel.allReadingsForDate.collect { readings ->
                        val currentDate = viewModel.selectedDate.value
                        val isToday = viewModel.isToday(currentDate)
                        binding.textDateLabel.text = viewModel.formatDateForDisplay(currentDate)
                        updateNavigationButtons()
                        binding.chipToday.visibility =
                            if (isToday) android.view.View.GONE else android.view.View.VISIBLE

                        if (readings.isEmpty()) {
                            binding.recyclerReadings.visibility = android.view.View.GONE
                            binding.textNoData.visibility = android.view.View.VISIBLE
                            adapter.submitList(emptyList())
                        } else {
                            binding.recyclerReadings.visibility = android.view.View.VISIBLE
                            binding.textNoData.visibility = android.view.View.GONE
                            val items = buildReadingsList(readings)
                            adapter.submitList(items)
                        }
                    }
                }
            }
        }
    }

    private fun updateNavigationButtons() {
        val currentDate = viewModel.selectedDate.value
        val currentIndex = availableDates.indexOf(currentDate)

        val hasPrevious: Boolean
        val hasNext: Boolean

        if (currentIndex >= 0) {
            hasPrevious = currentIndex < availableDates.size - 1
            hasNext = currentIndex > 0
        } else {
            hasPrevious = availableDates.any { viewModel.isDateBefore(it, currentDate) }
            hasNext = availableDates.any { !viewModel.isDateBefore(it, currentDate) }
        }

        binding.btnPrevDate.isEnabled = hasPrevious
        binding.btnPrevDate.alpha = if (hasPrevious) 1.0f else 0.3f
        binding.btnNextDate.isEnabled = hasNext
        binding.btnNextDate.alpha = if (hasNext) 1.0f else 0.3f
    }
}
