package com.sreer.rainfallmonitor.ui.graph

import com.github.mikephil.charting.data.Entry
import com.sreer.rainfallmonitor.data.entity.RainfallReading
import com.sreer.rainfallmonitor.ui.SensorMode

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
     * @param yValueExtractor    Extracts the Y-axis value from a reading.
     *                           Return null to skip that reading (e.g. sensor failure).
     *                           Defaults to [RainfallReading.hourlyRate].
     * @param mode               Sensor mode — determines gap edge positioning.
     *                           RAINFALL gaps stop at measurement-start (10 min before
     *                           data point); T/P/H gaps extend to the data point itself
     *                           because those readings are instantaneous snapshots.
     */
    fun build(
        readings: List<RainfallReading>,
        previousDayLast: RainfallReading?,
        nextDayFirst: RainfallReading?,
        midnightMs: Long,
        isToday: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        yValueExtractor: (RainfallReading) -> Float?,
        mode: SensorMode = SensorMode.RAINFALL
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
            val prevY = yValueExtractor(previousDayLast)
            val deadZone = firstMeasurementStart - previousDayLast.timestamp
            if (prevY != null && deadZone <= GAP_THRESHOLD_MS && firstX > 0f) {
                // Normal midnight crossing — carry forward the previous value
                // from midnight to the first reading of this day.
                // Guard (firstX > 0f): if the first reading IS at midnight
                // (x == 0), skip the duplicate entry — the reading itself
                // provides the point.
                currentSegment.entries.add(
                    Entry(0f, prevY)
                )
                hasMidnightEntry = true
            } else if (firstX > 0f) {
                // Confirmed gap at midnight. For rainfall the gap ends at the
                // measurement start (reading covers the last 10 min); for T/P/H
                // (instant snapshots) the gap extends to the data point itself.
                val startGapEnd = if (mode == SensorMode.RAINFALL) {
                    (firstMeasurementStart - midnightMs).toFloat()
                } else {
                    firstX
                }
                if (startGapEnd > 0f) {
                    gapRegions.add(GapRegion(0f, startGapEnd))
                }
            }
        } else if (firstX > 0f) {
            // No data from previous day = data unavailable → grey from midnight
            val startGapEnd = if (mode == SensorMode.RAINFALL) {
                (firstMeasurementStart - midnightMs).toFloat()
            } else {
                firstX
            }
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
            val yValue = yValueExtractor(reading)

            // Skip readings with null Y value (sensor failure) —
            // close current segment and let the next valid reading
            // detect the gap naturally (prevTimestamp stays at last
            // valid timestamp so deadZone won't be zero).
            if (yValue == null) {
                if (currentSegment.entries.isNotEmpty()) {
                    segments.add(currentSegment)
                    currentSegment = Segment()
                }
                continue
            }

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
                        // RAINFALL: gap ends at measurement start (reading
                        // covers [T-10min, T]). T/P/H: gap extends to the
                        // data point (instantaneous snapshot).
                        val gapEndX = if (mode == SensorMode.RAINFALL) {
                            (measurementStart - midnightMs).toFloat()
                        } else {
                            xOffset
                        }
                        gapRegions.add(GapRegion(gapStartX, gapEndX))
                    }
                    currentSegment = Segment()
                }
            }

            currentSegment.entries.add(Entry(xOffset, yValue))
            prevTimestamp = reading.timestamp
        }

        // ── Live edge extension (today only) ─────────────────────────
        var esp32OfflineSinceX: Float? = null
        val lastValidReading = readings.lastOrNull { yValueExtractor(it) != null }
        if (isToday && currentSegment.entries.isNotEmpty() && lastValidReading != null) {
            val lastY = yValueExtractor(lastValidReading)!!
            val deadZone = (nowMs - MEASUREMENT_WINDOW_MS) - lastValidReading.timestamp
            if (deadZone <= GAP_THRESHOLD_MS) {
                // ESP32 is within normal cadence — extend line to NOW
                val nowOffset = (nowMs - midnightMs).toFloat()
                currentSegment.entries.add(Entry(nowOffset, lastY))
            } else {
                // ESP32 appears down — record gap start for today-only hatch
                esp32OfflineSinceX = currentSegment.entries.last().x
            }
        }

        // Add the last segment
        if (currentSegment.entries.isNotEmpty()) {
            segments.add(currentSegment)
        }

        // ── Today: trailing null readings → gap to now ───────────────
        // If the last reading(s) have null sensor values, the current
        // segment was closed empty. Create a gap from the last valid
        // reading to now (T/P/H) or to the current measurement window
        // (RAINFALL).
        if (isToday && currentSegment.entries.isEmpty() && lastValidReading != null) {
            val gapStartX = (lastValidReading.timestamp - midnightMs).toFloat()
            val gapEndX = if (mode == SensorMode.RAINFALL) {
                (nowMs - MEASUREMENT_WINDOW_MS - midnightMs).toFloat()
            } else {
                (nowMs - midnightMs).toFloat()
            }
            if (gapEndX > gapStartX) {
                gapRegions.add(GapRegion(gapStartX, gapEndX))
            }
        }

        // ── Today: ESP32-offline gap ─────────────────────────────────
        // Grey from the last confirmed reading to now (T/P/H) or to the
        // current measurement window (RAINFALL).
        esp32OfflineSinceX?.let { start ->
            val gapEnd = if (mode == SensorMode.RAINFALL) {
                (nowMs - MEASUREMENT_WINDOW_MS - midnightMs).toFloat()
            } else {
                (nowMs - midnightMs).toFloat()
            }
            gapRegions.add(GapRegion(start, gapEnd))
        }

        // ── Midnight boundary handling (END of day) ───────────────────
        // For historical days, check if the last reading connects to the
        // next day's first reading. A break (or no data at all on the next
        // day) means a gap at the right edge. For RAINFALL the gap stops
        // at the next day's measurement start (reading covers 10 min before
        // midnight); for T/P/H it extends to the next day's first data point.
        if (!isToday && segments.isNotEmpty()) {
            val lastX = segments.last().entries.last().x
            if (nextDayFirst == null) {
                gapRegions.add(GapRegion(lastX, xMax))
            } else {
                val lastReading = readings.last()
                val nextMeasurementStart = nextDayFirst.timestamp - MEASUREMENT_WINDOW_MS
                val deadZone = nextMeasurementStart - lastReading.timestamp
                if (deadZone > GAP_THRESHOLD_MS) {
                    val gapEnd = if (mode == SensorMode.RAINFALL) {
                        ((nextMeasurementStart - midnightMs).toFloat()).coerceAtMost(xMax)
                    } else {
                        ((nextDayFirst.timestamp - midnightMs).toFloat()).coerceAtMost(xMax)
                    }
                    gapRegions.add(GapRegion(lastX, gapEnd))
                }
            }
        }

        return ChartData(segments, xMin, xMax, gapRegions)
    }
}

/**
 * Extracts the Y-axis value from a [RainfallReading] based on the [SensorMode].
 * Returns null if the sensor has no data for that reading.
 */
internal fun RainfallReading.yValueForMode(mode: SensorMode): Float? = when (mode) {
    SensorMode.RAINFALL -> hourlyRate
    SensorMode.TEMPERATURE -> temperature
    SensorMode.PRESSURE -> pressure
    SensorMode.HUMIDITY -> humidity
}
