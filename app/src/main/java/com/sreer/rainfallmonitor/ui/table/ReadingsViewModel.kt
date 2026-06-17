package com.sreer.rainfallmonitor.ui.table

import android.app.Application
import com.sreer.rainfallmonitor.data.entity.RainfallReading
import com.sreer.rainfallmonitor.ui.BaseDateNavigationViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * ViewModel for the full-screen [ReadingsActivity].
 *
 * Provides all readings (newest-first) for the selected date.
 * Date-navigation behaviour is inherited from [BaseDateNavigationViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingsViewModel(application: Application) : BaseDateNavigationViewModel(application) {

    // ── Reactive readings for selected date ──────────────────────────

    /**
     * All readings for the currently selected date, newest-first.
     * Automatically re-queries when selectedDate changes.
     * Capped at 10,000 rows per date (far above the ESP32's ~144/day).
     */
    val allReadingsForDate: Flow<List<RainfallReading>> =
        selectedDate.flatMapLatest { date ->
            dao.getLatestReadingsForDate(date, 10_000)
        }
}
