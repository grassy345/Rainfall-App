package com.sreer.rainfallmonitor.ui.graph

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sreer.rainfallmonitor.data.AppDatabase
import com.sreer.rainfallmonitor.data.entity.RainfallReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * ViewModel for the graph — shared between the dashboard preview
 * and the full-screen [GraphFragment].
 *
 * Responsibilities:
 *   - Holds the currently selected date
 *   - Provides a reactive Flow of readings for that date
 *   - Provides list of dates with data (for date picker / navigation)
 *   - Calls [GraphDataBuilder] to produce segmented chart data
 *   - Navigates between dates (prev/next, skipping empty dates)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).rainfallReadingDao()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)

    // ── Selected date ────────────────────────────────────────────────

    private val _selectedDate = MutableStateFlow(getTodayDate())
    /** Currently selected date in dd/MM/yyyy format. */
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

    // ── Reactive readings for selected date ──────────────────────────

    /**
     * Flow of readings for the currently selected date.
     * Automatically re-queries when selectedDate changes (flatMapLatest).
     */
    val readingsForDate: Flow<List<RainfallReading>> =
        _selectedDate.flatMapLatest { date ->
            dao.getReadingsForDate(date)
        }

    // ── Available dates (for date picker) ────────────────────────────

    /** All dates that have at least one reading. Sorted newest-first. */
    val datesWithData: Flow<List<String>> = dao.getDatesWithData()

    // ── Date navigation ──────────────────────────────────────────────

    /**
     * Navigate to the previous date that has data.
     * Skips dates without readings.
     *
     * @param availableDates List of dates with data (newest-first from [datesWithData])
     */
    fun navigatePrevious(availableDates: List<String>) {
        val currentDate = _selectedDate.value
        val currentIndex = availableDates.indexOf(currentDate)
        // availableDates is newest-first, so "previous" = next index (older date)
        if (currentIndex >= 0 && currentIndex < availableDates.size - 1) {
            _selectedDate.value = availableDates[currentIndex + 1]
        }
    }

    /**
     * Navigate to the next date that has data.
     * Skips dates without readings.
     *
     * @param availableDates List of dates with data (newest-first from [datesWithData])
     */
    fun navigateNext(availableDates: List<String>) {
        val currentDate = _selectedDate.value
        val currentIndex = availableDates.indexOf(currentDate)
        // availableDates is newest-first, so "next" = previous index (newer date)
        if (currentIndex > 0) {
            _selectedDate.value = availableDates[currentIndex - 1]
        }
    }

    /** Jump directly to a specific date (from the calendar picker). */
    fun navigateToDate(date: String) {
        _selectedDate.value = date
    }

    /** Navigate to today's date. */
    fun navigateToToday() {
        _selectedDate.value = getTodayDate()
    }

    // ── Chart data building ──────────────────────────────────────────

    /**
     * Builds chart data for the currently selected date.
     *
     * Fetches the previous day's last reading (for midnight boundary),
     * then delegates to [GraphDataBuilder].
     *
     * @param readings Today's readings (from [readingsForDate])
     * @param onResult Callback with the built [GraphDataBuilder.ChartData]
     */
    fun buildChartData(
        readings: List<RainfallReading>,
        onResult: (GraphDataBuilder.ChartData) -> Unit
    ) {
        viewModelScope.launch {
            val currentDate = _selectedDate.value
            val isToday = currentDate == getTodayDate()
            val midnightMs = computeMidnightMs(currentDate)

            // Get previous day's last reading for midnight boundary
            val prevDate = getPreviousDate(currentDate)
            val previousDayLast = dao.getLastReadingForDate(prevDate)

            val chartData = GraphDataBuilder.build(
                readings = readings,
                previousDayLast = previousDayLast,
                midnightMs = midnightMs,
                isToday = isToday
            )

            onResult(chartData)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** Returns today's date in dd/MM/yyyy format. */
    fun getTodayDate(): String = dateFormat.format(Date())

    /** Returns true if the selected date is today (for a given date string). */
    fun isToday(date: String): Boolean = date == getTodayDate()

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
     * Returns the date string for the day BEFORE the given date.
     */
    private fun getPreviousDate(dateStr: String): String {
        val date = dateFormat.parse(dateStr) ?: return dateStr
        val cal = Calendar.getInstance()
        cal.time = date
        cal.add(Calendar.DAY_OF_MONTH, -1)
        return dateFormat.format(cal.time)
    }

    /**
     * Formats a dd/MM/yyyy date string for display.
     * Today → "Today", Yesterday → "Yesterday", else → "29 May 2026"
     */
    fun formatDateForDisplay(dateStr: String): String {
        if (dateStr == getTodayDate()) return "Today"

        val yesterday = getPreviousDate(getTodayDate())
        if (dateStr == yesterday) return "Yesterday"

        return try {
            val date = dateFormat.parse(dateStr)
            val displayFormat = SimpleDateFormat("d MMM yyyy", Locale.US)
            displayFormat.format(date!!)
        } catch (e: Exception) {
            dateStr
        }
    }
}
