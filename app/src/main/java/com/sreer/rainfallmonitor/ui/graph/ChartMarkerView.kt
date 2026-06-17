package com.sreer.rainfallmonitor.ui.graph

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import com.sreer.rainfallmonitor.R
import com.sreer.rainfallmonitor.ui.SensorMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom tooltip that appears when the user taps a data point on the chart.
 *
 * Displays:
 *   - Line 1: Exact time (e.g., "09:29 AM")
 *   - Line 2: Rainfall rate (e.g., "8.38 mm/hr")
 *
 * The tooltip is positioned above the tapped point, centered horizontally.
 * It auto-adjusts position to stay within the chart bounds.
 *
 * [midnightMs] must be set before use — it converts the Entry's X value
 * (ms offset from midnight) back into an absolute timestamp for display.
 */
class ChartMarkerView(context: Context) : MarkerView(context, R.layout.marker_view) {

    private val textTime: TextView = findViewById(R.id.textMarkerTime)
    private val textRate: TextView = findViewById(R.id.textMarkerRate)
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)

    /**
     * Epoch ms of midnight for the currently displayed date.
     * Must be set whenever the date changes (before the chart renders).
     */
    var midnightMs: Long = 0L

    /** Sensor mode — determines which unit to show in the tooltip. */
    var sensorMode: SensorMode = SensorMode.RAINFALL

    /**
     * Called by MPAndroidChart when a data point is highlighted (tapped).
     * Populates the tooltip with the entry's time and value for the current mode.
     */
    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        if (e != null) {
            val absoluteMs = midnightMs + e.x.toLong()
            textTime.text = timeFormat.format(Date(absoluteMs))
            textRate.text = when (sensorMode) {
                SensorMode.RAINFALL -> String.format(Locale.US, "%.2f mm/hr", e.y)
                SensorMode.TEMPERATURE -> String.format(Locale.US, "%.1f°C", e.y)
                SensorMode.PRESSURE -> String.format(Locale.US, "%.1f hPa", e.y)
                SensorMode.HUMIDITY -> String.format(Locale.US, "%.0f%%", e.y)
            }
        }
        super.refreshContent(e, highlight)
    }

    /**
     * Positions the tooltip centered horizontally and above the tapped point.
     * The -10f adds a small gap between the tooltip and the highlight dot.
     */
    override fun getOffset(): MPPointF {
        return MPPointF(-(width / 2f), -height.toFloat() - 10f)
    }
}
