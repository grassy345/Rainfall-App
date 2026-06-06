package com.sreer.rainfallmonitor.ui.graph

import com.github.mikephil.charting.data.Entry
import com.sreer.rainfallmonitor.data.entity.RainfallReading

/**
 * ESP32 measurement interval: each reading covers 10 minutes.
 */
internal const val MEASUREMENT_WINDOW_MS = 10 * 60 * 1000L

/**
 * Maximum acceptable dead zone between consecutive readings.
 * Beyond this, we treat the gap as real data unavailability.
 * Accounts for ESP32 timing jitter (WiFi, NTP sync, loop time).
 */
internal const val GAP_THRESHOLD_MS = 5 * 60 * 1000L

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

    /**
     * A continuous line segment on the graph.
     * Multiple segments = multiple line segments with visual breaks (gaps) between them.
     */
    data class Segment(val entries: MutableList<Entry> = mutableListOf())

    /**
     * A time range on the X-axis where data was unavailable (ESP32 offline).
     * The renderer fills this region with a hatched pattern.
     */
    data class GapRegion(val xStart: Float, val xEnd: Float)

    /**
     * Result of building chart data for a day.
     *
     * @property segments     Continuous line segments (each becomes a DataSet)
     * @property gapRegions   X-axis ranges where data was unavailable (for hatch fill)
     * @property xMin         Left edge of the X-axis (midnight epoch ms for offset calc)
     * @property xMax         Right edge of the X-axis (end of day)
     */
    data class ChartData(
        val segments: List<Segment>,
        val xMin: Float,
        val xMax: Float,
        val gapRegions: List<GapRegion> = emptyList()
    )

    /**
     * Builds segmented chart data for a given day.
     *
     * @param readings           Today's readings, sorted by timestamp ASC
     * @param previousDayLast    Last reading from the previous day (for midnight boundary)
     * @param nextDayFirst       First reading of the next day (for end-of-day gap detection)
     * @param midnightMs         Epoch ms of midnight (00:00:00.000) for this day
     * @param isToday            True if this is today's graph (enables live edge)
     * @param nowMs              Current time in epoch ms (for live edge extension)
     */
    fun build(
        readings: List<RainfallReading>,
        previousDayLast: RainfallReading?,
        nextDayFirst: RainfallReading?,
        midnightMs: Long,
        isToday: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): ChartData {
        val endOfDayMs = midnightMs + 24 * 60 * 60 * 1000L

        // X-axis always spans the full day (midnight → midnight) so the
        // right edge is stable. For today, the live-edge extension still
        // draws the stepped line up to NOW, but the X-axis extends to
        // midnight, giving breathing room so the latest spike is visible.
        val xMin = 0f
        val xMax = (endOfDayMs - midnightMs).toFloat() // 86_400_000

        if (readings.isEmpty()) {
            return ChartData(emptyList(), xMin, xMax)
        }

        val segments = mutableListOf<Segment>()
        var currentSegment = Segment()
        val gapRegions = mutableListOf<GapRegion>()

        // ── Midnight boundary handling (START of day) ─────────────────
        // Check if the previous day's last reading connects seamlessly
        // to this day's first reading.
        val firstReading = readings.first()
        val firstMeasurementStart = firstReading.timestamp - MEASUREMENT_WINDOW_MS
        val firstX = (firstReading.timestamp - midnightMs).toFloat()
        var hasMidnightEntry = false

        if (previousDayLast != null) {
            val deadZone = firstMeasurementStart - previousDayLast.timestamp
            if (deadZone <= GAP_THRESHOLD_MS && firstX > 0f) {
                // Normal midnight crossing — carry forward the previous rate
                // from midnight to the first reading of this day.
                // Guard (firstX > 0f): if the first reading IS at midnight
                // (x == 0), skip the duplicate entry — the reading itself
                // provides the point.
                currentSegment.entries.add(
                    Entry(0f, previousDayLast.hourlyRate)
                )
                hasMidnightEntry = true
            } else if (firstX > 0f) {
                // Confirmed gap at midnight — grey from midnight to first reading's
                // measurement start (the reading covers the last 10 min before it).
                // If the measurement window starts before midnight (reading within
                // 10 min of midnight), the gap has zero width — skip it.
                val startGapEnd = (firstMeasurementStart - midnightMs).toFloat()
                if (startGapEnd > 0f) {
                    gapRegions.add(GapRegion(0f, startGapEnd))
                }
            }
        } else if (firstX > 0f) {
            // No data from previous day = data unavailable → grey from midnight
            val startGapEnd = (firstMeasurementStart - midnightMs).toFloat()
            if (startGapEnd > 0f) {
                gapRegions.add(GapRegion(0f, startGapEnd))
            }
        }

        // ── Process readings ─────────────────────────────────────────
        var prevTimestamp = if (hasMidnightEntry) {
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
                    // GAP detected — end current segment, record gap region,
                    // then start a new segment.
                    if (currentSegment.entries.isNotEmpty()) {
                        segments.add(currentSegment)
                        val gapStartX = (prevTimestamp - midnightMs).toFloat()
                        val gapEndX = (measurementStart - midnightMs).toFloat()
                        gapRegions.add(GapRegion(gapStartX, gapEndX))
                    }
                    currentSegment = Segment()
                }
            }

            currentSegment.entries.add(Entry(xOffset, reading.hourlyRate))
            prevTimestamp = reading.timestamp
        }

        // ── Live edge extension (today only) ─────────────────────────
        var esp32OfflineSinceX: Float? = null
        if (isToday && currentSegment.entries.isNotEmpty()) {
            val lastReading = readings.last()
            val deadZone = (nowMs - MEASUREMENT_WINDOW_MS) - lastReading.timestamp
            if (deadZone <= GAP_THRESHOLD_MS) {
                // ESP32 is within normal cadence — extend line to NOW
                val nowOffset = (nowMs - midnightMs).toFloat()
                currentSegment.entries.add(Entry(nowOffset, lastReading.hourlyRate))
            } else {
                // ESP32 appears down — record gap start for today-only hatch
                esp32OfflineSinceX = currentSegment.entries.last().x
            }
        }

        // Add the last segment
        if (currentSegment.entries.isNotEmpty()) {
            segments.add(currentSegment)
        }

        // ── Today: ESP32-offline gap ─────────────────────────────────
        // Grey from the last confirmed reading to the start of the current
        // measurement window ([now-10min, now] is still in progress).
        esp32OfflineSinceX?.let { start ->
            val gapEnd = (nowMs - MEASUREMENT_WINDOW_MS - midnightMs).toFloat()
            gapRegions.add(GapRegion(start, gapEnd))
        }

        // ── Midnight boundary handling (END of day) ───────────────────
        // For historical days, check if the last reading connects to the
        // next day's first reading. A break (or no data at all on the next
        // day) means a gap at the right edge. Grey stops at the next day's
        // measurement start — that window covers the minutes just before
        // midnight and isn't truly unknown.
        if (!isToday && segments.isNotEmpty()) {
            val lastX = segments.last().entries.last().x
            if (nextDayFirst == null) {
                gapRegions.add(GapRegion(lastX, xMax))
            } else {
                val lastReading = readings.last()
                val nextMeasurementStart = nextDayFirst.timestamp - MEASUREMENT_WINDOW_MS
                val deadZone = nextMeasurementStart - lastReading.timestamp
                if (deadZone > GAP_THRESHOLD_MS) {
                    val gapEnd = ((nextMeasurementStart - midnightMs).toFloat()).coerceAtMost(xMax)
                    gapRegions.add(GapRegion(lastX, gapEnd))
                }
            }
        }

        return ChartData(segments, xMin, xMax, gapRegions)
    }
}
