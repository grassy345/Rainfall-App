package com.sreer.rainfallmonitor.ui.graph

import com.github.mikephil.charting.data.Entry
import com.sreer.rainfallmonitor.data.entity.RainfallReading

/**
 * Builds chart data with gap detection, midnight boundary handling,
 * and live edge extension.
 *
 * This is the "brain" of the graph. Both the dashboard preview and
 * the full-screen GraphFragment use this to convert raw readings
 * into segmented chart data.
 *
 * Key concepts:
 *   - Each reading at timestamp T means the ESP32 was alive during [T-10m, T].
 *   - Two consecutive readings are "continuous" if the dead zone between
 *     them is ≤ 5 minutes. Otherwise, there's a gap.
 *   - dead_zone = new.measurement_start - prev.timestamp
 *     where measurement_start = new.timestamp - 10 minutes
 *   - Gaps result in separate [Segment]s, each drawn as its own line.
 */
object GraphDataBuilder {

    /** ESP32 measurement interval: each reading covers 10 minutes. */
    private const val MEASUREMENT_WINDOW_MS = 10 * 60 * 1000L

    /**
     * Maximum acceptable dead zone between consecutive readings.
     * Beyond this, we treat the gap as real data unavailability.
     * Accounts for ESP32 timing jitter (WiFi, NTP sync, loop time).
     */
    private const val GAP_THRESHOLD_MS = 5 * 60 * 1000L

    /**
     * A continuous line segment on the graph.
     * Multiple segments = multiple line segments with visual breaks (gaps) between them.
     */
    data class Segment(val entries: MutableList<Entry> = mutableListOf())

    /**
     * Result of building chart data for a day.
     *
     * @property segments  List of continuous line segments (each becomes a DataSet)
     * @property xMin      Left edge of the X-axis (midnight epoch ms for offset calc)
     * @property xMax      Right edge of the X-axis (end of day or NOW for today)
     */
    data class ChartData(
        val segments: List<Segment>,
        val xMin: Float,
        val xMax: Float
    )

    /**
     * Builds segmented chart data for a given day.
     *
     * @param readings           Today's readings, sorted by timestamp ASC
     * @param previousDayLast    Last reading from the previous day (for midnight boundary)
     * @param midnightMs         Epoch ms of midnight (00:00:00.000) for this day
     * @param isToday            True if this is today's graph (enables live edge)
     * @param nowMs              Current time in epoch ms (for live edge extension)
     */
    fun build(
        readings: List<RainfallReading>,
        previousDayLast: RainfallReading?,
        midnightMs: Long,
        isToday: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): ChartData {
        val endOfDayMs = midnightMs + 24 * 60 * 60 * 1000L

        // X-axis range: midnight to midnight (or NOW for today)
        val xMin = 0f  // midnight offset = 0
        val xMax = if (isToday) {
            (nowMs - midnightMs).toFloat()
        } else {
            (endOfDayMs - midnightMs).toFloat() // 86_400_000
        }

        if (readings.isEmpty()) {
            return ChartData(emptyList(), xMin, xMax)
        }

        val segments = mutableListOf<Segment>()
        var currentSegment = Segment()

        // ── Midnight boundary handling ───────────────────────────────
        // Check if the previous day's last reading connects seamlessly
        // to this day's first reading.
        val firstReading = readings.first()
        val firstMeasurementStart = firstReading.timestamp - MEASUREMENT_WINDOW_MS

        if (previousDayLast != null) {
            val deadZone = firstMeasurementStart - previousDayLast.timestamp
            if (deadZone <= GAP_THRESHOLD_MS) {
                // Normal midnight crossing — carry forward the previous rate
                // from midnight to the first reading of this day.
                currentSegment.entries.add(
                    Entry(0f, previousDayLast.hourlyRate)
                )
            }
            // else: gap at midnight — leave empty space before first reading
        }
        // If no previousDayLast: no data from yesterday — leave empty space

        // ── Process readings ─────────────────────────────────────────
        var prevTimestamp = if (currentSegment.entries.isNotEmpty()) {
            // We just added a midnight point from prev day's rate
            midnightMs
        } else {
            -1L
        }

        for (reading in readings) {
            val xOffset = (reading.timestamp - midnightMs).toFloat()
            val measurementStart = reading.timestamp - MEASUREMENT_WINDOW_MS

            if (prevTimestamp >= 0) {
                val deadZone = measurementStart - prevTimestamp
                if (deadZone > GAP_THRESHOLD_MS) {
                    // GAP detected — end current segment, start a new one
                    if (currentSegment.entries.isNotEmpty()) {
                        segments.add(currentSegment)
                    }
                    currentSegment = Segment()
                }
            }

            currentSegment.entries.add(Entry(xOffset, reading.hourlyRate))
            prevTimestamp = reading.timestamp
        }

        // ── Live edge extension (today only) ─────────────────────────
        // If today's last reading is recent enough (dead_zone ≤ threshold),
        // extend the stepped line to the current time.
        if (isToday && currentSegment.entries.isNotEmpty()) {
            val lastReading = readings.last()
            // Check if ESP32 is within normal reporting cadence.
            // dead_zone = (nowMs - MEASUREMENT_WINDOW_MS) - lastReading.timestamp
            val deadZone = (nowMs - MEASUREMENT_WINDOW_MS) - lastReading.timestamp
            if (deadZone <= GAP_THRESHOLD_MS) {
                // ESP32 is within normal cadence — extend line to NOW
                val nowOffset = (nowMs - midnightMs).toFloat()
                currentSegment.entries.add(Entry(nowOffset, lastReading.hourlyRate))
            }
            // else: ESP32 appears down — line stops at last reading (gap after it)
        }

        // Add the last segment
        if (currentSegment.entries.isNotEmpty()) {
            segments.add(currentSegment)
        }

        return ChartData(segments, xMin, xMax)
    }
}
