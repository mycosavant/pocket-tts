package org.pockettts.android.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Pays window insets back as padding.
 *
 * Under `targetSdk 35` Android 15 lays every window out edge to edge, so the
 * status and navigation bars sit on top of the content instead of beside it.
 * Nothing warns you about this at build time - the first symptom is a toolbar
 * wearing the status bar and a button sliced in half at the top of the screen.
 *
 * Each screen names which view should absorb the top inset (its toolbar) and
 * which should absorb the bottom (whatever sits against the navigation bar).
 */
object Insets {

    fun apply(top: View? = null, bottom: View? = null) {
        top?.let { view ->
            val initial = view.paddingTop
            ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
                val bars = windowInsets.systemBarInsets()
                target.updatePadding(top = initial + bars.top)
                windowInsets
            }
        }

        bottom?.let { view ->
            val initial = view.paddingBottom
            // Padding rather than margin, so a scrolling child keeps drawing
            // underneath the navigation bar as it passes behind it.
            if (view is android.view.ViewGroup) view.clipToPadding = false
            ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
                val bars = windowInsets.systemBarInsets()
                target.updatePadding(bottom = initial + bars.bottom)
                windowInsets
            }
        }
    }

    /**
     * System bars plus any display cutout: on a device with a punch hole in
     * landscape the cutout can be wider than the bars alone.
     */
    private fun WindowInsetsCompat.systemBarInsets(): androidx.core.graphics.Insets =
        androidx.core.graphics.Insets.max(
            getInsets(WindowInsetsCompat.Type.systemBars()),
            getInsets(WindowInsetsCompat.Type.displayCutout()),
        )
}
