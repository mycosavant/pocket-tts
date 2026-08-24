package org.pockettts.android

import android.app.Application
import com.google.android.material.color.DynamicColors
import org.pockettts.android.debug.CrashLog

class PocketTtsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Installed first, so a crash anywhere later in startup is still caught.
        CrashLog.install(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
