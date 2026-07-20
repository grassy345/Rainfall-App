package com.sreer.rainfallmonitor.ui.graph

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sreer.rainfallmonitor.R
import com.sreer.rainfallmonitor.databinding.ActivityGraphBinding

/**
 * Landscape graph screen launched from the dashboard preview.
 *
 * The window intentionally keeps system bars visible and lays content inside
 * system-bar/display-cutout insets so labels never disappear under the status
 * bar or the phone camera cutout.
 */
class GraphActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGraphBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        super.onCreate(savedInstanceState)

        configureWindow()

        binding = ActivityGraphBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeAreaInsets()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.graphFragmentContainer, GraphFragment())
                .commit()
        }
    }

    private fun configureWindow() {
        enableEdgeToEdge()

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun applySafeAreaInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.graphActivityRoot) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom)
            insets
        }
    }
}
