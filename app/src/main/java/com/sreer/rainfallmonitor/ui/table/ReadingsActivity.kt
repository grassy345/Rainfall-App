package com.sreer.rainfallmonitor.ui.table

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import com.sreer.rainfallmonitor.R
import com.sreer.rainfallmonitor.databinding.ActivityReadingsBinding
import com.sreer.rainfallmonitor.ui.SensorMode
import com.sreer.rainfallmonitor.ui.startBlinking
import com.sreer.rainfallmonitor.ui.stopBlinking

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
    private val mode: SensorMode by lazy {
        intent.getStringExtra("sensor_mode")?.let { SensorMode.valueOf(it) } ?: SensorMode.RAINFALL
    }
    private var availableDates: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReadingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.setMode(mode)
        setupRecyclerView()
        setupNavigation()
        observeData()
    }

    private fun setupRecyclerView() {
        binding.recyclerReadings.layoutManager = LinearLayoutManager(this)
        binding.recyclerReadings.adapter = adapter
        adapter.currentMode = mode
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

                        // Set title based on mode
                        binding.textTitle.text = when (mode) {
                            SensorMode.RAINFALL -> getString(R.string.mode_rainfall)
                            SensorMode.TEMPERATURE -> getString(R.string.mode_temperature)
                            SensorMode.PRESSURE -> getString(R.string.mode_pressure)
                            SensorMode.HUMIDITY -> getString(R.string.mode_humidity)
                        }

                        // ── Title warning: blink if any T/P/H reading is null ──
                        val hasNullReadings = mode != SensorMode.RAINFALL && readings.any { r ->
                            when (mode) {
                                SensorMode.TEMPERATURE -> r.temperature == null
                                SensorMode.PRESSURE -> r.pressure == null
                                SensorMode.HUMIDITY -> r.humidity == null
                                else -> false
                            }
                        }
                        if (hasNullReadings) binding.imgTitleWarning.startBlinking()
                        else binding.imgTitleWarning.stopBlinking()

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
                            val items = buildReadingsList(readings, mode)
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
