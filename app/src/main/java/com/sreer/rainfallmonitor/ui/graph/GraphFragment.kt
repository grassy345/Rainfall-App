package com.sreer.rainfallmonitor.ui.graph

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.datepicker.MaterialDatePicker
import com.sreer.rainfallmonitor.R
import com.sreer.rainfallmonitor.databinding.FragmentGraphBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen graph fragment hosted by [GraphActivity].
 *
 * Design philosophy:
 *   - Runs in the landscape graph screen launched from the dashboard
 *   - Uses the full rotated screen while keeping chart controls overlaid
 *   - Chart inside a rounded card with breathing room from edges
 *   - Floating frosted-glass controls overlaid on the chart
 *   - "Jump to Today" chip for quick navigation back
 *   - Tap-to-inspect: MarkerView tooltip shows exact time + rate
 *   - Smooth entry animation on first load
 *
 * Features:
 *   - Gap detection with separate line segments (via GraphDataBuilder)
 *   - Date navigation (←/→ arrows skip dates without data)
 *   - Calendar picker for long-range date jumps
 *   - Live edge extension for today's graph
 *   - Midnight boundary handling from previous day
 *   - Pinch-to-zoom and pan for detailed inspection
 */
class GraphFragment : Fragment() {

    private var _binding: FragmentGraphBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GraphViewModel by viewModels()

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)

    /** Cached list of dates with data, for navigation. */
    private var availableDates: List<String> = emptyList()

    /** Track if this is the first data load (for entry animation). */
    private var isFirstLoad = true

    /** MarkerView for tap-to-inspect tooltips. */
    private lateinit var markerView: ChartMarkerView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGraphBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupChart()
        setupNavigation()
        observeData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ═══════════════════════════════════════════════════════════════════
    // CHART SETUP
    // ═══════════════════════════════════════════════════════════════════

    private fun setupChart() {
        val chart = binding.chartFullscreen
        val ctx = requireContext()

        // General
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setDrawGridBackground(false)
        chart.setNoDataText("")

        // Enable touch for zoom/pan
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)

        // Tap-to-inspect highlighting
        chart.isHighlightPerTapEnabled = true

        // Extra offsets: breathing room for labels + space for floating controls
        // Left: Y-axis labels, Top: floating date nav pill, Right: margin, Bottom: X-axis
        chart.setExtraOffsets(8f, 48f, 16f, 8f)

        // X-axis (bottom — time of day)
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(true)
        xAxis.gridColor = ContextCompat.getColor(ctx, R.color.chart_grid)
        xAxis.textColor = ContextCompat.getColor(ctx, R.color.chart_label)
        xAxis.textSize = 11f
        xAxis.setLabelCount(6, false)

        // Y-axis (left — rate in mm/hr)
        val yAxis = chart.axisLeft
        yAxis.setDrawGridLines(true)
        yAxis.gridColor = ContextCompat.getColor(ctx, R.color.chart_grid)
        yAxis.textColor = ContextCompat.getColor(ctx, R.color.chart_label)
        yAxis.textSize = 11f
        yAxis.axisMinimum = 0f
        yAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format(Locale.US, "%.1f", value)
            }
        }

        // Hide right Y-axis
        chart.axisRight.isEnabled = false

        // Set up MarkerView for tap-to-inspect tooltips
        markerView = ChartMarkerView(ctx)
        markerView.chartView = chart
        chart.marker = markerView
    }

    // ═══════════════════════════════════════════════════════════════════
    // NAVIGATION
    // ═══════════════════════════════════════════════════════════════════

    private fun setupNavigation() {
        binding.btnPrevDate.setOnClickListener {
            viewModel.navigatePrevious(availableDates)
        }

        binding.btnNextDate.setOnClickListener {
            viewModel.navigateNext(availableDates)
        }

        binding.btnCalendar.setOnClickListener {
            showDatePicker()
        }

        binding.btnClose.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // "Jump to Today" chip
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
            // MaterialDatePicker returns UTC midnight — must parse with UTC timezone.
            val utcFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val dateStr = utcFormat.format(Date(selection))
            viewModel.navigateToDate(dateStr)
        }

        picker.show(childFragmentManager, "date_picker")
    }

    // ═══════════════════════════════════════════════════════════════════
    // DATA OBSERVATION
    // ═══════════════════════════════════════════════════════════════════

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Track available dates for navigation
                launch {
                    viewModel.datesWithData.collect { dates ->
                        availableDates = dates
                        updateNavigationButtons()
                    }
                }

                // React to date changes + readings
                launch {
                    viewModel.readingsForDate
                        .combine(viewModel.selectedDate) { readings, date ->
                            Triple(readings, date, viewModel.isToday(date))
                        }
                        .collect { (readings, date, isToday) ->
                            // Update date label
                            binding.textDateLabel.text = viewModel.formatDateForDisplay(date)
                            updateNavigationButtons()

                            // Show/hide "Jump to Today" chip
                            binding.chipToday.visibility =
                                if (isToday) View.GONE else View.VISIBLE

                            // Update X-axis formatter + MarkerView midnight
                            val midnightMs = viewModel.computeMidnightMs(date)
                            updateXAxisFormatter(midnightMs)
                            markerView.midnightMs = midnightMs

                            // Build and render chart data
                            viewModel.buildChartData(readings) { chartData ->
                                renderChart(chartData)
                            }
                        }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CHART RENDERING
    // ═══════════════════════════════════════════════════════════════════

    private fun renderChart(chartData: GraphDataBuilder.ChartData) {
        val binding = _binding ?: return  // Fragment may be detached
        val chart = binding.chartFullscreen
        val ctx = context ?: return

        if (chartData.segments.isEmpty()) {
            chart.visibility = View.GONE
            binding.textNoData.visibility = View.VISIBLE
            chart.clear()
            return
        }

        chart.visibility = View.VISIBLE
        binding.textNoData.visibility = View.GONE

        val lineColor = ContextCompat.getColor(ctx, R.color.chart_line)
        val highlightColor = ContextCompat.getColor(ctx, R.color.chart_label)

        // Each segment = one DataSet = one continuous line
        val dataSets = chartData.segments.mapIndexed { index, segment ->
            LineDataSet(segment.entries, "Segment $index").apply {
                mode = LineDataSet.Mode.STEPPED
                color = lineColor
                lineWidth = 2f
                setDrawCircles(false)
                setDrawValues(false)
                setDrawFilled(true)
                fillColor = lineColor
                fillAlpha = 30

                // Highlight (tap-to-inspect) styling
                isHighlightEnabled = true
                highLightColor = highlightColor
                highlightLineWidth = 1f
                setDrawHighlightIndicators(true)
            }
        }

        chart.data = LineData(dataSets)

        // Set X-axis range
        chart.xAxis.axisMinimum = chartData.xMin
        chart.xAxis.axisMaximum = chartData.xMax

        chart.notifyDataSetChanged()

        // Smooth entry animation on first load
        if (isFirstLoad) {
            chart.animateX(600)
            isFirstLoad = false
        } else {
            chart.invalidate()
        }
    }

    /**
     * Updates the X-axis formatter to show times relative to midnight.
     */
    private fun updateXAxisFormatter(midnightMs: Long) {
        binding.chartFullscreen.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return timeFormat.format(Date(midnightMs + value.toLong()))
            }
        }
    }

    /**
     * Enables/disables navigation buttons based on current position in available dates.
     */
    private fun updateNavigationButtons() {
        val binding = _binding ?: return
        val currentDate = viewModel.selectedDate.value
        val currentIndex = availableDates.indexOf(currentDate)

        // Previous = older date = higher index in newest-first list
        binding.btnPrevDate.isEnabled = currentIndex >= 0 && currentIndex < availableDates.size - 1
        binding.btnPrevDate.alpha = if (binding.btnPrevDate.isEnabled) 1.0f else 0.3f

        // Next = newer date = lower index
        binding.btnNextDate.isEnabled = currentIndex > 0
        binding.btnNextDate.alpha = if (binding.btnNextDate.isEnabled) 1.0f else 0.3f
    }
}
