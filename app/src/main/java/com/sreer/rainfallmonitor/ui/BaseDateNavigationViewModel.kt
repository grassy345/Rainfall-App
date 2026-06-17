package com.sreer.rainfallmonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.sreer.rainfallmonitor.data.AppDatabase
import com.sreer.rainfallmonitor.data.dao.RainfallReadingDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Base ViewModel for any screen that needs date navigation.
 *
 * Provides:
 * - Selected date state ([[selectedDate]])
 * - Sensor mode state ([[setMode]])
 * - Date navigation ([[navigatePrevious]], [[navigateNext]], etc.)
 * - Date formatting ([[formatDateForDisplay]])
 * - Reactive list of dates with data ([[datesWithData]])
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseDateNavigationViewModel(application: Application) : AndroidViewModel(application) {

    // ── Shared dependencies ───────────────────────────────────────────

    protected val dao: RainfallReadingDao = AppDatabase.getInstance(application).rainfallReadingDao()
    protected val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)

    // ── Selected date ────────────────────────────────────────────────

    private val _selectedDate = MutableStateFlow(getTodayDate())
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

    // ── Sensor mode ──────────────────────────────────────────────────

    private val _mode = MutableStateFlow(SensorMode.RAINFALL)
    fun setMode(mode: SensorMode) { _mode.value = mode }

    // ── Available dates (for date picker) ────────────────────────────

    val datesWithData: Flow<List<String>> = _mode.flatMapLatest { m ->
        when (m) {
            SensorMode.RAINFALL -> dao.getDatesWithRainfallData()
            SensorMode.TEMPERATURE -> dao.getDatesWithTemperatureData()
            SensorMode.PRESSURE -> dao.getDatesWithPressureData()
            SensorMode.HUMIDITY -> dao.getDatesWithHumidityData()
        }
    }

    // ── Date navigation ──────────────────────────────────────────────

    fun navigatePrevious(availableDates: List<String>) {
        val currentDate = _selectedDate.value
        val currentIndex = availableDates.indexOf(currentDate)
        if (currentIndex >= 0 && currentIndex < availableDates.size - 1) {
            _selectedDate.value = availableDates[currentIndex + 1]
        } else if (currentIndex == -1) {
            val older = availableDates.firstOrNull { isDateBefore(it, currentDate) }
            if (older != null) _selectedDate.value = older
        }
    }

    fun navigateNext(availableDates: List<String>) {
        val currentDate = _selectedDate.value
        val currentIndex = availableDates.indexOf(currentDate)
        if (currentIndex > 0) {
            _selectedDate.value = availableDates[currentIndex - 1]
        } else if (currentIndex == -1) {
            val newer = availableDates.lastOrNull { !isDateBefore(it, currentDate) }
            if (newer != null) _selectedDate.value = newer
        }
    }

    fun isDateBefore(date1: String, date2: String): Boolean {
        val d1 = dateFormat.parse(date1) ?: return false
        val d2 = dateFormat.parse(date2) ?: return false
        return d1.before(d2)
    }

    fun navigateToDate(date: String) {
        _selectedDate.value = date
    }

    fun navigateToToday() {
        _selectedDate.value = getTodayDate()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    fun getTodayDate(): String = dateFormat.format(Date())

    fun isToday(date: String): Boolean = date == getTodayDate()

    fun formatDateForDisplay(dateStr: String): String {
        if (dateStr == getTodayDate()) return "Today"

        val yesterday = getPreviousDate(getTodayDate())
        if (dateStr == yesterday) return "Yesterday"

        return try {
            val date = dateFormat.parse(dateStr) ?: return dateStr
            val displayFormat = SimpleDateFormat("d MMM yyyy", Locale.US)
            displayFormat.format(date)
        } catch (e: Exception) {
            dateStr
        }
    }

    protected fun getPreviousDate(dateStr: String): String {
        val date = dateFormat.parse(dateStr) ?: return dateStr
        val cal = Calendar.getInstance()
        cal.time = date
        cal.add(Calendar.DAY_OF_MONTH, -1)
        return dateFormat.format(cal.time)
    }
}
