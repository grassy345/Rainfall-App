package com.sreer.rainfallmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.sreer.rainfallmonitor.data.entity.RainfallReading
import com.sreer.rainfallmonitor.databinding.ActivityMainBinding
import com.sreer.rainfallmonitor.mqtt.MqttService
import com.sreer.rainfallmonitor.repository.ConnectionState
import com.sreer.rainfallmonitor.ui.MainViewModel
import com.sreer.rainfallmonitor.ui.graph.GraphActivity
import com.sreer.rainfallmonitor.ui.graph.GraphDataBuilder
import com.sreer.rainfallmonitor.ui.graph.GraphViewModel

import com.sreer.rainfallmonitor.ui.table.ReadingsActivity
import com.sreer.rainfallmonitor.ui.table.ReadingsAdapter
import com.sreer.rainfallmonitor.ui.table.ReadingsListItem
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main Activity — the single-screen dashboard.
 *
 * Observes [MainViewModel] StateFlows and updates the UI reactively.
 * Also starts the MQTT service, requests notification permission,
 * and prompts for battery optimization whitelisting.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "app_prefs"
        private const val PREF_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val graphViewModel: GraphViewModel by viewModels()
    private val readingsAdapter = ReadingsAdapter()

    /** Time format for chart X-axis labels. */
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)

    /** Midnight timestamp for today, used as chart X-axis origin to avoid float precision loss. */
    private var midnightMs = computeMidnightMs()

    // ── Android 13+ notification permission request ───────────────────
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.i(TAG, "Notification permission granted")
            } else {
                Log.w(TAG, "Notification permission denied — rain alerts won't work")
                Toast.makeText(
                    this,
                    "Notification permission denied — rain alerts won't appear",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Start the MQTT service (backup — in case boot receiver didn't fire)
        startMqttService()

        // 2. Request notification permission (Android 13+ / API 33+)
        requestNotificationPermission()

        // 3. Prompt battery optimization whitelisting (one-time)
        promptBatteryOptimization()

        // 4. Set up the chart
        setupChart()

        // 5. Set up readings preview list
        setupReadingsPreview()

        // 6. Observe ViewModel flows and update UI
        observeViewModel()

        // 7. Fullscreen button -> opens landscape graph screen
        binding.btnFullscreen.setOnClickListener {
            startActivity(Intent(this, GraphActivity::class.java))
        }

        // 8. Readings fullscreen button -> opens readings table
        binding.btnReadingsFullscreen.setOnClickListener {
            startActivity(Intent(this, ReadingsActivity::class.java))
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // VIEW MODEL OBSERVATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Observes all StateFlows from the ViewModel and updates the dashboard.
     *
     * Uses repeatOnLifecycle(STARTED) to automatically pause collection
     * when the Activity is stopped and resume when started again.
     * Each flow is collected in a separate coroutine to avoid blocking.
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // ── Connection state ──────────────────────────────────
                launch {
                    viewModel.connectionState.collect { state ->
                        updateConnectionUI(state)
                    }
                }

                // ── Hourly rate + intensity (combined with lastUpdated for stale detection) ──
                launch {
                    viewModel.hourlyRate
                        .combine(viewModel.lastUpdated) { rate, lastUpdated ->
                            Pair(rate, lastUpdated)
                        }
                        .collect { (rate, lastUpdated) ->
                            updateRateUI(rate, lastUpdated)
                        }
                }

                // ── State duration ("Since X ago") ────────────────────
                launch {
                    viewModel.stateStartedAt.collect { startedAt ->
                        updateStateDurationUI(startedAt)
                    }
                }

                // ── Last updated + ESP32 warning ──────────────────────
                launch {
                    viewModel.lastUpdated
                        .combine(viewModel.connectionState) { lastUpdated, connState ->
                            Pair(lastUpdated, connState)
                        }
                        .collect { (lastUpdated, connState) ->
                            updateLastUpdatedUI(lastUpdated, connState)
                        }
                }

                // ── Graph preview (today's readings) ──────────────────
                launch {
                    viewModel.todayReadings.collect { readings ->
                        updateChart(readings)
                    }
                }

                // ── Readings table preview (latest 10) ────────────────
                launch {
                    viewModel.latestTodayReadings.collect { readings ->
                        updateReadingsPreview(readings)
                    }
                }

                // ── Periodic time-ago refresh (every 30 seconds) ──────
                // "Since X ago" and "Last update: X ago" are computed from
                // timestamps. StateFlow only emits when the VALUE changes
                // (every ~10 min when a new reading arrives). Without this
                // ticker, "just now" stays on screen for 10 minutes.
                // Also refreshes the stale visual state on the intensity text.
                launch {
                    while (true) {
                        kotlinx.coroutines.delay(30_000)
                        val rate = viewModel.hourlyRate.value
                        val startedAt = viewModel.stateStartedAt.value
                        val lastUpdated = viewModel.lastUpdated.value
                        val connState = viewModel.connectionState.value

                        updateRateUI(rate, lastUpdated)
                        updateStateDurationUI(startedAt)
                        updateLastUpdatedUI(lastUpdated, connState)
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // UI UPDATE METHODS
    // ═══════════════════════════════════════════════════════════════════

    private fun updateConnectionUI(state: ConnectionState) {
        // Update dot color
        val colorRes = viewModel.getConnectionColorRes(state)
        val color = ContextCompat.getColor(this, colorRes)
        binding.viewConnectionDot.background.colorFilter =
            PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

        // Update label
        binding.textConnectionState.text = viewModel.getConnectionLabel(state)
        binding.textConnectionState.setTextColor(color)
    }

    private fun updateStateDurationUI(startedAt: Long) {
        val text = if (startedAt == 0L) {
            getString(R.string.waiting_for_data)
        } else {
            getString(R.string.since_format, viewModel.formatTimeAgo(startedAt))
        }
        binding.textStateDuration.text = text

        // When data is stale (>15 min since last reading), the "Since X ago"
        // claim is unverifiable — rain may have started or stopped during the
        // gap. Strikethrough visually communicates "take this with a grain of salt."
        val lastUpdated = viewModel.lastUpdated.value
        val isStale = startedAt != 0L && viewModel.isDataStale(lastUpdated)

        if (isStale) {
            // Add strikethrough flag (bitmask OR)
            binding.textStateDuration.paintFlags =
                binding.textStateDuration.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            binding.textStateDuration.setTextColor(
                ContextCompat.getColor(this, R.color.intensity_stale)
            )
        } else {
            // Remove strikethrough flag (bitmask AND-NOT) — critical for when
            // fresh data arrives and clears the stale state
            binding.textStateDuration.paintFlags =
                binding.textStateDuration.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            binding.textStateDuration.setTextColor(
                ContextCompat.getColor(this, R.color.on_surface_secondary)
            )
        }
    }

    private fun updateRateUI(rate: Float, lastUpdated: Long) {
        val isStale = viewModel.isDataStale(lastUpdated)

        // Intensity label (large text) — grey when stale
        binding.textIntensity.text = viewModel.getIntensityLabel(rate)
        val intensityColor = if (isStale) {
            ContextCompat.getColor(this, R.color.intensity_stale)
        } else {
            ContextCompat.getColor(this, viewModel.getIntensityColorRes(rate))
        }
        binding.textIntensity.setTextColor(intensityColor)

        // Formatted rate — append "(stale)" when data is old
        val rateText = if (isStale) {
            getString(R.string.rate_format, viewModel.formatRate(rate)) + " (stale)"
        } else {
            getString(R.string.rate_format, viewModel.formatRate(rate))
        }
        binding.textRate.text = rateText
    }

    private fun updateLastUpdatedUI(lastUpdated: Long, connState: ConnectionState) {
        // Last updated text
        val text = if (lastUpdated == 0L) {
            getString(R.string.waiting_for_data)
        } else {
            getString(R.string.last_update_format, viewModel.formatTimeAgo(lastUpdated))
        }
        binding.textLastUpdated.text = text

        // ESP32 offline warning
        if (viewModel.isEsp32Offline(connState, lastUpdated)) {
            val age = viewModel.formatTimeAgo(lastUpdated)
            binding.textEsp32Warning.text = getString(R.string.esp32_warning_format, age)
            binding.cardEsp32Warning.visibility = View.VISIBLE
        } else {
            binding.cardEsp32Warning.visibility = View.GONE
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // READINGS PREVIEW SETUP
    // ═══════════════════════════════════════════════════════════════════

    private fun setupReadingsPreview() {
        binding.recyclerReadingsPreview.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerReadingsPreview.adapter = readingsAdapter
    }

    // ═══════════════════════════════════════════════════════════════════
    // CHART SETUP & UPDATE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Configures the MPAndroidChart LineChart for the preview.
     * Styling only — data is set in [updateChart].
     */
    private fun setupChart() {
        val chart = binding.chartPreview

        // General
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(false)
        chart.setDrawGridBackground(false)
        chart.setNoDataText("")

        // X-axis (bottom — time of day)
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(true)
        xAxis.gridColor = ContextCompat.getColor(this, R.color.chart_grid)
        xAxis.textColor = ContextCompat.getColor(this, R.color.chart_label)
        xAxis.textSize = 10f
        xAxis.setLabelCount(5, false)
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                // value = ms since midnight → convert back to absolute time
                return timeFormat.format(Date(midnightMs + value.toLong()))
            }
        }

        // Y-axis (left — rate in mm/hr)
        val yAxis = chart.axisLeft
        yAxis.setDrawGridLines(true)
        yAxis.gridColor = ContextCompat.getColor(this, R.color.chart_grid)
        yAxis.textColor = ContextCompat.getColor(this, R.color.chart_label)
        yAxis.textSize = 10f
        yAxis.axisMinimum = 0f
        yAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format(Locale.US, "%.1f", value)
            }
        }

        // Hide right Y-axis
        chart.axisRight.isEnabled = false
    }

    /**
     * Updates the chart with today's readings.
     *
     * Uses [GraphDataBuilder] for gap detection, midnight boundary
     * handling, and live edge extension. Each continuous segment
     * becomes a separate DataSet so gaps appear as visual breaks.
     */
    private fun updateChart(readings: List<RainfallReading>) {
        val chart = binding.chartPreview

        if (readings.isEmpty()) {
            chart.visibility = View.GONE
            binding.textGraphNoData.visibility = View.VISIBLE
            chart.clear()
            return
        }

        chart.visibility = View.VISIBLE
        binding.textGraphNoData.visibility = View.GONE

        // Recompute midnight in case day changed
        midnightMs = computeMidnightMs()

        // Build chart data with gap detection via GraphDataBuilder
        graphViewModel.buildChartData(readings) { chartData ->
            renderPreviewChart(chart, chartData)
        }
    }

    /**
     * Renders segmented chart data on the preview chart.
     * Each segment = one continuous line. Gaps = visual breaks.
     */
    private fun renderPreviewChart(chart: LineChart, chartData: GraphDataBuilder.ChartData) {
        if (chartData.segments.isEmpty()) {
            chart.clear()
            return
        }

        val lineColor = ContextCompat.getColor(this, R.color.chart_line)

        val segmentSets = chartData.segments.mapIndexed { index, segment ->
            LineDataSet(segment.entries, "Segment $index").apply {
                mode = LineDataSet.Mode.STEPPED
                color = lineColor
                lineWidth = 2f
                setDrawValues(false)
                setDrawFilled(true)
                fillColor = lineColor
                fillAlpha = 30

                if (segment.entries.size == 1) {
                    setDrawCircles(true)
                    circleRadius = 3.5f
                    setCircleColor(lineColor)
                    circleHoleRadius = 0f
                } else {
                    setDrawCircles(false)
                }
            }
        }

        // ── Gap region hatch fills ──────────────────────────────────
        val allDataSets = if (chartData.gapRegions.isEmpty()) {
            segmentSets
        } else {
            val maxRate = chartData.segments
                .flatMap { it.entries }
                .maxOfOrNull { it.y } ?: 0f
            val hatchY = maxOf(maxRate * 1.3f, 5f)

            val ctx = this
            val gapSets = chartData.gapRegions.map { gap ->
                LineDataSet(
                    listOf(Entry(gap.xStart, hatchY), Entry(gap.xEnd, hatchY)),
                    "Gap"
                ).apply {
                    setDrawFilled(true)
                    fillColor = ContextCompat.getColor(ctx, R.color.chart_grid)
                    fillAlpha = 80
                    color = Color.TRANSPARENT
                    lineWidth = 0f
                    setDrawCircles(false)
                    setDrawValues(false)
                    isHighlightEnabled = false
                }
            }
            segmentSets + gapSets
        }

        chart.data = LineData(allDataSets)

        // Lock Y-axis max so gap DataSets don't push the scale up
        if (chartData.gapRegions.isNotEmpty()) {
            val maxRate = chartData.segments
                .flatMap { it.entries }
                .maxOfOrNull { it.y } ?: 0f
            chart.axisLeft.axisMaximum = maxOf(maxRate * 1.3f, 5f)
        }

        // Set X-axis range: midnight to now (today)
        chart.xAxis.axisMinimum = chartData.xMin
        chart.xAxis.axisMaximum = chartData.xMax

        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    // ═══════════════════════════════════════════════════════════════════
    // READINGS TABLE PREVIEW UPDATE
    // ═══════════════════════════════════════════════════════════════════

    private fun updateReadingsPreview(readings: List<RainfallReading>) {
        if (readings.isEmpty()) {
            binding.recyclerReadingsPreview.visibility = View.GONE
            binding.textReadingsNoData.visibility = View.VISIBLE
            readingsAdapter.submitList(emptyList())
            return
        }

        binding.recyclerReadingsPreview.visibility = View.VISIBLE
        binding.textReadingsNoData.visibility = View.GONE
        readingsAdapter.submitList(readings.map { ReadingsListItem.Reading(it) })
    }

    // ═══════════════════════════════════════════════════════════════════
    // MIDNIGHT CALCULATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Returns the epoch milliseconds for midnight (00:00:00.000) today.
     * Used as the chart X-axis origin.
     */
    private fun computeMidnightMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ═══════════════════════════════════════════════════════════════════
    // SERVICE STARTUP
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Starts MqttService as a foreground service.
     *
     * This is a BACKUP mechanism — the primary start is via BootReceiver.
     * If the service is already running, Android simply delivers a new
     * Intent to onStartCommand (no duplicate service created).
     */
    private fun startMqttService() {
        try {
            val intent = Intent(this, MqttService::class.java)
            startForegroundService(intent)
            Log.i(TAG, "MqttService start requested from Activity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MqttService: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // NOTIFICATION PERMISSION (Android 13+)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * On Android 13 (API 33) and above, POST_NOTIFICATIONS is a runtime
     * permission. Without it, notifications won't be shown — including
     * the foreground service notification and rain alerts.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BATTERY OPTIMIZATION WHITELISTING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Prompts the user to whitelist the app from battery optimization.
     *
     * Without this, Android (especially Motorola's ThinkShield) can
     * aggressively kill the service even with connectedDevice type.
     *
     * Shown only once (remembered via SharedPreferences). The user
     * can also re-trigger this from Settings (drawer) in a future step.
     */
    private fun promptBatteryOptimization() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Already prompted? Don't show again.
        if (prefs.getBoolean(PREF_BATTERY_PROMPT_SHOWN, false)) return

        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Log.d(TAG, "Already whitelisted from battery optimization")
            prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN, true).apply()
            return
        }

        // Show explanation dialog, then launch system whitelist intent
        AlertDialog.Builder(this)
            .setTitle("Battery Optimization")
            .setMessage(
                "To keep monitoring rainfall 24/7, this app needs to be excluded " +
                "from battery optimization.\n\n" +
                "On the next screen, select \"Allow\" to prevent Android from " +
                "stopping the background service."
            )
            .setPositiveButton("Continue") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open battery optimization settings", e)
                    Toast.makeText(
                        this,
                        "Please manually disable battery optimization in Settings",
                        Toast.LENGTH_LONG
                    ).show()
                }
                prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN, true).apply()
            }
            .setNegativeButton("Not Now") { _, _ ->
                prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN, true).apply()
            }
            .setCancelable(false)
            .show()
    }
}
