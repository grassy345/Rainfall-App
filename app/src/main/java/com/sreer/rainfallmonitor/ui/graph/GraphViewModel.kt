package com.sreer.rainfallmonitor.ui.graph

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.sreer.rainfallmonitor.data.entity.RainfallReading
import com.sreer.rainfallmonitor.ui.BaseDateNavigationViewModel
import com.sreer.rainfallmonitor.ui.SensorMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * ViewModel for the graph — shared between the dashboard preview
 * and the full-screen [GraphFragment].
 *
 * Responsibilities:
 *   - Provides a reactive Flow of readings for the selected date
 *   - Calls [GraphDataBuilder] to produce segmented chart data
 *   - Caches boundary readings to avoid re-querying on timer ticks
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphViewModel(application: Application) : BaseDateNavigationViewModel(application) {

    // ── Boundary cache (avoid re-querying prev/next day on every timer tick) ─

    private var cachedDate: String? = null
    private var cachedPrevDayLast: RainfallReading? = null
    private var cachedNextDayFirst: RainfallReading? = null

    // ── Reactive readings for selected date ──────────────────────────

    /**
     * Flow of readings for the currently selected date.
     * Automatically re-queries when selectedDate changes (flatMapLatest).
     */
    val readingsForDate: Flow<List<RainfallReading>> =
        selectedDate.flatMapLatest { date ->
            dao.getReadingsForDate(date)
        }

    // ── Chart data building ──────────────────────────────────────────

    /** Cancels any in-flight chart build to avoid stale callbacks. */
    private var buildChartJob: Job? = null

    /**
     * Builds chart data for the currently selected date.
     *
     * Fetches the previous day's last reading and next day's first reading
     * (for midnight boundary), then delegates to [GraphDataBuilder].
     * Cancels any previously launched build to avoid stale callbacks.
     *
     * @param readings Readings for the selected date, sorted by timestamp ASC
     * @param mode     The current sensor mode — determines which field to plot
     * @param onResult Callback with the built chart data
     */
    fun buildChartData(
        readings: List<RainfallReading>,
        mode: SensorMode = SensorMode.RAINFALL,
        onResult: (GraphDataBuilder.ChartData) -> Unit
    ) {
        buildChartJob?.cancel()
        buildChartJob = viewModelScope.launch {
            val currentDate = selectedDate.value
            val isToday = currentDate == getTodayDate()
            val midnightMs = computeMidnightMs(currentDate)
            val prevDate = getPreviousDate(currentDate)
            val nextDate = getNextDate(currentDate)

            if (currentDate != cachedDate) {
                cachedPrevDayLast = dao.getLastReadingForDate(prevDate)
                cachedNextDayFirst = dao.getFirstReadingForDate(nextDate)
                cachedDate = currentDate
            }

            val chartData = GraphDataBuilder.build(
                readings = readings,
                previousDayLast = cachedPrevDayLast,
                nextDayFirst = cachedNextDayFirst,
                midnightMs = midnightMs,
                isToday = isToday,
                yValueExtractor = { reading -> reading.yValueForMode(mode) },
                mode = mode
            )
            onResult(chartData)
        }
    }

    /**
     * Computes midnight epoch ms for a given dd/MM/yyyy date string.
     */
    fun computeMidnightMs(dateStr: String): Long {
        val date = dateFormat.parse(dateStr) ?: return 0L
        val cal = Calendar.getInstance()
        cal.time = date
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Returns the date string for the day AFTER the given date.
     */
    private fun getNextDate(dateStr: String): String {
        val date = dateFormat.parse(dateStr) ?: return dateStr
        val cal = Calendar.getInstance()
        cal.time = date
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return dateFormat.format(cal.time)
    }
}
