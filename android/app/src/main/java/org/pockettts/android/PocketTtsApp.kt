package org.pockettts.android

import android.app.Application
import com.google.android.material.color.DynamicColors

class PocketTtsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
