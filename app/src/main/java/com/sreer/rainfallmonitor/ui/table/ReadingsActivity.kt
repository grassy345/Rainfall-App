package com.sreer.rainfallmonitor.ui.table

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import com.sreer.rainfallmonitor.R
import com.sreer.rainfallmonitor.data.entity.RainfallReading
import com.sreer.rainfallmonitor.databinding.ActivityReadingsBinding
import com.sreer.rainfallmonitor.repository.RainfallRepository
import com.sreer.rainfallmonitor.ui.SensorMode
import com.sreer.rainfallmonitor.ui.graph.GraphActivity
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
    private var suppressSpinnerListener = false
    private var availableDates: List<String> = emptyList()
    private var lastReadings: List<RainfallReading> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReadingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val fromFullscreen = intent.getBooleanExtra("from_fullscreen", false)
        viewModel.restoreDateFromSharedIfNeeded(fromFullscreen)
        viewModel.enableDateSyncToRepository()

        setupRecyclerView()
        setupSpinner()
        // Initialize Spinner to the current mode before observers start
        binding.spinnerMode.setSelection(RainfallRepository.sensorMode.value.ordinal, false)
        setupNavigation()
        observeData()
    }

    private fun setupRecyclerView() {
        binding.recyclerReadings.layoutManager = LinearLayoutManager(this)
        binding.recyclerReadings.adapter = adapter
        // adapter.currentMode is updated by the mode observer in observeData()
    }

    // ═══════════════════════════════════════════════════════════════════
    // MODE SPINNER
    // ═══════════════════════════════════════════════════════════════════

    private fun setupSpinner() {
        val modeNames = resources.getStringArray(R.array.sensor_modes)
        val abbreviations = arrayOf(
            getString(R.string.mode_abbr_rainfall),
            getString(R.string.mode_abbr_temperature),
            getString(R.string.mode_abbr_pressure),
            getString(R.string.mode_abbr_humidity)
        )
        object : ArrayAdapter<String>(this, R.layout.spinner_item_readings, modeNames) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return super.getView(position, convertView, parent).also {
                    (it as android.widget.TextView).text = abbreviations[position]
                }
            }
        }.also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerMode.adapter = adapter
        }
        binding.spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (suppressSpinnerListener) return
                val newMode = SensorMode.values()[position]
                if (newMode != RainfallRepository.sensorMode.value) {
                    RainfallRepository.setSensorMode(newMode)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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

        binding.btnSwitchGraph.setOnClickListener {
            RainfallRepository.updateFullscreenChain(true)
            startActivity(Intent(this, GraphActivity::class.java).apply {
                putExtra("from_fullscreen", true)
            })
            finish()
        }
    }

    private fun showDatePicker() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Select Date")
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            val localOffset = java.util.TimeZone.getDefault().getOffset(selection)
            val localFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            val dateStr = localFormat.format(Date(selection + localOffset))
            viewModel.navigateToDate(dateStr)
        }

        picker.show(supportFragmentManager, "readings_date_picker")
    }

    override fun onResume() {
        super.onResume()
        if (RainfallRepository.isFullscreenChainActive.value) {
            val sharedDate = RainfallRepository.fullscreenSelectedDate.value
            if (sharedDate.isNotEmpty() && sharedDate != viewModel.selectedDate.value) {
                viewModel.navigateToDate(sharedDate)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // DATA OBSERVATION
    // ═══════════════════════════════════════════════════════════════════

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Observe shared mode — keeps adapter, ViewModel, and Spinner in sync
                launch {
                    RainfallRepository.sensorMode.collect { mode ->
                        adapter.currentMode = mode
                        viewModel.setMode(mode)
                        suppressSpinnerListener = true
                        binding.spinnerMode.setSelection(mode.ordinal, false)
                        suppressSpinnerListener = false
                        // Re-render readings list with the new mode
                        if (lastReadings.isNotEmpty()) {
                            val items = buildReadingsList(lastReadings, mode)
                            adapter.submitList(items)
                        }
                    }
                }

                launch {
                    viewModel.datesWithData.collect { dates ->
                        availableDates = dates
                        updateNavigationButtons()
                    }
                }

                launch {
                    viewModel.allReadingsForDate.collect { readings ->
                        lastReadings = readings
                        val currentDate = viewModel.selectedDate.value
                        val isToday = viewModel.isToday(currentDate)

                        // ── Title warning: blink if any T/P/H reading is null ──
                        val currentMode = adapter.currentMode
                        val hasNullReadings = currentMode != SensorMode.RAINFALL && readings.any { r ->
                            when (currentMode) {
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
                            val items = buildReadingsList(readings, currentMode)
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
