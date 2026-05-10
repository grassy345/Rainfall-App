package com.sreer.rainfallmonitor

import android.app.Application

class RainfallApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Will initialize DB singleton, notification channels, etc. in later steps
    }
}
