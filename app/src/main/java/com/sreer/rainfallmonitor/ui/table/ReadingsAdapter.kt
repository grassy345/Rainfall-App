package com.sreer.rainfallmonitor.ui.table

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sreer.rainfallmonitor.R
import com.sreer.rainfallmonitor.data.entity.RainfallReading
import com.sreer.rainfallmonitor.databinding.ItemGapRowBinding
import com.sreer.rainfallmonitor.databinding.ItemReadingRowBinding
import com.sreer.rainfallmonitor.ui.SensorMode
import com.sreer.rainfallmonitor.ui.graph.GAP_THRESHOLD_MS
import com.sreer.rainfallmonitor.ui.graph.MEASUREMENT_WINDOW_MS
import com.sreer.rainfallmonitor.ui.startBlinking
import com.sreer.rainfallmonitor.ui.stopBlinking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single item in the readings list.
 */
sealed class ReadingsListItem {
    data class Reading(val data: RainfallReading, val mode: SensorMode = SensorMode.RAINFALL) : ReadingsListItem()
    data class Gap(val durationMs: Long) : ReadingsListItem()
}

/**
 * Converts a raw list of [RainfallReading]s (newest-first) into a list of [ReadingsListItem]s
 * with gap separators inserted between readings that exceed the threshold.
 *
 * Gaps are purely timestamp-based — every reading advances the timer regardless
 * of whether its sensor value is null. This ensures gap rows represent real
 * ESP32 offline periods (power cuts), not individual sensor failures.
 *
 * @param mode The sensor mode — used to determine gap duration formula
 *             (RAINFALL uses measurement-start, T/P/H use data-point).
 */
fun buildReadingsList(readings: List<RainfallReading>, mode: SensorMode = SensorMode.RAINFALL): List<ReadingsListItem> {
    if (readings.isEmpty()) return emptyList()
    if (readings.size < 2) return listOf(ReadingsListItem.Reading(readings[0], mode))

    // Process in chronological order (oldest first) for gap detection
    val asc = readings.reversed()
    val items = mutableListOf<ReadingsListItem>()

    var lastTimestamp: Long? = null

    for (reading in asc) {
        if (lastTimestamp != null) {
            val measurementStart = reading.timestamp - MEASUREMENT_WINDOW_MS
            val deadZone = measurementStart - lastTimestamp
            if (deadZone > GAP_THRESHOLD_MS) {
                val gapDuration = if (mode == SensorMode.RAINFALL) {
                    deadZone
                } else {
                    reading.timestamp - lastTimestamp
                }
                items.add(ReadingsListItem.Gap(gapDuration))
            }
        }

        items.add(ReadingsListItem.Reading(reading, mode))
        lastTimestamp = reading.timestamp
    }

    return items.reversed()  // newest-first for display
}

/**
 * RecyclerView adapter for the readings list.
 *
 * Supports two view types:
 *   - [ReadingsListItem.Reading]: a single row with time, rate, and intensity-coloured background
 *   - [ReadingsListItem.Gap]: a separator row showing "gap: Xh Ym (no data)"
 */
class ReadingsAdapter : ListAdapter<ReadingsListItem, RecyclerView.ViewHolder>(DiffCallback()) {

    /** Current sensor mode — determines which field and colour to display. */
    var currentMode: SensorMode = SensorMode.RAINFALL

    companion object {
        private const val VIEW_TYPE_READING = 0
        private const val VIEW_TYPE_GAP = 1
        private const val TINT_ALPHA = 46  // ≈ 18%
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ReadingsListItem.Reading -> VIEW_TYPE_READING
        is ReadingsListItem.Gap -> VIEW_TYPE_GAP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_READING -> {
                val binding = ItemReadingRowBinding.inflate(inflater, parent, false)
                ReadingViewHolder(binding)
            }
            VIEW_TYPE_GAP -> {
                val binding = ItemGapRowBinding.inflate(inflater, parent, false)
                GapViewHolder(binding)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ReadingViewHolder -> holder.bind(getItem(position) as ReadingsListItem.Reading, currentMode)
            is GapViewHolder -> holder.bind(getItem(position) as ReadingsListItem.Gap)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ReadingViewHolder) {
            holder.binding.imgWarning.stopBlinking()
        }
    }

    // ── ViewHolders ────────────────────────────────────────────────────

    class ReadingViewHolder(val binding: ItemReadingRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)

        fun bind(item: ReadingsListItem.Reading, mode: SensorMode) {
            val reading = item.data
            val ctx = binding.root.context

            binding.textTime.text = timeFormat.format(Date(reading.timestamp))
            binding.imgWarning.stopBlinking()

            when (mode) {
                SensorMode.RAINFALL -> {
                    val v = reading.hourlyRate
                    binding.textRate.text = String.format(Locale.US, "%.2f mm/hr", v)
                    applyTint(ctx, getRainfallColorRes(v))
                }
                SensorMode.TEMPERATURE -> {
                    val v = reading.temperature
                    if (v != null) {
                        binding.textRate.text = String.format(Locale.US, "%.1f°C", v)
                        applyTint(ctx, getTemperatureColorRes(v))
                    } else {
                        binding.textRate.text = "N/A"
                        binding.rootRow.setBackgroundColor(Color.TRANSPARENT)
                        binding.imgWarning.startBlinking()
                    }
                }
                SensorMode.PRESSURE -> {
                    val v = reading.pressure
                    if (v != null) {
                        binding.textRate.text = String.format(Locale.US, "%.1f hPa", v)
                        applyTint(ctx, getPressureColorRes(v))
                    } else {
                        binding.textRate.text = "N/A"
                        binding.rootRow.setBackgroundColor(Color.TRANSPARENT)
                        binding.imgWarning.startBlinking()
                    }
                }
                SensorMode.HUMIDITY -> {
                    val v = reading.humidity
                    if (v != null) {
                        binding.textRate.text = String.format(Locale.US, "%.0f%%", v)
                        applyTint(ctx, getHumidityColorRes(v))
                    } else {
                        binding.textRate.text = "N/A"
                        binding.rootRow.setBackgroundColor(Color.TRANSPARENT)
                        binding.imgWarning.startBlinking()
                    }
                }
            }
        }

        private fun applyTint(ctx: Context, colorRes: Int) {
            val base = ContextCompat.getColor(ctx, colorRes)
            binding.rootRow.setBackgroundColor(
                Color.argb(TINT_ALPHA, Color.red(base), Color.green(base), Color.blue(base))
            )
        }

        private fun getRainfallColorRes(rate: Float): Int = when {
            rate <= 0f -> R.color.intensity_none
            rate <= 2.5f -> R.color.intensity_light
            rate <= 7.5f -> R.color.intensity_moderate
            rate <= 50f -> R.color.intensity_heavy
            else -> R.color.intensity_violent
        }

        private fun getTemperatureColorRes(temp: Float): Int = when {
            temp <= 15f -> R.color.temp_cool
            temp <= 25f -> R.color.temp_mild
            temp <= 35f -> R.color.temp_warm
            temp <= 40f -> R.color.temp_hot
            else -> R.color.temp_extreme
        }

        private fun getPressureColorRes(pressure: Float): Int = when {
            pressure < 1000f -> R.color.pressure_low
            pressure < 1013f -> R.color.pressure_below_normal
            pressure < 1025f -> R.color.pressure_normal
            pressure < 1040f -> R.color.pressure_high
            else -> R.color.pressure_very_high
        }

        private fun getHumidityColorRes(humidity: Float): Int = when {
            humidity <= 30f -> R.color.humidity_dry
            humidity <= 50f -> R.color.humidity_comfortable
            humidity <= 70f -> R.color.humidity_humid
            humidity <= 85f -> R.color.humidity_very_humid
            else -> R.color.humidity_extreme
        }
    }

    class GapViewHolder(private val binding: ItemGapRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ReadingsListItem.Gap) {
            val totalMinutes = item.durationMs / 60_000
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60

            val gapText = if (hours > 0) {
                "gap: ${hours}h ${minutes}m (no data)"
            } else {
                "gap: ${minutes}m (no data)"
            }
            binding.textGap.text = gapText
        }
    }

    // ── DiffUtil ───────────────────────────────────────────────────────

    private class DiffCallback : DiffUtil.ItemCallback<ReadingsListItem>() {
        override fun areItemsTheSame(oldItem: ReadingsListItem, newItem: ReadingsListItem): Boolean {
            return when {
                oldItem is ReadingsListItem.Reading && newItem is ReadingsListItem.Reading ->
                    oldItem.data.id == newItem.data.id
                oldItem is ReadingsListItem.Gap && newItem is ReadingsListItem.Gap ->
                    oldItem.durationMs == newItem.durationMs
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: ReadingsListItem, newItem: ReadingsListItem): Boolean =
            oldItem == newItem
    }
}
