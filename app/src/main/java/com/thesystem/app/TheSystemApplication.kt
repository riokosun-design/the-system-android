package com.thesystem.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.osmdroid.config.Configuration

@HiltAndroidApp
class TheSystemApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Osmdroid requires a user agent + config before any MapView is created.
        val config = Configuration.getInstance()
        config.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        config.userAgentValue = packageName
        // Quota engine: 150MB disk cache ceiling, tiles trusted for 30 days
        com.thesystem.app.ui.territory.SystemMapEngine.configureDiskCache(this)
    }
}
