package org.pockettts.android.debug

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The header of a crash report, on every Android this app runs on.
 *
 * The version line used to be read with `PackageInfo.longVersionCode`, which
 * arrived in API 28 while this app starts at 26. The call sat inside a
 * `runCatching`, so on Android 8 it did not crash: it threw NoSuchMethodError,
 * the result was swallowed, and the report said "unknown" where the build
 * should be. A crash report that cannot say which build crashed is most of the
 * way to useless, and this is the one class of bug that only shows up on the
 * devices least likely to be in the room.
 *
 * Run against the oldest supported release as well as the newest, which is the
 * only way a test can see it at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.O, Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class DeviceMetadataTest {

    @Test
    fun `the report names the build that crashed`() {
        val metadata = CrashLog.deviceMetadata(ApplicationProvider.getApplicationContext<Context>())
        val app = metadata["app"].orEmpty()

        assertTrue("no app version in the report header (got \"$app\")", app != "unknown")
        assertTrue("version name missing from \"$app\"", app.contains("0.1.0"))
        assertTrue("version code missing from \"$app\"", app.contains("(1)"))
    }

    @Test
    fun `the report names the device and the abis it was built for`() {
        val metadata = CrashLog.deviceMetadata(ApplicationProvider.getApplicationContext<Context>())

        assertTrue("no android version", metadata["android"].orEmpty().contains("API "))
        assertTrue("no device", metadata["device"].orEmpty().isNotBlank())
        assertTrue("no abis", metadata["abis"].orEmpty().isNotBlank())
    }
}
