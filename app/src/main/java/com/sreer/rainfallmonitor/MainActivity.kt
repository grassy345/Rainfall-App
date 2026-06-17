package com.sreer.rainfallmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
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
import com.sreer.rainfallmonitor.ui.SensorMode
import com.sreer.rainfallmonitor.ui.graph.GraphActivity
import com.sreer.rainfallmonitor.ui.graph.GraphDataBuilder
import com.sreer.rainfallmonitor.ui.graph.GraphViewModel

import com.sreer.rainfallmonitor.ui.startBlinking
import com.sreer.rainfallmonitor.ui.stopBlinking
import com.sreer.rainfallmonitor.ui.table.ReadingsActivity
import com.sreer.rainfallmonitor.ui.table.ReadingsAdapter
import com.sreer.rainfallmonitor.ui.table.ReadingsListItem
import com.sreer.rainfallmonitor.ui.table.buildReadingsList
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
        private const val MAX_PREVIEW_ITEMS = 10
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val graphViewModel: GraphViewModel by viewModels()
    private val readingsAdapter = ReadingsAdapter()

    /** Time format for chart X-axis labels. */
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)

    /** Midnight timestamp for today, used as chart X-axis origin to avoid float precision loss. */
    private var midnightMs = computeMidnightMs()

    /** Cached latest readings for chart updates when mode changes. */
    private var latestReadings: List<RainfallReading> = emptyList()

    /** Cached latest readings for the preview table (used to rebuild list on mode switch). */
    private var latestPreviewReadings: List<RainfallReading> = emptyList()

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
            val intent = Intent(this, GraphActivity::class.java).apply {
                putExtra("sensor_mode", viewModel.sensorMode.value.name)
            }
            startActivity(intent)
        }

        // 8. Readings fullscreen button -> opens readings table
        binding.btnReadingsFullscreen.setOnClickListener {
            val intent = Intent(this, ReadingsActivity::class.java).apply {
                putExtra("sensor_mode", viewModel.sensorMode.value.name)
            }
            startActivity(intent)
        }

        // 9. Mode switcher buttons
        binding.btnModePrev.setOnClickListener {
            val modes = SensorMode.values()
            val currentIndex = viewModel.sensorMode.value.ordinal
            val prevIndex = if (currentIndex == 0) modes.size - 1 else currentIndex - 1
            viewModel.setSensorMode(modes[prevIndex])
        }
        binding.btnModeNext.setOnClickListener {
            val modes = SensorMode.values()
            val currentIndex = viewModel.sensorMode.value.ordinal
            val nextIndex = (currentIndex + 1) % modes.size
            viewModel.setSensorMode(modes[nextIndex])
        }

        // 10. Sensor row long-click -> intensity toast
        binding.rowTemperature.setOnLongClickListener {
            showIntensityToast(viewModel.lastValidTemperature.value, viewModel::getTemperatureIntensityLabel, "Temperature")
        }
        binding.rowPressure.setOnLongClickListener {
            showIntensityToast(viewModel.lastValidPressure.value, viewModel::getPressureIntensityLabel, "Pressure")
        }
        binding.rowHumidity.setOnLongClickListener {
            showIntensityToast(viewModel.lastValidHumidity.value, viewModel::getHumidityIntensityLabel, "Humidity")
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

                // ── Graph preview (today's readings + mode) ──────────
                launch {
                    viewModel.todayReadings.collect { readings ->
                        latestReadings = readings
                        updateChart(readings, viewModel.sensorMode.value)
                    }
                }
                launch {
                    viewModel.sensorMode.collect { mode ->
                        latestReadings.takeIf { it.isNotEmpty() }?.let { readings ->
                            updateChart(readings, mode)
                        }
                    }
                }

                // ── Readings table preview (latest 10) ────────────────
                launch {
                    viewModel.latestTodayReadings.collect { readings ->
                        latestPreviewReadings = readings
                        readingsAdapter.currentMode = viewModel.sensorMode.value
                        updateReadingsPreview(readings)
                    }
                }
                launch {
                    viewModel.sensorMode.collect { mode ->
                        readingsAdapter.currentMode = mode
                        if (latestPreviewReadings.isNotEmpty()) {
                            updateReadingsPreview(latestPreviewReadings)
                        }
                    }
                }

                // ── T/P/H sensor values (value + timestamp paired to avoid staleness race) ──
                launch {
                    combine(
                        combine(viewModel.lastValidTemperature, viewModel.lastTemperatureUpdate) { v, t -> v to t },
                        combine(viewModel.lastValidPressure, viewModel.lastPressureUpdate) { v, t -> v to t },
                        combine(viewModel.lastValidHumidity, viewModel.lastHumidityUpdate) { v, t -> v to t }
                    ) { t1, t2, t3 -> Triple(t1, t2, t3) }
                    .collect { (t, p, h) ->
                        updateSensorUI(t.first, t.second, p.first, p.second, h.first, h.second)
                    }
                }

                // ── Sensor mode label ──────────────────────────────────
                launch {
                    viewModel.sensorMode.collect { mode ->
                        updateModeUI(mode)
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

                        // Refresh T/P/H staleness
                        updateSensorUI(
                            viewModel.lastValidTemperature.value,
                            viewModel.lastTemperatureUpdate.value,
                            viewModel.lastValidPressure.value,
                            viewModel.lastPressureUpdate.value,
                            viewModel.lastValidHumidity.value,
                            viewModel.lastHumidityUpdate.value
                        )
                    }
                }

                // ── Periodic chart refresh (every 1 second) ───────────
                // When the ESP32 goes offline, the Room Flow stops emitting,
                // so the chart's "Data Unavailable" gap never grows.
                // This timer forces a rebuild with cached readings + fresh nowMs.
                launch {
                    while (true) {
                        kotlinx.coroutines.delay(1_000)
                        if (latestReadings.isNotEmpty()) {
                            updateChart(latestReadings, viewModel.sensorMode.value)
                        }
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
            BlendModeColorFilterCompat.createBlendModeColorFilterCompat(color, BlendModeCompat.SRC_IN)

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
    // T/P/H SENSOR UI UPDATE
    // ═══════════════════════════════════════════════════════════════════

    private fun updateSensorUI(
        temperature: Float?, temperatureUpdate: Long,
        pressure: Float?, pressureUpdate: Long,
        humidity: Float?, humidityUpdate: Long
    ) {
        updateSensorItem(
            value = temperature,
            textView = binding.textTemperature,
            iconView = binding.iconTemperature,
            formatFn = viewModel::formatTemperature,
            colorFn = viewModel::getTemperatureIntensityColorRes,
            stalenessFn = { viewModel.isDataStale(temperatureUpdate) }
        )
        updateSensorItem(
            value = pressure,
            textView = binding.textPressure,
            iconView = binding.iconPressure,
            formatFn = viewModel::formatPressure,
            colorFn = viewModel::getPressureIntensityColorRes,
            stalenessFn = { viewModel.isDataStale(pressureUpdate) }
        )
        updateSensorItem(
            value = humidity,
            textView = binding.textHumidity,
            iconView = binding.iconHumidity,
            formatFn = viewModel::formatHumidity,
            colorFn = viewModel::getHumidityIntensityColorRes,
            stalenessFn = { viewModel.isDataStale(humidityUpdate) }
        )
    }

    private fun updateSensorItem(
        value: Float?,
        textView: TextView,
        iconView: ImageView,
        formatFn: (Float) -> String,
        colorFn: (Float) -> Int,
        stalenessFn: () -> Boolean
    ) {
        if (value != null) {
            textView.text = formatFn(value)
            val isStale = stalenessFn()
            val colorRes = if (isStale) R.color.intensity_stale else colorFn(value)
            val color = ContextCompat.getColor(this, colorRes)
            textView.setTextColor(color)
            iconView.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(color, BlendModeCompat.SRC_IN)
        } else {
            textView.text = "--"
            val secondaryColor = ContextCompat.getColor(this, R.color.on_surface_secondary)
            textView.setTextColor(secondaryColor)
            iconView.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(secondaryColor, BlendModeCompat.SRC_IN)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MODE SWITCHER
    // ═══════════════════════════════════════════════════════════════════

    private fun updateModeUI(mode: SensorMode) {
        binding.textModeName.text = when (mode) {
            SensorMode.RAINFALL -> getString(R.string.mode_rainfall)
            SensorMode.TEMPERATURE -> getString(R.string.mode_temperature)
            SensorMode.PRESSURE -> getString(R.string.mode_pressure)
            SensorMode.HUMIDITY -> getString(R.string.mode_humidity)
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

    private fun getChartLineColorRes(mode: SensorMode): Int = when (mode) {
        SensorMode.RAINFALL -> R.color.chart_line_rainfall
        SensorMode.TEMPERATURE -> R.color.chart_line_temperature
        SensorMode.PRESSURE -> R.color.chart_line_pressure
        SensorMode.HUMIDITY -> R.color.chart_line_humidity
    }

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

        // Y-axis (left — unit depends on selected mode, set in renderPreviewChart)
        val yAxis = chart.axisLeft
        yAxis.setDrawGridLines(true)
        yAxis.gridColor = ContextCompat.getColor(this, R.color.chart_grid)
        yAxis.textColor = ContextCompat.getColor(this, R.color.chart_label)
        yAxis.textSize = 10f
        yAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format(Locale.US, "%.1f", value)
            }
        }

        // Hide right Y-axis
        chart.axisRight.isEnabled = false
    }

    /**
     * Updates the chart with today's readings and the current sensor mode.
     *
     * Uses [GraphDataBuilder] for gap detection, midnight boundary
     * handling, and live edge extension. Each continuous segment
     * becomes a separate DataSet so gaps appear as visual breaks.
     */
    private fun updateChart(readings: List<RainfallReading>, mode: SensorMode) {
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
        graphViewModel.buildChartData(readings, mode) { chartData ->
            renderPreviewChart(chart, chartData, mode)
        }
    }

    /**
     * Renders segmented chart data on the preview chart.
     * Each segment = one continuous line. Gaps = visual breaks.
     */
    private fun renderPreviewChart(
        chart: LineChart,
        chartData: GraphDataBuilder.ChartData,
        mode: SensorMode
    ) {
        if (chartData.segments.isEmpty()) {
            chart.clear()
            chart.visibility = View.GONE
            binding.textGraphNoData.visibility = View.VISIBLE
            binding.textGraphNoData.text = getString(R.string.graph_no_data_mode, when (mode) {
                SensorMode.RAINFALL -> getString(R.string.mode_rainfall)
                SensorMode.TEMPERATURE -> getString(R.string.mode_temperature)
                SensorMode.PRESSURE -> getString(R.string.mode_pressure)
                SensorMode.HUMIDITY -> getString(R.string.mode_humidity)
            })
            return
        }

        chart.visibility = View.VISIBLE
        binding.textGraphNoData.visibility = View.GONE

        val lineColorRes = getChartLineColorRes(mode)
        val lineColor = ContextCompat.getColor(this, lineColorRes)

        val segmentSets = chartData.segments.mapIndexed { index, segment ->
            LineDataSet(segment.entries, "Segment $index").apply {
                this.mode = LineDataSet.Mode.STEPPED
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

        // ── Compute Y-axis ceiling once (gap fills + axis clamp) ──
        var hatchY: Float? = null
        if (chartData.gapRegions.isNotEmpty()) {
            val maxRate = chartData.segments
                .flatMap { it.entries }
                .maxOfOrNull { it.y } ?: 0f
            hatchY = when (mode) {
                SensorMode.RAINFALL -> maxOf(maxRate * 1.3f, 5f)
                SensorMode.TEMPERATURE -> maxOf(maxRate + 5f, 25f)
                SensorMode.PRESSURE -> maxOf(maxRate + 10f, 1010f)
                SensorMode.HUMIDITY -> 100f
            }
        }

        // ── Gap region hatch fills ──────────────────────────────────
        val gapSets = if (hatchY == null) emptyList() else {
            val ctx = this
            chartData.gapRegions.map { gap ->
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
        }
        val allDataSets = segmentSets + gapSets

        // ── Configure Y-axis for current mode ───────────────────────
        val yAxis = chart.axisLeft
        when (mode) {
            SensorMode.RAINFALL -> {
                yAxis.axisMinimum = 0f
                yAxis.resetAxisMaximum()
            }
            SensorMode.TEMPERATURE -> {
                yAxis.resetAxisMinimum()
                yAxis.resetAxisMaximum()
            }
            SensorMode.PRESSURE -> {
                yAxis.resetAxisMinimum()
                yAxis.resetAxisMaximum()
            }
            SensorMode.HUMIDITY -> {
                yAxis.axisMinimum = 0f
                yAxis.axisMaximum = 100f
            }
        }

        chart.data = LineData(allDataSets)

        // Clamp Y-axis max when gap regions are present
        if (hatchY != null) {
            chart.axisLeft.axisMaximum = maxOf(hatchY, chart.yMax)
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
        val mode = viewModel.sensorMode.value
        // ── Title warning: blink if any T/P/H reading is null ───────
        val hasNullReadings = mode != SensorMode.RAINFALL && readings.any { r ->
            when (mode) {
                SensorMode.TEMPERATURE -> r.temperature == null
                SensorMode.PRESSURE -> r.pressure == null
                SensorMode.HUMIDITY -> r.humidity == null
                else -> false
            }
        }
        if (hasNullReadings) binding.imgReadingsWarning.startBlinking()
        else binding.imgReadingsWarning.stopBlinking()

        if (readings.isEmpty()) {
            binding.recyclerReadingsPreview.visibility = View.GONE
            binding.textReadingsNoData.visibility = View.VISIBLE
            readingsAdapter.submitList(emptyList())
            return
        }

        binding.recyclerReadingsPreview.visibility = View.VISIBLE
        binding.textReadingsNoData.visibility = View.GONE
        val items = buildReadingsList(readings, mode)
        readingsAdapter.submitList(items)

        // Compute height using per-type heights, showing up to MAX_PREVIEW_ITEMS
        // reading rows plus any gap rows that fall between them.
        val readingHeightPx = (48 * resources.displayMetrics.density).toInt()
        val gapHeightPx = (32 * resources.displayMetrics.density).toInt()
        val visibleItems = mutableListOf<ReadingsListItem>()
        var readingCount = 0
        for (item in items) {
            visibleItems.add(item)
            if (item is ReadingsListItem.Reading) {
                readingCount++
                if (readingCount >= MAX_PREVIEW_ITEMS) break
            }
        }
        val totalHeight = visibleItems.sumOf { item ->
            when (item) {
                is ReadingsListItem.Reading -> readingHeightPx
                is ReadingsListItem.Gap -> gapHeightPx
            }
        }
        if (binding.recyclerReadingsPreview.layoutParams.height != totalHeight) {
            binding.recyclerReadingsPreview.layoutParams.height = totalHeight
            binding.recyclerReadingsPreview.requestLayout()
        }
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

    private fun showIntensityToast(value: Float?, labelFn: (Float) -> String, sensorName: String): Boolean {
        if (value == null) return false
        Toast.makeText(this, "$sensorName: ${labelFn(value)}", Toast.LENGTH_SHORT).show()
        return true
    }
}
