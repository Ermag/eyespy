package com.eyespy.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CAMERA, "Camera service", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_CAMERA = "camera"
    }
}
