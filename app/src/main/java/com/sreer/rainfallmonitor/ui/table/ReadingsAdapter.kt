package com.sreer.rainfallmonitor.ui.table

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sreer.rainfallmonitor.R
import com.sreer.rainfallmonitor.data.entity.RainfallReading
import com.sreer.rainfallmonitor.databinding.ItemGapRowBinding
import com.sreer.rainfallmonitor.databinding.ItemReadingRowBinding
import com.sreer.rainfallmonitor.ui.graph.GAP_THRESHOLD_MS
import com.sreer.rainfallmonitor.ui.graph.MEASUREMENT_WINDOW_MS
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single item in the readings list.
 */
sealed class ReadingsListItem {
    data class Reading(val data: RainfallReading) : ReadingsListItem()
    data class Gap(val durationMs: Long) : ReadingsListItem()
}

/**
 * Converts a raw list of [RainfallReading]s (newest-first) into a list of [ReadingsListItem]s
 * with gap separators inserted between readings that exceed the threshold.
 */
fun buildReadingsList(readings: List<RainfallReading>): List<ReadingsListItem> {
    if (readings.isEmpty()) return emptyList()
    if (readings.size < 2) return listOf(ReadingsListItem.Reading(readings[0]))

    // Process in chronological order (oldest first) for gap detection
    val asc = readings.reversed()
    val items = mutableListOf<ReadingsListItem>()

    items.add(ReadingsListItem.Reading(asc[0]))

    for (i in 1 until asc.size) {
        val prev = asc[i - 1]  // older
        val curr = asc[i]      // newer

        // dead_zone = measurement_start(curr) - timestamp(prev)
        val measurementStart = curr.timestamp - MEASUREMENT_WINDOW_MS
        val deadZone = measurementStart - prev.timestamp

        if (deadZone > GAP_THRESHOLD_MS) {
            items.add(ReadingsListItem.Gap(deadZone))
        }
        items.add(ReadingsListItem.Reading(curr))
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

    companion object {
        private const val VIEW_TYPE_READING = 0
        private const val VIEW_TYPE_GAP = 1
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
            is ReadingViewHolder -> holder.bind(getItem(position) as ReadingsListItem.Reading)
            is GapViewHolder -> holder.bind(getItem(position) as ReadingsListItem.Gap)
        }
    }

    // ── ViewHolders ────────────────────────────────────────────────────

    class ReadingViewHolder(private val binding: ItemReadingRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)

        fun bind(item: ReadingsListItem.Reading) {
            val reading = item.data
            val ctx = binding.root.context

            // Time
            binding.textTime.text = timeFormat.format(Date(reading.timestamp))

            // Rate
            binding.textRate.text = String.format(Locale.US, "%.2f mm/hr", reading.hourlyRate)

            // Background tint — 18% alpha (≈46/255) of the intensity colour
            val colorRes = getIntensityBgColorRes(reading.hourlyRate)
            val baseColor = androidx.core.content.ContextCompat.getColor(ctx, colorRes)
            val tintColor = Color.argb(46, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
            binding.rootRow.setBackgroundColor(tintColor)
        }

        private fun getIntensityBgColorRes(rate: Float): Int = when {
            rate <= 0f -> R.color.intensity_none
            rate <= 2.5f -> R.color.intensity_light
            rate <= 7.5f -> R.color.intensity_moderate
            rate <= 50f -> R.color.intensity_heavy
            else -> R.color.intensity_violent
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
