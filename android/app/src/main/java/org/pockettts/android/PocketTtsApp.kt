package org.pockettts.android

import android.app.Application
import com.google.android.material.color.DynamicColors
import org.pockettts.android.debug.CrashLog
import org.pockettts.android.player.NowPlaying

class PocketTtsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Installed first, so a crash anywhere later in startup is still caught.
        CrashLog.install(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
        // Here rather than in any one screen or service, because a read can
        // begin from five places and the controls have to follow every one.
        NowPlaying.attach(this)
    }
}
