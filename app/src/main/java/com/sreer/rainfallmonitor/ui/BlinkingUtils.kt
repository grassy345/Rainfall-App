package com.sreer.rainfallmonitor.ui

import android.animation.ObjectAnimator
import android.view.View
import com.sreer.rainfallmonitor.R

private const val BLINK_DURATION_MS = 600L
private const val MIN_ALPHA = 0.3f

fun View.startBlinking() {
    stopBlinking()
    visibility = View.VISIBLE
    val animator = ObjectAnimator.ofFloat(this, View.ALPHA, 1.0f, MIN_ALPHA).apply {
        duration = BLINK_DURATION_MS
        repeatMode = ObjectAnimator.REVERSE
        repeatCount = ObjectAnimator.INFINITE
        start()
    }
    setTag(R.id.tag_blink_animator, animator)
}

fun View.stopBlinking() {
    val animator = getTag(R.id.tag_blink_animator) as? ObjectAnimator
    if (animator != null) {
        animator.removeAllListeners()
        animator.cancel()
        setTag(R.id.tag_blink_animator, null)
    }
    alpha = 1.0f
    visibility = View.GONE
}
